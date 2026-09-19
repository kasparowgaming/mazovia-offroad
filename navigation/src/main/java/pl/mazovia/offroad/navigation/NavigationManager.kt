package pl.mazovia.offroad.navigation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.mazovia.offroad.domain.location.LocationClient
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.navigation.OffRouteDetector
import pl.mazovia.offroad.domain.navigation.OffRouteState
import pl.mazovia.offroad.domain.routing.RoutingEngine
import pl.mazovia.offroad.domain.routing.RoutingResult
import java.util.UUID

class NavigationManager(
    private val routingEngine: RoutingEngine,
    private val locationClient: LocationClient? = null
) {
    private val _navigationState = MutableStateFlow(NavigationState(status = NavigationStatus.IDLE))
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val offRouteDetector = OffRouteDetector()
    private var activeRoute: Route? = null
    
    // Tracking state
    private var currentSegmentIndex = 0
    private var currentPointIndex = 0
    private var activeRoutePoints: List<GeoPoint> = emptyList()
    private var activeRoutePointDistances: DoubleArray = DoubleArray(0)
    private var maneuverIndices: List<Int> = emptyList()

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
                // Ignore for now
            }
        }
    }

    private fun initializeRouteData(route: Route, startSegmentIdx: Int) {
        activeRoute = route
        activeRoutePoints = route.allPoints
        activeRoutePointDistances = DoubleArray(activeRoutePoints.size)
        var cumulativeDist = 0.0
        for (i in 1 until activeRoutePoints.size) {
            cumulativeDist += activeRoutePoints[i - 1].distanceTo(activeRoutePoints[i])
            activeRoutePointDistances[i] = cumulativeDist
        }
        
        maneuverIndices = route.maneuvers.map { maneuver ->
            var bestIdx = 0
            var bestDist = Double.MAX_VALUE
            for (i in activeRoutePoints.indices) {
                val dist = maneuver.point.distanceTo(activeRoutePoints[i])
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = i
                }
            }
            bestIdx
        }
        
        currentSegmentIndex = startSegmentIdx
        currentPointIndex = 0 // Will snap correctly on first GPS update
    }

    fun startNavigation(route: Route) {
        initializeRouteData(route, 0)
        offRouteDetector.reset()
        _navigationState.value = NavigationState(
            status = NavigationStatus.ON_ROUTE,
            route = route,
            currentSegmentIndex = 0,
            remainingDistanceMeters = activeRoutePointDistances.lastOrNull() ?: 0.0,
            remainingTimeSeconds = route.metrics.estimatedTimeSeconds
        )
    }

    fun startGpxFollowing(gpxData: GpxData, route: Route?) {
        val gpxRoute = route ?: createPseudoRouteFromGpx(gpxData)
        initializeRouteData(gpxRoute, 0)
        offRouteDetector.reset()
        _navigationState.value = NavigationState(
            status = NavigationStatus.FOLLOWING_GPX,
            route = gpxRoute,
            currentSegmentIndex = 0,
            remainingDistanceMeters = activeRoutePointDistances.lastOrNull() ?: 0.0,
            remainingTimeSeconds = gpxRoute.metrics.estimatedTimeSeconds
        )
    }

    private fun createPseudoRouteFromGpx(gpx: GpxData): Route {
        val allPoints = gpx.tracks.flatMap { t -> t.segments.flatMap { s -> s.points.map { it.point } } }
        val origin = allPoints.firstOrNull() ?: GeoPoint.WARSAW
        val destination = allPoints.lastOrNull() ?: GeoPoint.WARSAW
        val distance = allPoints.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        val segments = listOf(
            RouteSegment(
                points = allPoints,
                distanceMeters = distance,
                surface = Surface.UNKNOWN,
                highway = HighwayType.UNKNOWN,
                dataConfidence = DataConfidence.UNKNOWN
            )
        )
        return Route(
            id = UUID.randomUUID().toString(),
            origin = origin,
            destination = destination,
            segments = segments,
            metrics = RouteMetrics(
                totalDistanceMeters = distance,
                estimatedTimeSeconds = (distance / (30.0 * 1000.0 / 3600.0)).toLong(),
                asphaltDistanceMeters = 0.0,
                offRoadDistanceMeters = 0.0,
                longestAsphaltConnectorMeters = 0.0,
                surfaceDistribution = emptyMap(),
                dataConfidenceScore = 0.0
            ),
            profile = pl.mazovia.offroad.domain.model.RoutingProfile.ODKRYWCZY
        )
    }

    fun restoreSession(route: Route, segmentIndex: Int) {
        initializeRouteData(route, segmentIndex)
        offRouteDetector.reset()
        val totalDist = activeRoutePointDistances.lastOrNull() ?: 0.0
        _navigationState.value = NavigationState(
            status = NavigationStatus.RECOVERED,
            route = route,
            currentSegmentIndex = segmentIndex,
            isRecovered = true,
            remainingDistanceMeters = totalDist, // Will refine on first GPS ping
            remainingTimeSeconds = route.metrics.estimatedTimeSeconds
        )
    }

    fun updatePosition(position: GeoPoint, bearing: Double?, speedMps: Double?) {
        val route = activeRoute ?: return
        val currentState = _navigationState.value

        if (currentState.status == NavigationStatus.IDLE ||
            currentState.status == NavigationStatus.ARRIVED) return

        var effectiveSpeed = speedMps?.coerceAtLeast(0.0) ?: 0.0
        if (effectiveSpeed < 1.5) effectiveSpeed = 0.0

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

        if (speedMps == null || speedMps < 2.0) {
            effectiveBearing = lastAcceptedBearing
        } else {
            effectiveBearing = bearing
            lastAcceptedBearing = bearing
        }

        // Snap to point index
        val searchStart = (currentPointIndex - 5).coerceAtLeast(0)
        val searchEnd = (currentPointIndex + 200).coerceAtMost(activeRoutePoints.size - 1)
        var minDist = Double.MAX_VALUE
        var nearestPointIdx = currentPointIndex
        if (activeRoutePoints.isNotEmpty()) {
            for (i in searchStart..searchEnd) {
                val dist = effectivePosition.distanceTo(activeRoutePoints[i])
                if (dist < minDist) {
                    minDist = dist
                    nearestPointIdx = i
                }
            }
        }
        currentPointIndex = nearestPointIdx
        currentSegmentIndex = findNearestSegmentIndex(effectivePosition, route)

        val offRouteState = offRouteDetector.checkPosition(effectivePosition, activeRoutePoints)

        // Find next maneuver
        var nextManeuver: Maneuver? = null
        var distToManeuver: Double? = null
        for (i in maneuverIndices.indices) {
            val mIdx = maneuverIndices[i]
            if (mIdx > currentPointIndex) {
                nextManeuver = route.maneuvers[i]
                distToManeuver = (activeRoutePointDistances[mIdx] - activeRoutePointDistances[currentPointIndex]).coerceAtLeast(0.0)
                break
            }
        }

        val totalDist = activeRoutePointDistances.lastOrNull() ?: 0.0
        val remainingDist = if (activeRoutePoints.isNotEmpty()) {
            (totalDist - activeRoutePointDistances[currentPointIndex]).coerceAtLeast(0.0)
        } else 0.0
        
        val remainingTime = if (totalDist > 0) {
            (route.metrics.estimatedTimeSeconds * remainingDist / totalDist).toLong()
        } else 0L

        val straightLineDest = effectivePosition.distanceTo(route.destination)
        val isArrived = remainingDist < 50.0 || straightLineDest < 30.0

        val newStatus = when {
            isArrived -> NavigationStatus.ARRIVED
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
            remainingDistanceMeters = remainingDist,
            remainingTimeSeconds = remainingTime,
            currentSegmentIndex = currentSegmentIndex,
            isRecovered = currentState.isRecovered && newStatus == NavigationStatus.RECOVERED
        )
    }

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
                initializeRouteData(result.route, 0)
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

    fun stopNavigation() {
        activeRoute = null
        currentSegmentIndex = 0
        currentPointIndex = 0
        offRouteDetector.reset()
        _navigationState.value = NavigationState(status = NavigationStatus.IDLE)
    }

    fun getCurrentSegmentIndex(): Int = currentSegmentIndex

    private fun findNearestSegmentIndex(position: GeoPoint, route: Route): Int {
        if (route.segments.isEmpty()) return 0
        var minDist = Double.MAX_VALUE
        var nearestIdx = currentSegmentIndex
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
}
