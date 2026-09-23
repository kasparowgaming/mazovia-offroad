package pl.mazovia.offroad.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import pl.mazovia.offroad.data.db.entity.RoughnessSampleEntity

@Dao
interface RoughnessSampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSample(sample: RoughnessSampleEntity)
    
    @Query("SELECT * FROM roughness_samples WHERE rideId = :rideId")
    fun getSamplesForRide(rideId: String): Flow<List<RoughnessSampleEntity>>

    @Query("SELECT COUNT(*) FROM roughness_samples")
    fun getSampleCount(): Flow<Int>

    @Query("DELETE FROM roughness_samples WHERE rideId = :rideId")
    suspend fun deleteSamplesForRide(rideId: String)
}
