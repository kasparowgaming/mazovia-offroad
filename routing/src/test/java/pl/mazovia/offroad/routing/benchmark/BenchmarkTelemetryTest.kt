package pl.mazovia.offroad.routing.benchmark

import com.graphhopper.*
import com.graphhopper.util.InstructionList
import com.graphhopper.util.PointList
import com.graphhopper.util.details.PathDetail
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.*
import pl.mazovia.offroad.routing.engine.GraphHopperRoutingEngine
import pl.mazovia.offroad.routing.engine.RouteTournament

class BenchmarkTelemetryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scenario = BenchmarkScenario("test", "point-to-point", "", 0, 0.0, listOf(GeoPoint(52.0, 21.0), GeoPoint(52.1, 21.0)))

    @Before fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
    }
    @After fun teardown() { unmockkStatic(android.util.Log::class) }

    private fun route(id: String, distance: Double, terrain: Double) = Route(
        id = id, origin = scenario.origin, destination = scenario.destination, segments = emptyList(),
        profile = RoutingProfile.TERENOWY,
        metrics = RouteMetrics.EMPTY.copy(totalDistanceMeters = distance, offRoadDistanceMeters = terrain,
            asphaltDistanceMeters = distance - terrain, longestContinuousTerrainMeters = terrain,
            terrainRunCount = 1, longestAsphaltConnectorMeters = distance - terrain)
    )

    @Test fun `authoritative scores detour and failed attempts survive summary and CSV`() {
        for (profile in listOf(RoutingProfile.TERENOWY, RoutingProfile.ODKRYWCZY)) {
            val listener = RecordingBenchmarkListener("test", profile, 1, false)
            val baseline = route("baseline-route", 1000.0, 100.0)
            val better = route("better-route", 1200.0, 1000.0)
            val rejected = route("rejected-route", 2200.0, 2200.0)
            listener.onRouteStarted()
            listener.onCandidateEvaluated("baseline", baseline, true, emptyList(), 1, null, null)
            listener.onCandidateEvaluated("corridor-1", better, false, emptyList(), 2, null, null)
            listener.onCandidateEvaluated("corridor-2", rejected, false, emptyList(), 3, null, null)
            listener.onCandidateEvaluated("corridor-3", null, false, emptyList(), 4, "No connection", RoutingError.NO_ROUTE_FOUND)
            listener.onCandidateEvaluated("corridor-4", null, false, emptyList(), 5, "Engine failure", RoutingError.CALCULATION_ERROR)
            listener.onCandidateEvaluated("corridor-5", null, false, emptyList(), 6, "Cannot find point", RoutingError.POINT_NOT_FOUND)
            val observations = mutableListOf<RouteTournament.CandidateEvaluation>()
            val winner = RouteTournament.chooseTournamentWinner(listOf(better, rejected, baseline), 1000.0, profile, observations)
            assertSame(better, winner)
            listener.onTournamentFinished(winner, observations, 123_456)
            listener.onRouteFinished(winner, "EVALUATED")
            val summary = listener.summarize(scenario, winner, null, 1_000_000, 10, 20)
            val rows = listener.capturedCandidates
            assertEquals(6, rows.size)
            assertEquals(6, rows.map { it.candidateId }.toSet().size)
            val selected = rows.single { it.selected }
            assertEquals(RouteTournament.tournamentTerrainValue(better, profile), selected.tournamentScore!!, 0.0)
            assertEquals(RouteTournament.tournamentTerrainValue(baseline, profile), rows.first().tournamentScore!!, 0.0)
            assertNotEquals(rows.first().tournamentScore, selected.tournamentScore)
            assertEquals(selected.candidateId, summary.selectedCandidateId)
            assertEquals(selected.tournamentScore, summary.selectedTournamentScore)
            assertEquals(selected.distanceMeters, summary.selectedDistanceMeters)
            assertEquals(selected.terrainDistanceMeters, summary.terrainDistanceMeters)
            assertEquals(selected.terrainPercent, summary.terrainPercent)
            assertEquals(selected.longestContinuousTerrainMeters, summary.longestContinuousTerrainMeters)
            assertEquals(0.123456, summary.tournamentTimeMs!!, 0.0)
            val rejectedRow = rows[2]
            assertEquals("DETOUR_LIMIT", rejectedRow.rejectionReason)
            assertEquals("DETOUR_REJECTED", rejectedRow.status)
            assertEquals(1000.0, rejectedRow.baselineDistanceMeters!!, 0.0)
            assertEquals(2.2, rejectedRow.detourRatio!!, 0.0)
            assertEquals(1200.0, rejectedRow.extraDistanceMeters!!, 0.0)
            assertEquals(RouteTournament.profileDetourLimit(profile) * 100, rejectedRow.detourLimitPercent!!, 0.0)
            assertEquals(1000.0 * (1 + RouteTournament.profileDetourLimit(profile)) + 0.1, rejectedRow.maxAllowedDistanceMeters!!, 0.0)
            assertEquals(true, rejectedRow.exceedsDetourLimit)
            assertEquals(false, rejectedRow.acceptedByDetourGuard)
            assertNull(rejectedRow.tournamentScore)
            assertEquals(listOf("NO_PATH", "ROUTING_FAILURE", "INVALID_CANDIDATE"), rows.takeLast(3).map { it.status })
            rows.takeLast(3).forEach {
                assertFalse(it.selected)
                assertFalse(it.routingSuccess)
                assertNull(it.distanceMeters)
                assertNull(it.terrainDistanceMeters)
                assertNull(it.tournamentScore)
            }
            assertEquals(1, summary.candidateCountRejectedByDetour)
            assertEquals(3, summary.candidateCountFailed)
            val candidatesFile = temporary.newFile("candidates-${profile.name}.csv")
            val summaryFile = temporary.newFile("summary-${profile.name}.csv")
            BenchmarkCsvExporter.writeCandidateCsv(candidatesFile, rows)
            BenchmarkCsvExporter.writeSummaryCsv(summaryFile, listOf(summary))
            val exported = candidatesFile.readLines()
            assertEquals(7, exported.size)
            val headers = exported.first().split(',')
            val failureFields = exported[4].split(',')
            assertEquals("", failureFields[headers.indexOf("distanceMeters")])
            assertEquals("NO_PATH", failureFields[headers.indexOf("status")])
            val summaryHeaders = summaryFile.readLines()[0].split(',')
            val summaryFields = summaryFile.readLines()[1].split(',')
            val selectedFields = exported[2].split(',')
            assertEquals(selectedFields[headers.indexOf("tournamentScore")], summaryFields[summaryHeaders.indexOf("selectedTournamentScore")])
            assertEquals("0.123456", summaryFields[summaryHeaders.indexOf("tournamentTimeMs")])
        }
    }

    @Test fun `instrumentation preserves engine route selection and records real timing and every attempt`() = runBlocking {
        val gh = mockk<GraphHopper>(relaxed = true)
        var attempt = 0
        every { gh.route(any()) } answers {
            val index = attempt++ % 9
            if (index == 2) GHResponse().addError(IllegalArgumentException("Connection between locations not found"))
            else {
                val delta = if (index == 0) 0.1 else if (index == 3) 0.3 else 0.11 + index * 0.001
                val points = PointList().apply { add(52.0, 21.0); add(52.0 + delta, 21.0) }
                val path = ResponsePath().apply {
                    setPoints(points)
                    setDistance(GeoPoint(52.0, 21.0).distanceTo(GeoPoint(52.0 + delta, 21.0)))
                    setInstructions(InstructionList(null))
                    addPathDetails(mapOf("surface" to listOf(PathDetail(if (index == 0 || index == 4) "asphalt" else "dirt").apply { first = 0; last = 1 })))
                }
                GHResponse().apply { add(path) }
            }
        }
        val engine = object : GraphHopperRoutingEngine() {
            override fun initGraphHopper(graphPath: String) = gh
        }
        assertTrue(engine.loadGraph("fake"))
        val plain = engine.calculateRoute(scenario.origin, scenario.destination, RoutingProfile.TERENOWY) as RoutingResult.Success
        val listener = RecordingBenchmarkListener("test", RoutingProfile.TERENOWY, 1, false)
        engine.benchmarkListener = listener
        val observed = engine.calculateRoute(scenario.origin, scenario.destination, RoutingProfile.TERENOWY) as RoutingResult.Success
        val summary = listener.summarize(scenario, observed.route, null, 0, 0, 0)
        assertEquals(plain.route.metrics, observed.route.metrics)
        assertEquals(plain.route.segments, observed.route.segments)
        assertEquals(9, listener.capturedCandidates.size)
        assertEquals("NO_PATH", listener.capturedCandidates.single { !it.routingSuccess }.status)
        assertTrue(listener.capturedCandidates.any { it.status == "DETOUR_REJECTED" })
        assertTrue(listener.capturedCandidates.any { it.status == "MARGINAL_TERRAIN_EFFICIENCY_REJECTED" })
        assertTrue(summary.tournamentTimeNanos!! > 0)
        assertTrue(summary.tournamentTimeMs!! > 0.0)
        assertEquals(RouteTournament.tournamentTerrainValue(observed.route, RoutingProfile.TERENOWY), summary.selectedTournamentScore!!, 0.0)
        engine.benchmarkListener = object : GraphHopperRoutingEngine.BenchmarkListener {
            override fun onCandidateEvaluated(candidateId: String, route: Route?, isBaseline: Boolean, waypoints: List<GeoPoint>, elapsedMs: Long, error: String?, routingError: RoutingError?) { error("observer failure") }
            override fun onTournamentFinished(winner: Route?, evaluations: List<RouteTournament.CandidateEvaluation>, elapsedNanos: Long) { error("observer failure") }
        }
        val throwing = engine.calculateRoute(scenario.origin, scenario.destination, RoutingProfile.TERENOWY) as RoutingResult.Success
        assertEquals(plain.route.metrics, throwing.route.metrics)
        assertEquals(plain.route.segments, throwing.route.segments)
        assertEquals(27, attempt)
    }

    @Test fun `ties singleton and no tournament keep original selection and missing score semantics`() {
        val a = route("a", 1000.0, 800.0)
        val b = route("b", 1000.0, 800.0)
        for (profile in RoutingProfile.entries) {
            for (candidates in listOf(listOf(a, b), listOf(b, a), listOf(a), emptyList())) {
                val observations = mutableListOf<RouteTournament.CandidateEvaluation>()
                assertSame(RouteTournament.chooseTournamentWinner(candidates, 1000.0, profile), RouteTournament.chooseTournamentWinner(candidates, 1000.0, profile, observations))
                if (profile != RoutingProfile.BEZPIECZNY && candidates.size == 1) assertNotNull(observations.single().score)
            }
        }
        val listener = RecordingBenchmarkListener("test", RoutingProfile.BEZPIECZNY, 1, false)
        listener.onRouteStarted()
        listener.onCandidateEvaluated("baseline", a, true, emptyList(), 1, null, null)
        listener.onRouteFinished(a, "NOT_APPLICABLE")
        val summary = listener.summarize(scenario, a, null, 0, 0, 0)
        assertNull(summary.selectedTournamentScore)
        assertNull(summary.tournamentTimeMs)
        assertEquals("NOT_APPLICABLE", summary.tournamentStatus)
        assertTrue(listener.capturedCandidates.single().selected)
    }

    @Test fun `loop telemetry uses unique subroute identities and explicitly disclaims legacy geometry`() {
        val listener = RecordingBenchmarkListener("loop-A", RoutingProfile.TERENOWY, 1, false, true)
        val a = route("loop-a", 1000.0, 800.0)
        val b = route("loop-b", 1100.0, 900.0)
        for (r in listOf(a, b)) {
            listener.onRouteStarted()
            listener.onCandidateEvaluated("baseline", r, true, listOf(scenario.destination), 1, null, null)
            listener.onRouteFinished(r, "NOT_APPLICABLE")
        }
        val loopScenario = scenario.copy(kind = "loop")
        val summary = listener.summarize(loopScenario, b, null, 0, 0, 0)
        assertEquals(2, listener.capturedCandidates.map { it.candidateId }.distinct().size)
        assertEquals(2, summary.candidateCountAttempted)
        assertEquals("call-2:baseline", summary.selectedCandidateId)
        assertNull(summary.baselineDistanceMeters)
        assertNull(summary.selectedTournamentScore)
        assertTrue(summary.executionSemantics.contains("NOT_LEGACY_GEOMETRY"))
        assertEquals("NOT_APPLICABLE_LOOP_SUBROUTES", summary.tournamentStatus)
    }

    @Test fun `CSV distinguishes missing zero and quoted failure text`() {
        assertEquals(",0.000000,\"error, \"\"quoted\"\"\"", BenchmarkCsvExporter.row(listOf(null, 0.0, "error, \"quoted\"")))
    }

    @Test fun `efficiency rejection uses shortest corridor and survives exported telemetry`() {
        val profile = RoutingProfile.ODKRYWCZY
        val baseline = route("baseline", 12000.0, 7500.0)
        val shortest = route("shortest", 10000.0, 7000.0)
        val rejected = route("rejected", 14000.0, 9500.0)
        val listener = RecordingBenchmarkListener("test", profile, 1, false)
        listener.onRouteStarted()
        listener.onCandidateEvaluated("baseline", baseline, true, emptyList(), 1, null, null)
        listener.onCandidateEvaluated("corridor-1", shortest, false, emptyList(), 1, null, null)
        listener.onCandidateEvaluated("corridor-2", rejected, false, emptyList(), 1, null, null)
        val observations = mutableListOf<RouteTournament.CandidateEvaluation>()
        val candidates = listOf(shortest, rejected, baseline)
        val winner = RouteTournament.chooseTournamentWinner(candidates, 12000.0, profile, observations)
        assertSame(shortest, winner)
        assertSame(winner, RouteTournament.chooseTournamentWinner(candidates, 12000.0, profile))
        listener.onTournamentFinished(winner, observations, 100)
        listener.onRouteFinished(winner, "EVALUATED")
        val summary = listener.summarize(scenario, winner, null, 1000, 0, 0)
        assertEquals("call-1:corridor-1", summary.selectedCandidateId)
        assertEquals(10000.0, summary.selectedDistanceMeters!!, 0.0)
        assertEquals(7000.0, summary.terrainDistanceMeters!!, 0.0)
        assertEquals(RouteTournament.tournamentTerrainValue(shortest, profile), summary.selectedTournamentScore!!, 0.0)
        assertEquals(1, summary.candidateCountRejectedByMarginalTerrainEfficiency) // excludes baseline, like existing counts
        assertEquals(0, summary.candidateCountRejectedByDetour)
        assertEquals(0, summary.candidateCountFailed)
        val rows = listener.capturedCandidates
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.shortestReferenceDistance == 10000.0 && it.shortestReferenceTerrainDistance == 7000.0 })
        val rejectedRow = rows.last()
        assertEquals(0.625, rejectedRow.marginalTerrainEfficiency!!, 0.0)
        assertEquals(0.70, rejectedRow.marginalTerrainEfficiencyLimit!!, 0.0)
        assertEquals(true, rejectedRow.acceptedByDetourGuard)
        assertTrue(rejectedRow.routingSuccess)
        assertFalse(rejectedRow.selected)
        assertNull(rejectedRow.tournamentScore)
        assertEquals("MARGINAL_TERRAIN_EFFICIENCY_REJECTED", rejectedRow.status)
        assertNull(rows[1].marginalTerrainEfficiency)
        val file = temporary.newFile("efficiency.csv")
        BenchmarkCsvExporter.writeCandidateCsv(file, rows)
        val lines = file.readLines()
        assertEquals(4, lines.size)
        val headers = lines.first().split(',')
        val fields = lines.last().split(',')
        fun field(name: String) = fields[headers.indexOf(name)]
        assertEquals("MARGINAL_TERRAIN_EFFICIENCY", field("rejectionReason"))
        assertEquals("MARGINAL_TERRAIN_EFFICIENCY_REJECTED", field("status"))
        assertEquals("10000.000000", field("shortestReferenceDistance"))
        assertEquals("7000.000000", field("shortestReferenceTerrainDistance"))
        assertEquals("0.625000", field("marginalTerrainEfficiency"))
        assertEquals("0.700000", field("marginalTerrainEfficiencyLimit"))
        assertEquals("", field("tournamentScore"))
        assertEquals("false", field("selected"))
    }
}
