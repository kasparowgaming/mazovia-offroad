package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * A forest area candidate for off-road riding.
 * NOT merely a green pixel - requires metadata about riding potential.
 */
@Serializable
data class ForestArea(
    val id: String,
    val name: String?,
    val center: GeoPoint,
    val boundingBox: BoundingBox,
    val areaSquareKm: Double,
    val distanceFromUserMeters: Double,
    val trackDensityKmPerSqKm: Double,
    val approachDistanceMeters: Double,
    val dataConfidence: DataConfidence,
    val hasConfirmedAccess: Boolean,
    val routeToForest: Route? = null
)

@Serializable
data class BoundingBox(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double
) {
    val center: GeoPoint get() = GeoPoint(
        latitude = (north + south) / 2,
        longitude = (east + west) / 2
    )

    fun contains(point: GeoPoint): Boolean =
        point.latitude in south..north && point.longitude in west..east
}
