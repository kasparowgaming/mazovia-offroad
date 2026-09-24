package pl.mazovia.offroad.terrain.profile

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.terrain.elevation.ElevationSample
import pl.mazovia.offroad.terrain.elevation.ElevationSampler
import pl.mazovia.offroad.terrain.elevation.UnavailableReason
import pl.mazovia.offroad.terrain.projection.RouteIndex
import kotlin.math.ceil

/**
 * Hard discontinuity between two consecutive samples that belong to different continuous route parts (a GPX segment
 * break). Both sides may carry the same `s` (the gap contributes 0 to the navigation axis); [geographicGapM] is the
 * ground distance between the two sampled points, null when positions are unknown.
 */
data class ProfileBreak(
    val beforeIndex: Int,
    val afterIndex: Int,
    val distanceBeforeM: Double,
    val distanceAfterM: Double,
    val geographicGapM: Double?
)

/**
 * Immutable sampled route profile (DESIGN §12, TA-000B-C1 / C2).
 *
 * Holds exactly what the sampler returned at positions on the navigation-compatible axis `s`. Unavailable samples keep
 * their reason and are never 0 m or interpolated. Every sample carries its continuous-part index; consecutive samples
 * in different parts are separated by a [ProfileBreak] that derived layers never cross (TA-001A-F1). Distances are
 * non-decreasing and may repeat across a break. All arrays are private copies; no API mutates them.
 */
