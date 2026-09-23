package pl.mazovia.offroad.domain.roughness

import org.junit.Assert.*
import org.junit.Test

class RoughnessWindowAccumulatorTest {

    @Test
    fun testWarmupRejection() {
        val config = RoughnessAlgorithmConfig.DEFAULT.copy(filterWarmupMillis = 2000, accelerometerTargetRateHz = 100)
        val accum = RoughnessWindowAccumulator(config)
        
        // 200 samples = 2 seconds warmup
        for (i in 0 until 200) {
            val res = accum.process(1.0, i * 10_000_000L)
            assertNull("Warmup samples must not return features", res)
        }
        
        // After warmup, next samples go into window
        accum.process(1.0, 201 * 10_000_000L)
        assertNotNull(accum.getWindowStartNanos())
    }

    @Test
    fun testMaxDurationClose() {
        val config = RoughnessAlgorithmConfig.DEFAULT.copy(
            filterWarmupMillis = 0,
            accelerometerTargetRateHz = 100,
            maxWindowDurationMillis = 6000,
            targetWindowDistanceMeters = 30.0
        )
        val accum = RoughnessWindowAccumulator(config)
        accum.updateSpeed(1.0) // 1 m/s = 6 meters in 6 seconds (shortfall)
        
        var feature: RoughnessFeature? = null
        for (i in 0..605) {
            feature = accum.process(1.0, i * 10_000_000L)
            if (feature != null) break
        }
        
        assertNotNull(feature)
        assertEquals(6000L, feature!!.durationMillis)
        assertEquals(6.0, feature.distanceMeters, 0.1) // 6 seconds at 1 m/s
    }

    @Test
    fun testDistanceReachedClose() {
        val config = RoughnessAlgorithmConfig.DEFAULT.copy(
            filterWarmupMillis = 0,
            accelerometerTargetRateHz = 100,
            minWindowDurationMillis = 2000,
            targetWindowDistanceMeters = 30.0
        )
        val accum = RoughnessWindowAccumulator(config)
        accum.updateSpeed(10.0) // 10 m/s = 30 meters in 3 seconds
        
        var feature: RoughnessFeature? = null
        // 3 seconds = 300 samples
        for (i in 0..305) {
            feature = accum.process(1.0, i * 10_000_000L)
            if (feature != null) break
        }
        
        assertNotNull(feature)
        assertTrue(feature!!.durationMillis >= 3000L)
        assertTrue(feature.distanceMeters >= 30.0)
    }

    @Test
    fun testBufferOverflowProtection() {
        val config = RoughnessAlgorithmConfig.DEFAULT.copy(
            filterWarmupMillis = 0,
            maxWindowDurationMillis = 6000,
            accelerometerTargetRateHz = 100
        )
        val accum = RoughnessWindowAccumulator(config)
        accum.updateSpeed(0.0) // stationary
        
        // 1200 samples is max * 2
        var feature: RoughnessFeature? = null
        for (i in 0..1201) {
            // We feed same timestamp so duration doesn't trigger time close
            feature = accum.process(1.0, 0L)
            if (feature != null) break
        }
        assertNotNull("Must flush on overflow", feature)
    }
}
