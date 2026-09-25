package pl.mazovia.offroad.terrain.dem

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.mazovia.offroad.terrain.elevation.ElevationSample
import pl.mazovia.offroad.terrain.elevation.GeoBounds
import pl.mazovia.offroad.terrain.elevation.UnavailableReason
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class PmtilesElevationSamplerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val z = 15
    private val tx0 = kotlin.math.floor(WebMercator.worldPxX(21.0, z) / 256).toInt()
    private val ty0 = kotlin.math.floor(WebMercator.worldPxY(52.2, z) / 256).toInt()
    private val gx0 = tx0 * 256L
    private val gy0 = ty0 * 256L

    /** Plane with exact 0.1 m codes: h = 100 + 0.1·(gi − gx0) + 0.2·(gj − gy0) at pixel centres. */
    private fun plane(gi: Long, gj: Long): Double = 100.0 + 0.1 * (gi - gx0) + 0.2 * (gj - gy0)

    /** Continuous plane value at world pixel coordinates (value at pixel centre = gi + 0.5). */
    private fun planeAt(px: Double, py: Double) = 100.0 + 0.1 * (px - 0.5 - gx0) + 0.2 * (py - 0.5 - gy0)

    private fun tile(tx: Int, ty: Int, h: (gi: Long, gj: Long) -> Double? = ::plane): ByteArray =
        DemTestSupport.terrainTile { c, r -> h(tx * 256L + c, ty * 256L + r) }

    private val decodes = AtomicInteger()
    private val diagnostics = mutableListOf<String>()

    private fun sampler(
        tiles: Map<TileKey, ByteArray>,
        boundsKeys: Collection<TileKey> = tiles.keys,
        cache: DemTileCache = DemTileCache()
    ): PmtilesElevationSampler {
        val f = tmp.newFile()
        DemTestSupport.writeArchive(
            f, tiles.mapKeys { TileId.fromKey(it.key) }, z, z, DemTestSupport.union(boundsKeys), DemTestSupport.metadataJson(z)
        )
        val counting = TileImageDecoder { b -> decodes.incrementAndGet(); PurePng.Decoder().decode(b) }
        return PmtilesElevationSampler(
            PmtilesReader.open(f), TerrainRgbTileDecoder(counting), cache, diagnostics = { synchronized(diagnostics) { diagnostics += it } }
        )
    }

    private fun key(dx: Int, dy: Int) = TileKey(z, tx0 + dx, ty0 + dy)
    private fun grid2x2() = mapOf(key(0, 0) to tile(tx0, ty0), key(1, 0) to tile(tx0 + 1, ty0),
        key(0, 1) to tile(tx0, ty0 + 1), key(1, 1) to tile(tx0 + 1, ty0 + 1))

    private fun lat(py: Double) = WebMercator.latitudeOfWorldPx(py, z)
    private fun lon(px: Double) = WebMercator.longitudeOfWorldPx(px, z)
    private fun PmtilesElevationSampler.at(px: Double, py: Double) = sample(lat(py), lon(px))
    private fun PmtilesElevationSampler.loadAll() = runBlocking { prefetchTiles(tilesFor(GeoBounds(archive.coverage.south, archive.coverage.west, archive.coverage.north, archive.coverage.east), ring = 0)) }

    private fun assertValue(expected: Double, s: ElevationSample, conf: Float = 1f, tol: Double = 1e-6) {
        assertTrue("expected value, got $s", s is ElevationSample.Value)
        s as ElevationSample.Value
        assertEquals(expected, s.heightM, tol)
        assertEquals(conf, s.confidence, 0f)
    }

    private fun assertUnavailable(reason: UnavailableReason, s: ElevationSample) =
        assertEquals(ElevationSample.Unavailable(reason), s)

    @Test
    fun flatTile() {
        sampler(mapOf(key(0, 0) to tile(tx0, ty0) { _, _ -> 123.4 })).use { s ->
            s.loadAll()
            assertValue(123.4, s.at(gx0 + 17.3, gy0 + 200.9))
            assertValue(123.4, s.at(gx0 + 128.0, gy0 + 128.0))
        }
    }

    @Test
    fun metadataExposesProvenance() {
        sampler(mapOf(key(0, 0) to tile(tx0, ty0))).use { s ->
            assertEquals("PL-EVRF2007-NH", s.metadata.verticalReference)
            assertEquals("gugik-nmt:test-build", s.metadata.sourceId)
            assertEquals(15, s.archive.zoom)
            assertEquals(2019..2025, s.archive.sourceYears)
            assertEquals(2.93, s.metadata.nominalResolutionM!!, 0.01)
        }
    }

    @Test
    fun bilinearIsExactOnPlanesIncludingPixelCentres() {
        sampler(grid2x2()).use { s ->
            s.loadAll()
            val rnd = java.util.Random(11)
            repeat(2000) {
                val px = gx0 + 0.5 + rnd.nextDouble() * 511.0
                val py = gy0 + 0.5 + rnd.nextDouble() * 511.0
                assertValue(planeAt(px, py), s.at(px, py))
            }
            for (gi in listOf(0L, 1L, 100L, 255L, 256L, 400L)) for (gj in listOf(0L, 7L, 255L, 256L, 511L)) {
                // exactly at the pixel centre the stored code is returned
                assertValue(plane(gx0 + gi, gy0 + gj), s.at(gx0 + gi + 0.5, gy0 + gj + 0.5), tol = 1e-6)
            }
        }
    }

    @Test
    fun knownFivePercentSlope() {
        // Heights along x follow a 5 % ground slope; codes quantise to 0.1 m.
        val spacing = WebMercator.groundSpacingM(z, 52.2)
        sampler(grid2x2().mapValues { (k, _) -> tile(k.x, k.y) { gi, _ -> 100.0 + Math.rint((gi - gx0) * spacing * 0.05 * 10) / 10 } }).use { s ->
            s.loadAll()
            val a = s.at(gx0 + 50.5, gy0 + 100.0) as ElevationSample.Value
            val b = s.at(gx0 + 50.5 + 100.0 / spacing, gy0 + 100.0) as ElevationSample.Value
            assertEquals(5.0, (b.heightM - a.heightM), 0.1) // 5 m over 100 m
        }
    }

    @Test
    fun continuousAcrossXAndYTileBoundariesAndFourTileCorner() {
        sampler(grid2x2()).use { s ->
            s.loadAll()
            for (d in listOf(-0.6, -0.5, -1e-7, 0.0, 1e-7, 0.4, 0.5)) {
                assertValue(planeAt(gx0 + 256 + d, gy0 + 100.0), s.at(gx0 + 256 + d, gy0 + 100.0)) // x boundary
                assertValue(planeAt(gx0 + 100.0, gy0 + 256 + d), s.at(gx0 + 100.0, gy0 + 256 + d)) // y boundary
                assertValue(planeAt(gx0 + 256 + d, gy0 + 256 + d), s.at(gx0 + 256 + d, gy0 + 256 + d)) // corner
            }
            val left = s.at(gx0 + 256 - 1e-6, gy0 + 30.0) as ElevationSample.Value
            val right = s.at(gx0 + 256 + 1e-6, gy0 + 30.0) as ElevationSample.Value
            assertTrue(abs(left.heightM - right.heightM) < 0.01)
        }
    }

    @Test
    fun missingNeighbourTileFallsBackToNearestValidWithHalfConfidence() {
        // Archive bounds cover 2x1 tiles but only the left one exists.
        sampler(mapOf(key(0, 0) to tile(tx0, ty0)), boundsKeys = listOf(key(0, 0), key(1, 0))).use { s ->
            s.loadAll()
            // px 255.8 -> corners at pixel 255 (left tile) and 256 (absent)
            assertValue(plane(gx0 + 255, gy0 + 10), s.at(gx0 + 255.8, gy0 + 10.6), conf = 0.5f)
            assertValue(planeAt(gx0 + 200.2, gy0 + 10.6), s.at(gx0 + 200.2, gy0 + 10.6)) // interior unaffected
            assertUnavailable(UnavailableReason.NO_TILE, s.at(gx0 + 300.0, gy0 + 10.0))
        }
    }

    @Test
    fun nodataNeighbourAndAllNodata() {
        val holes = setOf(gx0 + 10 to gy0 + 10, gx0 + 100 to gy0 + 100, gx0 + 101 to gy0 + 100,
            gx0 + 100 to gy0 + 101, gx0 + 101 to gy0 + 101)
        sampler(mapOf(key(0, 0) to tile(tx0, ty0) { gi, gj -> if (gi to gj in holes) null else plane(gi, gj) })).use { s ->
            s.loadAll()
            // point between pixel centres 10 and 11 (x) and 10/11 (y), closest to (11, 11)
            assertValue(plane(gx0 + 11, gy0 + 11), s.at(gx0 + 11.4, gy0 + 11.3), conf = 0.5f)
            // all four neighbours nodata
            assertUnavailable(UnavailableReason.NODATA, s.at(gx0 + 101.0, gy0 + 101.0))
        }
    }

    @Test
    fun outOfCoverage() {
        sampler(mapOf(key(0, 0) to tile(tx0, ty0))).use { s ->
            s.loadAll()
            assertUnavailable(UnavailableReason.OUT_OF_COVERAGE, s.at(gx0 - 10.0, gy0 + 10.0))
            assertUnavailable(UnavailableReason.OUT_OF_COVERAGE, s.sample(89.0, 21.0))
            assertUnavailable(UnavailableReason.OUT_OF_COVERAGE, s.sample(Double.NaN, 21.0))
        }
    }

    @Test
    fun notLoadedBeforePrefetchThenValueAfter() {
        sampler(grid2x2()).use { s ->
            assertUnavailable(UnavailableReason.NOT_LOADED, s.at(gx0 + 40.0, gy0 + 40.0))
            val r = runBlocking { s.prefetchTiles(listOf(key(0, 0))) }
            assertEquals(1, r.loaded)
            assertValue(planeAt(gx0 + 40.0, gy0 + 40.0), s.at(gx0 + 40.0, gy0 + 40.0))
            // neighbour tile not loaded yet -> NOT_LOADED, never a timing-dependent substitute
            assertUnavailable(UnavailableReason.NOT_LOADED, s.at(gx0 + 255.9, gy0 + 40.0))
            runBlocking { s.prefetch(GeoBounds(lat(gy0 + 300.0), lon(gx0 + 10.0), lat(gy0 + 10.0), lon(gx0 + 300.0))) }
            assertValue(planeAt(gx0 + 255.9, gy0 + 40.0), s.at(gx0 + 255.9, gy0 + 40.0))
        }
    }

    @Test
    fun corruptTileIsNegativelyCachedAndNotRetried() {
        val tiles = mapOf(key(0, 0) to byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3),
            key(1, 0) to tile(tx0 + 1, ty0))
        sampler(tiles).use { s ->
            val r1 = runBlocking { s.prefetchTiles(listOf(key(0, 0), key(1, 0))) }
            assertEquals(1, r1.corrupt)
            assertEquals(1, r1.loaded)
            assertUnavailable(UnavailableReason.CORRUPT, s.at(gx0 + 40.0, gy0 + 40.0))
            val before = decodes.get()
            val r2 = runBlocking { s.prefetchTiles(listOf(key(0, 0), key(1, 0))) }
            assertEquals(2, r2.alreadyCached)
            assertEquals(before, decodes.get())
            assertEquals(1, diagnostics.size)
            // a sample in the good tile whose left neighbour column is in the corrupt tile falls back
            assertValue(plane(gx0 + 256, gy0 + 40), s.at(gx0 + 256.2, gy0 + 40.5), conf = 0.5f)
        }
    }

    @Test
    fun cacheEvictionMakesTilesNotLoadedAgain() {
        val keys = (0 until 12).map { key(it, 0) }
        val cache = DemTileCache(maxBytes = 9 * DemBlock.RETAINED_BYTES)
        sampler(keys.associateWith { tile(it.x, it.y) }, cache = cache).use { s ->
            // one tile at a time so the LRU access sequence is deterministic
            keys.take(9).forEach { k -> runBlocking { s.prefetchTiles(listOf(k)) } }
            assertValue(planeAt(gx0 + 40.0, gy0 + 40.0), s.at(gx0 + 40.0, gy0 + 40.0)) // touches tile 0 -> most recent
            keys.drop(9).forEach { k -> runBlocking { s.prefetchTiles(listOf(k)) } }
            assertEquals(9, cache.stats().blocks)
            assertEquals(3L, cache.stats().evictions)
            // evicted: tiles 1, 2, 3 (least recently used); tile 0 survived because it was sampled
            assertValue(planeAt(gx0 + 40.0, gy0 + 40.0), s.at(gx0 + 40.0, gy0 + 40.0))
            for (i in 1..3) assertUnavailable(UnavailableReason.NOT_LOADED, s.at(gx0 + 256.0 * i + 40.0, gy0 + 40.0))
            assertValue(planeAt(gx0 + 256.0 * 4 + 40.0, gy0 + 40.0), s.at(gx0 + 256.0 * 4 + 40.0, gy0 + 40.0))
        }
    }

    @Test
    fun overlappingPrefetchRefreshesRecencyOfAlreadyCachedTiles() {
        // SR-002: tiles counted as already cached must not be the eviction victims of the same prefetch's loads.
        val keys = (0 until 12).map { key(it, 0) }
        val cache = DemTileCache(maxBytes = 9 * DemBlock.RETAINED_BYTES)
        fun centre(i: Int) = gx0 + 256.0 * i + 40.0
        sampler(keys.associateWith { tile(it.x, it.y) }, cache = cache).use { s ->
            val windowA = keys.take(6)
            val windowB = keys.drop(6)
            // Window A, one tile at a time so the LRU order is deterministic: 0 (oldest) .. 5.
            windowA.forEach { k -> runBlocking { s.prefetchTiles(listOf(k)) } }
            // Window B: 6 new tiles into a 9-block cache evicts A's tiles 0, 1, 2; LRU order is now 3, 4, 5, 6..11.
            val rb = runBlocking { s.prefetchTiles(windowB) }
            assertEquals(6, rb.loaded)
            assertEquals(3L, cache.stats().evictions)
            // Window A again: 3, 4, 5 are cached but are the oldest entries; loading 0, 1, 2 needs 3 evictions.
            val ra = runBlocking { s.prefetchTiles(windowA) }
            assertEquals(3, ra.alreadyCached)
            assertEquals(3, ra.loaded)
            assertEquals(9, cache.stats().blocks)
            assertEquals(6L, cache.stats().evictions)
            // Immediately after a successful prefetch the whole window is sampleable (pre-fix: 3, 4, 5 were NOT_LOADED).
            for (i in 0..5) assertValue(planeAt(centre(i), gy0 + 40.0), s.at(centre(i), gy0 + 40.0))
            // The victims are 3 of B's tiles (which 3 depends on B's parallel load completion order).
            val bNotLoaded = (6..11).count { s.at(centre(it), gy0 + 40.0) == ElevationSample.Unavailable(UnavailableReason.NOT_LOADED) }
            assertEquals(3, bNotLoaded)
        }
    }

    @Test
    fun prefetchLargerThanCacheIsRejected() {
        val cache = DemTileCache(maxBytes = 9 * DemBlock.RETAINED_BYTES)
        sampler(mapOf(key(0, 0) to tile(tx0, ty0)), cache = cache).use { s ->
            val many = (0 until 10).map { key(it, 0) }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { s.prefetchTiles(many) } }
        }
    }

    @Test
    fun concurrentPrefetchLoadsEachTileOnce() {
        sampler(grid2x2()).use { s ->
            val keys = grid2x2().keys.toList()
            runBlocking {
                (0 until 16).map { async(kotlinx.coroutines.Dispatchers.Default) { s.prefetchTiles(keys) } }.awaitAll()
            }
            assertEquals(4, decodes.get())
            assertValue(planeAt(gx0 + 300.0, gy0 + 300.0), s.at(gx0 + 300.0, gy0 + 300.0))
        }
    }

    @Test
    fun tilesForIncludesOneTileRing() {
        sampler(mapOf(key(0, 0) to tile(tx0, ty0))).use { s ->
            val b = GeoBounds(lat(gy0 + 200.0), lon(gx0 + 10.0), lat(gy0 + 10.0), lon(gx0 + 200.0))
            val t = s.tilesFor(b)
            assertEquals(9, t.size)
            assertTrue(t.contains(key(-1, -1)) && t.contains(key(1, 1)))
        }
    }
}
