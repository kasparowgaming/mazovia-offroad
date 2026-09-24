package pl.mazovia.offroad.terrain.profile

import kotlin.math.abs

/** Metadata-only anomaly kinds (DESIGN §12.4). Shape alone never yields BRIDGE/TUNNEL/EMBANKMENT/FORD. */
enum class AnomalyKind { SUSPECTED_PROFILE_ANOMALY, LOW_CONFIDENCE_SPAN }

enum class AnomalyReason { ISOLATED_SPIKE, STEP, SHORT_BUMP, SHORT_DIP, REDUCED_SAMPLE_CONFIDENCE }

/**
 * One anomaly span over raw sample indices [startIndex]..[endIndex].
 * @property magnitudeM signed deviation (spike/bump/dip) or step height; 0 for confidence spans
 * @property confidenceFactor factor applied to profile/grade confidence inside the span
 */
data class AnomalySpan(
    val startIndex: Int,
    val endIndex: Int,
    val startS: Double,
    val endS: Double,
    val kind: AnomalyKind,
    val reason: AnomalyReason,
    val magnitudeM: Double,
    val detectorConfigId: String,
    val confidenceFactor: Float
)

/** Detector parameters (TARGETS, DESIGN §12.4, §12.6). */
data class AnomalyDetectorConfig(
    val id: String = "AnomalyDetector-v1",
    val spikeDeviationM: Double = 2.0,
    val spikeNeighbourhoodRadiusSamples: Int = 5,
    val stepThresholdM: Double = 1.5,
    val bumpMinMagnitudeM: Double = 3.0,
    val bumpMaxLengthM: Double = 60.0,
    val bumpReturnToleranceM: Double = 1.0,
    val suspectedConfidenceFactor: Float = 0.5f
)

/**
 * Non-destructive anomaly detection (TA-000B-C1 / C2): reads [RawElevationProfile], returns metadata spans,
 * never modifies any profile and never suppresses events.
 */
class ProfileAnomalyDetector(private val config: AnomalyDetectorConfig = AnomalyDetectorConfig()) {

    fun detect(raw: RawElevationProfile): List<AnomalySpan> {
        val n = raw.size
        val out = mutableListOf<AnomalySpan>()
        val spikes = HashSet<Int>()
        // Detection never looks across a GPX break (TA-001A-F1): neighbours must belong to the same continuous part.
        fun samePart(a: Int, b: Int) = raw.partIndexAt(a) == raw.partIndexAt(b)

        // Isolated single-sample deviation from the local median.
        for (j in 0 until n) {
            val h = raw.heightAt(j) ?: continue
            val neighbourhood = (j - config.spikeNeighbourhoodRadiusSamples..j + config.spikeNeighbourhoodRadiusSamples)
                .filter { it in 0 until n && samePart(it, j) }
                .mapNotNull { raw.heightAt(it) }
                .sorted()
            if (neighbourhood.size < 5) continue
            val median = neighbourhood[neighbourhood.size / 2]
            val deviation = h - median
            if (abs(deviation) <= config.spikeDeviationM) continue
            val neighboursQuiet = listOf(j - 1, j + 1).filter { it in 0 until n && samePart(it, j) }
                .all { k -> raw.heightAt(k)?.let { abs(it - median) <= config.spikeDeviationM } ?: true }
            if (neighboursQuiet) {
                spikes += j
                out += span(raw, j, j, AnomalyKind.SUSPECTED_PROFILE_ANOMALY, AnomalyReason.ISOLATED_SPIKE, deviation)
            }
        }

        // Steps between adjacent available samples (not already explained by a spike).
        for (j in 0 until n - 1) {
            if (j in spikes || j + 1 in spikes || !samePart(j, j + 1)) continue
            val a = raw.heightAt(j) ?: continue
            val b = raw.heightAt(j + 1) ?: continue
            if (abs(b - a) > config.stepThresholdM) {
                out += span(raw, j, j + 1, AnomalyKind.SUSPECTED_PROFILE_ANOMALY, AnomalyReason.STEP, b - a)
            }
        }

        // Short bump/dip returning to the pre-level.
        val maxSteps = (config.bumpMaxLengthM / raw.spacingM).toInt()
        var j = 0
        while (j < n) {
            val pre = raw.heightAt(j)
            var next = j + 1
            if (pre != null) {
                for (k in j + 2..minOf(n - 1, j + maxSteps)) {
                    if (!samePart(k, j)) break
                    val hk = raw.heightAt(k) ?: break
                    if (abs(hk - pre) > config.bumpReturnToleranceM) continue
                    var extreme = 0.0
                    var deviating = 0
                    var gap = false
                    for (m in j + 1 until k) {
                        val hm = raw.heightAt(m)
                        if (hm == null) { gap = true; break }
                        val d = hm - pre
                        if (abs(d) > abs(extreme)) extreme = d
                        if (abs(d) > config.bumpMinMagnitudeM) deviating++
                    }
                    if (gap) break
                    if (abs(extreme) > config.bumpMinMagnitudeM) {
                        val onlySpike = deviating == 1 && (j + 1 until k).any { it in spikes }
                        if (!onlySpike) {
                            val reason = if (extreme > 0) AnomalyReason.SHORT_BUMP else AnomalyReason.SHORT_DIP
                            out += span(raw, j, k, AnomalyKind.SUSPECTED_PROFILE_ANOMALY, reason, extreme)
                            next = k
                        }
                        break
                    }
                }
            }
            j = next
        }

        // Reduced sample confidence (e.g. neighbour substitution in the sampler).
        var i = 0
        while (i < n) {
            if (raw.isAvailable(i) && raw.confidenceAt(i) < 1f) {
                var e = i
                while (e + 1 < n && samePart(e, e + 1) && raw.isAvailable(e + 1) && raw.confidenceAt(e + 1) < 1f) e++
                out += span(raw, i, e, AnomalyKind.LOW_CONFIDENCE_SPAN, AnomalyReason.REDUCED_SAMPLE_CONFIDENCE, 0.0, 1f)
                i = e + 1
            } else i++
        }

        return out.sortedWith(compareBy({ it.startIndex }, { it.endIndex }, { it.reason.ordinal }))
    }

    private fun span(
        raw: RawElevationProfile, start: Int, end: Int, kind: AnomalyKind, reason: AnomalyReason,
        magnitude: Double, factor: Float = config.suspectedConfidenceFactor
    ) = AnomalySpan(
        startIndex = start,
        endIndex = end,
        startS = raw.distanceAt(start),
        endS = raw.distanceAt(end),
        kind = kind,
        reason = reason,
        magnitudeM = magnitude,
        detectorConfigId = config.id,
        confidenceFactor = factor
    )
}
