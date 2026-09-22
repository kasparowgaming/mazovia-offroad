package pl.mazovia.offroad.domain.readiness

import android.content.Context
import android.location.LocationManager
import android.content.pm.PackageManager
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RouteSource
import pl.mazovia.offroad.domain.routing.RoutingEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RidePackEvaluator(
    private val context: Context,
    private val routingEngine: RoutingEngine
) {
    suspend fun evaluate(route: Route?): RidePackReadiness = withContext(Dispatchers.IO) {
        if (route == null) {
            return@withContext RidePackReadiness(
                route = null,
                resource = ResourceReadiness(ComponentReadiness.NOT_READY, ComponentReadiness.NOT_READY, ComponentReadiness.NOT_READY, ComponentReadiness.NOT_READY),
                capability = CapabilityReadiness(ComponentReadiness.NOT_READY, ComponentReadiness.NOT_READY, ComponentReadiness.NOT_READY, ComponentReadiness.NOT_READY, ComponentReadiness.NOT_READY, ComponentReadiness.NOT_READY, ComponentReadiness.NOT_READY),
                device = DeviceReadiness(ComponentReadiness.NOT_READY, ComponentReadiness.NOT_READY)
            )
        }

        // --- 1. RESOURCE READINESS ---

        // Map Resource (PMTiles existence check only, geographic coverage cannot be verified)
        val pmtilesFile = File(context.getExternalFilesDir(null), "mazowieckie_offroad.pmtiles")
        val mapResourceStatus = if (pmtilesFile.exists() && pmtilesFile.length() > 0) {
            ComponentReadiness.UNKNOWN // We have the file, but coverage is UNKNOWN per Phase-0 audit.
        } else {
            ComponentReadiness.NOT_READY
        }

        // Route Artifact
        val routeArtifactStatus = if (route.source == RouteSource.IMPORTED_GPX) {
            if (route.originalGpx != null) ComponentReadiness.READY else ComponentReadiness.NOT_READY
        } else {
            if (route.segments.isNotEmpty()) ComponentReadiness.READY else ComponentReadiness.NOT_READY
        }

        // Offline Graph
        val isGraphLoaded = routingEngine.isReady()
        val offlineGraphStatus = if (isGraphLoaded) {
            ComponentReadiness.READY
        } else {
            if (route.source == RouteSource.IMPORTED_GPX) ComponentReadiness.NOT_REQUIRED else ComponentReadiness.NOT_READY
        }

        // Persisted Data (Surface Data etc. - Informational only)
        val hasSurfaceData = if (route.source == RouteSource.IMPORTED_GPX) {
            false
        } else {
            route.roadDataConfidenceSummary.let { summary ->
                // Check if any explicit or inferred distance exists
                summary.explicitSurfaceDistanceMeters > 0 || summary.inferredSurfaceDistanceMeters > 0
            }
        }
        val persistedDataStatus = if (route.source == RouteSource.IMPORTED_GPX) {
            ComponentReadiness.NOT_REQUIRED
        } else {
            if (hasSurfaceData) ComponentReadiness.READY else ComponentReadiness.NOT_READY
        }

        val resourceReadiness = ResourceReadiness(
            mapResource = mapResourceStatus,
            routeArtifact = routeArtifactStatus,
            offlineGraph = offlineGraphStatus,
            persistedData = persistedDataStatus
        )

        // --- 2. CAPABILITY READINESS ---
        
        val viewMapStatus = if (mapResourceStatus != ComponentReadiness.NOT_READY) ComponentReadiness.PARTIAL else ComponentReadiness.NOT_READY
        
        val followGeometryStatus = if (routeArtifactStatus == ComponentReadiness.READY) ComponentReadiness.READY else ComponentReadiness.NOT_READY
        
        // Turn guidance relies on maneuvers.
        val turnGuidanceStatus = if (route.source == RouteSource.IMPORTED_GPX) {
            ComponentReadiness.NOT_REQUIRED
        } else {
            if (route.maneuvers.isNotEmpty()) ComponentReadiness.READY else ComponentReadiness.NOT_READY
        }

        val offRouteDetectionStatus = ComponentReadiness.READY // Based on geometry

        // Recovery Guidance
        val recoveryGuidanceStatus = if (route.source == RouteSource.IMPORTED_GPX) {
            ComponentReadiness.PARTIAL // Manual recovery ("Wróć do śladu") is Limited/Partial
        } else {
            if (isGraphLoaded) ComponentReadiness.READY else ComponentReadiness.PARTIAL
        }

        // Full Rerouting
        val fullReroutingStatus = if (isGraphLoaded) {
            ComponentReadiness.READY
        } else {
            if (route.source == RouteSource.IMPORTED_GPX) ComponentReadiness.NOT_REQUIRED else ComponentReadiness.NOT_READY
        }

        // Recording (Local capability, always available)
        val recordingStatus = ComponentReadiness.READY

        val capabilityReadiness = CapabilityReadiness(
            viewMap = viewMapStatus,
            followGeometry = followGeometryStatus,
            turnGuidance = turnGuidanceStatus,
            offRouteDetection = offRouteDetectionStatus,
            recoveryGuidance = recoveryGuidanceStatus,
            fullRerouting = fullReroutingStatus,
            recording = recordingStatus
        )

        // --- 3. DEVICE READINESS ---
        val hasLocationPerm = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val locationPermStatus = if (hasLocationPerm) ComponentReadiness.READY else ComponentReadiness.NOT_READY

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val gpsAvailableStatus = if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
            ComponentReadiness.READY
        } else {
            ComponentReadiness.NOT_READY
        }

        val deviceReadiness = DeviceReadiness(
            locationPermission = locationPermStatus,
            gpsAvailable = gpsAvailableStatus
        )

        RidePackReadiness(
            route = route,
            resource = resourceReadiness,
            capability = capabilityReadiness,
            device = deviceReadiness
        )
    }
}
