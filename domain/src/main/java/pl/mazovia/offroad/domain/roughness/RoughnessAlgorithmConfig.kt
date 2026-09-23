package pl.mazovia.offroad.domain.roughness

data class RoughnessAlgorithmConfig(
    val algorithmVersion: Int = 1,
    val speedModelVersion: Int = 0,

    val accelerometerTargetRateHz: Int = 100,
    val orientationTargetRateHz: Int = 50,

    val highPassHz: Double = 1.0,
    val lowPassHz: Double = 20.0,

    val targetWindowDistanceMeters: Double = 30.0,
    val minWindowDurationMillis: Long = 2000,
    val maxWindowDurationMillis: Long = 6000,

    val filterWarmupMillis: Long = 2000,

    val maxSensorGapMillis: Long = 100,
    val maxOrientationAgeMillis: Long = 50,
    val maxOrientationGapMillis: Long = 100,
    val maxLocationAgeMillis: Long = 3000,

    val minValidAccelerometerRateHz: Double = 80.0,
    val maxValidAccelerometerRateHz: Double = 120.0,

    val minimumSampleCompleteness: Double = 0.7,

    val minimumProductionSpeedMps: Double = 12.0 / 3.6, // 12 km/h

    val calibrationMinSpeedMps: Double = 30.0 / 3.6,
    val calibrationMaxSpeedMps: Double = 55.0 / 3.6,

    val calibrationRequiredValidWindows: Int = 20,
    val calibrationRequiredDistanceMeters: Double = 500.0,

    val impactThresholdMps2: Double? = 7.0
) {
    companion object {
        val DEFAULT = RoughnessAlgorithmConfig()
    }
}
