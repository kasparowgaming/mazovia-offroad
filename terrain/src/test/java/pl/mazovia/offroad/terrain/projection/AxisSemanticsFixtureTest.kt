package pl.mazovia.offroad.terrain.projection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.HighwayType
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RouteMetrics
import pl.mazovia.offroad.domain.model.RouteSegment
import pl.mazovia.offroad.domain.model.RouteSource
import pl.mazovia.offroad.domain.model.RoutingProfile
import pl.mazovia.offroad.domain.model.Surface

/**
 * SR-003 (TA-001A-F1): small fixture with HARD-CODED expected cumulative distances, independent of the RouteIndex
 * accumulation code and of TestFixtures. Points lie on one meridian, where haversine distance is exactly R·|Δφ|, so
 * vertex spacing in metres is chosen directly. Expectations encode the documented NavigationManager rules:
 * vertex order = route.allPoints; a shared calculated-segment boundary vertex adds 0; an IMPORTED_GPX break adds 0;
 * a gap between non-GPX segments is counted (NavigationManager applies breaks only to IMPORTED_GPX).
 */
class AxisSemanticsFixtureTest {

    private val metersPerDegLat = 6_371_000.0 * Math.PI / 180.0

    /** Point [northM] metres north of 52°N on the 21°E meridian. */
    private fun north(northM: Double) = GeoPoint(52.0 + northM / metersPerDegLat, 21.0)

    private fun route(source: RouteSource, vararg segments: List<Double>): Route {
        val segs = segments.map { ys ->
            RouteSegment(points = ys.map { north(it) }, distanceMeters = 0.0, surface = Surface.UNKNOWN, highway = HighwayType.UNKNOWN)
        }
        val all = segs.flatMap { it.points }
        return Route("fixture", all.first(), all.last(), segs, RouteMetrics.EMPTY, RoutingProfile.TERENOWY, source = source)
    }

    private fun assertAxis(expected: DoubleArray, route: Route) {
        val index = RouteIndex.build(route)
        assertArrayEquals(expected, DoubleArray(route.allPoints.size) { index.cumulativeAt(it) }, 1e-6)
    }

    @Test
    fun `calculated route - shared boundary vertex adds zero and the next edge resumes the axis`() {
        val r = route(RouteSource.CALCULATED_ROUTE, listOf(0.0, 100.0, 200.0), listOf(200.0, 300.0, 500.0))
        assertAxis(doubleArrayOf(0.0, 100.0, 200.0, 200.0, 300.0, 500.0), r)
        assertEquals(1, RouteIndex.build(r).parts.size)
    }

    @Test
    fun `imported GPX - a 400 m segment gap adds zero`() {
        val r = route(RouteSource.IMPORTED_GPX, listOf(0.0, 100.0, 200.0), listOf(600.0, 700.0))
        assertAxis(doubleArrayOf(0.0, 100.0, 200.0, 200.0, 300.0), r)
        val index = RouteIndex.build(r)
        assertFalse(index.edges.any { it.fromPointIndex == 2 && it.toPointIndex == 3 })
        assertEquals(2, index.parts.size)
        assertEquals(200.0, index.parts[1].startS, 1e-6)
    }

    @Test
    fun `imported GPX - identical boundary coordinates stay two parts`() {
        val r = route(RouteSource.IMPORTED_GPX, listOf(0.0, 100.0), listOf(100.0, 250.0))
        assertAxis(doubleArrayOf(0.0, 100.0, 100.0, 250.0), r)
        assertEquals(2, RouteIndex.build(r).parts.size)
    }

    @Test
    fun `non-GPX segments with a gap count the gap like NavigationManager`() {
        val r = route(RouteSource.CALCULATED_ROUTE, listOf(0.0, 100.0), listOf(150.0, 250.0))
        assertAxis(doubleArrayOf(0.0, 100.0, 150.0, 250.0), r)
    }
}
