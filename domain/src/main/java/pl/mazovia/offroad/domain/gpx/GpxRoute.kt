package pl.mazovia.offroad.domain.gpx

import pl.mazovia.offroad.domain.model.*
import java.util.UUID

/** A display/navigation route whose source geometry remains the parsed GPX artifact. */
object GpxRoute {
    fun create(gpx: GpxData): Route {
        require(gpx.tracks.size == 1) { "Plik zawiera wiele śladów. Wybierz GPX z jednym śladem." }
        require(gpx.totalPoints >= 2 && gpx.totalDistanceMeters > 0.0) {
            "GPX nie zawiera użytecznego śladu. Wybierz plik z co najmniej dwoma punktami."
        }
        val segments = gpx.tracks.single().segments.map { segment ->
            val points = segment.points.map { it.point }
            RouteSegment(
                points = points,
                distanceMeters = points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) },
                surface = Surface.UNKNOWN,
                highway = HighwayType.UNKNOWN
            )
        }
        val points = segments.flatMap { it.points }
        return Route(
            id = UUID.randomUUID().toString(),
            origin = points.first(),
            destination = points.last(),
            segments = segments,
            metrics = RouteMetrics(
                totalDistanceMeters = gpx.totalDistanceMeters,
                estimatedTimeSeconds = (gpx.totalDistanceMeters / (30.0 / 3.6)).toLong(),
                asphaltDistanceMeters = 0.0,
                offRoadDistanceMeters = 0.0,
                longestAsphaltConnectorMeters = 0.0,
                surfaceDistribution = emptyMap(),
                dataConfidenceScore = 0.0
            ),
            profile = RoutingProfile.ODKRYWCZY,
            waypoints = gpx.waypoints.map { it.point },
            source = RouteSource.IMPORTED_GPX,
            originalGpx = gpx
        )
    }
}