class RawElevationProfile private constructor(
    val routeId: String,
    val sourceId: String,
    /** Nominal sampling step; the last sample of a part may be closer to its predecessor (part end is kept). */
    val spacingM: Double,
    private val distanceM: DoubleArray,
    private val latitude: DoubleArray?,
    private val longitude: DoubleArray?,
    private val heightM: DoubleArray,
    private val unavailableReason: Array<UnavailableReason?>,
    private val confidence: FloatArray,
    private val partIndex: IntArray
) {
    val size: Int get() = distanceM.size

    /** Breaks between consecutive samples of different parts, in sample order. */
    val breaks: List<ProfileBreak> = (0 until size - 1).filter { partIndex[it] != partIndex[it + 1] }.map { i ->
        val a = positionAt(i)
        val b = positionAt(i + 1)
        ProfileBreak(i, i + 1, distanceM[i], distanceM[i + 1], if (a != null && b != null) a.distanceTo(b) else null)
    }

    fun distanceAt(i: Int): Double = distanceM[i]
    fun partIndexAt(i: Int): Int = partIndex[i]
    fun isAvailable(i: Int): Boolean = unavailableReason[i] == null
    /** Height in metres, or null when unavailable (never 0 as a placeholder). */
    fun heightAt(i: Int): Double? = if (isAvailable(i)) heightM[i] else null
    fun unavailableReasonAt(i: Int): UnavailableReason? = unavailableReason[i]
    fun confidenceAt(i: Int): Float = confidence[i]
    fun positionAt(i: Int): GeoPoint? =
        if (latitude == null || longitude == null) null else GeoPoint(latitude[i], longitude[i])

    /** Index of the sample closest to [sM] (ties → lower index), or -1 if the profile is empty. */
    fun indexNearest(sM: Double): Int {
        if (size == 0) return -1
        val i = firstIndexAtOrAfter(sM)
        if (i >= size) return size - 1
        if (i == 0) return 0
        return if (sM - distanceM[i - 1] <= distanceM[i] - sM) i - 1 else i
    }

    /** First index whose distance is ≥ [sM] − 1e-9 (size if none). Distances are non-decreasing. */
    fun firstIndexAtOrAfter(sM: Double): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (distanceM[mid] < sM - EPS_M) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Sampling step (TARGET, DESIGN §12.1). */
        const val DEFAULT_SPACING_M = 5.0
        private const val EPS_M = 1e-9
        /** A part end closer than this to the last grid sample is not duplicated. */
        private const val TERMINAL_MIN_M = 1e-6

        /**
         * Samples [sampler] along [index] every [spacingM], separately for each continuous part (grid anchored at the
         * part start, part end always kept), restricted to [fromS]..[toS]. Positions come from
         * [RouteIndex.locateInPart], so both sides of a GPX break are sampled even though they share the same `s`.
         */
        fun sample(
            index: RouteIndex,
            sampler: ElevationSampler,
            spacingM: Double = DEFAULT_SPACING_M,
            fromS: Double = 0.0,
            toS: Double = index.totalLengthM
        ): RawElevationProfile {
            require(spacingM > 0.0)
            val s = ArrayList<Double>()
            val lat = ArrayList<Double>()
            val lon = ArrayList<Double>()
            val h = ArrayList<Double>()
            val reasons = ArrayList<UnavailableReason?>()
            val conf = ArrayList<Float>()
            val parts = ArrayList<Int>()

            fun add(sValue: Double, p: GeoPoint, part: Int) {
                s += sValue; lat += p.latitude; lon += p.longitude; parts += part
                when (val sample = sampler.sample(p.latitude, p.longitude)) {
                    is ElevationSample.Value -> { h += sample.heightM; reasons += null; conf += sample.confidence }
                    is ElevationSample.Unavailable -> { h += Double.NaN; reasons += sample.reason; conf += 0f }
                }
            }

            for (part in index.parts) {
                val lo = maxOf(part.startS, fromS)
                val hi = minOf(part.endS, toS)
                if (lo > hi + EPS_M) continue
                if (!part.hasEdges) {
                    add(part.startS, index.points[part.firstPointIndex], part.index)
                    continue
                }
                var k = ceil((lo - part.startS) / spacingM - EPS_M).toInt()
                var last = Double.NaN
                while (true) {
                    val sk = part.startS + k * spacingM
                    if (sk > hi + EPS_M) break
                    add(sk, index.locateInPart(part, sk)!!.projected, part.index)
                    last = sk
                    k++
                }
                if (part.endS <= hi + EPS_M && part.endS >= lo - EPS_M && (last.isNaN() || part.endS - last > TERMINAL_MIN_M)) {
                    add(part.endS, index.locateInPart(part, part.endS)!!.projected, part.index)
                }
            }
            return RawElevationProfile(
                routeId = index.routeId,
                sourceId = sampler.metadata.sourceId,
                spacingM = spacingM,
                distanceM = s.toDoubleArray(),
                latitude = lat.toDoubleArray(),
                longitude = lon.toDoubleArray(),
                heightM = h.toDoubleArray(),
                unavailableReason = reasons.toTypedArray(),
                confidence = conf.toFloatArray(),
                partIndex = parts.toIntArray()
            )
        }

        /**
         * Builds a profile directly from heights at `startS + i·spacingM` (null = unavailable). Inputs are copied.
         * [partIndices] (optional, non-decreasing) marks continuous parts; defaults to a single part.
         */
        fun fromHeights(
            routeId: String,
            heights: List<Double?>,
            spacingM: Double = DEFAULT_SPACING_M,
            startS: Double = 0.0,
            sourceId: String = "direct",
            confidences: List<Float>? = null,
            partIndices: List<Int>? = null
        ): RawElevationProfile {
            val n = heights.size
            require(confidences == null || confidences.size == n)
            require(partIndices == null || (partIndices.size == n && partIndices.zipWithNext().all { (a, b) -> b >= a }))
            return RawElevationProfile(
                routeId = routeId,
                sourceId = sourceId,
                spacingM = spacingM,
                distanceM = DoubleArray(n) { startS + it * spacingM },
                latitude = null,
                longitude = null,
                heightM = DoubleArray(n) { heights[it] ?: Double.NaN },
                unavailableReason = Array(n) { if (heights[it] == null) UnavailableReason.NODATA else null },
                confidence = FloatArray(n) { if (heights[it] == null) 0f else confidences?.get(it) ?: 1f },
                partIndex = IntArray(n) { partIndices?.get(it) ?: 0 }
            )
        }
    }
}
