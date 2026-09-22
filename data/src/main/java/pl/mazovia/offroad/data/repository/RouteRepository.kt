package pl.mazovia.offroad.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.mazovia.offroad.data.db.dao.SavedRouteDao
import pl.mazovia.offroad.data.db.entity.SavedRouteEntity
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.RoutingProfile
import pl.mazovia.offroad.domain.model.GpxData
import pl.mazovia.offroad.domain.model.RouteSource
import pl.mazovia.offroad.domain.gpx.GpxRoute
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Saved routes (calculated + imported GPX).
 */
data class SavedRouteInfo(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val origin: GeoPoint,
    val destination: GeoPoint,
    val profile: RoutingProfile?,
    val totalDistanceMeters: Double,
    val offRoadPercentage: Double,
    val source: String
)

class RouteRepository(
    private val savedRouteDao: SavedRouteDao
) {
    suspend fun saveImportedGpx(gpx: GpxData): pl.mazovia.offroad.domain.model.Route {
        val route = GpxRoute.create(gpx)
        saveRoute(SavedRouteEntity(
            id = route.id,
            name = gpx.name ?: gpx.tracks.single().name ?: gpx.sourceFileName ?: "Ślad GPX",
            createdAtMillis = System.currentTimeMillis(),
            originLat = route.origin.latitude,
            originLon = route.origin.longitude,
            destinationLat = route.destination.latitude,
            destinationLon = route.destination.longitude,
            profile = route.profile.name,
            totalDistanceMeters = route.totalDistanceMeters,
            offRoadPercentage = 0.0,
            routeDataJson = Json.encodeToString(route),
            source = "gpx_import"
        ))
        return route
    }
    suspend fun openRoute(id: String): pl.mazovia.offroad.domain.model.Route {
        val entity = requireNotNull(getRouteById(id))
        val route = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(pl.mazovia.offroad.domain.model.Route.serializer(), entity.routeDataJson)
        require(route.allPoints.size >= 2 && route.totalDistanceMeters.isFinite() && route.totalDistanceMeters > 0)
        require(route.allPoints.all { it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
        if (route.source == RouteSource.IMPORTED_GPX) {
            val gpx = requireNotNull(route.originalGpx) { "Zapisany ślad GPX jest uszkodzony." }
            val expected = GpxRoute.create(gpx)
            require(route.segments.map { it.points } == expected.segments.map { it.points } &&
                route.totalDistanceMeters == expected.totalDistanceMeters) {
                "Zapisany ślad GPX jest uszkodzony. Otwórz ponownie oryginalny plik GPX."
            }
        }
        return route
    }
    fun getAllRoutes(): Flow<List<SavedRouteInfo>> = savedRouteDao.getAllRoutes().map { entities ->
        entities.map { it.toInfo() }
    }

    suspend fun getRouteById(id: String): SavedRouteEntity? = savedRouteDao.getRouteById(id)

    suspend fun saveRoute(entity: SavedRouteEntity) {
        savedRouteDao.insertRoute(entity)
    }

    suspend fun deleteRoute(entity: SavedRouteEntity) {
        savedRouteDao.deleteRoute(entity)
    }

    private fun SavedRouteEntity.toInfo() = SavedRouteInfo(
        id = id,
        name = name,
        createdAtMillis = createdAtMillis,
        origin = GeoPoint(originLat, originLon),
        destination = GeoPoint(destinationLat, destinationLon),
        profile = try { RoutingProfile.valueOf(profile) } catch (_: Exception) { null },
        totalDistanceMeters = totalDistanceMeters,
        offRoadPercentage = offRoadPercentage,
        source = source
    )
}
