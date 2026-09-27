package pl.mazovia.offroad.terrain.presentation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.terrain.TestFixtures
import pl.mazovia.offroad.terrain.elevation.ElevationSample
import pl.mazovia.offroad.terrain.elevation.ElevationSampler
import pl.mazovia.offroad.terrain.elevation.ElevationSourceMetadata
import pl.mazovia.offroad.terrain.elevation.GeoBounds
import pl.mazovia.offroad.terrain.elevation.UnavailableReason
import pl.mazovia.offroad.terrain.geo.LocalFrame
import pl.mazovia.offroad.terrain.profile.DisplayElevationProfile
import pl.mazovia.offroad.terrain.profile.FilteredElevationProfile
import pl.mazovia.offroad.terrain.profile.GradeEventDetector
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.profile.RawElevationProfile
import pl.mazovia.offroad.terrain.projection.RouteIndex
import kotlin.math.abs
import kotlin.math.hypot

class TerrainGridTest {

    private val world = LocalFrame(TestFixtures.ORIGIN)

    /** DEM over the test area: [height] of (east, north) metres from [TestFixtures.ORIGIN]; null = unavailable. */
    private class Dem(private val frame: LocalFrame, private val height: (Double, Double) -> Double?) : ElevationSampler {
        val prefetched = ArrayList<GeoBounds>()
        override val metadata = ElevationSourceMetadata("test", 3.0, "test", "synthetic grid test surface")
        override fun sample(latitude: Double, longitude: Double): ElevationSample {
            val p = pl.mazovia.offroad.domain.model.GeoPoint(latitude, longitude)
            val h = height(frame.eastM(p), frame.northM(p)) ?: return ElevationSample.Unavailable(UnavailableReason.NODATA)
            return ElevationSample.Value(h)
        }
        override suspend fun prefetch(bounds: GeoBounds) { prefetched += bounds }
    }

    /** Route along the DEM's east axis, profile taken from [dem] on the route (north = 0). */
    private fun corridor(dem: (Double, Double) -> Double?, riderM: Double, lengthM: Double = 2000.0,
                         exaggeration: Float = CorridorGeometry.DEFAULT_EXAGGERATION,
                         route: Route = TestFixtures.calculatedRoute("r", TestFixtures.line(0.0, 0.0, lengthM, 0.0)),
                         display: DisplayElevationProfile? = null): CorridorGeometry {
        val index = RouteIndex.build(route)
        val profile = display ?: displayOf(route.id, index.totalLengthM) { dem(it, 0.0) }
        return CorridorGeometry.build(CorridorRoute.build(route, index), profile, emptyList(), riderM, exaggeration)!!
    }

    private fun displayOf(id: String, lengthM: Double, height: (Double) -> Double?): DisplayElevationProfile {
        val raw = RawElevationProfile.fromHeights(id, (0..(lengthM / 5.0).toInt()).map { height(it * 5.0) })
        val grade = GradeProfile.from(FilteredElevationProfile.from(raw), emptyList())
        GradeEventDetector().detect(grade)
        return DisplayElevationProfile.from(grade.filtered)
    }

    /** Tilted plane: 3 % up to the east, 5 % up to the north. */
    private val plane: (Double, Double) -> Double? = { e, n -> 180.0 + 0.03 * e + 0.05 * n }

    private fun geoKey(grid: TerrainGrid, node: Int): Pair<Long, Long> {
        val p = LocalFrame(grid.origin).toGeo(grid.eastAt(node).toDouble(), grid.northAt(node).toDouble())
        return Math.round(p.latitude * 1e6) to Math.round(p.longitude * 1e6)
    }

    @Test fun buildPrefetchesTheBandBeforeSampling() = runBlocking {
        val dem = Dem(world, plane)
        val g = corridor(plane, 300.0)
        val grid = TerrainGrid.build(g, dem)!!
        assertEquals(listOf(TerrainGrid.bounds(g)), dem.prefetched)
        assertTrue(grid.cellCount > 0)
        assertEquals(0, grid.stats.cellsDropped)
    }

