package pl.mazovia.offroad.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import pl.mazovia.offroad.data.db.MazoviaDatabase
import pl.mazovia.offroad.data.db.entity.RideCalibrationProfileEntity
import pl.mazovia.offroad.data.db.entity.RoughnessSampleEntity

import pl.mazovia.offroad.domain.roughness.RoughnessPersistence
import pl.mazovia.offroad.domain.roughness.RoughnessSample
import pl.mazovia.offroad.domain.roughness.RideCalibrationProfile

class RoughnessRepository(
    private val database: MazoviaDatabase
) : RoughnessPersistence {
    
    override suspend fun saveSample(sample: RoughnessSample) {
        val entity = RoughnessSampleEntity(
            id = sample.id, rideId = sample.rideId, startElapsedRealtimeNanos = sample.startElapsedRealtimeNanos,
            endElapsedRealtimeNanos = sample.endElapsedRealtimeNanos, midpointElapsedRealtimeNanos = sample.midpointElapsedRealtimeNanos,
            epochMillis = sample.epochMillis, latitude = sample.latitude, longitude = sample.longitude,
            meanSpeedMps = sample.meanSpeedMps, speedDeltaMps = sample.speedDeltaMps, gpsAccuracyMeters = sample.gpsAccuracyMeters,
            speedAccuracyMps = sample.speedAccuracyMps, windowDurationMillis = sample.windowDurationMillis,
            windowDistanceMeters = sample.windowDistanceMeters, sampleCount = sample.sampleCount,
            expectedSampleCount = sample.expectedSampleCount, verticalRms = sample.verticalRms,
            p95AbsVerticalAccel = sample.p95AbsVerticalAccel, peakAbsVerticalAccel = sample.peakAbsVerticalAccel,
            baselineRatio = sample.baselineRatio, qualityStateCode = sample.qualityStateCode,
            qualityReasonFlags = sample.qualityReasonFlags, calibrationProfileId = sample.calibrationProfileId,
            algorithmVersion = sample.algorithmVersion, speedModelVersion = sample.speedModelVersion
        )
        database.roughnessSampleDao().insertSample(entity)
    }

    override suspend fun getActiveCalibrationProfile(): RideCalibrationProfile? {
        return database.calibrationProfileDao().getActiveProfile()?.toDomain()
    }

    fun getActiveCalibrationProfileFlow(): Flow<RideCalibrationProfile?> {
        return database.calibrationProfileDao().getActiveProfileFlow().map { it?.toDomain() }
    }

    override suspend fun saveCalibrationProfile(profile: RideCalibrationProfile) {
        val entity = RideCalibrationProfileEntity(
            id = profile.id, name = profile.name, bikeLabel = profile.bikeLabel, mountLabel = profile.mountLabel,
            deviceModel = profile.deviceModel, baselineVerticalRms = profile.baselineVerticalRms,
            baselineP95 = profile.baselineP95, baselineIqrRatio = profile.baselineIqrRatio,
            baselineSpeedMps = profile.baselineSpeedMps, acceptedWindowCount = profile.acceptedWindowCount,
            calibrationDistanceMeters = profile.calibrationDistanceMeters, algorithmVersion = profile.algorithmVersion,
            speedModelVersion = profile.speedModelVersion, createdAtMillis = profile.createdAtMillis,
            isActive = profile.isActive
        )
        database.withTransaction {
            if (profile.isActive) {
                database.calibrationProfileDao().deactivateAll()
            }
            database.calibrationProfileDao().insertProfile(entity)
        }
    }

    private fun RideCalibrationProfileEntity.toDomain() = RideCalibrationProfile(
        id = id, name = name, bikeLabel = bikeLabel, mountLabel = mountLabel, deviceModel = deviceModel,
        baselineVerticalRms = baselineVerticalRms, baselineP95 = baselineP95, baselineIqrRatio = baselineIqrRatio,
        baselineSpeedMps = baselineSpeedMps, acceptedWindowCount = acceptedWindowCount,
        calibrationDistanceMeters = calibrationDistanceMeters, algorithmVersion = algorithmVersion,
        speedModelVersion = speedModelVersion, createdAtMillis = createdAtMillis, isActive = isActive
    )
}
