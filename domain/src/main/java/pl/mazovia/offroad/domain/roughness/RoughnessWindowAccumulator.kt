package pl.mazovia.offroad.domain.roughness

import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.max

data class RoughnessFeature(
    val verticalRms: Double,
    val p95AbsVerticalAccel: Double,
    val peakAbsVerticalAccel: Double,
    val sampleCount: Int,
    val expectedSampleCount: Int,
    val durationMillis: Long,
    val distanceMeters: Double
)

class RoughnessWindowAccumulator(
    private val config: RoughnessAlgorithmConfig
) {
    private var isWarmingUp = true
    private var warmupSamplesRemaining = 0
    
    // Window state
    private var windowStartNanos = 0L
    private var windowEndNanos = 0L
    private var sampleCount = 0
    private var sumSq = 0.0
    private var peak = 0.0
    private val samples = mutableListOf<Double>()
    
    // Provisional distance integration for real-time close
    private var provisionalDistance = 0.0
    private var lastSampleNanos = 0L
    private var currentSpeedMps = 0.0

    init {
        reset(0L)
    }

    fun updateSpeed(speedMps: Double) {
        currentSpeedMps = speedMps
    }

    fun reset(nowNanos: Long) {
        isWarmingUp = true
        // calculate warmup samples based on configured warmup time and target Hz
        warmupSamplesRemaining = (config.filterWarmupMillis * config.accelerometerTargetRateHz / 1000).toInt()
        resetWindow(nowNanos)
    }
    
    private fun resetWindow(nowNanos: Long) {
        windowStartNanos = nowNanos
        windowEndNanos = nowNanos
        sampleCount = 0
        sumSq = 0.0
        peak = 0.0
        samples.clear()
        provisionalDistance = 0.0
        lastSampleNanos = nowNanos
    }

    /**
     * Feed a filtered sample. Returns a completed feature if a window just closed.
     */
    fun process(sample: Double, timestampNanos: Long): RoughnessFeature? {
        if (isWarmingUp) {
            if (warmupSamplesRemaining > 0) {
                warmupSamplesRemaining--
            }
            if (warmupSamplesRemaining <= 0) {
                isWarmingUp = false
                resetWindow(timestampNanos)
            }
            return null
        }

        if (sampleCount == 0) {
            windowStartNanos = timestampNanos
            lastSampleNanos = timestampNanos
        } else {
            val dtS = (timestampNanos - lastSampleNanos) / 1_000_000_000.0
            if (dtS > 0) {
                provisionalDistance += currentSpeedMps * dtS
            }
            lastSampleNanos = timestampNanos
        }
        
        windowEndNanos = timestampNanos
        sampleCount++
        sumSq += sample * sample
        val absVal = abs(sample)
        if (absVal > peak) {
            peak = absVal
        }
        samples.add(absVal)
        
        // Overflow protection (e.g., max duration * target rate * 2)
        val maxSamples = (config.maxWindowDurationMillis * config.accelerometerTargetRateHz / 1000) * 2
        if (sampleCount > maxSamples) {
            // Buffer overflow - return invalid/degraded or reset. We handle rejection downstream.
            // But we must flush it to prevent infinite memory.
            return flushAndReset(timestampNanos)
        }

        val durationMillis = (timestampNanos - windowStartNanos) / 1_000_000L
        
        // Hybrid close logic:
        val timeReached = durationMillis >= config.minWindowDurationMillis
        val distanceReached = provisionalDistance >= config.targetWindowDistanceMeters
        val maxTimeReached = durationMillis >= config.maxWindowDurationMillis
        
        if ((timeReached && distanceReached) || maxTimeReached) {
            return flushAndReset(timestampNanos)
        }
        return null
    }
    
    private fun flushAndReset(nowNanos: Long): RoughnessFeature? {
        if (sampleCount == 0) return null
        
        val durationMillis = max(1L, (windowEndNanos - windowStartNanos) / 1_000_000L)
        val expectedSamples = (durationMillis * config.accelerometerTargetRateHz / 1000).toInt()
        val rms = sqrt(sumSq / sampleCount)
        
        // p95 calculation (using absolute values)
        samples.sort()
        val p95Index = (samples.size * 0.95).toInt().coerceIn(0, samples.lastIndex)
        val p95 = samples[p95Index]
        
        val feature = RoughnessFeature(
            verticalRms = rms,
            p95AbsVerticalAccel = p95,
            peakAbsVerticalAccel = peak,
            sampleCount = sampleCount,
            expectedSampleCount = expectedSamples,
            durationMillis = durationMillis,
            distanceMeters = provisionalDistance
        )
        resetWindow(nowNanos)
        return feature
    }
    
    fun getWindowStartNanos() = windowStartNanos
    fun getWindowEndNanos() = windowEndNanos
}
