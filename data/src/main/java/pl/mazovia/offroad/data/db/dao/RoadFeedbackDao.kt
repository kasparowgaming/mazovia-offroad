package pl.mazovia.offroad.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import pl.mazovia.offroad.data.db.entity.RoadFeedbackEntity

@Dao
interface RoadFeedbackDao {
    @Query("SELECT * FROM road_feedback WHERE rideId = :rideId")
    suspend fun getFeedbackForRide(rideId: String): List<RoadFeedbackEntity>

    @Query("SELECT * FROM road_feedback WHERE answer IS NULL")
    fun getUnansweredFeedback(): Flow<List<RoadFeedbackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedback(feedback: RoadFeedbackEntity)

    @Update
    suspend fun updateFeedback(feedback: RoadFeedbackEntity)

    @Query("SELECT COUNT(*) FROM road_feedback WHERE answer IS NULL")
    suspend fun getUnansweredCount(): Int
}
