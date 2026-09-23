package pl.mazovia.offroad.domain.roughness

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementQualityEvaluatorTest {
    
    private val evaluator = MeasurementQualityEvaluator(RoughnessAlgorithmConfig.DEFAULT)

    private fun eval(
        isCalibrated: Boolean = true,
        isCalibrationMode: Boolean = false,
        speedMps: Double = 10.0,
        gpsStaleSeconds: Double = 1.0,
        gpsAccuracyMeters: Double? = 5.0,
        sampleCompleteness: Double = 1.0,
        hasSensorGap: Boolean = false,
        orientationStale: Boolean = false,
        observedSampleRateHz: Double = 100.0,
        bufferOverflow: Boolean = false,
        inFreeFall: Boolean = false,
        longitudinalAccelMps2: Double = 0.0,
        phoneHandlingDetected: Boolean = false,
        nonMonotonicTimestamp: Boolean = false,
        gpsJumpDetected: Boolean = false,
        distanceReached: Boolean = true,
        sensorClipping: Boolean = false
    ): QualityResult = evaluator.evaluate(
        isCalibrated, isCalibrationMode, speedMps, gpsStaleSeconds, gpsAccuracyMeters, 
        sampleCompleteness, hasSensorGap, orientationStale, observedSampleRateHz, 
        bufferOverflow, inFreeFall, longitudinalAccelMps2, phoneHandlingDetected, 
        nonMonotonicTimestamp, gpsJumpDetected, distanceReached, sensorClipping
    )

    @Test
    fun testValid() {
        val result = eval()
        assertEquals(QualityState.VALID, result.state)
        assertEquals(0L, result.flags)
    }

    @Test
    fun testUncalibratedRejected() {
        val result = eval(isCalibrated = false)
        assertEquals(QualityState.REJECTED, result.state)
    }

    @Test
    fun testUncalibratedAcceptedInCalibrationMode() {
        val result = eval(isCalibrated = false, isCalibrationMode = true)
        assertEquals(QualityState.VALID, result.state)
    }

    @Test
    fun testDegradedSpeed() {
        // 15 km/h is 4.16 m/s
        val result = eval(speedMps = 15.0 / 3.6)
        assertEquals(QualityState.DEGRADED, result.state)
        assertEquals(QualityReason.LOW_SPEED, result.flags and QualityReason.LOW_SPEED)
    }

    @Test
    fun testRejectedSpeed() {
        // 10 km/h is 2.77 m/s
        val result = eval(speedMps = 10.0 / 3.6)
        assertEquals(QualityState.REJECTED, result.state)
    }

    @Test
    fun testFatalPrecedence() {
        // Speed 15 km/h (degraded) + GPS Stale (rejected)
        val result = eval(speedMps = 15.0 / 3.6, gpsStaleSeconds = 5.0)
        assertEquals(QualityState.REJECTED, result.state)
        // Must contain both flags
        assertEquals(QualityReason.LOW_SPEED, result.flags and QualityReason.LOW_SPEED)
        assertEquals(QualityReason.GPS_STALE, result.flags and QualityReason.GPS_STALE)
    }
}
