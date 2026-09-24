package pl.mazovia.offroad.terrain.profile

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class GradeStatus {
    AVAILABLE,
    /** Source window touches unavailable elevation. */
    UNAVAILABLE_DATA,
    /** Grade window extends beyond the start or end of the sampled profile. */
    OUT_OF_RANGE,
    /** Grade window would cross a GPX segment break ([ProfileBreak]); never computed across it. */
    ACROSS_BREAK
}

/**
 * Grade from the **Filtered** profile only (DESIGN §12.3, §12.5):
 * `g(s) = (hF(s + L/2) − hF(s − L/2)) / L`, heights at `s ± L/2` linearly interpolated **by distance** between the
 * bracketing filtered samples of the same continuous part (works for the shorter last step of a part).
 *
 * Grade is a fraction (0.04 = 4 %). Never computed from raw adjacent samples, display heights, renderer meshes or
 * across a break. Confidence = min filtered confidence in the source window × anomaly factor of any suspected span
 * touching the raw source span.
 */
class GradeProfile private constructor(
    val filtered: FilteredElevationProfile,
    private val grade: DoubleArray,
    private val status: Array<GradeStatus>,
    private val rawSpanStart: IntArray,
    private val rawSpanEnd: IntArray,
    private val confidence: FloatArray
) {
    val size: Int get() = filtered.size
    val spacingM: Double get() = filtered.spacingM
    val configId: String get() = filtered.config.id

    fun distanceAt(i: Int): Double = filtered.distanceAt(i)
    fun partIndexAt(i: Int): Int = filtered.partIndexAt(i)
    fun statusAt(i: Int): GradeStatus = status[i]
    fun isAvailable(i: Int): Boolean = status[i] == GradeStatus.AVAILABLE
    fun gradeAt(i: Int): Double? = if (isAvailable(i)) grade[i] else null
    fun rawSpanAt(i: Int): IntRange = rawSpanStart[i]..rawSpanEnd[i]
    fun confidenceAt(i: Int): Float = confidence[i]

    /** Index of the sample closest to [sM]. */
    fun indexNearest(sM: Double): Int = filtered.raw.indexNearest(sM)

    /** Indices of samples with distance in [fromS, toS] (may include both sides of a break at equal `s`). */
    fun indicesWithin(fromS: Double, toS: Double): IntRange {
        if (size == 0) return IntRange.EMPTY
        val first = filtered.raw.firstIndexAtOrAfter(fromS)
        val last = filtered.raw.firstIndexAtOrAfter(toS + 2 * EPS) - 1
        return if (first > last) IntRange.EMPTY else first..last
    }

    /**
     * Longest run (metres, first-to-last sample distance) of consecutive available samples of one continuous part in
     * [range] whose grade is at or beyond [threshold] in the given [sign] direction (+1 climb, −1 descent).
     * −1.0 if no sample qualifies; 0.0 for a single qualifying sample.
     */
    fun longestRunM(range: IntRange, threshold: Double, sign: Int): Double {
        var best = -1.0
        var runStart = -1
        for (i in range) {
            val g = gradeAt(i)
            val qualifies = g != null && g * sign >= threshold - EPS
            if (qualifies && runStart >= 0 && partIndexAt(i) != partIndexAt(i - 1)) runStart = i
            if (qualifies) {
                if (runStart < 0) runStart = i
                best = max(best, distanceAt(i) - distanceAt(runStart))
            } else runStart = -1
        }
        return best
    }

    /** Maximum |grade| over available samples in [range], or null if none. */
    fun maxAbsGrade(range: IntRange): Double? = range.mapNotNull { gradeAt(it) }.maxOfOrNull { abs(it) }

    companion object {
        /** Tolerance for threshold comparisons on computed grades and distances. */
        const val EPS = 1e-9

        /**
         * Builds the grade profile. [anomalies] is mandatory (TA-001A-F1 / SR-006): pass the detector output for the
         * same raw profile so suspected spans lower confidence; pass an empty list only when that is the intent.
         */
        fun from(filtered: FilteredElevationProfile, anomalies: List<AnomalySpan>): GradeProfile {
            val n = filtered.size
            val half = filtered.config.gradeWindowM / 2.0
            val grade = DoubleArray(n) { Double.NaN }
            val status = Array(n) { GradeStatus.OUT_OF_RANGE }
            val spanStart = IntArray(n) { it }
            val spanEnd = IntArray(n) { it }
            val conf = FloatArray(n)
            val suspected = anomalies.filter { it.kind == AnomalyKind.SUSPECTED_PROFILE_ANOMALY }
            for (j in 0 until n) {
                val part = filtered.partRangeAt(j)
                val behindS = filtered.distanceAt(j) - half
                val aheadS = filtered.distanceAt(j) + half
                val beforeStart = behindS < filtered.distanceAt(part.first) - EPS
                val afterEnd = aheadS > filtered.distanceAt(part.last) + EPS
                if (beforeStart || afterEnd) {
                    val crossesBreak = (beforeStart && part.first > 0) || (afterEnd && part.last < n - 1)
                    status[j] = if (crossesBreak) GradeStatus.ACROSS_BREAK else GradeStatus.OUT_OF_RANGE
                    continue
                }
                val loIdx = bracketBelow(filtered, part, behindS)
                val hiIdx = bracketAbove(filtered, part, aheadS)
                var lo = Int.MAX_VALUE
                var hi = Int.MIN_VALUE
                var c = 1f
                var ok = true
                for (k in loIdx..hiIdx) {
                    if (!filtered.isAvailable(k)) ok = false
                    lo = min(lo, filtered.rawSpanAt(k).first)
                    hi = max(hi, filtered.rawSpanAt(k).last)
                    c = min(c, filtered.confidenceAt(k))
                }
                spanStart[j] = lo
                spanEnd[j] = hi
                if (!ok) { status[j] = GradeStatus.UNAVAILABLE_DATA; continue }
                val ahead = heightAtDistance(filtered, part, aheadS)
                val behind = heightAtDistance(filtered, part, behindS)
                grade[j] = (ahead - behind) / filtered.config.gradeWindowM
                status[j] = GradeStatus.AVAILABLE
                val factor = suspected.filter { it.startIndex <= hi && it.endIndex >= lo }
                    .minOfOrNull { it.confidenceFactor } ?: 1f
                conf[j] = c * factor
            }
            return GradeProfile(filtered, grade, status, spanStart, spanEnd, conf)
        }

        /** Largest index in [part] whose distance ≤ [s] (binary search; distances increase within a part). */
        private fun bracketBelow(f: FilteredElevationProfile, part: IntRange, s: Double): Int {
            var lo = part.first
            var hi = part.last
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (f.distanceAt(mid) <= s + EPS) lo = mid else hi = mid - 1
            }
            return lo
        }

        /** Smallest index in [part] whose distance ≥ [s] (binary search). */
        private fun bracketAbove(f: FilteredElevationProfile, part: IntRange, s: Double): Int {
            var lo = part.first
            var hi = part.last
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (f.distanceAt(mid) >= s - EPS) hi = mid else lo = mid + 1
            }
            return lo
        }

        private fun heightAtDistance(f: FilteredElevationProfile, part: IntRange, s: Double): Double {
            val lo = bracketBelow(f, part, s)
            val a = f.heightAt(lo)!!
            if (abs(f.distanceAt(lo) - s) <= EPS || lo == part.last) return a
            val hi = lo + 1
            val b = f.heightAt(hi)!!
            val t = (s - f.distanceAt(lo)) / (f.distanceAt(hi) - f.distanceAt(lo))
            return a + t * (b - a)
        }
    }
}
