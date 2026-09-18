package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.*

/**
 * Immutable geographic coordinate with optional elevation.
 */
@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be in [-90, 90]: $latitude" }
        require(longitude in -180.0..180.0) { "Longitude must be in [-180, 180]: $longitude" }
    }

    /**
     * Haversine distance in meters to another point.
     */
    fun distanceTo(other: GeoPoint): Double {
        val r = 6_371_000.0 // Earth radius in meters
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
                sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /**
     * Bearing in degrees [0, 360) from this point to another.
     */
    fun bearingTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    companion object {
        /** Default center of Mazovia region */
        val WARSAW = GeoPoint(52.2297, 21.0122)
    }
}
