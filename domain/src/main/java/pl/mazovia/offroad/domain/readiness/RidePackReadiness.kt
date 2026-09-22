package pl.mazovia.offroad.domain.readiness

import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RouteSource

enum class ComponentReadiness {
    READY,
    PARTIAL,
    NOT_READY,
    NOT_REQUIRED,
    UNKNOWN
}

data class ResourceReadiness(
    val mapResource: ComponentReadiness,
    val routeArtifact: ComponentReadiness,
    val offlineGraph: ComponentReadiness,
    val persistedData: ComponentReadiness
)

data class CapabilityReadiness(
    val viewMap: ComponentReadiness,
    val followGeometry: ComponentReadiness,
    val turnGuidance: ComponentReadiness,
    val offRouteDetection: ComponentReadiness,
    val recoveryGuidance: ComponentReadiness,
    val fullRerouting: ComponentReadiness,
    val recording: ComponentReadiness
)

data class DeviceReadiness(
    val locationPermission: ComponentReadiness,
    val gpsAvailable: ComponentReadiness
)

data class RidePackReadiness(
    val route: Route?,
    val resource: ResourceReadiness,
    val capability: CapabilityReadiness,
    val device: DeviceReadiness
) {
    val overallStatus: ComponentReadiness
        get() {
            if (route == null) return ComponentReadiness.NOT_READY

            // Essential Blockers
            if (resource.routeArtifact == ComponentReadiness.NOT_READY ||
                capability.followGeometry == ComponentReadiness.NOT_READY) {
                return ComponentReadiness.NOT_READY
            }

            // UNKNOWN rule: Never promote UNKNOWN to READY.
            // If the local map exists but coverage is UNKNOWN, overall is PARTIAL.
            // If the local map doesn't exist at all, it's NOT_READY.
            if (resource.mapResource == ComponentReadiness.NOT_READY) {
                return ComponentReadiness.NOT_READY
            }

            // If we have some unknowns or partials in essential capabilities
            val capabilities = listOf(
                capability.viewMap,
                capability.followGeometry,
                capability.offRouteDetection,
                capability.recoveryGuidance
            )

            if (capabilities.any { it == ComponentReadiness.NOT_READY }) {
                return ComponentReadiness.NOT_READY
            }

            // Device issues don't block the *route's* innate readiness, but they block departure.
            // (We will handle device readiness separately in UI, but if we want overall to reflect it):
            if (device.locationPermission == ComponentReadiness.NOT_READY || 
                device.gpsAvailable == ComponentReadiness.NOT_READY) {
                return ComponentReadiness.NOT_READY
            }

            val hasUnknowns = capabilities.any { it == ComponentReadiness.UNKNOWN } || resource.mapResource == ComponentReadiness.UNKNOWN
            val hasPartials = capabilities.any { it == ComponentReadiness.PARTIAL } || 
                              capability.fullRerouting == ComponentReadiness.NOT_READY

            if (hasUnknowns || hasPartials) {
                return ComponentReadiness.PARTIAL
            }

            return ComponentReadiness.READY
        }

    val hasEssentialDepartureBlocker: Boolean
        get() = resource.routeArtifact == ComponentReadiness.NOT_READY ||
                capability.followGeometry == ComponentReadiness.NOT_READY ||
                resource.mapResource == ComponentReadiness.NOT_READY
}
