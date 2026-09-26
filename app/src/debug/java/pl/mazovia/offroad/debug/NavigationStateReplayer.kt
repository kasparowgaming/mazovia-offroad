package pl.mazovia.offroad.debug

import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.ui.riding.terrain.TerrainDisplayState
import pl.mazovia.offroad.ui.riding.terrain.TerrainPresenter

/** Debug-only deterministic inputs. No location service or NavigationManager access. */
object NavigationStateReplayer {
    data class Emission(val atMillis: Long, val state: NavigationState)
    data class Sample(val atMillis: Long, val display: TerrainDisplayState)

    /** Feed one script with virtual 33 ms frames. The caller owns the presenter and its lifetime. */
    fun replay(
        script: List<Emission>, presenter: TerrainPresenter = TerrainPresenter(),
        untilMillis: Long = script.lastOrNull()?.atMillis ?: 0,
        frameMillis: Long = 33
    ): List<Sample> {
        require(frameMillis > 0 && untilMillis >= 0)
        require(script.zipWithNext().all { (a, b) -> a.atMillis <= b.atMillis })
        val output = mutableListOf<Sample>()
        var next = 0
        var time = 0L
        while (time <= untilMillis) {
            while (next < script.size && script[next].atMillis <= time) {
                val emission = script[next++]
                output += Sample(emission.atMillis,
                    presenter.onNavigationState(emission.state, emission.atMillis * 1_000_000))
            }
            output += Sample(time, presenter.frame(time * 1_000_000))
            time += frameMillis
        }
        while (next < script.size && script[next].atMillis <= untilMillis) {
            val emission = script[next++]
            output += Sample(emission.atMillis,
                presenter.onNavigationState(emission.state, emission.atMillis * 1_000_000))
        }
        return output
    }

    private fun point(metres: Double, north: Double = 0.0) =
        GeoPoint(52.0 + north / 111_195.0, 21.0 + metres / 68_459.0)

    fun route(id: String = "road", lengthM: Double = 1000.0): Route {
        val a = point(0.0)
        val b = point(lengthM)
        val segment = RouteSegment(listOf(a, b), a.distanceTo(b), Surface.UNKNOWN, HighwayType.UNCLASSIFIED)
        return Route(id, a, b, listOf(segment), RouteMetrics.fromSegments(listOf(segment)), RoutingProfile.BEZPIECZNY)
    }

    fun state(route: Route, metres: Double, speed: Double = 10.0,
              status: NavigationStatus = NavigationStatus.ON_ROUTE, north: Double = 0.0): NavigationState =
        NavigationState(status, route, point(metres, north), currentSpeedMps = speed,
            currentBearing = 90.0, distanceToGpxMeters = if (status == NavigationStatus.OFF_ROUTE) north else null)

    fun steady() = listOf(0L to 100.0, 1000L to 110.0, 2000L to 120.0)
        .map { (time, s) -> Emission(time, state(route(), s)) }

    fun jitter() = listOf(100.0, 104.0, 101.0, 106.0, 103.0, 109.0)
        .mapIndexed { i, s -> Emission(i * 1000L, state(route(), s)) }

    fun acceleration() = listOf(0L to (100.0 to 2.0), 1000L to (103.0 to 5.0), 2000L to (111.0 to 10.0))
        .map { (time, pair) -> Emission(time, state(route(), pair.first, pair.second)) }

    fun deceleration() = listOf(0L to (100.0 to 12.0), 1000L to (110.0 to 5.0), 2000L to (113.0 to 0.0))
        .map { (time, pair) -> Emission(time, state(route(), pair.first, pair.second)) }

    fun stopped() = listOf(Emission(0, state(route(), 100.0, 0.0)))
    fun missing() = listOf(Emission(0, state(route(), 100.0, 10.0)))
    fun routeChange() = listOf(Emission(0, state(route("old"), 700.0)),
        Emission(1000, state(route("new"), 50.0)))
    fun reversal() = listOf(100.0, 115.0, 100.0, 85.0, 70.0, 85.0, 100.0, 115.0)
        .mapIndexed { i, s -> Emission(i * 1000L, state(route(), s, 10.0)) }
    fun offRoute() = listOf(Emission(0, state(route(), 100.0)),
        Emission(1000, state(route(), 110.0, status = NavigationStatus.OFF_ROUTE, north = 80.0)))
    /** Attached to 200 m, DETACHED, then a same-route §6.3 re-attachment 70 m behind and normal forward progress. */
    fun reattach() = listOf(Emission(0, state(route(), 180.0)), Emission(1000, state(route(), 190.0)),
        Emission(2000, state(route(), 200.0)),
        Emission(3000, state(route(), 205.0, status = NavigationStatus.OFF_ROUTE, north = 80.0))) +
        listOf(130.0, 140.0, 150.0, 160.0, 170.0).mapIndexed { i, s -> Emission(4000L + i * 1000L, state(route(), s)) }
    fun arrived() =listOf(Emission(0, state(route(), 990.0)),
        Emission(1000, state(route(), 1000.0, status = NavigationStatus.ARRIVED)))

    fun gpx(): List<Emission> {
        val first = listOf(point(0.0), point(100.0))
        val second = listOf(point(300.0), point(400.0))
        val trackSegments = listOf(
            GpxSegment(first.map { GpxTrackPoint(it) }),
            GpxSegment(second.map { GpxTrackPoint(it) })
        )
        val gpx = GpxData(null, null, listOf(GpxTrack(null, trackSegments)))
        val segments = listOf(first, second).map { points ->
            RouteSegment(points, points[0].distanceTo(points[1]), Surface.UNKNOWN, HighwayType.UNCLASSIFIED)
        }
        val route = Route("gpx", first.first(), second.last(), segments, RouteMetrics.fromSegments(segments),
            RoutingProfile.BEZPIECZNY, source = RouteSource.IMPORTED_GPX, originalGpx = gpx)
        return listOf(Emission(0, state(route, 50.0, status = NavigationStatus.FOLLOWING_GPX)),
            Emission(1000, state(route, 350.0, status = NavigationStatus.FOLLOWING_GPX)))
    }
}