    @Test fun latticeNodesKeepTheirGroundPositionAndHeightAcrossRebuilds() {
        val dem = Dem(world, plane)
        val a = TerrainGrid.sample(corridor(plane, 300.0), dem)!!
        val b = TerrainGrid.sample(corridor(plane, 331.0), dem)!!
        assertTrue(a.origin != b.origin)
        val heights = HashMap<Pair<Long, Long>, Float>()
        for (node in 0 until a.nodeCount) if (!a.heightAt(node).isNaN()) heights[geoKey(a, node)] = a.heightAt(node)
        var shared = 0
        for (node in 0 until b.nodeCount) {
            val h = b.heightAt(node)
            if (h.isNaN()) continue
            val before = heights[geoKey(b, node)] ?: continue
            assertEquals(before, h, 1e-3f)
            shared++
        }
        // The rebuilt band overlaps the old one almost entirely (31 m of motion on a ~1.1 km band).
        assertTrue("shared $shared of ${heights.size}", shared > heights.size * 0.9)
    }

    @Test fun onlyNodesWithinTheBandAreSampled() {
        val dem = Dem(world, plane)
        val g = corridor(plane, 300.0)
        val grid = TerrainGrid.sample(g, dem)!!
        for (node in 0 until grid.nodeCount) {
            val e = grid.eastAt(node).toDouble()
            val n = grid.northAt(node).toDouble()
            // Distance to the straight corridor polyline (east axis between its first and last sample).
            val along = e.coerceIn(g.eastAt(0).toDouble(), g.eastAt(g.size - 1).toDouble())
            val distance = hypot(e - along, n)
            if (distance > TerrainGrid.HALF_WIDTH_M + 1e-3) assertTrue(grid.heightAt(node).isNaN())
            if (distance < TerrainGrid.HALF_WIDTH_M - CorridorGeometry.STEP_M) {
                assertFalse("node at $e/$n", grid.heightAt(node).isNaN())
            }
        }
        assertEquals(grid.stats.nodesInBand, grid.stats.nodesAvailable)
    }

    @Test fun bandMatchesTheExhaustiveSampleDistanceTest() {
        // A winding route and a GPX route with a break between its parts (the plan path jumps at the break).
        val winding = TestFixtures.calculatedRoute("w", TestFixtures.meanderingPoints(150))
        val gpx = TestFixtures.gpxRoute("g", listOf(TestFixtures.line(0.0, 0.0, 400.0, 0.0),
            TestFixtures.line(400.0, 150.0, 1400.0, 150.0)))
        for ((route, rider) in listOf(winding to 200.0, gpx to 300.0)) {
            val g = corridor(plane, rider, route = route,
                display = displayOf(route.id, RouteIndex.build(route).totalLengthM) { 100.0 })
            val grid = TerrainGrid.sample(g, Dem(world, plane))!!
            val half2 = (TerrainGrid.HALF_WIDTH_M * TerrainGrid.HALF_WIDTH_M).toFloat()
            var inBand = 0
            for (node in 0 until grid.nodeCount) {
                val e = grid.eastAt(node)
                val n = grid.northAt(node)
                val expected = (0 until g.size).any {
                    val dx = g.eastAt(it) - e
                    val dy = g.northAt(it) - n
                    dx * dx + dy * dy <= half2
                }
                assertEquals("${route.id} node $node", expected, !grid.heightAt(node).isNaN())
                if (expected) inBand++
            }
            assertEquals(inBand, grid.stats.nodesInBand)
        }
    }

