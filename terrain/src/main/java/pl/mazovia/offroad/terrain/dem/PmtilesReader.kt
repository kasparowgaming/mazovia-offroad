package pl.mazovia.offroad.terrain.dem

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/** Malformed, truncated or out-of-bounds archive content. */
open class PmtilesFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Well-formed archive using a feature outside the supported subset (e.g. brotli/zstd compression). */
class PmtilesUnsupportedException(message: String) : PmtilesFormatException(message)

enum class PmtilesCompression(val code: Int) {
    UNKNOWN(0), NONE(1), GZIP(2), BROTLI(3), ZSTD(4);

    companion object {
        fun of(code: Int): PmtilesCompression =
            entries.firstOrNull { it.code == code } ?: throw PmtilesFormatException("compression code $code")
    }
}

enum class PmtilesTileType(val code: Int) {
    UNKNOWN(0), MVT(1), PNG(2), JPEG(3), WEBP(4), AVIF(5), MLT(6);

    companion object {
        fun of(code: Int): PmtilesTileType =
            entries.firstOrNull { it.code == code } ?: throw PmtilesFormatException("tile type code $code")
    }
}

/** PMTiles v3 header (spec §3, 127 bytes, little endian). Positions are degrees·10⁷. */
data class PmtilesHeader(
    val rootOffset: Long,
    val rootLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafDirectoriesOffset: Long,
    val leafDirectoriesLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val addressedTiles: Long,
    val tileEntries: Long,
    val tileContents: Long,
    val clustered: Boolean,
    val internalCompression: PmtilesCompression,
    val tileCompression: PmtilesCompression,
    val tileType: PmtilesTileType,
    val minZoom: Int,
    val maxZoom: Int,
    val minLonE7: Int,
    val minLatE7: Int,
    val maxLonE7: Int,
    val maxLatE7: Int,
    val centerZoom: Int,
    val centerLonE7: Int,
    val centerLatE7: Int
) {
    val minLon: Double get() = minLonE7 / 1e7
    val minLat: Double get() = minLatE7 / 1e7
    val maxLon: Double get() = maxLonE7 / 1e7
    val maxLat: Double get() = maxLatE7 / 1e7
}

/** Directory entry (spec §4.1). [runLength] 0 = leaf directory. */
data class PmtilesEntry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

/** Allocation and nesting bounds; every length read from the file is checked against these before allocating. */
data class PmtilesLimits(
    val maxDirectoryBytes: Int = 4 shl 20,
    val maxDecompressedDirectoryBytes: Int = 16 shl 20,
    val maxDirectoryEntries: Int = 1_000_000,
    val maxMetadataBytes: Int = 1 shl 20,
    val maxDecompressedMetadataBytes: Int = 4 shl 20,
    val maxTileBytes: Int = 4 shl 20,
    val maxLeafDepth: Int = 3,
    val leafCacheEntries: Int = 16
)

/**
 * Minimal read-only PMTiles v3 reader for LOCAL seekable files (DESIGN §8.3), written from the v3 specification
 * (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md, CC0). No HTTP/remote/write support.
 *
 * Supported subset: header validation; root + leaf directories (nesting ≤ [PmtilesLimits.maxLeafDepth]); varint
 * delta-coded entries with run lengths; internal compression none/gzip; tile compression none/gzip (gzip tiles are
 * returned decompressed). brotli/zstd → [PmtilesUnsupportedException]. All offsets/lengths are range-checked against
 * the section they address and the file size. Thread-safe: positional reads on a shared [FileChannel]; the root
 * directory is immutable after open; leaf directories are held in a small synchronized LRU.
 */
