package pl.mazovia.offroad.terrain.dem

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream


/** Test-side encoders/writers written independently of the production reader. */
object DemTestSupport {

    /** 8-bit RGB PNG of 0xRRGGBB codes, row-major. */
    fun png(width: Int, height: Int, rgb: (col: Int, row: Int) -> Int): ByteArray = PurePng.encodeRgb(width, height, rgb)

    /** Terrain-RGB tile from a height function (null = nodata). */
    fun terrainTile(height: (col: Int, row: Int) -> Double?): ByteArray =
        png(256, 256) { c, r -> TerrainRgb.encode(height(c, r)) }

    fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    /** Directory bytes per spec §4.2 (uncompressed). */
    fun directory(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        varint(out, entries.size.toLong())
        var last = 0L
        for (e in entries) { varint(out, e.tileId - last); last = e.tileId }
        for (e in entries) varint(out, e.runLength.toLong())
        for (e in entries) varint(out, e.length.toLong())
        var next = 0L
        entries.forEachIndexed { i, e ->
            varint(out, if (i > 0 && e.offset == next) 0 else e.offset + 1)
            next = e.offset + e.length
        }
        return out.toByteArray()
    }

    fun metadataJson(zoom: Int, buildId: String = "test-build") = """
        {"name":"test","attribution":"test","mazovia_terrain":{"schema":"mazovia-terrain-archive-1","build_id":"$buildId",
        "source":"TEST","vertical_datum":"PL-EVRF2007-NH","horizontal_grid":"EPSG:3857 XYZ","zoom":$zoom,"tile_size":256,
        "encoding":"mapbox","quantisation_m":0.1,"nodata_rgb":[255,255,255],"source_years":[2019,2025]}}
    """.trimIndent()

    /**
     * Minimal PMTiles v3 writer for tests (spec §3-§4): tiles sorted by id, identical consecutive payloads become runs,
     * optional leaf directories of [leafSize] entries, internal compression gzip or none.
     */
    fun writeArchive(
        file: File,
        tiles: Map<Long, ByteArray>,
        minZoom: Int,
        maxZoom: Int,
        bounds: DoubleArray, // west, south, east, north
        metadata: String,
        internalGzip: Boolean = true,
        leafSize: Int? = null,
        tileType: Int = 2,
        tileCompression: Int = 1,
        internalCompressionCode: Int? = null
    ) {
        val data = ByteArrayOutputStream()
        val entries = ArrayList<Entry>()
        for ((id, bytes) in tiles.toSortedMap()) {
            val last = entries.lastOrNull()
            val lastBytes = last?.let { tiles[it.tileId] }
            if (last != null && lastBytes.contentEquals(bytes) && id == last.tileId + last.runLength) {
                entries[entries.size - 1] = last.copy(runLength = last.runLength + 1)
            } else {
                entries += Entry(id, data.size().toLong(), bytes.size, 1)
                data.write(bytes)
            }
        }
        fun comp(b: ByteArray) = if (internalGzip) gzip(b) else b
        val leaves = ByteArrayOutputStream()
        val rootBytes = if (leafSize == null) comp(directory(entries)) else {
            val rootEntries = ArrayList<Entry>()
            entries.chunked(leafSize).forEach { chunk ->
                val leaf = comp(directory(chunk))
                rootEntries += Entry(chunk.first().tileId, leaves.size().toLong(), leaf.size, 0)
                leaves.write(leaf)
            }
            comp(directory(rootEntries))
        }
        val meta = comp(metadata.toByteArray())
        val rootOff = 127L
        val metaOff = rootOff + rootBytes.size
        val leafOff = metaOff + meta.size
        val dataOff = leafOff + leaves.size()
        val h = java.nio.ByteBuffer.allocate(127).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("PMTiles".toByteArray()); h.put(3)
        h.putLong(rootOff); h.putLong(rootBytes.size.toLong())
        h.putLong(metaOff); h.putLong(meta.size.toLong())
        h.putLong(leafOff); h.putLong(leaves.size().toLong())
        h.putLong(dataOff); h.putLong(data.size().toLong())
        h.putLong(tiles.size.toLong()); h.putLong(entries.size.toLong()); h.putLong(entries.size.toLong())
        h.put(1); h.put((internalCompressionCode ?: if (internalGzip) 2 else 1).toByte())
        h.put(tileCompression.toByte()); h.put(tileType.toByte())
        h.put(minZoom.toByte()); h.put(maxZoom.toByte())
        h.putInt(Math.floor(bounds[0] * 1e7).toInt()); h.putInt(Math.floor(bounds[1] * 1e7).toInt())
        h.putInt(Math.ceil(bounds[2] * 1e7).toInt()); h.putInt(Math.ceil(bounds[3] * 1e7).toInt())
        h.put(maxZoom.toByte()); h.putInt(0); h.putInt(0)
        file.outputStream().use {
            it.write(h.array()); it.write(rootBytes); it.write(meta); it.write(leaves.toByteArray()); it.write(data.toByteArray())
        }
    }

    /** West/south/east/north degrees of tile [key]. */
    fun tileBounds(key: TileKey): DoubleArray {
        val s = WebMercator.TILE_SIZE.toDouble()
        return doubleArrayOf(
            WebMercator.longitudeOfWorldPx(key.x * s, key.z), WebMercator.latitudeOfWorldPx((key.y + 1) * s, key.z),
            WebMercator.longitudeOfWorldPx((key.x + 1) * s, key.z), WebMercator.latitudeOfWorldPx(key.y * s, key.z)
        )
    }

    fun union(keys: Collection<TileKey>): DoubleArray {
        val b = keys.map { tileBounds(it) }
        return doubleArrayOf(b.minOf { it[0] }, b.minOf { it[1] }, b.maxOf { it[2] }, b.maxOf { it[3] })
    }
}
