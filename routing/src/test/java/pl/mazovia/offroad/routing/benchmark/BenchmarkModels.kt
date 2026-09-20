package pl.mazovia.offroad.routing.benchmark

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.RoutingProfile

internal data class BenchmarkScenario(
    val name: String,
    val kind: String,
    val group: String,
    val variant: Int,
    val targetKm: Double,
    val points: List<GeoPoint>
) {
    val origin: GeoPoint get() = points.first()
    val destination: GeoPoint get() = points.last()
    // Keep legacy names for CSV consumers, but never imply legacy geometry ran.
    val executionSemantics: String get() = if (kind == "loop")
        "CURRENT_LOOP_START_AND_TARGET_ONLY_NOT_LEGACY_GEOMETRY" else "CURRENT_POINT_TO_POINT"
}

/** Existing columns retain order. Blank = unavailable/not applicable, never a synthetic zero.
 * Point-to-point counts continue to exclude baseline attempts; loop counts include all subroute attempts.
 * tournamentTimeMs is fractional milliseconds of evaluation/selection only, summed for the run.
 */
internal data class BenchmarkSummaryResult(
    val scenarioName: String,
    val origin: GeoPoint,
    val destination: GeoPoint,
    val profile: RoutingProfile,
    val runIndex: Int,
    val isWarmup: Boolean,
    val success: Boolean,
    val failureReason: String?,
    val baselineDistanceMeters: Double?,
    val selectedDistanceMeters: Double?,
    val detourPercent: Int?,
    val terrainDistanceMeters: Double?,
    val terrainPercent: Int?,
    val longestContinuousTerrainMeters: Double?,
    val terrainRunCount: Int?,
    val longestAsphaltConnectorMeters: Double?,
    val selectedTournamentScore: Double?,
    val candidateCountAttempted: Int,
    val candidateCountSuccessful: Int,
    val candidateCountRejectedByDetour: Int,
    val candidateCountFailed: Int,
    val baselineTimeMs: Long?,
    val tournamentTimeMs: Double?,
    val totalTimeMs: Long,
    val uTurnCount: Int?,
    val shortManeuverLegCount: Int?,
    val usedHeapBeforeBytes: Long,
    val usedHeapAfterBytes: Long,
    val heapDeltaBytes: Long,
    val selectedCandidateId: String?,
    val tournamentStatus: String,
    val executionSemantics: String,
    val tournamentTimeNanos: Long?
)

/** score is blank when not scored (failed, detour rejected, or no tournament).
 * detourRatio = candidate / baseline; detourLimitPercent uses percent units (60, 100).
 * acceptedByDetourGuard comes from the real tournament, including its 0.1m epsilon.
 * SUCCESS and NOT_SELECTED both mean successful eligible evaluations; NOT_SELECTED
 * records a lower score/tie, not a routing failure. Loop rows describe current
 * subroutes only; loop filtering/LoopScore is not a point-to-point tournament.
 */
internal data class BenchmarkCandidateResult(
    val scenarioName: String,
    val profile: RoutingProfile,
    val runIndex: Int,
    val isWarmup: Boolean,
    val candidateId: String,
    val isBaseline: Boolean,
    val distanceMeters: Double?,
    val detourPercent: Int?,
    val terrainPercent: Int?,
    val longestContinuousTerrainMeters: Double?,
    val terrainRunCount: Int?,
    val longestAsphaltConnectorMeters: Double?,
    val tournamentScore: Double?,
    val acceptedByDetourGuard: Boolean?,
    val routingSuccess: Boolean,
    val elapsedMs: Long,
    val failureReason: String?,
    val selected: Boolean = false,
    val source: String,
    val status: String,
    val rejectionReason: String? = null,
    val baselineDistanceMeters: Double? = null,
    val detourRatio: Double? = null,
    val extraDistanceMeters: Double? = null,
    val detourLimitPercent: Double? = null,
    val maxAllowedDistanceMeters: Double? = null,
    val exceedsDetourLimit: Boolean? = null,
    val terrainDistanceMeters: Double?,
    val asphaltDistanceMeters: Double?,
    val routeId: String?,
    val waypoints: String
)
