package pl.mazovia.offroad.terrain.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.terrain.TestFixtures
import pl.mazovia.offroad.terrain.TestFixtures.at
import pl.mazovia.offroad.terrain.TestFixtures.line
import kotlin.math.abs

class RouteIndexTest {

    private fun maxAxisError(route: pl.mazovia.offroad.domain.model.Route): Double {
        val expected = TestFixtures.navigationManagerAxis(route)
        val index = RouteIndex.build(route)
        assertEquals(expected.size, route.allPoints.size)
        var max = 0.0
        for (i in expected.indices) max = maxOf(max, abs(index.cumulativeAt(i) - expected[i]))
        return max
    }

    @Test
    fun `100 km route - RouteIndex matches the documented NavigationManager-compatible cumulative-distance semantics (regression)`() {
        val route = TestFixtures.calculatedRoute("r100", TestFixtures.meanderingPoints(10_001), segmentLength = 997)
        val index = RouteIndex.build(route)
        assertTrue("route length ${index.totalLengthM}", index.totalLengthM in 99_000.0..101_000.0)
        val err = maxAxisError(route)
        println("AXIS 100 km (regression vs documented NM loop, not an independent NM oracle): vertices=${route.allPoints.size} maxAbsError=${err} m")
        assertTrue("max axis error $err m", err <= 1e-3)
    }

    @Test
    fun `300 km stress route - RouteIndex matches the documented NavigationManager-compatible cumulative-distance semantics (regression)`() {
        val route = TestFixtures.calculatedRoute("r300", TestFixtures.meanderingPoints(30_001), segmentLength = 1_499)
        val index = RouteIndex.build(route)
        assertTrue("route length ${index.totalLengthM}", index.totalLengthM in 297_000.0..303_000.0)
        val err = maxAxisError(route)
        println("AXIS 300 km (regression vs documented NM loop, not an independent NM oracle): vertices=${route.allPoints.size} maxAbsError=${err} m")
        assertTrue("max axis error $err m", err <= 1e-3)
    }

    @Test
    fun `projection at every sampled vertex reproduces the axis on the 300 km route`() {
        val route = TestFixtures.calculatedRoute("r300p", TestFixtures.meanderingPoints(30_001), segmentLength = 1_499)
        val index = RouteIndex.build(route)
        val expected = TestFixtures.navigationManagerAxis(route)
        var max = 0.0
        for (i in route.allPoints.indices step 97) {
            val best = index.candidates(route.allPoints[i], 60.0)
                .filter { it.crossTrackM < 1e-6 }
                .minByOrNull { abs(it.distanceAlongM - expected[i]) }
            assertNotNull("vertex $i not found", best)
            max = maxOf(max, abs(best!!.distanceAlongM - expected[i]))
        }
        println("AXIS 300 km projected vertices: maxAbsError=$max m")
        assertTrue("projected vertex error $max", max <= 1e-3)
    }

    @Test
    fun `last vertex has no cumulative drift`() {
        val route = TestFixtures.calculatedRoute("r100d", TestFixtures.meanderingPoints(10_001))
        val index = RouteIndex.build(route)
        val expected = TestFixtures.navigationManagerAxis(route)
        assertEquals(expected.last(), index.totalLengthM, 1e-3)
        assertEquals(expected.last(), index.locate(Double.MAX_VALUE)!!.distanceAlongM, 1e-3)
    }

