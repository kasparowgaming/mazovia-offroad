package pl.mazovia.offroad.domain.roughness

data class RoughnessSample(
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

data class RideCalibrationProfile(
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

interface RoughnessPersistence {
    suspend fun saveSample(sample: RoughnessSample)
    suspend fun getActiveCalibrationProfile(): RideCalibrationProfile?
    suspend fun saveCalibrationProfile(profile: RideCalibrationProfile)
}
