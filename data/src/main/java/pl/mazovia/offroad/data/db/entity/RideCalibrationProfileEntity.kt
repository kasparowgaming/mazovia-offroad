package pl.mazovia.offroad.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ride_calibration_profiles")
data class RideCalibrationProfileEntity(
    @PrimaryKey
    val id: String,
    
    val name: String,

    val bikeLabel: String?,
    val mountLabel: String?,
    val deviceModel: String,

    val baselineVerticalRms: Double,
    val baselineP95: Double?,
    val baselineIqrRatio: Double?,

    val baselineSpeedMps: Double,
    val acceptedWindowCount: Int,
    val calibrationDistanceMeters: Double,

    val algorithmVersion: Int,
    val speedModelVersion: Int,
    
    val createdAtMillis: Long,
    val isActive: Boolean
)
