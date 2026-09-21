package pl.mazovia.offroad.routing.engine

import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.domain.model.*

class LoopPlannerTest {
    private val start = GeoPoint(52.1567802, 22.3448868)

    private fun point(x: Double, y: Double) = GeoPoint(start.latitude + y / 111_195.0,
        start.longitude + x / (111_195.0 * kotlin.math.cos(Math.toRadians(start.latitude))))

    private fun route(id: String, points: List<GeoPoint>, distance: Double, terrain: Double = distance / 2): Route {
        val segment = RouteSegment(points, distance, Surface.GRAVEL, HighwayType.TRACK)
        return Route(id, start, start, listOf(segment), RouteMetrics.fromSegments(listOf(segment)).copy(
            totalDistanceMeters = distance, offRoadDistanceMeters = terrain,
            asphaltDistanceMeters = distance - terrain, longestContinuousTerrainMeters = terrain),
            RoutingProfile.TERENOWY)
    }

    private fun attempt(id: String, route: Route?): LoopPlanner.Attempt =
        LoopPlanner.Attempt(LoopPlanner.Shape(id, "test", listOf(point(1000.0, 0.0), point(0.0, 1000.0))), route,
            if (route == null) "NO_PATH" else null)

    private fun square(size: Double, offset: Double = 0.0) = listOf(start,
        point(size + offset, 0.0), point(size + offset, size), point(offset, size), start)

    @Test fun `generated geometry starts and returns to same logical location with two waypoints`() {
        for (target in listOf(20, 50, 100, 150)) {
            val shapes = LoopPlanner.shapes(start, target, null)
            assertEquals(18, shapes.size)
            assertTrue(shapes.all { it.waypoints.size == 2 && it.waypoints.all { p -> p != start } })
            assertEquals(18, shapes.map { it.id }.distinct().size)
            val recovery = LoopPlanner.shapes(start, target, null, recovery = true)
            assertEquals(6, recovery.size)
            assertEquals(24, (shapes + recovery).map { it.id }.distinct().size)
            assertEquals(recovery, LoopPlanner.shapes(start, target, null, recovery = true))
        }
    }

    @Test fun `primary distance beats outside band and fallback stays within 25 percent`() {
        val primary = route("primary", square(3000.0), 50_000.0)
        val outside = route("outside", square(5000.0), 64_000.0)
        val decisions = LoopPlanner.select(listOf(attempt("a", primary), attempt("b", outside)), 50, 1)
        assertEquals("SELECTED", decisions[0].status)
        assertEquals("TARGET_DISTANCE_FAILURE", decisions[1].status)
        val fallback = LoopPlanner.select(listOf(attempt("a", route("fallback", square(3000.0), 59_000.0))), 50, 1)
        assertEquals("SELECTED_FALLBACK", fallback[0].status)
        assertEquals("FALLBACK_25_PERCENT", fallback[0].candidate!!.status)
    }

    @Test fun `repeated stem loses to genuine loop and short stem is tolerated`() {
        val stem = listOf(start, point(5000.0, 0.0), start, point(0.0, 5000.0), start)
        val good = route("good", square(6000.0), 50_000.0)
        val bad = route("bad", stem, 50_000.0)
        assertTrue(LoopPlanner.retraceMeters(bad) > 3000.0)
        assertTrue(LoopPlanner.retraceMeters(good) < 1000.0)
        val chosen = LoopPlanner.select(listOf(attempt("bad", bad), attempt("good", good)), 50, 1)
        assertEquals("SELECTED", chosen[1].status)
        val short = route("short", listOf(start, point(200.0, 0.0), point(3000.0, 0.0),
            point(3000.0, 3000.0), point(200.0, 3000.0), point(200.0, 0.0), start), 50_000.0)
        assertTrue(LoopPlanner.retraceMeters(short) / short.totalDistanceMeters < 0.10)
    }

    @Test fun `crossing once does not resemble retracing a road`() {
        val crossing = route("cross", listOf(start, point(3000.0, 3000.0), point(0.0, 3000.0),
            point(3000.0, 0.0), start), 20_000.0)
        assertTrue(LoopPlanner.retraceMeters(crossing) < 500.0)
        assertEquals(0.0, LoopPlanner.localSpike(crossing, emptyList()).distanceMeters, 0.0)
    }

    private fun spiked(length: Double, noise: Double = 0.0) = route("spiked", listOf(start,
        point(5000.0, 0.0), point(5000.0 + length, 0.0), point(5000.0, noise),
        point(5000.0, 5000.0), point(0.0, 5000.0), start), 50_000.0)

