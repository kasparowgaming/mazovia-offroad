package pl.mazovia.offroad.terrain.projection

import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.terrain.TestFixtures

/**
 * DEVELOPMENT MEASUREMENT (DESIGN §6.5, TA-001A): JVM timing of TerrainRouteProjection updates on a
 * ~10 000-point route. Not an Android device measurement; the assertion is a loose sanity bound only.
 */
class ProjectionPerformanceTest {

    @Test
    fun `projection update cost on a 10 000-point route`() {
        val route = TestFixtures.calculatedRoute("perf", TestFixtures.meanderingPoints(10_000), segmentLength = 500)
        val buildStart = System.nanoTime()
        val index = RouteIndex.build(route)
        val buildMs = (System.nanoTime() - buildStart) / 1e6

        // Rider at 10 m/s, one fix per second, deterministic ±3 m lateral wobble.
        val fixes = (0 until 9_999 step 1).map { i ->
            val p = route.allPoints[i]
            TestFixtures.offset(p, 3.0 * kotlin.math.sin(i * 0.7), 3.0 * kotlin.math.cos(i * 1.3))
        }
        val candidateCounts = fixes.map { index.candidates(it, 60.0).size }

        val projection = TerrainRouteProjection(indexBuilder = { index })
        // Warm-up pass (JIT), then measured pass on a fresh instance.
        fixes.forEachIndexed { i, p -> projection.onNavigationState(TestFixtures.state(route, p), i * 1_000_000_000L) }
        val measured = TerrainRouteProjection(indexBuilder = { index })
        val times = LongArray(fixes.size)
        var attached = 0
        var held = 0
        var other = 0
        fixes.forEachIndexed { i, p ->
            val t0 = System.nanoTime()
            val r = measured.onNavigationState(TestFixtures.state(route, p), i * 1_000_000_000L)
            times[i] = System.nanoTime() - t0
            when (r.mode) {
                ProjectionMode.ATTACHED -> attached++
                ProjectionMode.HOLD -> held++ // duplicated segment-boundary vertices + wobble can step back < 8 m
                else -> other++
            }
        }
        val sorted = times.sorted()
        val medianUs = sorted[sorted.size / 2] / 1e3
        val p95Us = sorted[(sorted.size * 95) / 100] / 1e3
        val meanUs = times.average() / 1e3
        println(
            "DEVELOPMENT MEASUREMENT | route points=${route.allPoints.size} edges=${index.edges.size} build=${"%.1f".format(buildMs)} ms | " +
                "updates=${fixes.size} median=${"%.1f".format(medianUs)} us p95=${"%.1f".format(p95Us)} us mean=${"%.1f".format(meanUs)} us | " +
                "candidates mean=${"%.1f".format(candidateCounts.average())} max=${candidateCounts.max()} | " +
                "attached=$attached hold=$held other=$other | JVM ${System.getProperty("java.version")} (desktop, not Android)"
        )
        assertTrue("no fix may detach on-route", other == 0)
        assertTrue("HOLD only at duplicated boundary vertices", held <= route.segments.size)
        assertTrue("sanity bound only: p95 ${p95Us} us", p95Us < 50_000.0)
    }
}
