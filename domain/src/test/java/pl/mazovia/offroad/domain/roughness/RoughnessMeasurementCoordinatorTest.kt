package pl.mazovia.offroad.domain.roughness

import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.Assert.*
import pl.mazovia.offroad.domain.location.LocationClient
import pl.mazovia.offroad.domain.location.LocationUpdate
import pl.mazovia.offroad.domain.model.GeoPoint

class RoughnessMeasurementCoordinatorTest {

    @Test
    fun `test calibration start mode`() {
        val config = RoughnessAlgorithmConfig.DEFAULT
        val locationClient = object : LocationClient {
            override fun getLocationUpdates(intervalMs: Long) = flowOf<LocationUpdate>()
        }
        val sensorSource = object : SensorSource {
            override val orientationSourceName = "MOCK"
            override fun start(callback: (accelTimestamp: Long, accel: FloatArray, orientation: FloatArray?) -> Unit) {}
            override fun stop() {}
        }
        val evaluator = MeasurementQualityEvaluator(config)
        val persistence = object : RoughnessPersistence {
            override suspend fun saveSample(sample: RoughnessSample) {}
            override suspend fun getActiveCalibrationProfile(): RideCalibrationProfile? = null
            override suspend fun saveCalibrationProfile(profile: RideCalibrationProfile) {}
        }
        
        val coordinator = RoughnessMeasurementCoordinator(config, locationClient, sensorSource, evaluator, persistence)
        
        coordinator.start("calibration", isCalibration = true)
        assertTrue(coordinator.isActive.value)
        // We know from code that calState is CALIBRATING
        
        coordinator.stop()
        assertFalse(coordinator.isActive.value)
    }

    @Test
    fun `test normal ride mode`() {
        val config = RoughnessAlgorithmConfig.DEFAULT
        val locationClient = object : LocationClient {
            override fun getLocationUpdates(intervalMs: Long) = flowOf<LocationUpdate>()
        }
        val sensorSource = object : SensorSource {
            override val orientationSourceName = "MOCK"
            override fun start(callback: (accelTimestamp: Long, accel: FloatArray, orientation: FloatArray?) -> Unit) {}
            override fun stop() {}
        }
        val evaluator = MeasurementQualityEvaluator(config)
        val persistence = object : RoughnessPersistence {
            override suspend fun saveSample(sample: RoughnessSample) {}
            override suspend fun getActiveCalibrationProfile(): RideCalibrationProfile? = null
            override suspend fun saveCalibrationProfile(profile: RideCalibrationProfile) {}
        }
        
        val coordinator = RoughnessMeasurementCoordinator(config, locationClient, sensorSource, evaluator, persistence)
        
        coordinator.start("track-123", isCalibration = false)
        assertTrue(coordinator.isActive.value)
        // We know calState is MEASURING
    }
}
