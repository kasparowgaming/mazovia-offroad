package pl.mazovia.offroad.terrain

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.HighwayType
import pl.mazovia.offroad.domain.model.NavigationState
import pl.mazovia.offroad.domain.model.NavigationStatus
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RouteMetrics
import pl.mazovia.offroad.domain.model.RouteSegment
import pl.mazovia.offroad.domain.model.RouteSource
import pl.mazovia.offroad.domain.model.RoutingProfile
import pl.mazovia.offroad.domain.model.Surface
import pl.mazovia.offroad.terrain.geo.SphericalEarth
import kotlin.math.cos
import kotlin.math.sin

/** Deterministic geometry and route builders for TA-001A tests. */
object TestFixtures {
    val ORIGIN = GeoPoint(52.2, 21.0)

    /** Point [eastM]/[northM] metres from [from] (local equirectangular offset at [from]'s latitude). */
    fun offset(from: GeoPoint, eastM: Double, northM: Double): GeoPoint = GeoPoint(
        latitude = from.latitude + northM / SphericalEarth.METERS_PER_DEG_LAT,
        longitude = from.longitude + eastM / SphericalEarth.metersPerDegLon(from.latitude)
    )

    fun at(eastM: Double, northM: Double = 0.0) = offset(ORIGIN, eastM, northM)

    /** Straight polyline from (x0,y0) to (x1,y1) in metres relative to [ORIGIN], vertices every [stepM]. */
    fun line(x0: Double, y0: Double, x1: Double, y1: Double, stepM: Double = 10.0): List<GeoPoint> {
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        val n = maxOf(1, Math.round(len / stepM).toInt())
        return (0..n).map { at(x0 + (x1 - x0) * it / n, y0 + (y1 - y0) * it / n) }
    }

    /** Joins polylines, dropping duplicated junction vertices. */
    fun path(vararg parts: List<GeoPoint>): List<GeoPoint> {
        val out = mutableListOf<GeoPoint>()
        for (p in parts) for (pt in p) if (out.isEmpty() || out.last() != pt) out += pt
        return out
    }

    private fun segment(points: List<GeoPoint>) = RouteSegment(
        points = points,
        distanceMeters = points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) },
        surface = Surface.GRAVEL,
        highway = HighwayType.TRACK
    )

    /** Calculated route; consecutive segments share their boundary vertex (as GraphHopperRoutingEngine does). */
    fun calculatedRoute(id: String, points: List<GeoPoint>, segmentLength: Int = Int.MAX_VALUE): Route {
        val segments = mutableListOf<RouteSegment>()
        var start = 0
        while (start < points.size - 1) {
            val end = minOf(points.size - 1, start + segmentLength)
            segments += segment(points.subList(start, end + 1))
            start = end
        }
        return route(id, segments, RouteSource.CALCULATED_ROUTE)
    }

    /** Imported GPX route: one RouteSegment per trkseg; gaps between segments. */
    fun gpxRoute(id: String, segments: List<List<GeoPoint>>): Route =
        route(id, segments.map { segment(it) }, RouteSource.IMPORTED_GPX)

    private fun route(id: String, segments: List<RouteSegment>, source: RouteSource): Route {
        val all = segments.flatMap { it.points }
        return Route(
            id = id,
            origin = all.first(),
            destination = all.last(),
            segments = segments,
            metrics = RouteMetrics.fromSegments(segments),
            profile = RoutingProfile.TERENOWY,
            source = source
        )
    }

    fun state(
        route: Route?,
        position: GeoPoint?,
        status: NavigationStatus = NavigationStatus.ON_ROUTE,
        speedMps: Double? = 10.0
    ) = NavigationState(status = status, route = route, currentPosition = position, currentSpeedMps = speedMps)

    /**
     * Long meandering route (deterministic): [count] vertices, [stepM] apart, heading oscillating around east.
     */
    fun meanderingPoints(count: Int, stepM: Double = 10.0, start: GeoPoint = ORIGIN): List<GeoPoint> {
        val pts = ArrayList<GeoPoint>(count)
        var p = start
        pts += p
        for (i in 1 until count) {
            val bearing = Math.toRadians(90.0 + 35.0 * sin(i / 300.0) + 10.0 * sin(i / 37.0))
            p = offset(p, stepM * sin(bearing), stepM * cos(bearing))
            pts += p
        }
        return pts
    }

    /**
     * Regression reference: a verbatim re-statement of the documented NavigationManager.initializeRouteData loop
     * (NavigationManager.kt:49-60) using the same GeoPoint.distanceTo primitive as RouteIndex. It is NOT an independent
     * NavigationManager oracle — agreement proves RouteIndex still follows the documented semantics (ordering,
     * boundaries, GPX gap = 0, stable accumulation), not that the documentation matches NavigationManager. A real
     * NavigationManager cross-check is deferred to the app/integration task.
     */
    fun navigationManagerAxis(route: Route): DoubleArray {
        val activeRoutePoints = route.allPoints
        val activeRoutePointDistances = DoubleArray(activeRoutePoints.size)
        val gpxBreaks = if (route.source == RouteSource.IMPORTED_GPX)
            route.segments.dropLast(1).runningFold(0) { count, segment -> count + segment.points.size }
                .drop(1).toSet() else emptySet()
        var cumulativeDist = 0.0
        for (i in 1 until activeRoutePoints.size) {
            if (i !in gpxBreaks) cumulativeDist += activeRoutePoints[i - 1].distanceTo(activeRoutePoints[i])
            activeRoutePointDistances[i] = cumulativeDist
        }
        return activeRoutePointDistances
    }
}
