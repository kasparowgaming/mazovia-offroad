package pl.mazovia.offroad.terrain.projection

import pl.mazovia.offroad.domain.model.GeoPoint

/** Presentation-only projection mode (DESIGN §6.1, §6.4). */
enum class ProjectionMode {
    /** Projected onto the route; progress follows the rider. */
    ATTACHED,
    /** Last accepted progress kept (small backward jitter, pending confirmation, no fix yet, arrived). */
    HOLD,
    /** Rider is off the route (navigation status or geometry); progress frozen at the last attached value. */
    DETACHED,
    /** No route or navigation idle. */
    NO_ROUTE
}

/**
 * Derived, presentation-only route progress. Never fed back into navigation.
 *
 * [distanceAlongM] is on the navigation-compatible haversine axis (DESIGN §6.2.1).
 */
data class ProjectionResult(
    val routeId: String?,
    val mode: ProjectionMode,
    val distanceAlongM: Double?,
    val edgeIndex: Int?,
    val edgeFraction: Double?,
    val projected: GeoPoint?,
    val tangentBearingDeg: Double?,
    val crossTrackM: Double?,
    val confidence: Float
) {
    companion object {
        val NO_ROUTE = ProjectionResult(null, ProjectionMode.NO_ROUTE, null, null, null, null, null, null, 0f)
    }
}
