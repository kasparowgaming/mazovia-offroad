package pl.mazovia.offroad.terrain.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.domain.model.NavigationStatus
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.terrain.TestFixtures
import pl.mazovia.offroad.terrain.TestFixtures.at
import pl.mazovia.offroad.terrain.TestFixtures.line
import pl.mazovia.offroad.terrain.TestFixtures.path
import pl.mazovia.offroad.terrain.TestFixtures.state

class TerrainRouteProjectionTest {

    private val second = 1_000_000_000L

    /** Feeds fixes 1 s apart; returns all results. */
    private fun run(
        projection: TerrainRouteProjection,
        route: Route,
        positions: List<Pair<Double, Double>>,
        speed: Double = 10.0,
        status: NavigationStatus = NavigationStatus.ON_ROUTE,
        t0: Long = 0L
    ): List<ProjectionResult> = positions.mapIndexed { i, (x, y) ->
        projection.onNavigationState(state(route, at(x, y), status, speed), t0 + i * second)
    }

    private val straight = TestFixtures.calculatedRoute("straight", line(0.0, 0.0, 2000.0, 0.0))

    @Test
    fun `straight route attaches with distance and cross-track`() {
        val r = TerrainRouteProjection().onNavigationState(state(straight, at(305.0, 5.0)), 0L)
        assertEquals(ProjectionMode.ATTACHED, r.mode)
        assertEquals(305.0, r.distanceAlongM!!, 0.5)
        assertEquals(5.0, r.crossTrackM!!, 0.05)
        assertEquals(90.0, r.tangentBearingDeg!!, 1e-6)
    }

    @Test
    fun `gentle curve at a vertex reproduces the cumulative axis`() {
        val pts = (0..90).map { k ->
            val a = Math.toRadians(k.toDouble())
            at(500.0 * kotlin.math.sin(a), 500.0 - 500.0 * kotlin.math.cos(a))
        }
        val route = TestFixtures.calculatedRoute("curve", pts)
        val p = TerrainRouteProjection()
        val index = RouteIndex.build(route)
        val r = p.onNavigationState(state(route, pts[40]), 0L)
        assertEquals(ProjectionMode.ATTACHED, r.mode)
        assertEquals(index.cumulativeAt(40), r.distanceAlongM!!, 1e-3)
        assertEquals(0.0, r.crossTrackM!!, 1e-6)
    }