    @Test fun placeInPutsAnOlderGridIntoTheNewWindowFrame() {
        val dem = Dem(world, plane)
        val older = corridor(plane, 300.0)
        val newer = corridor(plane, 331.0, display = older.display)
        val grid = TerrainGrid.sample(older, dem)!!
        val fresh = TerrainGrid.sample(newer, dem)!!
        assertEquals(newer.origin, fresh.origin)
        val x = FloatArray(grid.nodeCount)
        val y = FloatArray(grid.nodeCount)
        val z = FloatArray(grid.nodeCount)
        grid.placeIn(newer, x, y, z)
        // The same ground node, built for either window, lands on the same scene point of the newer window.
        val freshNodes = HashMap<Pair<Long, Long>, Int>()
        for (node in 0 until fresh.nodeCount) if (!fresh.heightAt(node).isNaN()) freshNodes[geoKey(fresh, node)] = node
        var shared = 0
        for (node in 0 until grid.nodeCount) {
            if (grid.heightAt(node).isNaN()) { assertTrue(z[node].isNaN()); continue }
            val other = freshNodes[geoKey(grid, node)] ?: continue
            assertEquals(fresh.eastAt(other), x[node], 0.01f)
            assertEquals(fresh.northAt(other), y[node], 0.01f)
            val scene = ((fresh.heightAt(other) - newer.baseDisplayM) * newer.exaggeration).toFloat()
            assertEquals(scene, z[node], 0.01f)
            shared++
        }
        assertTrue("shared $shared", shared > freshNodes.size * 0.9)
    }

    @Test(expected = IllegalArgumentException::class) fun placeInRejectsACorridorItDoesNotFit() {
        val grid = TerrainGrid.sample(corridor(plane, 300.0), Dem(world, plane))!!
        val size = grid.nodeCount
        grid.placeIn(corridor(plane, 300.0), FloatArray(size), FloatArray(size), FloatArray(size))
    }

    @Test fun heightsUseTheDisplayDatumOfTheCorridor() {
        // DEM 180 m + slope; the profile's Display heights are relative, so the grid recovers the datum.
        val dem = Dem(world, plane)
        val g = corridor(plane, 300.0)
        val grid = TerrainGrid.sample(g, dem)!!
        assertTrue(grid.fits(g))
        assertTrue(grid.stats.ribbonOffsetMedianM < 0.05)
        // A node on the route axis gets the corridor's own scene height there.
        val frame = LocalFrame(g.origin)
        val point = FloatArray(3)
        for (node in 0 until grid.nodeCount) {
            val h = grid.heightAt(node)
            if (h.isNaN()) continue
            val gridFrame = LocalFrame(grid.origin)
            val geo = gridFrame.toGeo(grid.eastAt(node).toDouble(), grid.northAt(node).toDouble())
            val e = frame.eastM(geo)
            val n = frame.northM(geo)
            if (abs(n) > 13.0 || e < -10.0 || e > 500.0) continue
            val i = g.indexAtOrBefore(g.anchorM + e)
            point[2] = g.sceneHeightAt(i) + (g.sceneHeightAt(i + 1) - g.sceneHeightAt(i)) *
                ((g.anchorM + e - g.distanceAt(i)) / (g.distanceAt(i + 1) - g.distanceAt(i))).toFloat()
            val scene = ((h - g.baseDisplayM) * g.exaggeration).toFloat()
            // The node lies up to 12.5 m north of the axis on a 5 % slope.
            assertEquals(point[2], scene, (0.05 * 13.0 * g.exaggeration + 0.2).toFloat())
        }
    }

