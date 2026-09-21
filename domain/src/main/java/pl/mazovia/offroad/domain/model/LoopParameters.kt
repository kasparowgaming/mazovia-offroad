package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * Parameters for recreational loop generation.
 */
@Serializable
data class LoopParameters(
    val startPoint: GeoPoint,
    val targetDistanceKm: Int,
    val profile: RoutingProfile = RoutingProfile.TERENOWY,
    val preferredDirection: Double? = null // Bearing in degrees, null = any
) {
    companion object {
        val DISTANCE_OPTIONS = listOf(20, 50, 100, 150)
    }
}

/**
 * A scored loop candidate for selection.
 */
@Serializable
data class LoopCandidate(
    val route: Route,
    val score: LoopScore,
    val candidateId: String = "",
    val geometry: String = "",
    val retraceDistanceMeters: Double = 0.0,
    val status: String = "PRIMARY"
)

/**
 * Detailed scoring for loop quality comparison.
 */
@Serializable
data class LoopScore(
    val overallScore: Double,
    val offRoadShare: Double,
    val continuity: Double,
    val targetDistanceError: Double,
    val retraceFraction: Double,
    val asphaltConnectorScore: Double,
    val surfaceQuality: Double,
    val dataConfidence: Double,
    val explorationScore: Double
) {
    companion object {
        fun calculate(
            metrics: RouteMetrics,
            targetDistanceKm: Int
        ): LoopScore {
            val targetDistanceM = targetDistanceKm * 1000.0
            val distanceError = kotlin.math.abs(metrics.totalDistanceMeters - targetDistanceM) / targetDistanceM

            val offRoadShare = metrics.offRoadPercentage / 100.0
            val continuity = metrics.continuitScore
            val asphaltConnector = 1.0 - (metrics.longestAsphaltConnectorMeters / metrics.totalDistanceMeters).coerceIn(0.0, 1.0)

            // Weighted overall score
            val overall = offRoadShare * 0.30 +
                    continuity * 0.15 +
                    (1.0 - distanceError).coerceIn(0.0, 1.0) * 0.15 +
                    (1.0 - metrics.retraceFraction) * 0.10 +
                    asphaltConnector * 0.10 +
                    metrics.dataConfidenceScore * 0.10 +
                    metrics.explorationScore * 0.10

            return LoopScore(
                overallScore = overall,
                offRoadShare = offRoadShare,
                continuity = continuity,
                targetDistanceError = distanceError,
                retraceFraction = metrics.retraceFraction,
                asphaltConnectorScore = asphaltConnector,
                surfaceQuality = offRoadShare, // Simplified
                dataConfidence = metrics.dataConfidenceScore,
                explorationScore = metrics.explorationScore
            )
        }
    }
}
