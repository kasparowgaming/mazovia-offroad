package pl.mazovia.offroad.domain.navigation

import pl.mazovia.offroad.domain.model.*

/**
 * Off-route detection with GPS noise tolerance.
 */
class OffRouteDetector(
    /** Distance threshold in meters before considering off-route */
    private val offRouteThresholdMeters: Double = 50.0,
    /** Number of consecutive off-route readings before triggering */
    private val consecutiveReadingsThreshold: Int = 3,
    /** Distance threshold for considering back on route */
    private val onRouteThresholdMeters: Double = 30.0
) {
    private var consecutiveOffRouteCount = 0
    private var currentState = OffRouteState.ON_ROUTE

    fun reset() {
        consecutiveOffRouteCount = 0
        currentState = OffRouteState.ON_ROUTE
    }

    /**
     * Check if the current position is off route.
     * Returns the new state considering GPS noise tolerance.
     */
    fun checkPosition(
        currentPosition: GeoPoint,
        routePoints: List<GeoPoint>
    ): OffRouteState {
        if (routePoints.isEmpty()) return OffRouteState.ON_ROUTE

        val minDistance = findMinDistanceToRoute(currentPosition, routePoints)

        return when (currentState) {
            OffRouteState.ON_ROUTE -> {
                if (minDistance > offRouteThresholdMeters) {
                    consecutiveOffRouteCount++
                    if (consecutiveOffRouteCount >= consecutiveReadingsThreshold) {
                        currentState = OffRouteState.OFF_ROUTE
                        OffRouteState.OFF_ROUTE
                    } else {
                        OffRouteState.ON_ROUTE
                    }
                } else {
                    consecutiveOffRouteCount = 0
                    OffRouteState.ON_ROUTE
                }
            }
            OffRouteState.OFF_ROUTE, OffRouteState.RECALCULATING -> {
                if (minDistance <= onRouteThresholdMeters) {
                    consecutiveOffRouteCount = 0
                    currentState = OffRouteState.ROUTE_RECOVERED
                    OffRouteState.ROUTE_RECOVERED
                } else {
                    currentState
                }
            }
            OffRouteState.ROUTE_RECOVERED -> {
                consecutiveOffRouteCount = 0
                currentState = OffRouteState.ON_ROUTE
                OffRouteState.ON_ROUTE
            }
            OffRouteState.ROUTING_ERROR -> currentState
        }
    }

    fun setRecalculating() {
        currentState = OffRouteState.RECALCULATING
    }

    fun setError() {
        currentState = OffRouteState.ROUTING_ERROR
    }

    private fun findMinDistanceToRoute(
        point: GeoPoint,
        routePoints: List<GeoPoint>
    ): Double {
        if (routePoints.size < 2) {
            return routePoints.firstOrNull()?.let { point.distanceTo(it) } ?: Double.MAX_VALUE
        }

        var minDist = Double.MAX_VALUE
        for (i in 0 until routePoints.size - 1) {
            val dist = distanceToSegment(point, routePoints[i], routePoints[i + 1])
            minDist = minOf(minDist, dist)
        }
        return minDist
    }

    /**
     * Distance from a point to a line segment (great-circle approximation for short distances).
     */
    private fun distanceToSegment(
        point: GeoPoint,
        segStart: GeoPoint,
        segEnd: GeoPoint
    ): Double {
        val d = segStart.distanceTo(segEnd)
        if (d < 1.0) return point.distanceTo(segStart)

        // Project point onto segment using linear approximation (valid for short segments)
        val t = ((
            (point.latitude - segStart.latitude) * (segEnd.latitude - segStart.latitude) +
            (point.longitude - segStart.longitude) * (segEnd.longitude - segStart.longitude)
        ) / (
            (segEnd.latitude - segStart.latitude) * (segEnd.latitude - segStart.latitude) +
            (segEnd.longitude - segStart.longitude) * (segEnd.longitude - segStart.longitude)
        )).coerceIn(0.0, 1.0)

        val projectedPoint = GeoPoint(
            latitude = segStart.latitude + t * (segEnd.latitude - segStart.latitude),
            longitude = segStart.longitude + t * (segEnd.longitude - segStart.longitude)
        )
        return point.distanceTo(projectedPoint)
    }
}

enum class OffRouteState {
    ON_ROUTE,
    OFF_ROUTE,
    RECALCULATING,
    ROUTE_RECOVERED,
    ROUTING_ERROR
}