    @Test
    fun `hairpin keeps the rider on the current leg`() {
        val route = TestFixtures.calculatedRoute("hairpin", path(line(0.0, 0.0, 500.0, 0.0), line(500.0, 0.0, 500.0, 10.0), line(500.0, 10.0, 0.0, 10.0)))
        val p = TerrainRouteProjection()
        // Outbound on y = 0 riding 4 m north of it (6 m from the return leg), round the turn, back on y = 10 riding 4 m south.
        val out = (10..49).map { it * 10.0 to 4.0 } // at x = 500 the rider is already on the turn connector
        val turn = listOf(500.0 to 5.0)
        val back = (1..20).map { 500.0 - it * 10.0 to 6.0 }
        val results = run(p, route, out + turn + back)
        assertTrue(results.all { it.mode == ProjectionMode.ATTACHED })
        assertTrue(results.take(out.size).all { it.distanceAlongM!! <= 500.0 + 1e-6 })
        val s = results.map { it.distanceAlongM!! }
        assertTrue("progress must be monotonic", s.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(510.0 + 200.0, s.last(), 1.0)
    }

    @Test
    fun `X crossing keeps the leg the rider is on`() {
        // East along y = 0 through (500, 0); later the route comes back south to north through the same point.
        val route = TestFixtures.calculatedRoute("x", path(
            line(0.0, 0.0, 1000.0, 0.0), line(1000.0, 0.0, 1000.0, -500.0),
            line(1000.0, -500.0, 500.0, -500.0), line(500.0, -500.0, 500.0, 500.0)
        ))
        val p = TerrainRouteProjection()
        val results = run(p, route, (0..10).map { 450.0 + it * 10.0 to 0.0 })
        assertTrue(results.all { it.mode == ProjectionMode.ATTACHED && it.distanceAlongM!! in 440.0..560.0 })
    }

    @Test
    fun `parallel out-and-back 20 m apart does not switch branches`() {
        val route = TestFixtures.calculatedRoute("par", path(line(0.0, 0.0, 300.0, 0.0), line(300.0, 0.0, 300.0, 20.0), line(300.0, 20.0, 0.0, 20.0)))
        val p = TerrainRouteProjection()
        val results = run(p, route, (0..15).map { 50.0 + it * 10.0 to 8.0 })
        assertTrue(results.all { it.distanceAlongM!! < 300.0 })
    }

    @Test
    fun `closed loop starts at s = 0 and finishes near the total`() {
        val route = TestFixtures.calculatedRoute("loop", path(
            line(0.0, 0.0, 400.0, 0.0), line(400.0, 0.0, 400.0, 400.0),
            line(400.0, 400.0, 0.0, 400.0), line(0.0, 400.0, 0.0, 0.0)
        ))
        val index = RouteIndex.build(route)
        val p = TerrainRouteProjection()
        val first = p.onNavigationState(state(route, at(0.0, 0.0)), 0L)
        assertEquals(0.0, first.distanceAlongM!!, 1e-6)
        // Ride the whole loop in 10 m steps at 10 m/s.
        val lap = (1..160).map { k ->
            val d = k * 10.0
            when {
                d <= 400 -> d to 0.0
                d <= 800 -> 400.0 to d - 400
                d <= 1200 -> 1200.0 - d to 400.0
                else -> 0.0 to 1600.0 - d
            }
        }
        val results = run(p, route, lap, t0 = second)
        assertEquals(index.totalLengthM, results.last().distanceAlongM!!, 1.0)
    }

    @Test
    fun `small backward jitter holds progress`() {
        val p = TerrainRouteProjection()
        val r = run(p, straight, listOf(290.0 to 0.0, 300.0 to 0.0, 295.0 to 0.0))
        assertEquals(ProjectionMode.HOLD, r[2].mode)
        assertEquals(r[1].distanceAlongM!!, r[2].distanceAlongM!!, 0.0)
    }

    @Test
    fun `genuine reversal is accepted after two consistent fixes then follows the rider back`() {
        val p = TerrainRouteProjection()
        val r = run(p, straight, listOf(300.0 to 0.0, 280.0 to 0.0, 262.0 to 0.0, 255.0 to 0.0), speed = 5.0)
        assertEquals(ProjectionMode.ATTACHED, r[0].mode)
        assertEquals(ProjectionMode.HOLD, r[1].mode)
        assertEquals(300.0, r[1].distanceAlongM!!, 0.5)
        assertEquals(ProjectionMode.ATTACHED, r[2].mode)
        assertEquals(262.0, r[2].distanceAlongM!!, 0.5)
        assertEquals(ProjectionMode.ATTACHED, r[3].mode)
        assertEquals(255.0, r[3].distanceAlongM!!, 0.5)
        assertEquals(270.0, r[3].tangentBearingDeg!!, 1e-6)
    }

    @Test
    fun `suspicious forward jump needs three consistent fixes`() {
        val p = TerrainRouteProjection()
        val r = run(p, straight, listOf(300.0 to 0.0, 600.0 to 0.0, 610.0 to 0.0, 620.0 to 0.0))
        assertEquals(ProjectionMode.HOLD, r[1].mode)
        assertEquals(ProjectionMode.HOLD, r[2].mode)
        assertEquals(300.0, r[2].distanceAlongM!!, 0.5)
        assertEquals(ProjectionMode.ATTACHED, r[3].mode)
        assertEquals(620.0, r[3].distanceAlongM!!, 0.5)
    }

    @Test
    fun `geometric off-route detaches after two fixes and reattaches only within 30 m`() {
        val p = TerrainRouteProjection()
        val r = run(p, straight, listOf(300.0 to 0.0, 310.0 to 80.0, 320.0 to 80.0, 330.0 to 40.0, 340.0 to 20.0))
        assertEquals(ProjectionMode.HOLD, r[1].mode)
        assertEquals(ProjectionMode.DETACHED, r[2].mode)
        assertEquals(300.0, r[2].distanceAlongM!!, 0.5)
        assertEquals(ProjectionMode.DETACHED, r[3].mode) // 40 m > reattach 30 m
        assertEquals(ProjectionMode.ATTACHED, r[4].mode)
        assertEquals(340.0, r[4].distanceAlongM!!, 0.5)
    }

    @Test
    fun `navigation OFF_ROUTE status wins over on-route geometry`() {
        val p = TerrainRouteProjection()
        run(p, straight, listOf(300.0 to 0.0))
        val off = p.onNavigationState(state(straight, at(310.0, 0.0), NavigationStatus.OFF_ROUTE), second)
        assertEquals(ProjectionMode.DETACHED, off.mode)
        assertEquals(300.0, off.distanceAlongM!!, 0.5)
        val recalculating = p.onNavigationState(state(straight, at(320.0, 0.0), NavigationStatus.RECALCULATING), 2 * second)
        assertEquals(ProjectionMode.DETACHED, recalculating.mode)
        val recovered = p.onNavigationState(state(straight, at(330.0, 5.0), NavigationStatus.ROUTE_RECOVERED), 3 * second)
        assertEquals(ProjectionMode.ATTACHED, recovered.mode)
        assertEquals(330.0, recovered.distanceAlongM!!, 0.5)
    }

    @Test
    fun `route id replacement rebuilds the index and resets progress`() {
        val p = TerrainRouteProjection()
        run(p, straight, listOf(300.0 to 0.0, 310.0 to 0.0))
        val other = TestFixtures.calculatedRoute("other", line(300.0, 0.0, 300.0, 1000.0))
        val r = p.onNavigationState(state(other, at(300.0, 100.0)), 2 * second)
        assertEquals("other", r.routeId)
        assertEquals(ProjectionMode.ATTACHED, r.mode)
        assertEquals(100.0, r.distanceAlongM!!, 0.5)
    }

    @Test
    fun `GPX with a large gap progresses across segments without counting the gap`() {
        val route = TestFixtures.gpxRoute("gpx", listOf(line(0.0, 0.0, 500.0, 0.0), line(800.0, 0.0, 1300.0, 0.0)))
        val p = TerrainRouteProjection()
        val before = run(p, route, listOf(480.0 to 0.0), status = NavigationStatus.FOLLOWING_GPX)
        assertEquals(480.0, before[0].distanceAlongM!!, 0.5)
        // Inside the gap there is no edge: first HOLD, then DETACHED.
        val gap = run(p, route, listOf(650.0 to 0.0, 700.0 to 0.0), status = NavigationStatus.FOLLOWING_GPX, t0 = second)
        assertEquals(ProjectionMode.HOLD, gap[0].mode)
        assertEquals(ProjectionMode.DETACHED, gap[1].mode)
        // Reattach on the second segment: 820 m east = 500 + 20 m on the axis.
        val after = run(p, route, listOf(820.0 to 0.0), status = NavigationStatus.FOLLOWING_GPX, t0 = 3 * second)
        assertEquals(ProjectionMode.ATTACHED, after[0].mode)
        assertEquals(520.0, after[0].distanceAlongM!!, 0.5)
    }

    @Test
    fun `ARRIVED holds at the end, IDLE and missing route give NO_ROUTE`() {
        val p = TerrainRouteProjection()
        run(p, straight, listOf(1990.0 to 0.0))
        val arrived = p.onNavigationState(state(straight, at(1995.0, 0.0), NavigationStatus.ARRIVED), second)
        assertEquals(ProjectionMode.HOLD, arrived.mode)
        assertEquals(RouteIndex.build(straight).totalLengthM, arrived.distanceAlongM!!, 1e-6)
        assertEquals(ProjectionMode.NO_ROUTE, p.onNavigationState(state(straight, at(0.0, 0.0), NavigationStatus.IDLE), 2 * second).mode)
        assertEquals(ProjectionMode.NO_ROUTE, p.onNavigationState(state(null, at(0.0, 0.0)), 3 * second).mode)
    }

    @Test
    fun `no fix yet holds and a repeated identical state is stable`() {
        val p = TerrainRouteProjection()
        val none = p.onNavigationState(state(straight, null), 0L)
        assertEquals(ProjectionMode.HOLD, none.mode)
        assertNull(none.distanceAlongM)
        val a = p.onNavigationState(state(straight, at(300.0, 0.0), speedMps = 0.0), second)
        val b = p.onNavigationState(state(straight, at(300.0, 0.0), speedMps = 0.0), 5 * second)
        assertEquals(ProjectionMode.ATTACHED, b.mode)
        assertEquals(a.distanceAlongM!!, b.distanceAlongM!!, 0.0)
    }

    @Test
    fun `RECOVERED initialises tracking without previous progress`() {
        val p = TerrainRouteProjection()
        run(p, straight, listOf(300.0 to 0.0))
        val r = p.onNavigationState(state(straight, at(1500.0, 0.0), NavigationStatus.RECOVERED), second)
        assertEquals(ProjectionMode.ATTACHED, r.mode)
        assertEquals(1500.0, r.distanceAlongM!!, 0.5)
    }

    @Test
    fun `same input sequence gives identical outputs`() {
        val route = TestFixtures.calculatedRoute("det", TestFixtures.meanderingPoints(2_000))
        val fixes = route.allPoints.filterIndexed { i, _ -> i % 3 == 0 }
        fun once() = TerrainRouteProjection().let { p ->
            fixes.mapIndexed { i, pt -> p.onNavigationState(state(route, TestFixtures.offset(pt, 3.0, -2.0)), i * second) }
        }
        assertEquals(once(), once())
    }

    // --- SR-002 (TA-001A-F1): reversal confirmation must be direction-coherent -----------------------------------

    @Test
    fun `non-monotonic backward fixes 300 - 285 - 290 do not flip direction`() {
        val p = TerrainRouteProjection()
        val r = run(p, straight, listOf(300.0 to 0.0, 285.0 to 0.0, 290.0 to 0.0), speed = 0.0)
        assertEquals(ProjectionMode.HOLD, r[1].mode)
        assertEquals(ProjectionMode.HOLD, r[2].mode)
        assertEquals(300.0, r[2].distanceAlongM!!, 0.5)
        assertEquals(1, p.travelDirection)
    }

    @Test
    fun `coherent reversal 300 - 285 - 278 confirms on the second fix`() {
        val p = TerrainRouteProjection()
        val r = run(p, straight, listOf(300.0 to 0.0, 285.0 to 0.0, 278.0 to 0.0), speed = 0.0)
        assertEquals(ProjectionMode.HOLD, r[1].mode)
        assertEquals(ProjectionMode.ATTACHED, r[2].mode)
        assertEquals(278.0, r[2].distanceAlongM!!, 0.5)
        assertEquals(-1, p.travelDirection)
    }

    @Test
    fun `backward progress below 3 m between pending fixes is noise`() {
        val p = TerrainRouteProjection()
        val r = run(p, straight, listOf(300.0 to 0.0, 285.0 to 0.0, 283.0 to 0.0), speed = 0.0)
        assertEquals(ProjectionMode.HOLD, r[2].mode)
        assertEquals(1, p.travelDirection)
        // Continuing coherently from the latest pending fix confirms afterwards.
        val next = p.onNavigationState(state(straight, at(276.0, 0.0), speedMps = 0.0), 3 * second)
        assertEquals(ProjectionMode.ATTACHED, next.mode)
        assertEquals(-1, p.travelDirection)
    }

    @Test
    fun `hopping to a nearby parallel leg cannot confirm a reversal`() {
        // Hairpin legs 12 m apart: y = 0 eastwards to x = 320, connector, y = 12 westwards.
        val route = TestFixtures.calculatedRoute("hop", path(line(0.0, 0.0, 320.0, 0.0), line(320.0, 0.0, 320.0, 12.0), line(320.0, 12.0, 0.0, 12.0)))
        val p = TerrainRouteProjection()
        val fixes = listOf(310.0 to 0.0, 295.0 to 0.0, 300.0 to 11.0, 292.0 to 11.5, 286.0 to 0.5)
        val directions = mutableListOf<Int>()
        fixes.forEachIndexed { i, (x, y) ->
            p.onNavigationState(state(route, at(x, y), speedMps = 0.0), i * second)
            directions += p.travelDirection
        }
        assertTrue("direction flipped: $directions", directions.all { it == 1 })
    }

    // --- SR-010: non-finite speed must not disable the forward-jump limit -----------------------------------------

    @Test
    fun `NaN, infinite or negative speed cannot bypass forward-jump confirmation`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -5.0)) {
            val p = TerrainRouteProjection()
            val r = run(p, straight, listOf(300.0 to 0.0, 550.0 to 0.0), speed = bad)
            assertEquals("speed=$bad", ProjectionMode.HOLD, r[1].mode)
            assertEquals("speed=$bad", 300.0, r[1].distanceAlongM!!, 0.5)
        }
    }
}
