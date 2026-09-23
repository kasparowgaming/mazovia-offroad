package pl.mazovia.offroad.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "roughness_samples",
    indices = [
        Index("rideId"),
        Index("rideId", "startElapsedRealtimeNanos", "endElapsedRealtimeNanos", "algorithmVersion", unique = true),
        Index("calibrationProfileId")
    ]
)
data class RoughnessSampleEntity(
    @PrimaryKey
    val id: String,
    
    val rideId: String,

    val startElapsedRealtimeNanos: Long,
    val endElapsedRealtimeNanos: Long,
    val midpointElapsedRealtimeNanos: Long,
    val epochMillis: Long,

    val latitude: Double,
    val longitude: Double,

    val meanSpeedMps: Double,
    val speedDeltaMps: Double?,
    val gpsAccuracyMeters: Double,
    val speedAccuracyMps: Double?,

    val windowDurationMillis: Long,
    val windowDistanceMeters: Double,

    val sampleCount: Int,
    val expectedSampleCount: Int,

    val verticalRms: Double,
    val p95AbsVerticalAccel: Double,
    val peakAbsVerticalAccel: Double,

    val baselineRatio: Double?,

    val qualityStateCode: Int,
    val qualityReasonFlags: Long,

    val calibrationProfileId: String,
    val algorithmVersion: Int,
    val speedModelVersion: Int
)
