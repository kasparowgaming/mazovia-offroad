package pl.mazovia.offroad.routing.engine

import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import pl.mazovia.offroad.domain.model.*
import java.io.File

/** Opt-in read-only validation against the current graph. Never imports or swaps graph data. */
class LoopGraphValidationTest {
    private data class Observation(
        val id: String,
        val geometry: String,
        val candidate: LoopCandidate?,
        val status: String,
        val selected: Boolean
    )

    @Test fun validateTargetsOnCurrentGraph() = runBlocking {
        assumeTrue(System.getenv("MAZOVIA_RUN_LOOP_VALIDATION") == "1")
        val graph = File(System.getenv("MAZOVIA_GRAPH_PATH") ?: error("MAZOVIA_GRAPH_PATH is required"))
        assertTrue(graph.isDirectory)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        val engine = GraphHopperRoutingEngine()
        try {
            assertTrue(engine.loadGraph(graph.absolutePath))
            val start = GeoPoint(52.1567802, 22.3448868)
            for (profile in listOf(RoutingProfile.TERENOWY, RoutingProfile.ODKRYWCZY)) {
                for (target in listOf(20, 50, 100, 150)) {
                    if (System.getenv("MAZOVIA_LOOP_PROFILE")?.let { it != profile.name } == true ||
                        System.getenv("MAZOVIA_LOOP_TARGET")?.let { it != target.toString() } == true) continue
                    val observed = mutableListOf<Observation>()
                    engine.benchmarkListener = object : GraphHopperRoutingEngine.BenchmarkListener {
                        override fun onLoopAttempt(candidateId: String, geometry: String, targetKm: Int,
                            candidate: LoopCandidate?, status: String, selected: Boolean) {
                            observed.add(Observation(candidateId, geometry, candidate, status, selected))
                        }
                    }
                    val candidate = engine.generateLoopCandidates(LoopParameters(start, target, profile), 1).firstOrNull()
                    val rejected = observed.filter { !it.selected && it.status != "NOT_SELECTED" }
                        .groupingBy { it.status }.eachCount()
                    val valid = observed.count { it.candidate != null && it.status in
                        setOf("SELECTED", "SELECTED_FALLBACK", "NOT_SELECTED", "DUPLICATE") }
                    observed.forEach { item ->
                        println("ATTEMPT target=$target profile=$profile id=${item.id} status=${item.status} " +
                            "actualKm=${item.candidate?.route?.totalDistanceMeters?.div(1000)} " +
                            "errorPercent=${item.candidate?.score?.targetDistanceError?.times(100)} " +
                            "retracePercent=${item.candidate?.score?.retraceFraction?.times(100)} " +
                            "terrainKm=${item.candidate?.route?.metrics?.offRoadDistanceMeters?.div(1000)}")
                    }
                    val alternative = observed.filter { !it.selected && it.candidate != null }
                        .minWithOrNull(compareBy<Observation> { it.candidate!!.score.targetDistanceError }
                            .thenBy { it.candidate!!.score.retraceFraction })
                    println("LOOP_COUNTS target=$target profile=$profile attempted=${observed.size} " +
                        "valid=$valid rejected=$rejected alternative=${alternative?.id} " +
                        "alternativeStatus=${alternative?.status} alternativeKm=${alternative?.candidate?.route?.totalDistanceMeters?.div(1000)} " +
                        "alternativeErrorPercent=${alternative?.candidate?.score?.targetDistanceError?.times(100)} " +
                        "alternativeRetracePercent=${alternative?.candidate?.score?.retraceFraction?.times(100)}")
                    if (candidate == null) {
                        println("LOOP target=$target profile=$profile status=NO_ACCEPTABLE_LOOP")
                        continue
                    }
                    val metrics = candidate.route.metrics
                    val shape = LoopPlanner.shapes(start, target, null).first { it.id == candidate.candidateId }
                    val points = candidate.route.allPoints
                    println("LOOP target=$target profile=$profile id=${candidate.candidateId} " +
                        "geometry=${candidate.geometry} waypointCount=${shape.waypoints.size} " +
                        "startEndMeters=${points.first().distanceTo(points.last())} deduplicated=${observed.any { it.status == "DUPLICATE" }} " +
                        "actualKm=${metrics.totalDistanceMeters / 1000} " +
                        "errorPercent=${candidate.score.targetDistanceError * 100} terrainKm=${metrics.offRoadDistanceMeters / 1000} " +
                        "terrainPercent=${metrics.offRoadPercentage} retraceKm=${candidate.retraceDistanceMeters / 1000} " +
                        "retracePercent=${candidate.score.retraceFraction * 100} longestTerrainKm=${metrics.longestContinuousTerrainMeters / 1000} " +
                        "status=${candidate.status}")
                    assertTrue(candidate.score.targetDistanceError <= 0.25)
                    assertTrue(candidate.score.retraceFraction <= 0.20)
                }
            }
        } finally {
            engine.unloadGraph()
        }
    }
}
