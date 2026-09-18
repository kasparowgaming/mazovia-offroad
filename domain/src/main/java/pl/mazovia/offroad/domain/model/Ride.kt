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
    val surfaceDistribution: Map<Surface, Double> = emptyMap()
) {
    val offRoadPercentage: Double
        get() = if (totalDistanceMeters > 0) (offRoadDistanceMeters / totalDistanceMeters) * 100 else 0.0
}