class PmtilesReader private constructor(
    private val channel: FileChannel,
    val fileSize: Long,
    val header: PmtilesHeader,
    private val limits: PmtilesLimits,
    private val root: List<PmtilesEntry>
) : Closeable {

    private val leafCache = object : LinkedHashMap<Long, List<PmtilesEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<PmtilesEntry>>?) =
            size > limits.leafCacheEntries
    }

    /** Metadata JSON (spec §5), decompressed; read on demand. */
    fun metadataJson(): String {
        if (header.metadataLength == 0L) return "{}"
        if (header.metadataLength > limits.maxMetadataBytes) throw PmtilesFormatException("metadata too large")
        val raw = read(header.metadataOffset, header.metadataLength.toInt())
        val bytes = decompress(raw, header.internalCompression, limits.maxDecompressedMetadataBytes, "metadata")
        return String(bytes, Charsets.UTF_8)
    }

    /** Tile bytes (tile-compression removed) or null when the archive has no such tile. */
    fun getTile(z: Int, x: Int, y: Int): ByteArray? = getTile(TileId.fromZxy(z, x, y))

    fun getTile(tileId: Long): ByteArray? {
        var directory = root
        for (depth in 0..limits.maxLeafDepth) {
            val entry = findEntry(directory, tileId) ?: return null
            if (entry.runLength > 0) {
                if (entry.length > limits.maxTileBytes) throw PmtilesFormatException("tile $tileId too large: ${entry.length}")
                checkSection(entry.offset, entry.length.toLong(), header.tileDataLength, "tile $tileId")
                val raw = read(header.tileDataOffset + entry.offset, entry.length)
                return when (header.tileCompression) {
                    PmtilesCompression.NONE, PmtilesCompression.UNKNOWN -> raw
                    PmtilesCompression.GZIP -> decompress(raw, PmtilesCompression.GZIP, limits.maxTileBytes, "tile $tileId")
                    else -> throw PmtilesUnsupportedException("tile compression ${header.tileCompression}")
                }
            }
            directory = leaf(entry)
        }
        throw PmtilesFormatException("leaf directory nesting deeper than ${limits.maxLeafDepth}")
    }

    private fun leaf(entry: PmtilesEntry): List<PmtilesEntry> {
        synchronized(leafCache) { leafCache[entry.offset]?.let { return it } }
        if (entry.length > limits.maxDirectoryBytes) throw PmtilesFormatException("leaf directory too large")
        checkSection(entry.offset, entry.length.toLong(), header.leafDirectoriesLength, "leaf directory")
        val raw = read(header.leafDirectoriesOffset + entry.offset, entry.length)
        val entries = decodeDirectory(
            decompress(raw, header.internalCompression, limits.maxDecompressedDirectoryBytes, "leaf directory"), limits
        )
        synchronized(leafCache) { leafCache[entry.offset] = entries }
        return entries
    }

    private fun read(position: Long, length: Int): ByteArray = readFully(channel, fileSize, position, length)

    override fun close() = channel.close()

    companion object {
        const val HEADER_BYTES = 127
        private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)

        fun open(file: File, limits: PmtilesLimits = PmtilesLimits()): PmtilesReader {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            try {
                val size = channel.size()
                val header = parseHeader(readFully(channel, size, 0, minOf(HEADER_BYTES.toLong(), size).toInt()), size)
                if (header.rootLength > limits.maxDirectoryBytes) throw PmtilesFormatException("root directory too large")
                val rootRaw = readFully(channel, size, header.rootOffset, header.rootLength.toInt())
                val root = decodeDirectory(
                    decompress(rootRaw, header.internalCompression, limits.maxDecompressedDirectoryBytes, "root directory"),
                    limits
                )
                return PmtilesReader(channel, size, header, limits, root)
            } catch (t: Throwable) {
                channel.close()
                throw t
            }
        }

        /** Parses and validates the 127-byte header against [fileSize]. */
        fun parseHeader(bytes: ByteArray, fileSize: Long): PmtilesHeader {
            if (bytes.size < HEADER_BYTES) throw PmtilesFormatException("truncated header: ${bytes.size} bytes")
            for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) throw PmtilesFormatException("bad magic")
            val version = bytes[7].toInt() and 0xFF
            if (version != 3) throw PmtilesUnsupportedException("spec version $version")
            val b = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            fun u64(pos: Int): Long {
                val v = b.getLong(pos)
                if (v < 0) throw PmtilesFormatException("header field at $pos exceeds 2^63")
                return v
            }
            fun u8(pos: Int) = bytes[pos].toInt() and 0xFF
            val h = PmtilesHeader(
                rootOffset = u64(8), rootLength = u64(16),
                metadataOffset = u64(24), metadataLength = u64(32),
                leafDirectoriesOffset = u64(40), leafDirectoriesLength = u64(48),
                tileDataOffset = u64(56), tileDataLength = u64(64),
                addressedTiles = u64(72), tileEntries = u64(80), tileContents = u64(88),
                clustered = when (u8(96)) { 0 -> false; 1 -> true; else -> throw PmtilesFormatException("clustered flag ${u8(96)}") },
                internalCompression = PmtilesCompression.of(u8(97)),
                tileCompression = PmtilesCompression.of(u8(98)),
                tileType = PmtilesTileType.of(u8(99)),
                minZoom = u8(100), maxZoom = u8(101),
                minLonE7 = b.getInt(102), minLatE7 = b.getInt(106),
                maxLonE7 = b.getInt(110), maxLatE7 = b.getInt(114),
                centerZoom = u8(118), centerLonE7 = b.getInt(119), centerLatE7 = b.getInt(123)
            )
            fun sectionOk(off: Long, len: Long) = off <= fileSize && len <= fileSize - off
            if (h.rootLength <= 0 || h.rootOffset < HEADER_BYTES || !sectionOk(h.rootOffset, h.rootLength))
                throw PmtilesFormatException("root directory out of bounds")
            if (!sectionOk(h.metadataOffset, h.metadataLength)) throw PmtilesFormatException("metadata out of bounds")
            if (!sectionOk(h.leafDirectoriesOffset, h.leafDirectoriesLength))
                throw PmtilesFormatException("leaf directories out of bounds")
            if (!sectionOk(h.tileDataOffset, h.tileDataLength)) throw PmtilesFormatException("tile data out of bounds")
            if (h.minZoom > h.maxZoom || h.maxZoom > WebMercator.MAX_ZOOM) throw PmtilesFormatException("zoom range ${h.minZoom}..${h.maxZoom}")
            if (h.minLonE7 > h.maxLonE7 || h.minLatE7 > h.maxLatE7 ||
                h.minLonE7 < -1_800_000_000 || h.maxLonE7 > 1_800_000_000 ||
                h.minLatE7 < -900_000_000 || h.maxLatE7 > 900_000_000
            ) throw PmtilesFormatException("invalid bounds")
            if (h.internalCompression != PmtilesCompression.NONE && h.internalCompression != PmtilesCompression.GZIP)
                throw PmtilesUnsupportedException("internal compression ${h.internalCompression}")
            return h
        }

        /** Decodes an already-decompressed directory (spec §4.2 / A.2) with strict validation. */
        fun decodeDirectory(bytes: ByteArray, limits: PmtilesLimits = PmtilesLimits()): List<PmtilesEntry> {
            val r = VarintReader(bytes)
            val n = r.next("entry count")
            if (n <= 0) throw PmtilesFormatException("directory has no entries")
            // every entry needs ≥ 4 bytes (id delta, run length, length, offset)
            if (n > limits.maxDirectoryEntries || n > bytes.size / 4) throw PmtilesFormatException("entry count $n")
            val count = n.toInt()
            val ids = LongArray(count)
            var last = 0L
            for (i in 0 until count) {
                val delta = r.next("tile id")
                if (i > 0 && delta == 0L) throw PmtilesFormatException("tile ids not strictly increasing")
                last = Math.addExact(last, delta)
                ids[i] = last
            }
            val runs = IntArray(count) { toInt(r.next("run length"), "run length") }
            val lengths = IntArray(count) {
                val len = toInt(r.next("length"), "length")
                if (len <= 0) throw PmtilesFormatException("entry length must be > 0")
                len
            }
            val out = ArrayList<PmtilesEntry>(count)
            for (i in 0 until count) {
                val v = r.next("offset")
                val offset = if (v == 0L) {
                    if (i == 0) throw PmtilesFormatException("first offset cannot be implicit")
                    val prev = out[i - 1]
                    Math.addExact(prev.offset, prev.length.toLong())
                } else v - 1
                out += PmtilesEntry(ids[i], offset, lengths[i], runs[i])
            }
            if (!r.atEnd) throw PmtilesFormatException("trailing bytes after directory")
            return out
        }

        /** Entry covering [tileId] (tile run or leaf starting at or before it), null if none (spec reference lookup). */
        fun findEntry(entries: List<PmtilesEntry>, tileId: Long): PmtilesEntry? {
            var lo = 0
            var hi = entries.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = entries[mid].tileId.compareTo(tileId)
                when {
                    c < 0 -> lo = mid + 1
                    c > 0 -> hi = mid - 1
                    else -> return entries[mid]
                }
            }
            if (hi >= 0) {
                val e = entries[hi]
                if (e.runLength == 0) return e
                if (tileId - e.tileId < e.runLength) return e
            }
            return null
        }

        private fun toInt(v: Long, what: String): Int {
            if (v > Int.MAX_VALUE) throw PmtilesFormatException("$what $v too large")
            return v.toInt()
        }

        private fun checkSection(offset: Long, length: Long, sectionLength: Long, what: String) {
            if (offset < 0 || length <= 0 || offset > sectionLength || length > sectionLength - offset)
                throw PmtilesFormatException("$what out of section bounds")
        }

        private fun readFully(channel: FileChannel, fileSize: Long, position: Long, length: Int): ByteArray {
            if (length < 0 || position < 0 || position > fileSize || length > fileSize - position)
                throw PmtilesFormatException("read [$position, +$length) beyond file size $fileSize")
            val buf = ByteBuffer.allocate(length)
            var pos = position
            while (buf.hasRemaining()) {
                val n = channel.read(buf, pos)
                if (n < 0) throw PmtilesFormatException("unexpected end of file", EOFException())
                pos += n
            }
            return buf.array()
        }

        internal fun decompress(data: ByteArray, compression: PmtilesCompression, maxBytes: Int, what: String): ByteArray =
            when (compression) {
                PmtilesCompression.NONE -> data
                PmtilesCompression.GZIP -> try {
                    GZIPInputStream(ByteArrayInputStream(data)).use { input ->
                        val out = ByteArrayOutputStream(minOf(maxBytes, maxOf(64, data.size * 4)))
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (out.size() + n > maxBytes) throw PmtilesFormatException("$what exceeds $maxBytes bytes decompressed")
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                } catch (e: PmtilesFormatException) {
                    throw e
                } catch (e: ZipException) {
                    throw PmtilesFormatException("$what: corrupt gzip", e)
                } catch (e: EOFException) {
                    throw PmtilesFormatException("$what: truncated gzip", e)
                }
                else -> throw PmtilesUnsupportedException("$what compression $compression")
            }
    }
}

/** Unsigned LEB128 varints ≤ 2^63 − 1 (protobuf encoding), bounds-checked. */
internal class VarintReader(private val bytes: ByteArray) {
    var position = 0
        private set
    val atEnd: Boolean get() = position == bytes.size

    fun next(what: String): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (position >= bytes.size) throw PmtilesFormatException("truncated varint ($what)")
            val b = bytes[position++].toInt() and 0xFF
            if (shift == 63 && b > 0) throw PmtilesFormatException("varint overflow ($what)")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw PmtilesFormatException("varint too long ($what)")
        }
    }
}
