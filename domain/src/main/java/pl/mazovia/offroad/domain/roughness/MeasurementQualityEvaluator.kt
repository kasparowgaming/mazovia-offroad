package pl.mazovia.offroad.domain.roughness

enum class QualityState(val code: Int) {
    VALID(0),
    DEGRADED(1),
    REJECTED(2)
}

object QualityReason {
    const val NO_CALIBRATION = 1L shl 0
    const val LOW_SPEED = 1L shl 1
    const val HIGH_SPEED = 1L shl 2
    const val GPS_STALE = 1L shl 3
    const val GPS_ACCURACY_UNKNOWN = 1L shl 4
    const val GPS_ACCURACY_POOR = 1L shl 5
    const val GPS_ACCURACY_ELEVATED = 1L shl 6
    const val SAMPLE_COMPLETENESS_LOW = 1L shl 7
    const val SENSOR_GAP = 1L shl 8
    const val ORIENTATION_UNAVAILABLE = 1L shl 9
    const val SAMPLE_RATE_OUT_OF_RANGE = 1L shl 10
    const val BUFFER_OVERFLOW = 1L shl 11
    const val FREE_FALL = 1L shl 12
    const val HARD_ACCELERATION = 1L shl 13
    const val HARD_BRAKING = 1L shl 14
    const val PHONE_HANDLING = 1L shl 15
    const val NON_MONOTONIC_TIMESTAMP = 1L shl 16
    const val GROSS_GPS_JUMP = 1L shl 17
    const val MODERATE_LONGITUDINAL_ACCEL = 1L shl 18
    const val DISTANCE_SHORTFALL = 1L shl 19
    const val SENSOR_CLIPPING = 1L shl 20
}

data class QualityResult(
    val state: QualityState,
    val flags: Long
)

class MeasurementQualityEvaluator(
    private val config: RoughnessAlgorithmConfig
) {
    fun evaluate(
        isCalibrated: Boolean,
        isCalibrationMode: Boolean,
        speedMps: Double,
        gpsStaleSeconds: Double,
        gpsAccuracyMeters: Double?,
        sampleCompleteness: Double,
        hasSensorGap: Boolean,
        orientationStale: Boolean,
        observedSampleRateHz: Double,
        bufferOverflow: Boolean,
        inFreeFall: Boolean,
        longitudinalAccelMps2: Double,
        phoneHandlingDetected: Boolean,
        nonMonotonicTimestamp: Boolean,
        gpsJumpDetected: Boolean,
        distanceReached: Boolean,
        sensorClipping: Boolean
    ): QualityResult {
        var flags = 0L

        // Detect all conditions
        if (!isCalibrated && !isCalibrationMode) flags = flags or QualityReason.NO_CALIBRATION
        
        val speedKmh = speedMps * 3.6
        if (speedKmh < 12.0) flags = flags or QualityReason.LOW_SPEED
        else if (speedKmh in 12.0..20.0) flags = flags or QualityReason.LOW_SPEED // degraded range
        else if (speedKmh > 90.0) flags = flags or QualityReason.HIGH_SPEED

        if (gpsStaleSeconds > 3.0) flags = flags or QualityReason.GPS_STALE
        
        if (gpsAccuracyMeters == null) {
            flags = flags or QualityReason.GPS_ACCURACY_UNKNOWN
        } else {
            if (gpsAccuracyMeters > 30.0) flags = flags or QualityReason.GPS_ACCURACY_POOR
            else if (gpsAccuracyMeters > 15.0) flags = flags or QualityReason.GPS_ACCURACY_ELEVATED
        }

        if (sampleCompleteness < config.minimumSampleCompleteness) flags = flags or QualityReason.SAMPLE_COMPLETENESS_LOW
        else if (sampleCompleteness < 0.9) flags = flags or QualityReason.SAMPLE_COMPLETENESS_LOW // degraded part
        
        if (hasSensorGap) flags = flags or QualityReason.SENSOR_GAP
        if (orientationStale) flags = flags or QualityReason.ORIENTATION_UNAVAILABLE
        
        if (observedSampleRateHz < config.minValidAccelerometerRateHz || observedSampleRateHz > config.maxValidAccelerometerRateHz) {
            flags = flags or QualityReason.SAMPLE_RATE_OUT_OF_RANGE
        }
        
        if (bufferOverflow) flags = flags or QualityReason.BUFFER_OVERFLOW
        if (inFreeFall) flags = flags or QualityReason.FREE_FALL
        
        if (longitudinalAccelMps2 > 4.0) flags = flags or QualityReason.HARD_ACCELERATION
        else if (longitudinalAccelMps2 < -4.0) flags = flags or QualityReason.HARD_BRAKING
        else if (longitudinalAccelMps2 > 1.5 || longitudinalAccelMps2 < -1.5) flags = flags or QualityReason.MODERATE_LONGITUDINAL_ACCEL
        
        if (phoneHandlingDetected) flags = flags or QualityReason.PHONE_HANDLING
        if (nonMonotonicTimestamp) flags = flags or QualityReason.NON_MONOTONIC_TIMESTAMP
        if (gpsJumpDetected) flags = flags or QualityReason.GROSS_GPS_JUMP
        if (!distanceReached) flags = flags or QualityReason.DISTANCE_SHORTFALL
        if (sensorClipping) flags = flags or QualityReason.SENSOR_CLIPPING

        // Evaluate fatal vs degraded
        val fatalMask = QualityReason.NO_CALIBRATION or
                QualityReason.GPS_STALE or
                QualityReason.GPS_ACCURACY_UNKNOWN or
                QualityReason.GPS_ACCURACY_POOR or
                QualityReason.SENSOR_GAP or
                QualityReason.ORIENTATION_UNAVAILABLE or
                QualityReason.SAMPLE_RATE_OUT_OF_RANGE or
                QualityReason.BUFFER_OVERFLOW or
                QualityReason.FREE_FALL or
                QualityReason.HARD_ACCELERATION or
                QualityReason.HARD_BRAKING or
                QualityReason.PHONE_HANDLING or
                QualityReason.NON_MONOTONIC_TIMESTAMP or
                QualityReason.GROSS_GPS_JUMP

        // For LOW_SPEED and SAMPLE_COMPLETENESS, we need to check thresholds again since flags are shared
        var isFatal = (flags and fatalMask) != 0L
        if (speedKmh < 12.0) isFatal = true
        if (sampleCompleteness < config.minimumSampleCompleteness) isFatal = true

        if (isFatal) {
            return QualityResult(QualityState.REJECTED, flags)
        }
        
        if (flags != 0L) {
            return QualityResult(QualityState.DEGRADED, flags)
        }

        return QualityResult(QualityState.VALID, 0L)
    }
}
