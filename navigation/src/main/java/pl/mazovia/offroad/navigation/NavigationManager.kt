package pl.mazovia.offroad.navigation

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.mazovia.offroad.domain.location.LocationClient
import pl.mazovia.offroad.domain.gpx.GpxRoute
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.navigation.OffRouteDetector
import pl.mazovia.offroad.domain.navigation.OffRouteState
import pl.mazovia.offroad.domain.routing.RoutingEngine
import pl.mazovia.offroad.domain.routing.RoutingResult

class NavigationManager internal constructor(
    private val routingEngine: RoutingEngine,
    private val locationClient: LocationClient?,
    private val scope: CoroutineScope,
    private val logLocation: (String) -> Unit
) {
    constructor(routingEngine: RoutingEngine, locationClient: LocationClient? = null) : this(
        routingEngine,
        locationClient,
        CoroutineScope(Dispatchers.Default),
        { message -> android.util.Log.i(LOCATION_LOG_TAG, message) }
    )

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
    // Wakes a pending location retry early; conflated so repeated navigation events never queue retries.
    private val locationRetrySignal = Channel<Unit>(Channel.CONFLATED)
    private var lastAcceptedPosition: GeoPoint? = null
    private var lastAcceptedBearing: Double? = null

    init {
        if (locationClient != null) {
            locationJob = scope.launch { collectLocationWithRecovery(locationClient) }
        }
    }

    /**
     * Single long-lived location subscription. A failed or completed subscription is retried with a capped
     * backoff for the whole lifetime of [scope]; there is no exhaustion state. Cancellation of [scope] stops the
     * active collection, the pending delay and all later attempts. Logs never contain coordinates.
     */
    private suspend fun collectLocationWithRecovery(client: LocationClient) {
        var consecutiveFailures = 0
        while (true) {
            logLocation("subscription attempt (consecutive failures: $consecutiveFailures)")
            // Wake-ups are stale once an attempt starts or an update arrives; one still pending when this attempt
            // fails was sent after the last good update and must wake the retry, so nothing is drained after failure.
            locationRetrySignal.tryReceive()
            var receivedUpdate = false
            val failure = try {
                client.getLocationUpdates(LOCATION_INTERVAL_MS).collect { update ->
                    locationRetrySignal.tryReceive()
                    if (!receivedUpdate) {
                        receivedUpdate = true
                        if (consecutiveFailures > 0) {
                            logLocation("subscription recovered after $consecutiveFailures failures")
                        }
                        consecutiveFailures = 0
                    }
                    updatePosition(update.point, update.bearing?.toDouble(), update.speedMps?.toDouble())
                }
                "flow completed"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e::class.java.simpleName
            }
            consecutiveFailures++
            val delayMs = locationRetryDelayMs(consecutiveFailures)
            logLocation("subscription failed: $failure; retry #$consecutiveFailures in $delayMs ms")
            if (withTimeoutOrNull(delayMs) { locationRetrySignal.receive() } != null) {
                logLocation("retry woken early by navigation event")
            }
        }
    }

    private fun wakeLocationRetry() {
        locationRetrySignal.trySend(Unit)
    }

    private fun initializeRouteData(route: Route, startSegmentIdx: Int) {
        activeRoute = route
        activeRoutePoints = route.allPoints
        activeRoutePointDistances = DoubleArray(activeRoutePoints.size)
        val gpxBreaks = if (route.source == RouteSource.IMPORTED_GPX)
            route.segments.dropLast(1).runningFold(0) { count, segment -> count + segment.points.size }
                .drop(1).toSet() else emptySet()
        var cumulativeDist = 0.0
        for (i in 1 until activeRoutePoints.size) {
            if (i !in gpxBreaks) cumulativeDist += activeRoutePoints[i - 1].distanceTo(activeRoutePoints[i])
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
        wakeLocationRetry()
        if (route.source == RouteSource.IMPORTED_GPX) {
            startGpxFollowing(requireNotNull(route.originalGpx), route)
            return
        }
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
        wakeLocationRetry()
        val gpxRoute = route?.takeIf { it.source == RouteSource.IMPORTED_GPX && it.originalGpx == gpxData }
            ?: GpxRoute.create(gpxData)
        initializeRouteData(gpxRoute, 0)
        lastAcceptedPosition = null
        lastAcceptedBearing = null
        offRouteDetector.reset()
        _navigationState.value = NavigationState(
            status = NavigationStatus.FOLLOWING_GPX,
            route = gpxRoute,
            currentSegmentIndex = 0,
            remainingDistanceMeters = activeRoutePointDistances.lastOrNull() ?: 0.0,
            remainingTimeSeconds = gpxRoute.metrics.estimatedTimeSeconds
        )
    }

    fun restoreSession(route: Route, segmentIndex: Int) {
        wakeLocationRetry()
        if (route.source == RouteSource.IMPORTED_GPX) {
            startNavigation(route)
            _navigationState.value = _navigationState.value.copy(isRecovered = true)
            return
        }
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
        val searchStart = if (route.source == RouteSource.IMPORTED_GPX) 0
            else (currentPointIndex - 5).coerceAtLeast(0)
        val searchEnd = if (route.source == RouteSource.IMPORTED_GPX) activeRoutePoints.size - 1
            else (currentPointIndex + 200).coerceAtMost(activeRoutePoints.size - 1)
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

        val offRouteState = if (route.source == RouteSource.IMPORTED_GPX)
            offRouteDetector.checkPositionOnSegments(effectivePosition, route.segments.map { it.points })
        else offRouteDetector.checkPosition(effectivePosition, activeRoutePoints)
        val nearestGpxPoint = if (route.source == RouteSource.IMPORTED_GPX)
            activeRoutePoints.minByOrNull { effectivePosition.distanceTo(it) } else null

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
        val isArrived = if (route.source == RouteSource.IMPORTED_GPX)
            currentPointIndex == activeRoutePoints.lastIndex && straightLineDest < 30.0 &&
                offRouteState != OffRouteState.OFF_ROUTE
        else remainingDist < 50.0 || straightLineDest < 30.0

        val newStatus = when {
            isArrived -> NavigationStatus.ARRIVED
            offRouteState == OffRouteState.OFF_ROUTE -> NavigationStatus.OFF_ROUTE
            offRouteState == OffRouteState.RECALCULATING -> NavigationStatus.RECALCULATING
            offRouteState == OffRouteState.ROUTE_RECOVERED -> NavigationStatus.ROUTE_RECOVERED
            offRouteState == OffRouteState.ROUTING_ERROR -> NavigationStatus.ROUTING_ERROR
            currentState.status == NavigationStatus.RECOVERED -> NavigationStatus.ON_ROUTE
            route.source == RouteSource.IMPORTED_GPX -> NavigationStatus.FOLLOWING_GPX
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
            isRecovered = currentState.isRecovered && newStatus == NavigationStatus.RECOVERED,
            returnToGpx = currentState.returnToGpx && newStatus == NavigationStatus.OFF_ROUTE,
            distanceToGpxMeters = nearestGpxPoint?.let { effectivePosition.distanceTo(it) },
            bearingToGpx = nearestGpxPoint?.let { effectivePosition.bearingTo(it) }
        )
    }

    fun guideBackToGpx() {
        if (_navigationState.value.route?.source == RouteSource.IMPORTED_GPX)
            _navigationState.value = _navigationState.value.copy(returnToGpx = true)
    }

    fun continueGpx() {
        if (_navigationState.value.route?.source == RouteSource.IMPORTED_GPX)
            _navigationState.value = _navigationState.value.copy(returnToGpx = false)
    }

    suspend fun reroute(): RoutingResult {
        val route = activeRoute ?: return RoutingResult.Error(
            pl.mazovia.offroad.domain.routing.RoutingError.CALCULATION_ERROR
        )
        if (route.source == RouteSource.IMPORTED_GPX) return RoutingResult.Error(
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

    internal companion object {
        const val LOCATION_INTERVAL_MS = 1_000L
        const val LOCATION_RETRY_INITIAL_DELAY_MS = 1_000L
        const val LOCATION_RETRY_MAX_DELAY_MS = 30_000L
        const val LOCATION_LOG_TAG = "NavLocation"

        /** 1 s, 2 s, 4 s, 8 s, 16 s, then 30 s for every later consecutive failure. */
        fun locationRetryDelayMs(consecutiveFailures: Int): Long =
            (LOCATION_RETRY_INITIAL_DELAY_MS shl (consecutiveFailures - 1).coerceIn(0, 5))
                .coerceAtMost(LOCATION_RETRY_MAX_DELAY_MS)
    }
}
