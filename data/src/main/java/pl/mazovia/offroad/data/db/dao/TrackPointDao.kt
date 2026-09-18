package pl.mazovia.offroad.data.db.dao

import androidx.room.*
import pl.mazovia.offroad.data.db.entity.TrackPointEntity

@Dao
interface TrackPointDao {
    @Query("SELECT * FROM track_points WHERE rideId = :rideId ORDER BY sequenceIndex ASC")
    suspend fun getPointsForRide(rideId: String): List<TrackPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: TrackPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("DELETE FROM track_points WHERE rideId = :rideId")
    suspend fun deletePointsForRide(rideId: String)

    @Query("SELECT COUNT(*) FROM track_points WHERE rideId = :rideId")
    suspend fun getPointCount(rideId: String): Int

    @Query("SELECT * FROM track_points WHERE rideId = :rideId ORDER BY sequenceIndex DESC LIMIT 1")
    suspend fun getLastPoint(rideId: String): TrackPointEntity?
}
