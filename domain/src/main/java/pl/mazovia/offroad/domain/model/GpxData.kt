package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * Parsed GPX file data.
 */
@Serializable
data class GpxData(
    val name: String?,
    val description: String?,
    val tracks: List<GpxTrack>,
    val waypoints: List<GpxWaypoint> = emptyList(),
    val sourceFileName: String? = null
) {
    val totalPoints: Int get() = tracks.sumOf { it.totalPoints }
    val totalDistanceMeters: Double get() = tracks.sumOf { it.distanceMeters }
    val segmentCount: Int get() = tracks.sumOf { it.segments.size }
}

@Serializable
data class GpxTrack(
    val name: String?,
    val segments: List<GpxSegment>
) {
    val totalPoints: Int get() = segments.sumOf { it.points.size }
    val distanceMeters: Double get() = segments.sumOf { segment ->
        segment.points.zipWithNext().sumOf { (a, b) -> a.point.distanceTo(b.point) }
    }
}

@Serializable
data class GpxSegment(
    val points: List<GpxTrackPoint>
)

@Serializable
data class GpxTrackPoint(
    val point: GeoPoint,
    val timestampMillis: Long? = null,
    val speedMps: Double? = null
)

@Serializable
data class GpxWaypoint(
    val point: GeoPoint,
    val name: String? = null,
    val description: String? = null
)