    @Test fun `immediate reverse overlap tolerates coordinate noise and vertex segmentation`() {
        for (noise in listOf(0.0, 2.0, 5.0)) {
            val route = spiked(2000.0, noise)
            assertEquals(2000.0, LoopPlanner.localSpike(route, emptyList()).distanceMeters, 24.0)
            val reversed = route.copy(segments = route.segments.map { it.copy(points = it.points.reversed()) })
            assertEquals(2000.0, LoopPlanner.localSpike(reversed, emptyList()).distanceMeters, 24.0)
        }
        val segmented = route("segmented", listOf(start, point(5000.0, 0.0), point(7000.0, 0.0),
            point(6600.0, 2.0), point(5900.0, -2.0), point(5000.0, 0.0),
            point(5000.0, 5000.0), point(0.0, 5000.0), start), 50_000.0)
        assertEquals(2000.0, LoopPlanner.localSpike(segmented, emptyList()).distanceMeters, 24.0)
    }

    @Test fun `distributed short overlap differs from one concentrated spike`() {
        val points = mutableListOf(start)
        for (i in 1..10) {
            val x = i * 500.0
            points.add(point(x, 0.0))
            points.add(point(x, -200.0))
            points.add(point(x, 0.0))
        }
        points.addAll(listOf(point(5000.0, 5000.0), point(0.0, 5000.0), start))
        val distributed = route("distributed", points, 50_000.0)
        assertEquals(LoopPlanner.retraceMeters(spiked(2000.0)), LoopPlanner.retraceMeters(distributed), 150.0)
        assertTrue(LoopPlanner.localSpike(distributed, emptyList()).distanceMeters < 240.0)
        assertTrue(LoopPlanner.localSpike(spiked(2000.0), emptyList()).distanceMeters > 1900.0)
    }

    @Test fun `common start and end access stem is not an interior turnback`() {
        val access = route("access", listOf(start, point(200.0, 0.0), point(3000.0, 0.0),
            point(3000.0, 3000.0), point(200.0, 3000.0), point(200.0, 0.0), start), 50_000.0)
        assertEquals(0.0, LoopPlanner.localSpike(access, emptyList()).distanceMeters, 0.0)
    }

    @Test fun `low retrace fallback beats a primary distance inflated by repeated roads`() {
        val repeated = route("repeated", listOf(start, point(4000.0, 0.0), start,
            point(0.0, 4000.0), start), 50_000.0)
        val clean = route("clean", square(6000.0), 60_000.0)
        val decisions = LoopPlanner.select(listOf(attempt("repeated", repeated),
            attempt("clean", clean)), 50, 1)
        assertTrue(LoopPlanner.retraceMeters(repeated) / repeated.totalDistanceMeters in 0.10..0.20)
        assertEquals("LOCAL_WAYPOINT_SPIKE", decisions[0].status)
        assertEquals("SELECTED_FALLBACK", decisions[1].status)
    }

    @Test fun `duplicates and failures do not prevent deterministic selection`() {
        val one = route("one", square(4000.0), 50_000.0)
        val two = route("two", square(4000.0, 2.0), 50_000.0)
        val attempts = listOf(attempt("failure", null), attempt("one", one), attempt("two", two))
        val first = LoopPlanner.select(attempts, 50, 2)
        assertEquals("NO_PATH", first[0].status)
        assertEquals("SELECTED", first[1].status)
        assertEquals("DUPLICATE", first[2].status)
        assertEquals(first.map { it.status }, LoopPlanner.select(attempts, 50, 2).map { it.status })
    }

    @Test fun `large local spike below global ten percent cannot beat clean terrain poorer loop`() {
        val bad = spiked(2000.0).let { it.copy(metrics = it.metrics.copy(offRoadDistanceMeters = 49_000.0)) }
        assertTrue(LoopPlanner.retraceMeters(bad) / bad.totalDistanceMeters < 0.10)
        val attempts = listOf(attempt("bad", bad), attempt("clean", route("clean", square(8000.0), 52_000.0)))
        repeat(3) {
            val decisions = LoopPlanner.select(attempts, 50, 1)
            assertEquals("LOCAL_WAYPOINT_SPIKE", decisions[0].status)
            assertTrue(decisions[0].candidate!!.spikeRejected)
            assertEquals("SELECTED", decisions[1].status)
        }
    }

