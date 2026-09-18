package pl.mazovia.offroad.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import pl.mazovia.offroad.data.db.entity.RideEntity

@Dao
interface RideDao {
    @Query("SELECT * FROM rides ORDER BY startTimeMillis DESC")
    fun getAllRides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun getRideById(id: String): RideEntity?

    @Query("SELECT * FROM rides WHERE status = 'IN_PROGRESS' LIMIT 1")
    suspend fun getActiveRide(): RideEntity?

    @Query("SELECT * FROM rides WHERE pendingFeedback IS NOT NULL AND pendingFeedback != '[]'")
    fun getRidesWithPendingFeedback(): Flow<List<RideEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: RideEntity)

    @Update
    suspend fun updateRide(ride: RideEntity)

    @Delete
    suspend fun deleteRide(ride: RideEntity)

    @Query("SELECT COUNT(*) FROM rides")
    suspend fun getRideCount(): Int

    @Query("SELECT SUM(distanceMeters) FROM rides WHERE status = 'COMPLETED'")
    suspend fun getTotalDistanceMeters(): Double?
}
