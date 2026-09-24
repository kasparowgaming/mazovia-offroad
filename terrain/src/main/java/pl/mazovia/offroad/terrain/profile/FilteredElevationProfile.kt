package pl.mazovia.offroad.terrain.profile

import kotlin.math.min

/**
 * Frozen filter definition (DESIGN §12.2, TA-000B-C1). Changing it is a new declared experiment.
 *
 * @property medianWindowSamples median window (5 samples, 25 m effective at 5 m spacing)
 * @property averageWindowSamples moving-average window (5 samples, 25 m effective)
 * @property gradeWindowM grade difference window L (25 m)
 */
data class FilterConfig(
    val id: String,
    val medianWindowSamples: Int,
    val averageWindowSamples: Int,
    val gradeWindowM: Double
) {
    init {
        require(medianWindowSamples % 2 == 1 && averageWindowSamples % 2 == 1) { "Windows must be odd" }
    }

    companion object {
        val V1 = FilterConfig(id = "FilterConfig-v1", medianWindowSamples = 5, averageWindowSamples = 5, gradeWindowM = 25.0)
    }
}

/**
 * Derived, recomputable heights (DESIGN §12.2). Never written back into [raw].
 *
 * Median then moving average, both centred. Windows never cross a profile end or a [ProfileBreak]: near either they
 * are truncated symmetrically (radius = min(nominal, distance to the part boundary)), so no phase shift is introduced
 * and no sample from the other side of a GPX break is used. A sample whose source window contains any unavailable raw
 * sample is unavailable (gaps are not bridged). Each sample records its raw source span.
 */
class FilteredElevationProfile private constructor(
    val raw: RawElevationProfile,
    val config: FilterConfig,
    private val heightM: DoubleArray,
    private val available: BooleanArray,
    private val rawSpanStart: IntArray,
    private val rawSpanEnd: IntArray,
    private val confidence: FloatArray,
    private val partStart: IntArray,
    private val partEnd: IntArray
) {
    val size: Int get() = raw.size
    val spacingM: Double get() = raw.spacingM

    fun distanceAt(i: Int): Double = raw.distanceAt(i)
    fun partIndexAt(i: Int): Int = raw.partIndexAt(i)
    /** First/last sample index of the continuous part containing [i]. */
    fun partRangeAt(i: Int): IntRange = partStart[i]..partEnd[i]
    fun isAvailable(i: Int): Boolean = available[i]
    fun heightAt(i: Int): Double? = if (available[i]) heightM[i] else null
    fun rawSpanAt(i: Int): IntRange = rawSpanStart[i]..rawSpanEnd[i]
    fun confidenceAt(i: Int): Float = confidence[i]

    companion object {
        fun from(raw: RawElevationProfile, config: FilterConfig = FilterConfig.V1): FilteredElevationProfile {
            val n = raw.size
            val medR = config.medianWindowSamples / 2
            val avgR = config.averageWindowSamples / 2

            val partStart = IntArray(n)
            val partEnd = IntArray(n)
            var start = 0
            for (i in 0 until n) {
                if (i > 0 && raw.partIndexAt(i) != raw.partIndexAt(i - 1)) start = i
                partStart[i] = start
            }
            var end = n - 1
            for (i in n - 1 downTo 0) {
                if (i < n - 1 && raw.partIndexAt(i) != raw.partIndexAt(i + 1)) end = i
                partEnd[i] = end
            }
            fun radius(j: Int, nominal: Int) = min(nominal, min(j - partStart[j], partEnd[j] - j))

            val median = DoubleArray(n)
            val medianOk = BooleanArray(n)
            val medLo = IntArray(n)
            val medHi = IntArray(n)
            for (j in 0 until n) {
                val r = radius(j, medR)
                medLo[j] = j - r
                medHi[j] = j + r
                val values = DoubleArray(2 * r + 1)
                var ok = true
                for (k in -r..r) {
                    val h = raw.heightAt(j + k)
                    if (h == null) { ok = false; break }
                    values[k + r] = h
                }
                medianOk[j] = ok
                if (ok) { values.sort(); median[j] = values[r] }
            }

            val height = DoubleArray(n) { Double.NaN }
            val available = BooleanArray(n)
            val spanStart = IntArray(n)
            val spanEnd = IntArray(n)
            val conf = FloatArray(n)
            for (j in 0 until n) {
                val r = radius(j, avgR)
                var lo = Int.MAX_VALUE
                var hi = Int.MIN_VALUE
                var sum = 0.0
                var ok = true
                for (k in j - r..j + r) {
                    if (!medianOk[k]) ok = false
                    sum += median[k]
                    lo = min(lo, medLo[k])
                    hi = maxOf(hi, medHi[k])
                }
                spanStart[j] = lo
                spanEnd[j] = hi
                available[j] = ok
                if (ok) height[j] = sum / (2 * r + 1)
                var c = 1f
                for (k in lo..hi) c = min(c, raw.confidenceAt(k))
                conf[j] = if (ok) c else 0f
            }
            return FilteredElevationProfile(raw, config, height, available, spanStart, spanEnd, conf, partStart, partEnd)
        }
    }
}
