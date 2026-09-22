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
    val maneuvers: List<Maneuver> = emptyList(),
    val source: RouteSource = RouteSource.CALCULATED_ROUTE,
    val originalGpx: GpxData? = null
) {
    val totalDistanceMeters: Double get() = metrics.totalDistanceMeters
    val allPoints: List<GeoPoint> get() = segments.flatMap { it.points }
    val roadDataConfidenceSummary: RouteDataConfidenceSummary
        get() = RouteDataConfidenceSummary.fromSegments(segments)
}

@Serializable
enum class RouteSource { CALCULATED_ROUTE, IMPORTED_GPX, RECOVERY_ROUTE }
