package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * Current navigation session state.
 */
@Serializable
data class NavigationState(
    val status: NavigationStatus,
    val route: Route? = null,
    val currentPosition: GeoPoint? = null,
    val currentBearing: Double? = null,
    val currentSpeedMps: Double? = null,
    val nextManeuver: Maneuver? = null,
    val distanceToNextManeuverMeters: Double? = null,
    val remainingDistanceMeters: Double? = null,
    val remainingTimeSeconds: Long? = null,
    val currentSegmentIndex: Int = 0,
    val isRecovered: Boolean = false,
    val returnToGpx: Boolean = false,
    val distanceToGpxMeters: Double? = null,
    val bearingToGpx: Double? = null
)

@Serializable
enum class NavigationStatus {
    /** No active navigation */
    IDLE,
    /** Navigation is active and on route */
    ON_ROUTE,
    /** Rider has deviated from the route */
    OFF_ROUTE,
    /** Recalculating route after going off-route */
    RECALCULATING,
    /** Successfully returned to route after deviation */
    ROUTE_RECOVERED,
    /** Following a GPX trace (not turn-by-turn) */
    FOLLOWING_GPX,
    /** Routing error - cannot calculate */
    ROUTING_ERROR,
    /** Navigation completed - arrived at destination */
    ARRIVED,
    /** Session was recovered after process death */
    RECOVERED
}
