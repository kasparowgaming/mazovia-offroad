package pl.mazovia.offroad.domain.roughness

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.tan
import kotlin.math.sqrt

class BiquadFilter(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }
}

object FilterCoefficients {
    // Computes coefficients for a 2nd-order Butterworth low-pass filter
    fun lowPass(fs: Double, fc: Double): BiquadFilter {
        val w0 = 2.0 * PI * fc / fs
        val alpha = sin(w0) / (2.0 * 1.0 / sqrt(2.0)) // Q = 1/sqrt(2) for Butterworth 2nd order
        val cosW0 = cos(w0)
        
        val a0 = 1.0 + alpha
        val b0 = ((1.0 - cosW0) / 2.0) / a0
        val b1 = (1.0 - cosW0) / a0
        val b2 = ((1.0 - cosW0) / 2.0) / a0
        val a1Out = (-2.0 * cosW0) / a0
        val a2Out = (1.0 - alpha) / a0
        
        return BiquadFilter(b0, b1, b2, a1Out, a2Out)
    }

    // Computes coefficients for a 2nd-order Butterworth high-pass filter
    fun highPass(fs: Double, fc: Double): BiquadFilter {
        val w0 = 2.0 * PI * fc / fs
        val alpha = sin(w0) / (2.0 * 1.0 / sqrt(2.0))
        val cosW0 = cos(w0)
        
        val a0 = 1.0 + alpha
        val b0 = ((1.0 + cosW0) / 2.0) / a0
        val b1 = -(1.0 + cosW0) / a0
        val b2 = ((1.0 + cosW0) / 2.0) / a0
        val a1Out = (-2.0 * cosW0) / a0
        val a2Out = (1.0 - alpha) / a0
        
        return BiquadFilter(b0, b1, b2, a1Out, a2Out)
    }
}

class RoughnessFilter(
    private val sampleRateHz: Double,
    private val hpFreqHz: Double,
    private val lpFreqHz: Double
) {
    // 2nd order HPF
    private val hpFilter = FilterCoefficients.highPass(sampleRateHz, hpFreqHz)
    
    // 4th order LPF is achieved by cascading two 2nd order Butterworth filters.
    // However, to get a true 4th order Butterworth, the Q values must be different.
    // Q1 = 0.54119610, Q2 = 1.3065630
    private val lpFilter1: BiquadFilter
    private val lpFilter2: BiquadFilter

    init {
        val w0 = 2.0 * PI * lpFreqHz / sampleRateHz
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        
        // Stage 1: Q = 0.54119610
        var alpha = sinW0 / (2.0 * 0.54119610)
        var a0 = 1.0 + alpha
        lpFilter1 = BiquadFilter(
            ((1.0 - cosW0) / 2.0) / a0,
            (1.0 - cosW0) / a0,
            ((1.0 - cosW0) / 2.0) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - alpha) / a0
        )
        
        // Stage 2: Q = 1.3065630
        alpha = sinW0 / (2.0 * 1.3065630)
        a0 = 1.0 + alpha
        lpFilter2 = BiquadFilter(
            ((1.0 - cosW0) / 2.0) / a0,
            (1.0 - cosW0) / a0,
            ((1.0 - cosW0) / 2.0) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - alpha) / a0
        )
    }

    fun process(x: Double): Double {
        val yHp = hpFilter.process(x)
        val yLp1 = lpFilter1.process(yHp)
        return lpFilter2.process(yLp1)
    }

    fun reset() {
        hpFilter.reset()
        lpFilter1.reset()
        lpFilter2.reset()
    }
}
