package pl.mazovia.offroad.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.mazovia.offroad.data.db.dao.RideDao
import pl.mazovia.offroad.data.db.dao.TrackPointDao
import pl.mazovia.offroad.data.db.entity.RideEntity
import pl.mazovia.offroad.data.db.entity.TrackPointEntity
import pl.mazovia.offroad.domain.model.*

class RideRepository(
    private val rideDao: RideDao,
    private val trackPointDao: TrackPointDao
) {
    fun getAllRides(): Flow<List<Ride>> = rideDao.getAllRides().map { entities ->
        entities.map { it.toDomain() }
    }

    fun getRidesWithPendingFeedback(): Flow<List<Ride>> =
        rideDao.getRidesWithPendingFeedback().map { entities ->
            entities.map { it.toDomain() }
        }

    suspend fun getRideById(id: String): Ride? = rideDao.getRideById(id)?.toDomain()

    suspend fun getActiveRide(): Ride? = rideDao.getActiveRide()?.toDomain()

    suspend fun saveRide(ride: Ride) {
        rideDao.insertRide(ride.toEntity())
    }

    suspend fun saveCompletedRide(ride: Ride) {
        rideDao.saveCompletedRide(ride.toEntity(), ride.trackPoints.mapIndexed { index, point ->
            TrackPointEntity(rideId = ride.id, latitude = point.point.latitude, longitude = point.point.longitude,
                elevation = point.point.elevation, timestampMillis = point.timestampMillis,
                speedMps = point.speedMps, sequenceIndex = index)
        })
    }

    suspend fun updateRide(ride: Ride) {
        rideDao.updateRide(ride.toEntity())
    }

    suspend fun saveTrackPoint(rideId: String, point: GpxTrackPoint, index: Int) {
        trackPointDao.insertPoint(
            TrackPointEntity(
                rideId = rideId,
                latitude = point.point.latitude,
                longitude = point.point.longitude,
                elevation = point.point.elevation,
                timestampMillis = point.timestampMillis,
                speedMps = point.speedMps,
                sequenceIndex = index
            )
        )
    }

    suspend fun saveTrackPoints(rideId: String, points: List<GpxTrackPoint>) {
        val entities = points.mapIndexed { index, point ->
            TrackPointEntity(
                rideId = rideId,
                latitude = point.point.latitude,
                longitude = point.point.longitude,
                elevation = point.point.elevation,
                timestampMillis = point.timestampMillis,
                speedMps = point.speedMps,
                sequenceIndex = index
            )
        }
        trackPointDao.insertPoints(entities)
    }

    suspend fun getTrackPoints(rideId: String): List<GpxTrackPoint> {
        return trackPointDao.getPointsForRide(rideId).map { entity ->
            GpxTrackPoint(
                point = GeoPoint(entity.latitude, entity.longitude, entity.elevation),
                timestampMillis = entity.timestampMillis,
                speedMps = entity.speedMps
            )
        }
    }

    suspend fun getStatistics(): RideStatistics {
        val count = rideDao.getRideCount()
        val totalDistance = rideDao.getTotalDistanceMeters() ?: 0.0
        return RideStatistics(rideCount = count, totalDistanceMeters = totalDistance)
    }

    private fun RideEntity.toDomain() = Ride(
        id = id,
        startTimeMillis = startTimeMillis,
        endTimeMillis = endTimeMillis,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        status = try { RideStatus.valueOf(status) } catch (_: Exception) { RideStatus.COMPLETED },
        metrics = RideMetrics(
            totalDistanceMeters = distanceMeters,
            offRoadDistanceMeters = offRoadDistanceMeters,
            asphaltDistanceMeters = asphaltDistanceMeters,
            averageSpeedKmh = averageSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            durationSeconds = durationSeconds,
            newKilometersMeters = newKilometersMeters,
            explorationPercentage = explorationPercentage,
            surfaceDistribution = surfaceDistribution ?: emptyMap(),
            terrainClassificationAvailable = terrainClassificationAvailable
        ),
        routeId = routeId,
        pendingFeedback = pendingFeedback ?: emptyList()
    )

    private fun Ride.toEntity() = RideEntity(
        id = id,
        startTimeMillis = startTimeMillis,
        endTimeMillis = endTimeMillis,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        status = status.name,
        offRoadDistanceMeters = metrics?.offRoadDistanceMeters ?: 0.0,
        asphaltDistanceMeters = metrics?.asphaltDistanceMeters ?: 0.0,
        averageSpeedKmh = metrics?.averageSpeedKmh ?: 0.0,
        maxSpeedKmh = metrics?.maxSpeedKmh ?: 0.0,
        newKilometersMeters = metrics?.newKilometersMeters ?: 0.0,
        explorationPercentage = metrics?.explorationPercentage ?: 0.0,
        surfaceDistribution = metrics?.surfaceDistribution,
        routeId = routeId,
        pendingFeedback = pendingFeedback,
        terrainClassificationAvailable = metrics?.terrainClassificationAvailable ?: false
    )
}

data class RideStatistics(
    val rideCount: Int,
    val totalDistanceMeters: Double
)
