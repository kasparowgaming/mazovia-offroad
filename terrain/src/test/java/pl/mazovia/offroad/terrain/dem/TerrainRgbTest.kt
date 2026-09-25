package pl.mazovia.offroad.terrain.dem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainRgbTest {

    /** Known vectors computed by hand from the mapbox formula. */
    @Test
    fun knownVectors() {
        // 0 m -> code 100000 = 0x0186A0 -> (1, 134, 160)
        assertEquals(TerrainRgb.code(1, 134, 160), TerrainRgb.encode(0.0))
        assertEquals(0.0, TerrainRgb.heightOfCode(TerrainRgb.code(1, 134, 160)), 1e-9)
        // 100.0 m -> code 101000 = 0x018A88 -> (1, 138, 136)
        assertEquals(TerrainRgb.code(1, 138, 136), TerrainRgb.encode(100.0))
        // 123.4 m -> 101234 = 0x018B72 -> (1, 139, 114)
        assertEquals(TerrainRgb.code(1, 139, 114), TerrainRgb.encode(123.4))
        assertEquals(123.4, TerrainRgb.heightOfCode(TerrainRgb.code(1, 139, 114)), 1e-9)
        // -10000 m is code 0 but outside the valid range
        assertEquals(-10000.0, TerrainRgb.heightOfCode(0), 0.0)
        assertFalse(TerrainRgb.isValidHeightCode(0))
    }

    @Test
    fun roundTripMinus100To2000MetresEveryDecimetre() {
        for (k in -1000..20000) {
            val h = k / 10.0
            val c = TerrainRgb.encode(h)
            assertEquals(k + 100000, c)
            assertTrue(TerrainRgb.isValidHeightCode(c))
            assertEquals(h, TerrainRgb.heightOfCode(c), 1e-9)
        }
    }

    @Test
    fun roundingIsHalfToEven() {
        // exactly representable .5 cases: (h + 10000) * 10 = x.5
        assertEquals(100002, TerrainRgb.encode(0.25)) // 100002.5 -> 100002
        assertEquals(100008, TerrainRgb.encode(0.75)) // 100007.5 -> 100008
        assertEquals(99998, TerrainRgb.encode(-0.25)) // 99997.5 -> 99998
    }

    @Test
    fun nodataIsReservedAndNeverZeroMetres() {
        assertEquals(0xFFFFFF, TerrainRgb.encode(null))
        assertTrue(TerrainRgb.isNodata(TerrainRgb.code(255, 255, 255)))
        assertFalse(TerrainRgb.isValidHeightCode(TerrainRgb.NODATA_CODE))
        assertTrue(TerrainRgb.heightOfCode(TerrainRgb.NODATA_CODE) > TerrainRgb.MAX_VALID_M)
        assertThrows(IllegalArgumentException::class.java) { TerrainRgb.encode(9000.0) }
        assertThrows(IllegalArgumentException::class.java) { TerrainRgb.encode(-500.1) }
    }

    private val decoder = TerrainRgbTileDecoder(PurePng.Decoder())

    @Test
    fun decodesTileWithNodataMask() {
        val png = DemTestSupport.terrainTile { c, r -> if (c == 3 && r == 5) null else 100.0 + c * 0.1 }
        val block = decoder.decode(png)
        assertEquals(1, block.nodataCount)
        assertTrue(block.heightAt(3, 5).isNaN())
        assertEquals(100.4, block.heightAt(4, 0), 1e-9)
        assertEquals(125.5, block.heightAt(255, 255), 1e-9)
    }

    @Test
    fun wrongDimensionsAreCorrupt() {
        val png = DemTestSupport.png(128, 256) { _, _ -> TerrainRgb.encode(100.0) }
        assertThrows(TileDecodeException::class.java) { decoder.decode(png) }
    }

    @Test
    fun malformedPngIsCorrupt() {
        assertThrows(TileDecodeException::class.java) { decoder.decode(byteArrayOf(1, 2, 3, 4, 5)) }
        val good = DemTestSupport.terrainTile { _, _ -> 100.0 }
        assertThrows(TileDecodeException::class.java) { decoder.decode(good.copyOf(good.size / 2)) }
    }

    @Test
    fun implausibleCodeOrAlphaIsCorrupt() {
        val zeroCode = DemTestSupport.png(256, 256) { _, _ -> 0 } // -10000 m
        assertThrows(TileDecodeException::class.java) { decoder.decode(zeroCode) }
        val translucent = IntArray(256 * 256) { (0x80 shl 24) or TerrainRgb.encode(100.0) }
        assertThrows(TileDecodeException::class.java) { TerrainRgbTileDecoder.fromImage(DecodedImage(256, 256, translucent)) }
    }
}

class DemTileCacheTest {
    private fun block() = TerrainRgbTileDecoder.fromImage(DecodedImage(256, 256, IntArray(256 * 256) { (0xFF shl 24) or 101000 }))

    @Test
    fun byteBoundedLruEvictsLeastRecentlyUsed() {
        val cache = DemTileCache(maxBytes = 10 * DemBlock.RETAINED_BYTES)
        assertEquals(10, cache.maxBlocks)
        val keys = (0 until 11).map { TileKey(15, 18000 + it, 10000) }
        keys.take(10).forEach { cache.put(it, TileState.Loaded(block())) }
        cache.get(keys[0]) // touch 0 -> 1 is now eldest
        cache.put(keys[10], TileState.Loaded(block()))
        assertTrue(cache.get(keys[0]) is TileState.Loaded)
        assertEquals(null, cache.get(keys[1]))
        val s = cache.stats()
        assertEquals(10, s.blocks)
        assertEquals(1L, s.evictions)
        assertTrue(s.retainedBytes <= cache.maxBytes)
    }

    @Test
    fun defaultBudgetIs16MiB() {
        val cache = DemTileCache()
        assertEquals(16L shl 20, cache.maxBytes)
        assertEquals(63, cache.maxBlocks) // 262 192 B retained per block
    }

    @Test
    fun negativeEntriesAreKeptSeparatelyAndBounded() {
        val cache = DemTileCache(maxBytes = 9 * DemBlock.RETAINED_BYTES, maxNegativeEntries = 2)
        val a = TileKey(15, 1, 1); val b = TileKey(15, 2, 1); val c = TileKey(15, 3, 1)
        cache.put(a, TileState.Corrupt)
        cache.put(b, TileState.Absent)
        cache.put(c, TileState.Absent)
        assertEquals(null, cache.get(a))
        assertEquals(TileState.Absent, cache.get(b))
        cache.put(b, TileState.Loaded(block()))
        assertTrue(cache.get(b) is TileState.Loaded)
        assertEquals(1, cache.stats().negativeEntries)
    }
}
