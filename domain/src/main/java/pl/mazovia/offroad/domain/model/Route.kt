package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * A complete calculated route with segments and metrics.
 */
@Serializable
data class Route(
    val id: String,
    val origin: GeoPoint,
    val destination: GeoPoint,
    val segments: List<RouteSegment>,
    val metrics: RouteMetrics,
    val profile: RoutingProfile,
    val waypoints: List<GeoPoint> = emptyList(),
    val maneuvers: List<Maneuver> = emptyList()
) {
    val totalDistanceMeters: Double get() = metrics.totalDistanceMeters
    val allPoints: List<GeoPoint> get() = segments.flatMap { it.points }
    val roadDataConfidenceSummary: RouteDataConfidenceSummary
        get() = RouteDataConfidenceSummary.fromSegments(segments)
}
