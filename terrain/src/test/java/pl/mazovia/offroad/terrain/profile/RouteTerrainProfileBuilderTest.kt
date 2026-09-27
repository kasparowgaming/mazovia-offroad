package pl.mazovia.offroad.terrain.profile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.terrain.TestFixtures
import pl.mazovia.offroad.terrain.dem.DemBlock
import pl.mazovia.offroad.terrain.dem.DemTestSupport
import pl.mazovia.offroad.terrain.dem.DemTileCache
import pl.mazovia.offroad.terrain.dem.PmtilesElevationSampler
import pl.mazovia.offroad.terrain.dem.PmtilesReader
import pl.mazovia.offroad.terrain.dem.PurePng
import pl.mazovia.offroad.terrain.dem.TerrainRgbTileDecoder
import pl.mazovia.offroad.terrain.dem.TileId
import pl.mazovia.offroad.terrain.dem.TileKey
import pl.mazovia.offroad.terrain.dem.WebMercator
import pl.mazovia.offroad.terrain.elevation.UnavailableReason
import pl.mazovia.offroad.terrain.projection.RouteIndex
import kotlin.math.floor

class RouteTerrainProfileBuilderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val z = 15
    private val tx0 = floor(WebMercator.worldPxX(21.0, z) / 256).toInt()
    private val ty0 = floor(WebMercator.worldPxY(52.2, z) / 256).toInt()
    private val tilesAlong = 12
    private val missing = 5

    private fun key(dx: Int) = TileKey(z, tx0 + dx, ty0)

    /** A strip of tiles with a height ramp along x; tile [missing] is absent from the archive but inside its bounds. */
    private fun sampler(cache: DemTileCache): PmtilesElevationSampler {
        val keys = (0 until tilesAlong).map(::key)
        val tiles = keys.filter { it.x != tx0 + missing }.associate { k ->
            TileId.fromKey(k) to DemTestSupport.terrainTile { c, _ -> 100.0 + 0.01 * ((k.x - tx0) * 256 + c) }
        }
        val f = tmp.newFile()
        DemTestSupport.writeArchive(f, tiles, z, z, DemTestSupport.union(keys), DemTestSupport.metadataJson(z))
        return PmtilesElevationSampler(PmtilesReader.open(f), TerrainRgbTileDecoder(PurePng.Decoder()), cache)
    }

    private fun route(): RouteIndex {
        val lat = WebMercator.latitudeOfWorldPx(ty0 * 256.0 + 128.0, z)
        val lon0 = WebMercator.longitudeOfWorldPx(tx0 * 256.0 + 20.0, z)
        val lon1 = WebMercator.longitudeOfWorldPx((tx0 + tilesAlong) * 256.0 - 20.0, z)
        return RouteIndex.build(TestFixtures.calculatedRoute("strip", listOf(GeoPoint(lat, lon0), GeoPoint(lat, lon1))))
    }

    @Test fun `chunked build under a small tile budget equals a build that fits the cache`() = runBlocking {
        val small = sampler(DemTileCache(maxBytes = 9L * DemBlock.RETAINED_BYTES)).use {
            RouteTerrainProfileBuilder.build(route(), it)
        }
        val large = sampler(DemTileCache()).use { RouteTerrainProfileBuilder.build(route(), it) }
        assertEquals("strip", small.routeId)
        assertEquals(large.raw.size, small.raw.size)
        assertTrue(small.raw.size > 1000)
        for (i in 0 until small.raw.size) {
            assertEquals(large.raw.distanceAt(i), small.raw.distanceAt(i), 0.0)
            assertEquals(large.raw.heightAt(i), small.raw.heightAt(i))
            assertEquals(large.raw.unavailableReasonAt(i), small.raw.unavailableReasonAt(i))
        }
        assertTrue(small.availableSamples > small.raw.size / 2)
    }

    @Test fun `absent tile is a gap, never 0 m, and grade does not bridge it`() = runBlocking {
        val profile = sampler(DemTileCache()).use { RouteTerrainProfileBuilder.build(route(), it) }
        val raw = profile.raw
        val gap = (0 until raw.size).filter { !raw.isAvailable(it) }
        assertTrue(gap.isNotEmpty())
        gap.forEach {
            assertNull(raw.heightAt(it))
            assertTrue(raw.unavailableReasonAt(it) == UnavailableReason.NO_TILE ||
                raw.unavailableReasonAt(it) == UnavailableReason.NODATA)
            assertNull(profile.grade.gradeAt(it))
        }
        (0 until raw.size).mapNotNull { raw.heightAt(it) }.forEach { assertTrue(it >= 100.0) }
    }
}
