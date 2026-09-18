package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a ridden road segment for exploration tracking.
 * Efficient representation for mobile storage.
 */
@Serializable
data class RiddenSegment(
    /** OSM way ID if available */
    val osmWayId: Long?,
    /** Geohash-based segment identifier for segments without OSM IDs */
    val segmentHash: String,
    /** First time ridden */
    val firstRiddenMillis: Long,
    /** Last time ridden */
    val lastRiddenMillis: Long,
    /** Number of times ridden */
    val rideCount: Int,
    /** Simplified start point */
    val startLat: Double,
    val startLon: Double,
    /** Simplified end point */
    val endLat: Double,
    val endLon: Double
)
