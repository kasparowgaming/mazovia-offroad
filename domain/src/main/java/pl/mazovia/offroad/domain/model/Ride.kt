package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * A completed or in-progress ride record.
 */
@Serializable
data class Ride(
    val id: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long? = null,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val trackPoints: List<GpxTrackPoint> = emptyList(),
    val metrics: RideMetrics? = null,
    val routeId: String? = null,
    val status: RideStatus = RideStatus.COMPLETED,
    val pendingFeedback: List<String> = emptyList()
)

@Serializable
enum class RideStatus {
    IN_PROGRESS,
    COMPLETED,
    INCOMPLETE,
    DAMAGED
}

/**
 * Post-ride metrics emphasizing off-road quality.
 */
@Serializable
data class RideMetrics(
    val totalDistanceMeters: Double,
    val offRoadDistanceMeters: Double,
    val asphaltDistanceMeters: Double,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val durationSeconds: Long,
    val newKilometersMeters: Double = 0.0,
    val explorationPercentage: Double = 0.0,
    val surfaceDistribution: Map<Surface, Double> = emptyMap(),
    // Set only by a reliable recorded-track classification, never by the planned route.
    val terrainClassificationAvailable: Boolean = false
) {
    val measuredOffRoadPercentage: Double?
        get() = if (terrainClassificationAvailable && totalDistanceMeters > 0 &&
            totalDistanceMeters.isFinite() && offRoadDistanceMeters.isFinite() &&
            offRoadDistanceMeters in 0.0..totalDistanceMeters) offRoadPercentage else null
    val offRoadPercentage: Double
        get() = if (totalDistanceMeters > 0) (offRoadDistanceMeters / totalDistanceMeters) * 100 else 0.0
}
