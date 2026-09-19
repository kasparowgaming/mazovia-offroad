package pl.mazovia.offroad.routing.benchmark

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.RoutingProfile

internal data class BenchmarkScenario(
    val name: String,
    val kind: String, // "point-to-point" or "loop"
    val group: String,
    val variant: Int,
    val targetKm: Double,
    val points: List<GeoPoint>
) {
    val origin: GeoPoint get() = points.first()
    val destination: GeoPoint get() = points.last()
}

internal data class BenchmarkSummaryResult(
    val scenarioName: String,
    val origin: GeoPoint,
    val destination: GeoPoint,
    val profile: RoutingProfile,
    val runIndex: Int,
    val isWarmup: Boolean,
    
    val success: Boolean,
    val failureReason: String?,
    
    val baselineDistanceMeters: Double,
    val selectedDistanceMeters: Double,
    val detourPercent: Int,
    
    val terrainDistanceMeters: Double,
    val terrainPercent: Int,
    val longestContinuousTerrainMeters: Double,
    val terrainRunCount: Int,
    val longestAsphaltConnectorMeters: Double,
    
    val selectedTournamentScore: Double,
    
    val candidateCountAttempted: Int,
    val candidateCountSuccessful: Int,
    val candidateCountRejectedByDetour: Int,
    val candidateCountFailed: Int,
    
    val baselineTimeMs: Long,
    val tournamentTimeMs: Long,
    val totalTimeMs: Long,
    
    val uTurnCount: Int,
    val shortManeuverLegCount: Int,
    
    val usedHeapBeforeBytes: Long,
    val usedHeapAfterBytes: Long,
    val heapDeltaBytes: Long
)

internal data class BenchmarkCandidateResult(
    val scenarioName: String,
    val profile: RoutingProfile,
    val runIndex: Int,
    val isWarmup: Boolean,
    
    val candidateId: String,
    val isBaseline: Boolean,
    val distanceMeters: Double,
    val detourPercent: Int,
    
    val terrainPercent: Int,
    val longestContinuousTerrainMeters: Double,
    val terrainRunCount: Int,
    val longestAsphaltConnectorMeters: Double,
    
    val tournamentScore: Double,
    val acceptedByDetourGuard: Boolean,
    
    val routingSuccess: Boolean,
    val elapsedMs: Long,
    val failureReason: String?
)