    @Test fun `short overlaps and measured good long loop fixtures remain acceptable`() {
        assertFalse(LoopPlanner.spikeRejected(300.0, 10_000.0))
        assertTrue(LoopPlanner.spikeRejected(1008.0, 21_563.924))
        for (name in listOf("100-terenowy", "100-odkrywczy", "150-terenowy", "150-odkrywczy")) {
            val lines = javaClass.getResourceAsStream("/loop-fixtures/$name.txt")!!.bufferedReader().use { it.readLines() }
            val distance = lines.first().toDouble()
            val points = lines.drop(1).map { line ->
                val values = line.split(' ')
                GeoPoint(values[0].toDouble(), values[1].toDouble())
            }
            val fixture = route(name, points, distance)
            val spike = LoopPlanner.localSpike(fixture, emptyList())
            assertFalse("$name spike=${spike.distanceMeters}", LoopPlanner.spikeRejected(spike.distanceMeters, distance))
            assertTrue(LoopPlanner.select(listOf(attempt(name, fixture)), name.substringBefore('-').toInt(), 1)
                .single().status.startsWith("SELECTED"))
        }
    }

    @Test fun `direction wrap and bin boundaries preserve reverse spike detection with noise`() {
        for (degrees in listOf(0.0, 360.0, 180.0, 7.499, 7.501, 187.499, 187.501)) {
            val angle = Math.toRadians(degrees)
            fun rotate(p: GeoPoint): GeoPoint {
                val x = (p.longitude - start.longitude) * 111_195.0 * kotlin.math.cos(Math.toRadians(start.latitude))
                val y = (p.latitude - start.latitude) * 111_195.0
                return point(x * kotlin.math.cos(angle) - y * kotlin.math.sin(angle),
                    x * kotlin.math.sin(angle) + y * kotlin.math.cos(angle))
            }
            val noisy = spiked(2000.0, 2.0).let { r -> r.copy(segments = r.segments.map { it.copy(points = it.points.map(::rotate)) }) }
            assertEquals("angle=$degrees", 2000.0, LoopPlanner.localSpike(noisy, emptyList()).distanceMeters, 36.0)
            val reversed = noisy.copy(segments = noisy.segments.map { it.copy(points = it.points.reversed()) })
            assertEquals("reverse angle=$degrees", LoopPlanner.localSpike(noisy, emptyList()).distanceMeters,
                LoopPlanner.localSpike(reversed, emptyList()).distanceMeters, 12.0)
            val exact = spiked(2000.0).let { r -> r.copy(segments = r.segments.map { it.copy(points = it.points.map(::rotate)) }) }
            assertTrue("Global repeated geometry at $degrees", LoopPlanner.retraceMeters(exact) > 1500.0)
        }
    }

    @Test fun `distance boundaries include exactly fifteen and twenty five percent on both sides`() {
        for (sign in listOf(-1, 1)) {
            for ((error, expected) in listOf(0.14999 to "SELECTED", 0.15 to "SELECTED",
                0.15001 to "SELECTED_FALLBACK", 0.24999 to "SELECTED_FALLBACK",
                0.25 to "SELECTED_FALLBACK", 0.25001 to "TARGET_DISTANCE_FAILURE")) {
                val candidate = route("boundary", square(4000.0), 100_000.0 + sign * (100_000.0 * error))
                assertEquals("sign=$sign error=$error", expected,
                    LoopPlanner.select(listOf(attempt("boundary", candidate)), 100, 1).single().status)
            }
        }
    }

    @Test fun `partial shared road counts retrace but below ninety percent routes stay distinct`() {
        val partial = route("partial", listOf(start, point(4000.0, 0.0), point(2000.0, 0.0),
            point(2000.0, 4000.0), point(0.0, 4000.0), start), 50_000.0)
        assertEquals(2000.0, LoopPlanner.retraceMeters(partial), 100.0)
        val first = route("first", square(4000.0), 50_000.0)
        val second = route("second", listOf(start, point(4000.0, 0.0), point(4000.0, 6000.0),
            point(0.0, 6000.0), start), 50_000.0)
        val selected = LoopPlanner.select(listOf(attempt("first", first), attempt("second", second)), 50, 2)
        assertEquals(listOf("SELECTED", "SELECTED"), selected.map { it.status })
    }

    @Test fun `all rejected candidates produce no fabricated fallback`() {
        val decisions = LoopPlanner.select(listOf(attempt("failed", null),
            attempt("spike", spiked(2000.0)), attempt("distance", route("distance", square(4000.0), 70_000.0))), 50, 3)
        assertTrue(decisions.none { it.status.startsWith("SELECTED") })
        assertEquals(listOf("NO_PATH", "LOCAL_WAYPOINT_SPIKE", "TARGET_DISTANCE_FAILURE"), decisions.map { it.status })
        assertTrue(LoopPlanner.select(emptyList(), 50, 3).isEmpty())
    }
}
