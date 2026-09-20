package pl.mazovia.offroad.routing.benchmark

import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.RoutingError
import pl.mazovia.offroad.routing.engine.GraphHopperRoutingEngine
import pl.mazovia.offroad.routing.engine.RouteTournament

/** Passive collector. No scoring formula or eligibility decision is duplicated here. */
internal class RecordingBenchmarkListener(
    private val scenarioName: String,
    private val profile: RoutingProfile,
    private val runIndex: Int,
    private val isWarmup: Boolean,
    private val isLoop: Boolean = false
) : GraphHopperRoutingEngine.BenchmarkListener {
    private var operation = 0
    private var operationStart = 0
    var baselineDistance: Double? = null
        private set
    var baselineTimeMs: Long? = null
        private set
    var tournamentTimeNanos: Long? = null
        private set
    val tournamentTimeMs: Double? get() = tournamentTimeNanos?.div(1_000_000.0)
    var tournamentStatus = "NOT_STARTED"
        private set
    val capturedCandidates = mutableListOf<BenchmarkCandidateResult>()
    val candidatesEvaluated get() = capturedCandidates.count { !it.isBaseline || isLoop }
    val candidatesSuccess get() = capturedCandidates.count { (!it.isBaseline || isLoop) && it.routingSuccess }
    val candidatesFailedDetourGuard get() = capturedCandidates.count { (!it.isBaseline || isLoop) && it.rejectionReason == "DETOUR_LIMIT" }
    val candidatesFailed get() = capturedCandidates.count { (!it.isBaseline || isLoop) && !it.routingSuccess }
    val selectedCandidate get() = capturedCandidates.singleOrNull { it.selected }

    override fun onRouteStarted() {
        operation++
        operationStart = capturedCandidates.size
    }

    override fun onCandidateEvaluated(candidateId: String, route: Route?, isBaseline: Boolean, waypoints: List<GeoPoint>, elapsedMs: Long, error: String?, routingError: RoutingError?) {
        if (isBaseline && !isLoop) {
            baselineDistance = route?.totalDistanceMeters
            baselineTimeMs = elapsedMs
        }
        val status = when {
            route != null -> "ROUTED_NO_TOURNAMENT"
            routingError == RoutingError.NO_ROUTE_FOUND -> "NO_PATH"
            routingError == RoutingError.POINT_NOT_FOUND -> "INVALID_CANDIDATE"
            else -> "ROUTING_FAILURE"
        }
        capturedCandidates.add(BenchmarkCandidateResult(
            scenarioName, profile, runIndex, isWarmup,
            "call-$operation:$candidateId", isBaseline, route?.totalDistanceMeters,
            if (route != null && baselineDistance != null) routeDiagnostics(route, baselineDistance!!).detourPercent else null,
            route?.metrics?.offRoadPercentage?.toInt(),
            route?.metrics?.longestContinuousTerrainMeters, route?.metrics?.terrainRunCount,
            route?.metrics?.longestAsphaltConnectorMeters, null, null, route != null,
            elapsedMs, error ?: routingError?.name,
            source = if (isLoop) "CURRENT_LOOP_SUBROUTE" else if (isBaseline) "BASELINE" else "CORRIDOR",
            status = status,
            baselineDistanceMeters = baselineDistance,
            detourRatio = if (route != null && (baselineDistance ?: 0.0) > 0.0) route.totalDistanceMeters / baselineDistance!! else null,
            extraDistanceMeters = if (route != null && baselineDistance != null) route.totalDistanceMeters - baselineDistance!! else null,
            terrainDistanceMeters = route?.metrics?.offRoadDistanceMeters,
            asphaltDistanceMeters = route?.metrics?.asphaltDistanceMeters,
            routeId = route?.id,
            waypoints = waypoints.joinToString(";") { "${it.latitude}_${it.longitude}" }
        ))
    }

    override fun onTournamentFinished(winner: Route?, evaluations: List<RouteTournament.CandidateEvaluation>, elapsedNanos: Long) {
        tournamentTimeNanos = (tournamentTimeNanos ?: 0L) + elapsedNanos
        for (evaluation in evaluations) {
            val index = (operationStart until capturedCandidates.size).single { capturedCandidates[it].routeId == evaluation.route.id }
            val row = capturedCandidates[index]
            val baseline = evaluation.baselineDistanceMeters.takeIf { it > 0.0 }
            val accepted = evaluation.acceptedByDetourGuard
            val selected = evaluation.route === winner
            capturedCandidates[index] = row.copy(
                baselineDistanceMeters = baseline,
                detourRatio = baseline?.let { evaluation.route.totalDistanceMeters / it },
                extraDistanceMeters = baseline?.let { evaluation.route.totalDistanceMeters - it },
                detourPercent = baseline?.let { routeDiagnostics(evaluation.route, it).detourPercent },
                detourLimitPercent = evaluation.detourLimit?.times(100.0),
                maxAllowedDistanceMeters = evaluation.maxAllowedDistanceMeters,
                exceedsDetourLimit = accepted?.not(),
                acceptedByDetourGuard = accepted,
                tournamentScore = evaluation.score,
                status = when {
                    evaluation.rejectionReason == "INVALID_CANDIDATE" -> "INVALID_CANDIDATE"
                    accepted == false -> "DETOUR_REJECTED"
                    selected -> "SUCCESS"
                    else -> "NOT_SELECTED"
                },
                rejectionReason = evaluation.rejectionReason ?: if (!selected) "LOWER_SCORE_OR_TIE" else null
            )
        }
    }

    override fun onRouteFinished(selected: Route?, tournamentStatus: String) {
        this.tournamentStatus = if (isLoop) "NOT_APPLICABLE_LOOP_SUBROUTES" else tournamentStatus
        if (tournamentStatus == "EVALUATION_FAILED") {
            for (i in operationStart until capturedCandidates.size) {
                val row = capturedCandidates[i]
                if (row.routingSuccess && row.routeId != selected?.id) capturedCandidates[i] = row.copy(
                    status = "EVALUATION_REJECTED", rejectionReason = "EVALUATION_FAILED"
                )
            }
        }
    }

    /** Use the actual API result, including baseline fallbacks and the final loop selection. */
    fun completeScenario(route: Route?) {
        for (i in capturedCandidates.indices) {
            val row = capturedCandidates[i]
            capturedCandidates[i] = row.copy(selected = route != null && row.routeId == route.id)
        }
        if (route == null) {
            check(capturedCandidates.none { it.selected })
            return
        }
        val selected = capturedCandidates.single { it.selected }
        check(selected.routingSuccess && selected.acceptedByDetourGuard != false)
        check(selected.failureReason == null && selected.rejectionReason == null)
        check(selected.distanceMeters == route.totalDistanceMeters)
        check(selected.terrainDistanceMeters == route.metrics.offRoadDistanceMeters)
        check(selected.terrainPercent == route.metrics.offRoadPercentage.toInt())
        check(selected.longestContinuousTerrainMeters == route.metrics.longestContinuousTerrainMeters)
        check(selected.terrainRunCount == route.metrics.terrainRunCount)
        check(selected.longestAsphaltConnectorMeters == route.metrics.longestAsphaltConnectorMeters)
        if (!isLoop && tournamentStatus == "EVALUATED") {
            check(selected.tournamentScore != null && selected.status == "SUCCESS")
            check(capturedCandidates.filter { it.routingSuccess }.all {
                it.acceptedByDetourGuard != null && (it.acceptedByDetourGuard == false || it.tournamentScore != null)
            })
        }
    }

    fun summarize(scenario: BenchmarkScenario, route: Route?, failureReason: String?, totalTimeNanos: Long, heapBefore: Long, heapAfter: Long): BenchmarkSummaryResult {
        completeScenario(route)
        val selected = selectedCandidate
        val diagnostics = route?.let { routeDiagnostics(it, baselineDistance ?: 0.0) }
        return BenchmarkSummaryResult(
            scenarioName, scenario.origin, scenario.destination, profile, runIndex, isWarmup,
            route != null, failureReason, baselineDistance, selected?.distanceMeters, selected?.detourPercent,
            selected?.terrainDistanceMeters, selected?.terrainPercent, selected?.longestContinuousTerrainMeters,
            selected?.terrainRunCount, selected?.longestAsphaltConnectorMeters, selected?.tournamentScore,
            candidatesEvaluated, candidatesSuccess, candidatesFailedDetourGuard, candidatesFailed,
            baselineTimeMs, tournamentTimeMs, totalTimeNanos / 1_000_000,
            diagnostics?.uTurnCount, diagnostics?.shortManeuverLegCount,
            heapBefore, heapAfter, heapAfter - heapBefore, selected?.candidateId,
            tournamentStatus, scenario.executionSemantics, tournamentTimeNanos
        )
    }
}
