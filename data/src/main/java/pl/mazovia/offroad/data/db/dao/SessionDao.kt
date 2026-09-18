package pl.mazovia.offroad.data.db.dao

import androidx.room.*
import pl.mazovia.offroad.data.db.entity.NavigationSessionEntity
import pl.mazovia.offroad.data.db.entity.RecordingSessionEntity

@Dao
interface SessionDao {
    // Navigation session
    @Query("SELECT * FROM navigation_sessions WHERE id = 'active_session' AND isActive = 1")
    suspend fun getActiveNavigationSession(): NavigationSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNavigationSession(session: NavigationSessionEntity)

    @Query("DELETE FROM navigation_sessions WHERE id = 'active_session'")
    suspend fun clearNavigationSession()

    // Recording session
    @Query("SELECT * FROM recording_sessions WHERE id = 'active_recording'")
    suspend fun getActiveRecordingSession(): RecordingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRecordingSession(session: RecordingSessionEntity)

    @Query("DELETE FROM recording_sessions WHERE id = 'active_recording'")
    suspend fun clearRecordingSession()
}
