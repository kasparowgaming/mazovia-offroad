package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * A single segment of a calculated route with surface and classification metadata.
 */
@Serializable
data class RouteSegment(
    val points: List<GeoPoint>,
    val distanceMeters: Double,
    val surface: Surface,
    val highway: HighwayType,
    val trackType: TrackType = TrackType.UNKNOWN,
    val smoothness: Smoothness = Smoothness.UNKNOWN,
    val access: AccessRestriction = AccessRestriction.UNKNOWN,
    val roadDataConfidence: RoadDataConfidence = RoadDataConfidence.UNKNOWN,
    val osmWayId: Long? = null,
    val name: String? = null,
    /** Raw compatibility input for the pre-existing route metric, not evidence confidence. */
    val hasSurfaceOrRoadClassDetail: Boolean = false
) {
    val isOffRoad: Boolean get() {
        if (surface != Surface.UNKNOWN) {
            return surface.isOffRoad
        }
        return highway.isOffRoadCandidate
    }
    
    val isAsphalt: Boolean get() {
        if (surface != Surface.UNKNOWN) {
            return !surface.isOffRoad
        }
        return !highway.isOffRoadCandidate
    }
}