    @Test
    fun `GPX gap contributes zero and no edge crosses the gap`() {
        val seg1 = line(0.0, 0.0, 500.0, 0.0)
        val seg2 = line(800.0, 0.0, 1300.0, 0.0)
        val seg3 = line(1600.0, 0.0, 2000.0, 0.0)
        val route = TestFixtures.gpxRoute("gpx", listOf(seg1, seg2, seg3))
        val index = RouteIndex.build(route)
        val firstOfSeg2 = seg1.size
        val firstOfSeg3 = seg1.size + seg2.size
        assertEquals(index.cumulativeAt(firstOfSeg2 - 1), index.cumulativeAt(firstOfSeg2), 0.0)
        assertEquals(index.cumulativeAt(firstOfSeg3 - 1), index.cumulativeAt(firstOfSeg3), 0.0)
        assertFalse(index.edges.any { it.fromPointIndex == firstOfSeg2 - 1 && it.toPointIndex == firstOfSeg2 })
        assertFalse(index.edges.any { it.fromPointIndex == firstOfSeg3 - 1 && it.toPointIndex == firstOfSeg3 })
        assertEquals(0.0, maxAxisError(route), 1e-3)
        // Total = sum of segment lengths only (≈ 500 + 500 + 400), not including 600 m of gaps.
        assertEquals(1400.0, index.totalLengthM, 1.0)
        // A point in the middle of a gap is not on any edge.
        assertTrue(index.candidates(at(650.0, 0.0), 60.0).isEmpty())
    }

    @Test
    fun `shared segment boundary vertex yields a zero-length edge that is never a candidate`() {
        val pts = line(0.0, 0.0, 1000.0, 0.0)
        val route = TestFixtures.calculatedRoute("dup", pts, segmentLength = 25)
        val index = RouteIndex.build(route)
        val zero = index.edges.filter { it.isZeroLength }
        assertTrue("expected duplicate boundary edges", zero.isNotEmpty())
        val boundary = route.allPoints[zero.first().fromPointIndex]
        val candidates = index.candidates(boundary, 60.0)
        assertTrue(candidates.none { it.edge.isZeroLength })
        assertEquals(0.0, maxAxisError(route), 1e-3)
    }

    @Test
    fun `long single edge is found in grid cells far from both endpoints`() {
        val route = TestFixtures.calculatedRoute("long", listOf(at(0.0, 0.0), at(2000.0, 1500.0)))
        val index = RouteIndex.build(route)
        val onLine = at(1000.0 - 18.0, 750.0 + 24.0) // 30 m from the edge midpoint, perpendicular
        val c = index.candidates(onLine, 60.0)
        assertEquals(1, c.size)
        // Tolerances cover the fixture's own approximation (offsets use the origin-latitude scale over 750 m of
        // latitude), not the projection: DERIVED ≈ 1.5e-4 relative → ≤ 0.2 m here.
        assertEquals(30.0, c[0].crossTrackM, 0.3)
        assertEquals(1250.0, c[0].distanceAlongM, 1.0)
    }

    @Test
    fun `projection before start and after end clamps to route ends`() {
        val route = TestFixtures.calculatedRoute("ends", line(0.0, 0.0, 1000.0, 0.0))
        val index = RouteIndex.build(route)
        val before = index.candidates(at(-20.0, 0.0), 60.0).minBy { it.crossTrackM }
        assertEquals(0.0, before.distanceAlongM, 1e-9)
        assertEquals(0.0, before.edgeFraction, 1e-12)
        val after = index.candidates(at(1030.0, 0.0), 60.0).minBy { it.crossTrackM }
        assertEquals(index.totalLengthM, after.distanceAlongM, 1e-6)
        assertEquals(1.0, after.edgeFraction, 1e-12)
    }

    @Test
    fun `tangent bearing follows edge direction`() {
        val east = RouteIndex.build(TestFixtures.calculatedRoute("e", line(0.0, 0.0, 100.0, 0.0)))
        assertEquals(90.0, east.edges.first().tangentBearingDeg, 1e-6)
        val north = RouteIndex.build(TestFixtures.calculatedRoute("n", line(0.0, 0.0, 0.0, 100.0)))
        assertEquals(0.0, north.edges.first().tangentBearingDeg, 1e-6)
    }

    @Test
    fun `locate returns the point at a distance`() {
        val route = TestFixtures.calculatedRoute("loc", line(0.0, 0.0, 1000.0, 0.0))
        val index = RouteIndex.build(route)
        val p = index.locate(345.0)!!
        assertEquals(345.0, p.distanceAlongM, 1e-9)
        val back = index.candidates(p.projected, 10.0).minBy { it.crossTrackM }
        assertEquals(345.0, back.distanceAlongM, 0.01)
    }
}