    @Test fun unavailableNodesLeaveHolesNeverZero() {
        // No DEM within 60 m of a point 150 m ahead and 80 m left of the route.
        val holed: (Double, Double) -> Double? = { e, n -> if (hypot(e - 450.0, n - 80.0) < 60.0) null else plane(e, n) }
        val g = corridor(holed, 300.0)
        val grid = TerrainGrid.sample(g, Dem(world, holed))!!
        assertTrue(grid.stats.cellsDropped > 0)
        assertTrue(grid.stats.nodesAvailable < grid.stats.nodesInBand)
        val frame = LocalFrame(TestFixtures.ORIGIN)
        val gridFrame = LocalFrame(grid.origin)
        var holes = 0
        for (node in 0 until grid.nodeCount) {
            val geo = gridFrame.toGeo(grid.eastAt(node).toDouble(), grid.northAt(node).toDouble())
            if (hypot(frame.eastM(geo) - 450.0, frame.northM(geo) - 80.0) < 55.0) {
                assertTrue(grid.heightAt(node).isNaN())
                holes++
            }
            assertFalse(grid.heightAt(node) == 0f)
        }
        assertTrue(holes > 0)
        for (cell in 0 until grid.cellCount) {
            val a = grid.cellNode(cell)
            for (node in intArrayOf(a, a + 1, a + grid.cols, a + grid.cols + 1)) {
                assertFalse(grid.heightAt(node).isNaN())
            }
        }
    }

    @Test fun gridBuildNeverTouchesTheProfileOrTheCorridor() {
        val dem = Dem(world, plane)
        val g = corridor(plane, 300.0)
        val display = g.display!!
        val profileBefore = (0 until display.size).map { display.relativeHeightAt(it) }
        val sceneBefore = (0 until g.size).map { g.sceneHeightAt(it) }
        TerrainGrid.sample(g, dem)!!
        assertEquals(profileBefore, (0 until display.size).map { display.relativeHeightAt(it) })
        assertEquals(sceneBefore, (0 until g.size).map { g.sceneHeightAt(it) })
    }

    @Test fun noGridWithoutCorridorHeightsOrDatum() = runBlocking {
        val route = TestFixtures.calculatedRoute("r", TestFixtures.line(0.0, 0.0, 2000.0, 0.0))
        val noHeights = corridor(plane, 300.0, route = route, display = displayOf(route.id, 2000.0) { null })
        assertFalse(TerrainGrid.canBuild(noHeights))
        val dem = Dem(world, plane)
        assertNull(TerrainGrid.build(noHeights, dem))
        assertTrue(dem.prefetched.isEmpty())
        // Profile heights but no DEM at any corridor sample: no datum, no grid.
        val g = corridor(plane, 300.0)
        assertNull(TerrainGrid.sample(g, Dem(world) { _, _ -> null }))
    }

    @Test fun fitsOnlyTheSameRouteProfileAndExaggeration() {
        val dem = Dem(world, plane)
        val g = corridor(plane, 300.0)
        val grid = TerrainGrid.sample(g, dem)!!
        assertTrue(grid.fits(corridor(plane, 340.0, display = g.display)))
        assertFalse(grid.fits(corridor(plane, 300.0)))
        assertFalse(grid.fits(corridor(plane, 300.0, display = g.display, exaggeration = 1.5f)))
        val other = TestFixtures.calculatedRoute("other", TestFixtures.line(0.0, 0.0, 2000.0, 0.0))
        assertFalse(grid.fits(corridor(plane, 300.0, route = other)))
    }

    @Test fun slopesFacingTheLightAreBrighter() {
        // Ridge along the route 100 m north: south-facing below it, north-facing beyond it.
        val ridge: (Double, Double) -> Double? = { e, n -> 180.0 + 0.01 * e + 0.3 * (100.0 - abs(n - 100.0)) }
        val g = corridor(ridge, 300.0)
        val grid = TerrainGrid.sample(g, Dem(world, ridge))!!
        val gridFrame = LocalFrame(grid.origin)
        var south = 0f; var southN = 0; var north = 0f; var northN = 0
        for (cell in 0 until grid.cellCount) {
            val a = grid.cellNode(cell)
            val n = world.northM(gridFrame.toGeo(grid.eastAt(a).toDouble(), grid.northAt(a).toDouble()))
            if (n in 10.0..70.0) { south += grid.lightAt(cell); southN++ }
            if (n in 130.0..170.0) { north += grid.lightAt(cell); northN++ }
        }
        assertTrue(southN > 0 && northN > 0)
        assertTrue(south / southN > north / northN + 0.2f)
    }
}
