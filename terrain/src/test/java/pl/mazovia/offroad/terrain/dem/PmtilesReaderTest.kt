package pl.mazovia.offroad.terrain.dem

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.mazovia.offroad.terrain.dem.DemTestSupport.Entry
import java.io.File
import java.io.RandomAccessFile

class TileIdTest {

    /** Vectors from the PMTiles v3 spec §4.1 table. */
    @Test
    fun specVectors() {
        assertEquals(0L, TileId.fromZxy(0, 0, 0))
        assertEquals(1L, TileId.fromZxy(1, 0, 0))
        assertEquals(2L, TileId.fromZxy(1, 0, 1))
        assertEquals(3L, TileId.fromZxy(1, 1, 1))
        assertEquals(4L, TileId.fromZxy(1, 1, 0))
        assertEquals(5L, TileId.fromZxy(2, 0, 0))
        assertEquals(19078479L, TileId.fromZxy(12, 3423, 1763))
        assertEquals(TileKey(12, 3423, 1763), TileId.toKey(19078479L))
    }

    @Test
    fun roundTripAcrossZooms() {
        val rnd = java.util.Random(7)
        for (z in 0..26) repeat(200) {
            val n = 1L shl z
            val x = (rnd.nextDouble() * n).toLong().coerceAtMost(n - 1).toInt()
            val y = (rnd.nextDouble() * n).toLong().coerceAtMost(n - 1).toInt()
            val id = TileId.fromZxy(z, x, y)
            assertEquals(TileKey(z, x, y), TileId.toKey(id))
        }
        // zoom boundaries
        assertEquals(TileKey(3, 0, 0), TileId.toKey(TileId.zoomBase(3)))
        assertEquals(3, TileId.toKey(TileId.zoomBase(4) - 1).z)
    }

    @Test
    fun rejectsOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { TileId.fromZxy(1, 2, 0) }
        assertThrows(IllegalArgumentException::class.java) { TileId.fromZxy(27, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TileId.toKey(-1) }
        assertThrows(IllegalArgumentException::class.java) { TileId.toKey(TileId.zoomBase(27)) }
    }
}

class VarintTest {
    private fun read(vararg b: Int) = VarintReader(ByteArray(b.size) { b[it].toByte() })

    @Test
    fun edgeCases() {
        assertEquals(0L, read(0).next("x"))
        assertEquals(127L, read(0x7F).next("x"))
        assertEquals(128L, read(0x80, 0x01).next("x"))
        assertEquals(300L, read(0xAC, 0x02).next("x"))
        assertEquals(Long.MAX_VALUE, read(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F).next("x"))
    }

    @Test
    fun overflowAndTruncation() {
        // 2^63 does not fit a signed Long
        assertThrows(PmtilesFormatException::class.java) { read(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01).next("x") }
        assertThrows(PmtilesFormatException::class.java) { read(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01).next("x") }
        assertThrows(PmtilesFormatException::class.java) { read(0x80).next("x") }
        assertThrows(PmtilesFormatException::class.java) { read().next("x") }
    }
}

class PmtilesReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Directory from the spec §4.1 examples, encoded by hand: ids 5 and 6, run 1 and 3, lengths 42/10, contiguous. */
    @Test
    fun decodesHandEncodedDirectory() {
        val bytes = byteArrayOf(2, 5, 1, 1, 3, 42, 10, 1, 0)
        val e = PmtilesReader.decodeDirectory(bytes)
        assertEquals(listOf(PmtilesEntry(5, 0, 42, 1), PmtilesEntry(6, 42, 10, 3)), e)
        assertEquals(e[0], PmtilesReader.findEntry(e, 5))
        assertEquals(e[1], PmtilesReader.findEntry(e, 8)) // inside run 6..8
        assertNull(PmtilesReader.findEntry(e, 9))
        assertNull(PmtilesReader.findEntry(e, 4))
    }

    @Test
    fun directoryValidation() {
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.decodeDirectory(byteArrayOf(0)) } // no entries
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.decodeDirectory(byteArrayOf(1, 5, 1, 42, 0)) } // implicit first offset
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.decodeDirectory(byteArrayOf(1, 5, 1, 0, 1)) } // zero length
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.decodeDirectory(byteArrayOf(2, 5, 0, 1, 1, 4, 4, 1, 0)) } // duplicate id
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.decodeDirectory(byteArrayOf(1, 5, 1, 42, 1, 9)) } // trailing
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.decodeDirectory(byteArrayOf(3, 5, 1)) } // truncated
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.decodeDirectory(byteArrayOf(0x7F, 1, 1, 1, 1)) } // count > bytes/4
    }

    @Test
    fun leafEntryIsReturnedForLookup() {
        val e = PmtilesReader.decodeDirectory(DemTestSupport.directory(listOf(Entry(10, 0, 5, 0), Entry(100, 5, 5, 0))))
        assertEquals(10L, PmtilesReader.findEntry(e, 57)!!.tileId)
        assertEquals(100L, PmtilesReader.findEntry(e, 1000)!!.tileId)
        assertNull(PmtilesReader.findEntry(e, 3))
    }

    private fun sampleTiles(): Map<Long, ByteArray> {
        val m = LinkedHashMap<Long, ByteArray>()
        for (id in 0L until 85L) m[id] = "tile-$id".toByteArray()
        for (id in 85L until 100L) m[id] = "same".toByteArray() // one run of 15
        m.remove(40L) // hole
        return m
    }

    private fun write(name: String, tiles: Map<Long, ByteArray> = sampleTiles(), gzip: Boolean = true, leafSize: Int? = null,
                      internalCode: Int? = null, tileCompression: Int = 1): File {
        val f = tmp.newFile(name)
        DemTestSupport.writeArchive(f, tiles, 0, 4, doubleArrayOf(-180.0, -85.0, 180.0, 85.0), "{\"name\":\"t\"}",
            internalGzip = gzip, leafSize = leafSize, tileType = 0, tileCompression = tileCompression, internalCompressionCode = internalCode)
        return f
    }

    @Test
    fun rootDirectoryLookupGzipAndNone() {
        for (gzip in listOf(true, false)) {
            PmtilesReader.open(write("root-$gzip.pmtiles", gzip = gzip)).use { r ->
                assertEquals(if (gzip) PmtilesCompression.GZIP else PmtilesCompression.NONE, r.header.internalCompression)
                assertArrayEquals("tile-7".toByteArray(), r.getTile(7))
                assertArrayEquals("same".toByteArray(), r.getTile(99)) // run length
                assertNull(r.getTile(40)) // missing
                assertNull(r.getTile(100)) // beyond last run
                assertEquals("{\"name\":\"t\"}", r.metadataJson())
            }
        }
    }

    @Test
    fun leafDirectoryLookup() {
        PmtilesReader.open(write("leaves.pmtiles", leafSize = 7)).use { r ->
            assertTrue(r.header.leafDirectoriesLength > 0)
            for (id in 0L until 85L) {
                if (id == 40L) assertNull(r.getTile(id)) else assertArrayEquals("tile-$id".toByteArray(), r.getTile(id))
            }
            assertArrayEquals("same".toByteArray(), r.getTile(92))
            assertNull(r.getTile(500))
        }
    }

    @Test
    fun gzipTileCompressionReturnsDecompressedBytes() {
        val tiles = mapOf(3L to DemTestSupport.gzip("hello".toByteArray()))
        PmtilesReader.open(write("tilegz.pmtiles", tiles = tiles, tileCompression = 2)).use { r ->
            assertArrayEquals("hello".toByteArray(), r.getTile(3))
        }
    }

    @Test
    fun unsupportedInternalCompression() {
        assertThrows(PmtilesUnsupportedException::class.java) { PmtilesReader.open(write("br.pmtiles", internalCode = 3)) }
        assertThrows(PmtilesUnsupportedException::class.java) { PmtilesReader.open(write("zstd.pmtiles", internalCode = 4)) }
    }

    @Test
    fun badMagicVersionAndTruncatedHeader() {
        val good = write("good.pmtiles").readBytes()
        val badMagic = good.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.open(tmp.newFile("m.pmtiles").also { it.writeBytes(badMagic) }) }
        val v2 = good.copyOf().also { it[7] = 2 }
        assertThrows(PmtilesUnsupportedException::class.java) { PmtilesReader.open(tmp.newFile("v.pmtiles").also { it.writeBytes(v2) }) }
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.open(tmp.newFile("h.pmtiles").also { it.writeBytes(good.copyOf(100)) }) }
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.open(tmp.newFile("e.pmtiles")) }
    }

    @Test
    fun rootOutOfBoundsAndInvalidHeaderFields() {
        val good = write("good2.pmtiles").readBytes()
        val badRoot = good.copyOf().also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(16, 1L shl 40) }
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.open(tmp.newFile("r.pmtiles").also { it.writeBytes(badRoot) }) }
        val negative = good.copyOf().also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(24, -1L) }
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.open(tmp.newFile("n.pmtiles").also { it.writeBytes(negative) }) }
        val zoom = good.copyOf().also { it[100] = 9; it[101] = 3 }
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.open(tmp.newFile("z.pmtiles").also { it.writeBytes(zoom) }) }
        val corruptRoot = good.copyOf().also { for (i in 127 until 140) it[i] = 0x55 }
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.open(tmp.newFile("c.pmtiles").also { it.writeBytes(corruptRoot) }) }
    }

    @Test
    fun truncatedTilePayload() {
        val f = write("trunc.pmtiles")
        val full = f.length()
        RandomAccessFile(f, "rw").use { it.setLength(full - 3) }
        // header still claims the full tile-data length -> rejected at open
        assertThrows(PmtilesFormatException::class.java) { PmtilesReader.open(f) }
    }

    @Test
    fun entryPointingOutsideTileData() {
        val f = tmp.newFile("oob.pmtiles")
        val tiles = mapOf(1L to "abc".toByteArray())
        DemTestSupport.writeArchive(f, tiles, 0, 1, doubleArrayOf(-180.0, -85.0, 180.0, 85.0), "{}", internalGzip = false, tileType = 0)
        // rewrite the uncompressed root: one entry, id 1, run 1, length 3, offset 100 (+1 encoded)
        val bytes = f.readBytes()
        val root = DemTestSupport.directory(listOf(Entry(1, 100, 3, 1)))
        val rootLen = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(16).toInt()
        assertEquals(root.size, rootLen)
        System.arraycopy(root, 0, bytes, 127, root.size)
        f.writeBytes(bytes)
        PmtilesReader.open(f).use { r -> assertThrows(PmtilesFormatException::class.java) { r.getTile(1) } }
    }

    @Test
    fun tileLargerThanLimitIsRejected() {
        val f = write("big.pmtiles", tiles = mapOf(2L to ByteArray(5000)))
        PmtilesReader.open(f, PmtilesLimits(maxTileBytes = 4096)).use { r ->
            assertThrows(PmtilesFormatException::class.java) { r.getTile(2) }
        }
    }
}
