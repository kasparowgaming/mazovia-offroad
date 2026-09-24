package pl.mazovia.offroad.terrain.geo

import pl.mazovia.offroad.domain.model.GeoPoint
import kotlin.math.cos

/**
 * Spherical constants shared by all Terrain Ahead geometry.
 *
 * The radius is the one used by [GeoPoint.distanceTo] (haversine), so local metric frames agree with the
 * navigation-compatible distance axis (DESIGN §6.2, §10.3).
 */
object SphericalEarth {
    const val RADIUS_M = 6_371_000.0
    /** Metres per degree of latitude on the sphere. */
    const val METERS_PER_DEG_LAT = RADIUS_M * Math.PI / 180.0

    fun metersPerDegLon(latDeg: Double): Double = METERS_PER_DEG_LAT * cos(Math.toRadians(latDeg))
}

/**
 * Local east/north metric frame for **render-scene** coordinates (DESIGN §10.2–§10.3).
 *
 * Not used for the route-progress axis `s` nor for route matching; those live in
 * [pl.mazovia.offroad.terrain.projection.RouteIndex]. Rebasing a frame never changes `s`.
 * This is an **equirectangular** approximation of the tangent plane (fixed longitude scale at the origin latitude), not
 * an ECEF-derived ENU transform: its relative distortion grows ≈ tan φ · Δφ with the latitude distance from the origin
 * (≈ 2·10⁻⁴ at 1 km north/south of 52° N). Intended for the ≤ 1 km rebase radius.
 */
class LocalFrame(val origin: GeoPoint) {
    private val mPerDegLon = SphericalEarth.metersPerDegLon(origin.latitude)

    fun eastM(point: GeoPoint): Double = (point.longitude - origin.longitude) * mPerDegLon

    fun northM(point: GeoPoint): Double = (point.latitude - origin.latitude) * SphericalEarth.METERS_PER_DEG_LAT

    fun toGeo(eastM: Double, northM: Double): GeoPoint = GeoPoint(
        latitude = origin.latitude + northM / SphericalEarth.METERS_PER_DEG_LAT,
        longitude = origin.longitude + eastM / mPerDegLon
    )

    /** Returns a frame re-anchored at [newOrigin]. */
    fun rebase(newOrigin: GeoPoint): LocalFrame = LocalFrame(newOrigin)
}
