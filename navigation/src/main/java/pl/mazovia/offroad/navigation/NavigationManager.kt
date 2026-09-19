package pl.mazovia.offroad.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.navigation.OffRouteDetector
import pl.mazovia.offroad.domain.navigation.OffRouteState
import pl.mazovia.offroad.domain.routing.RoutingEngine
import pl.mazovia.offroad.domain.routing.RoutingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.mazovia.offroad.domain.location.LocationClient

/**
 * Core navigation logic - manages the navigation session lifecycle.
 * This is NOT a ViewModel. It's a domain-level manager used by ViewModels.
 */
class NavigationManager(
    private val routingEngine: RoutingEngine,
    private val locationClient: LocationClient? = null
) {
    private val _navigationState = MutableStateFlow(NavigationState(status = NavigationStatus.IDLE))
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val offRouteDetector = OffRouteDetector()
    private var activeRoute: Route? = null
    private var currentSegmentIndex = 0
    private var locationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var lastAcceptedPosition: GeoPoint? = null
    private var lastAcceptedBearing: Double? = null

    init {
        locationJob = scope.launch {
            try {
                locationClient?.getLocationUpdates(1000L)?.collect { update ->
                    updatePosition(update.point, update.bearing?.toDouble(), update.speedMps?.toDouble())
                }
            } catch (e: Exception) {
                // Handle or log
            }
        }
    }

    /**
     * Start navigation with a calculated route.
     */
    fun startNavigation(route: Route) {
        activeRoute = route
        currentSegmentIndex = 0
        offRouteDetector.reset()
        _navigationState.value = NavigationState(
            status = NavigationStatus.ON_ROUTE,
            route = route,
            nextManeuver = route.maneuvers.firstOrNull(),
            remainingDistanceMeters = route.totalDistanceMeters,
            remainingTimeSeconds = route.metrics.estimatedTimeSeconds
        )
    }

    /**
     * Start GPX trace following (not turn-by-turn).
     */
    fun startGpxFollowing(gpxData: GpxData, route: Route?) {
        val gpxRoute = route ?: createPseudoRouteFromGpx(gpxData)
        activeRoute = gpxRoute
        currentSegmentIndex = 0
        offRouteDetector.reset()
        _navigationState.value = NavigationState(
            status = NavigationStatus.FOLLOWING_GPX,
            route = gpxRoute,
            remainingDistanceMeters = gpxData.totalDistanceMeters
        )
    }

    private fun createPseudoRouteFromGpx(gpxData: GpxData): Route {
        val allPoints = gpxData.tracks.flatMap { t -> t.segments.flatMap { s -> s.points.map { it.point } } }
        val start = allPoints.firstOrNull() ?: GeoPoint(0.0, 0.0)
        val end = allPoints.lastOrNull() ?: GeoPoint(0.0, 0.0)
        
        return Route(
            id = "gpx_${System.currentTimeMillis()}",
            origin = start,
            destination = end,
            segments = listOf(
                pl.mazovia.offroad.domain.model.RouteSegment(
                    points = allPoints,
                    distanceMeters = gpxData.totalDistanceMeters,
                    surface = pl.mazovia.offroad.domain.model.Surface.UNKNOWN,
                    highway = pl.mazovia.offroad.domain.model.HighwayType.UNKNOWN
                )
            ),
            metrics = pl.mazovia.offroad.domain.model.RouteMetrics(
                totalDistanceMeters = gpxData.totalDistanceMeters,
                estimatedTimeSeconds = (gpxData.totalDistanceMeters / 13.8).toLong(), // Approx 50 km/h
                asphaltDistanceMeters = 0.0,
                offRoadDistanceMeters = 0.0,
                longestAsphaltConnectorMeters = 0.0,
                surfaceDistribution = emptyMap(),
                dataConfidenceScore = 0.0
            ),
            profile = pl.mazovia.offroad.domain.model.RoutingProfile.ODKRYWCZY
        )
    }

    /**
     * Restore a navigation session after process death.
     */
    fun restoreSession(route: Route, segmentIndex: Int) {
        activeRoute = route
        currentSegmentIndex = segmentIndex
        offRouteDetector.reset()
        _navigationState.value = NavigationState(
            status = NavigationStatus.RECOVERED,
            route = route,
            currentSegmentIndex = segmentIndex,
            isRecovered = true,
            remainingDistanceMeters = calculateRemainingDistance(route, segmentIndex),
            remainingTimeSeconds = calculateRemainingTime(route, segmentIndex)
        )
    }

    /**
     * Update with new GPS position.
     */
    fun updatePosition(position: GeoPoint, bearing: Double?, speedMps: Double?) {
        val route = activeRoute ?: return
        val currentState = _navigationState.value

        if (currentState.status == NavigationStatus.IDLE ||
            currentState.status == NavigationStatus.ARRIVED) return

        // Speed zero threshold
        var effectiveSpeed = speedMps?.coerceAtLeast(0.0) ?: 0.0
        if (effectiveSpeed < 1.5) {
            effectiveSpeed = 0.0
        }

        // Position deadband - use last accepted position if within 3.0 meters
        var effectivePosition = position
        var effectiveBearing = bearing
        if (lastAcceptedPosition != null) {
            val distance = position.distanceTo(lastAcceptedPosition!!)
            if (distance < 3.0) {
                effectivePosition = lastAcceptedPosition!!
            } else {
                lastAcceptedPosition = position
            }
        } else {
            lastAcceptedPosition = position
        }

        // Bearing low-speed guard - don't update bearing if speed < 2.0 mps or null
        if (speedMps == null || speedMps < 2.0) {
            effectiveBearing = lastAcceptedBearing
        } else {
            effectiveBearing = bearing
            lastAcceptedBearing = bearing
        }

        // Update segment index
        currentSegmentIndex = findNearestSegmentIndex(effectivePosition, route)

        // Check off-route
        val routePoints = route.allPoints
        val offRouteState = offRouteDetector.checkPosition(effectivePosition, routePoints)

        // Find next maneuver
        val nextManeuver = findNextManeuver(effectivePosition, route)
        val distToManeuver = nextManeuver?.let { effectivePosition.distanceTo(it.point) }

        // Calculate remaining
        val remaining = calculateRemainingDistance(route, currentSegmentIndex)
        val remainingTime = calculateRemainingTime(route, currentSegmentIndex)

        // Check if arrived
        val distToDestination = effectivePosition.distanceTo(route.destination)

        val newStatus = when {
            distToDestination < 50.0 -> NavigationStatus.ARRIVED
            offRouteState == OffRouteState.OFF_ROUTE -> NavigationStatus.OFF_ROUTE
            offRouteState == OffRouteState.RECALCULATING -> NavigationStatus.RECALCULATING
            offRouteState == OffRouteState.ROUTE_RECOVERED -> NavigationStatus.ROUTE_RECOVERED
            offRouteState == OffRouteState.ROUTING_ERROR -> NavigationStatus.ROUTING_ERROR
            currentState.status == NavigationStatus.RECOVERED -> NavigationStatus.ON_ROUTE
            else -> NavigationStatus.ON_ROUTE
        }

        _navigationState.value = NavigationState(
            status = newStatus,
            route = route,
            currentPosition = effectivePosition,
            currentBearing = effectiveBearing,
            currentSpeedMps = effectiveSpeed,
            nextManeuver = nextManeuver,
            distanceToNextManeuverMeters = distToManeuver,
            remainingDistanceMeters = remaining,
            remainingTimeSeconds = remainingTime,
            currentSegmentIndex = currentSegmentIndex,
            isRecovered = currentState.isRecovered && newStatus == NavigationStatus.RECOVERED
        )
    }

    /**
     * Reroute from current position after going off-route.
     */
    suspend fun reroute(): RoutingResult {
        val route = activeRoute ?: return RoutingResult.Error(
            pl.mazovia.offroad.domain.routing.RoutingError.CALCULATION_ERROR
        )
        val currentPos = _navigationState.value.currentPosition ?: return RoutingResult.Error(
            pl.mazovia.offroad.domain.routing.RoutingError.ORIGIN_NOT_FOUND
        )

        offRouteDetector.setRecalculating()
        _navigationState.value = _navigationState.value.copy(
            status = NavigationStatus.RECALCULATING
        )

        val result = routingEngine.recalculateFromPosition(
            currentPosition = currentPos,
            destination = route.destination,
            profile = route.profile
        )

        when (result) {
            is RoutingResult.Success -> {
                activeRoute = result.route
                currentSegmentIndex = 0
                offRouteDetector.reset()
                _navigationState.value = _navigationState.value.copy(
                    status = NavigationStatus.ON_ROUTE,
                    route = result.route,
                    nextManeuver = result.route.maneuvers.firstOrNull(),
                    remainingDistanceMeters = result.route.totalDistanceMeters,
                    remainingTimeSeconds = result.route.metrics.estimatedTimeSeconds
                )
            }
            is RoutingResult.Error -> {
                offRouteDetector.setError()
                _navigationState.value = _navigationState.value.copy(
                    status = NavigationStatus.ROUTING_ERROR
                )
            }
        }

        return result
    }

    /**
     * Stop navigation.
     */
    fun stopNavigation() {
        activeRoute = null
        currentSegmentIndex = 0
        offRouteDetector.reset()
        _navigationState.value = NavigationState(status = NavigationStatus.IDLE)
    }

    fun getCurrentSegmentIndex(): Int = currentSegmentIndex

    private fun findNearestSegmentIndex(position: GeoPoint, route: Route): Int {
        if (route.segments.isEmpty()) return 0

        var minDist = Double.MAX_VALUE
        var nearestIdx = currentSegmentIndex

        // Search around current index for efficiency
        val searchStart = (currentSegmentIndex - 2).coerceAtLeast(0)
        val searchEnd = (currentSegmentIndex + 5).coerceAtMost(route.segments.size - 1)

        for (i in searchStart..searchEnd) {
            val segment = route.segments[i]
            for (point in segment.points) {
                val dist = position.distanceTo(point)
                if (dist < minDist) {
                    minDist = dist
                    nearestIdx = i
                }
            }
        }

        return nearestIdx
    }

    private fun findNextManeuver(position: GeoPoint, route: Route): Maneuver? {
        return route.maneuvers
            .filter { position.distanceTo(it.point) > 20.0 }
            .minByOrNull { position.distanceTo(it.point) }
    }

    private fun calculateRemainingDistance(route: Route, fromSegment: Int): Double {
        return route.segments.drop(fromSegment).sumOf { it.distanceMeters }
    }

    private fun calculateRemainingTime(route: Route, fromSegment: Int): Long {
        val totalDist = route.segments.sumOf { it.distanceMeters }
        val remainDist = calculateRemainingDistance(route, fromSegment)
        if (totalDist <= 0) return 0
        return (route.metrics.estimatedTimeSeconds * remainDist / totalDist).toLong()
    }
}
