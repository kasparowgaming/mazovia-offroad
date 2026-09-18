package pl.mazovia.offroad.routing.forest

import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.RoutingEngine
import pl.mazovia.offroad.domain.routing.RoutingResult

/**
 * Finds forest areas suitable for off-road riding.
 * Uses routing data and geodata to identify forests with track/trail density.
 *
 * NOTE: Does NOT promise legal access merely because an area is classified as forest.
 * Access data from OSM/routing is still respected.
 */
class ForestAreaFinder(
    private val routingEngine: RoutingEngine
) {

    /**
     * Find candidate forest areas near the given position.
     * Returns areas sorted by riding quality (track density, access confidence).
     */
    suspend fun findCandidates(
        userPosition: GeoPoint,
        maxDistanceKm: Double = 50.0,
        maxCandidates: Int = 3
    ): List<ForestArea> {
        // In a full implementation, this would query BDOT/geodata for forest polygons
        // and calculate track density within each.
        //
        // Current implementation: Generate candidates based on known forest areas
        // from routing graph analysis.
        //
        // STATUS: PARTIAL - structure is real, geodata query is mocked

        // STATUS: UNAVAILABLE
        // Real BDOT10k forest polygon querying requires external preprocessing.
        // We refuse to mock forest geometries, densities, or access data.
        return emptyList()
    }

    private fun calculatePointAtBearing(
        origin: GeoPoint,
        bearingDegrees: Double,
        distanceMeters: Double
    ): GeoPoint {
        val R = 6_371_000.0
        val lat1 = Math.toRadians(origin.latitude)
        val lon1 = Math.toRadians(origin.longitude)
        val bearing = Math.toRadians(bearingDegrees)
        val d = distanceMeters / R

        val lat2 = Math.asin(
            Math.sin(lat1) * Math.cos(d) +
                    Math.cos(lat1) * Math.sin(d) * Math.cos(bearing)
        )
        val lon2 = lon1 + Math.atan2(
            Math.sin(bearing) * Math.sin(d) * Math.cos(lat1),
            Math.cos(d) - Math.sin(lat1) * Math.sin(lat2)
        )

        return GeoPoint(
            latitude = Math.toDegrees(lat2),
            longitude = Math.toDegrees(lon2)
        )
    }
}
