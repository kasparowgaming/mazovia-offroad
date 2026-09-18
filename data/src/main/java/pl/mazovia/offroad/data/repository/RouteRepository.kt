package pl.mazovia.offroad.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.mazovia.offroad.data.db.dao.SavedRouteDao
import pl.mazovia.offroad.data.db.entity.SavedRouteEntity
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.RoutingProfile

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
