package pl.mazovia.offroad.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import pl.mazovia.offroad.data.db.entity.RideCalibrationProfileEntity

@Dao
interface CalibrationProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: RideCalibrationProfileEntity)

    @Update
    suspend fun updateProfile(profile: RideCalibrationProfileEntity)

    @Query("SELECT * FROM ride_calibration_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProfile(): RideCalibrationProfileEntity?

    @Query("SELECT * FROM ride_calibration_profiles WHERE isActive = 1 LIMIT 1")
    fun getActiveProfileFlow(): Flow<RideCalibrationProfileEntity?>

    @Query("UPDATE ride_calibration_profiles SET isActive = 0")
    suspend fun deactivateAll()

    @Query("SELECT * FROM ride_calibration_profiles")
    fun getAllProfiles(): Flow<List<RideCalibrationProfileEntity>>
}
