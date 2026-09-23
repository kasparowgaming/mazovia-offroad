package pl.mazovia.offroad.domain.roughness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.sqrt

class RoughnessFilterTest {

    private fun computeRms(filter: RoughnessFilter, freq: Double, fs: Double, duration: Double): Double {
        var sumSq = 0.0
        val samples = (fs * duration).toInt()
        
        // Let it settle for 2 seconds
        for (i in 0 until (fs * 2.0).toInt()) {
            val t = i / fs
            val x = sin(2 * PI * freq * t)
            filter.process(x)
        }
        
        // Measure RMS
        for (i in 0 until samples) {
            val t = (i + fs * 2.0) / fs
            val x = sin(2 * PI * freq * t)
            val y = filter.process(x)
            sumSq += y * y
        }
        
        return sqrt(sumSq / samples)
    }

    @Test
    fun testAttenuation() {
        val filter = RoughnessFilter(100.0, 1.0, 20.0)
        
        // Input RMS is 1/sqrt(2) approx 0.707
        val inputRms = 1.0 / sqrt(2.0)
        
        // 0.2 Hz sine: output/input RMS gain <= 0.10
        val rms0_2 = computeRms(filter, 0.2, 100.0, 2.0)
        assertTrue("0.2 Hz gain ${rms0_2 / inputRms} must be <= 0.1", rms0_2 / inputRms <= 0.1)

        filter.reset()
        // 5 Hz sine: output/input RMS gain >= 0.90
        val rms5 = computeRms(filter, 5.0, 100.0, 2.0)
        assertTrue("5 Hz gain ${rms5 / inputRms} must be >= 0.9", rms5 / inputRms >= 0.9)

        filter.reset()
        // 40 Hz sine: output/input RMS gain <= 0.10
        val rms40 = computeRms(filter, 40.0, 100.0, 2.0)
        assertTrue("40 Hz gain ${rms40 / inputRms} must be <= 0.1", rms40 / inputRms <= 0.1)
    }

    @Test
    fun testConstantInputRejection() {
        val filter = RoughnessFilter(100.0, 1.0, 20.0)
        // Warm up with constant 9.8
        for (i in 0 until 200) {
            filter.process(9.8)
        }
        val out = filter.process(9.8)
        assertEquals("Constant input must yield near-zero output", 0.0, out, 0.05)
    }

    @Test
    fun testResetEquivalence() {
        val filter1 = RoughnessFilter(100.0, 1.0, 20.0)
        val filter2 = RoughnessFilter(100.0, 1.0, 20.0)
        
        for (i in 0 until 50) filter1.process(1.0)
        filter1.reset()
        
        val out1 = filter1.process(1.0)
        val out2 = filter2.process(1.0)
        
        assertEquals("Filter output after reset must match fresh filter", out2, out1, 0.00001)
    }

    @Test
    fun testFiniteLongRunOutput() {
        val filter = RoughnessFilter(100.0, 1.0, 20.0)
        for (i in 0 until 100000) {
            filter.process(sin(2 * PI * 10.0 * (i / 100.0)))
        }
        val out = filter.process(0.0)
        assertTrue("Output must not be NaN", !out.isNaN())
        assertTrue("Output must not be Infinite", !out.isInfinite())
    }
}
