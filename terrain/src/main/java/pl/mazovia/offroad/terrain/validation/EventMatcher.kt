package pl.mazovia.offroad.terrain.validation

import pl.mazovia.offroad.terrain.profile.GradeEvent
import pl.mazovia.offroad.terrain.profile.GradeEventType
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.profile.GradeStatus
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pre-declared G-DATA-3 / G-DATA-4 matching and scoring parameters (DESIGN §22.4, TA-000B-C1). Frozen before
 * TA-001B; changing any value is a new declared experiment.
 */
data class MatchConfig(
    val id: String = "EventMatch-v1",
    val minIoU: Double = 0.60,
    val maxStartErrorM: Double = 25.0,
    val eligibleStandardMinLengthM: Double = 60.0,
    val eligibleStandardMinPeakGrade: Double = 0.045,
    val eligibleShortGrade: Double = 0.075,
    val eligibleShortMinRunM: Double = 12.0,
    val runtimeBorderlineReferencePeakGrade: Double = 0.035,
    val minEligibleReferenceEvents: Int = 50,
    val gatePrecision: Double = 0.90,
    val gateRecall: Double = 0.90,
    val investigationThreshold: Double = 0.70
) {
    companion object {
        /** Tolerance for threshold comparisons so exact boundary values (e.g. IoU = 0.60) are inclusive. */
        const val EPS = 1e-9
    }
}

/** Diagnostics of one matched pair (runtime − reference). Grade errors in percentage points. */
data class MatchDiagnostics(
    val iou: Double,
    val startErrorM: Double,
    val endErrorM: Double,
    val lengthErrorM: Double,
    val lengthErrorPct: Double?,
    val averageGradeErrorPp: Double,
    val maxGradeErrorPp: Double,
    val elevationChangeErrorM: Double
)

data class MatchedPair(val reference: GradeEvent, val runtime: GradeEvent, val diagnostics: MatchDiagnostics)

/** Per-route result (DESIGN §22.4 G-DATA-3 "Per route"). Precision/recall are null when undefined. */
data class RouteMatchReport(
    val routeId: String,
    val eligibleReferenceEvents: Int,
    val runtimeEvents: Int,
    val truePositives: List<MatchedPair>,
    val falsePositives: List<GradeEvent>,
    val falseNegatives: List<GradeEvent>,
    val borderlineMatched: List<MatchedPair>,
    val borderlineReferenceUnmatched: List<GradeEvent>,
    val borderlineRuntime: List<GradeEvent>,
    val unscorableLengthM: Double,
    val precision: Double?,
    val recall: Double?,
    val noEventCase: Boolean,
    val requiresInvestigation: Boolean
) {
    val tp: Int get() = truePositives.size
    val fp: Int get() = falsePositives.size
    val fn: Int get() = falseNegatives.size
    val borderlineCount: Int get() = borderlineMatched.size + borderlineReferenceUnmatched.size + borderlineRuntime.size
}

enum class EventGateOutcome { PASS, FAIL, INCONCLUSIVE_INSUFFICIENT_ELIGIBLE_EVENTS }

data class Distribution(val median: Double, val p95: Double, val count: Int)

/** Pooled (micro) result over a dataset of routes. */
data class DatasetMatchReport(
    val routes: List<RouteMatchReport>,
    val totalEligibleReferenceEvents: Int,
    val totalRuntimeEvents: Int,
    val tp: Int,
    val fp: Int,
    val fn: Int,
    val precision: Double?,
    val recall: Double?,
    val f1: Double?,
    val outcome: EventGateOutcome,
    val routesRequiringInvestigation: List<String>,
    val noEventCaseRoutes: List<String>,
    val startErrorM: Distribution?,
    val endErrorM: Distribution?,
    val maxGradeErrorPp: Distribution?,
    val elevationChangeErrorM: Distribution?,
    val iou: Distribution?
)

/**
 * Formal event matcher for G-DATA-3 (and G-DATA-4 feature retention), DESIGN §22.4.
 *
 * Match: same type AND IoU ≥ 0.60 AND |start error| ≤ 25 m. One-to-one greedy assignment over admissible pairs
 * sorted by IoU desc, |start error| asc, reference start asc, then runtime start asc and list indices (added for
 * full determinism). BORDERLINE applies to scoring only; the production detector is unchanged.
 */
class EventMatcher(val config: MatchConfig = MatchConfig()) {

    fun matchRoute(
        routeId: String,
        reference: List<GradeEvent>,
        referenceGrade: GradeProfile,
        runtime: List<GradeEvent>,
        runtimeGrade: GradeProfile? = null
    ): RouteMatchReport {
        val eligible = BooleanArray(reference.size) { isEligible(reference[it], referenceGrade) }

        data class Candidate(val r: Int, val x: Int, val iou: Double, val startErr: Double)
        val candidates = mutableListOf<Candidate>()
        for (r in reference.indices) for (x in runtime.indices) {
            val ref = reference[r]
            val run = runtime[x]
            if (ref.type != run.type) continue
            val iou = iou(ref, run)
            val startErr = abs(run.startDistanceM - ref.startDistanceM)
            if (iou >= config.minIoU - MatchConfig.EPS && startErr <= config.maxStartErrorM + MatchConfig.EPS) {
                candidates += Candidate(r, x, iou, startErr)
            }
        }
        candidates.sortWith(
            compareByDescending<Candidate> { it.iou }
                .thenBy { it.startErr }
                .thenBy { reference[it.r].startDistanceM }
                .thenBy { runtime[it.x].startDistanceM }
                .thenBy { it.r }
                .thenBy { it.x }
        )
        val refUsed = BooleanArray(reference.size)
        val runUsed = BooleanArray(runtime.size)
        val tp = mutableListOf<MatchedPair>()
        val borderlineMatched = mutableListOf<MatchedPair>()
        for (c in candidates) {
            if (refUsed[c.r] || runUsed[c.x]) continue
            refUsed[c.r] = true
            runUsed[c.x] = true
            val pair = MatchedPair(reference[c.r], runtime[c.x], diagnostics(reference[c.r], runtime[c.x], c.iou))
            if (eligible[c.r]) tp += pair else borderlineMatched += pair
        }

        val fn = reference.indices.filter { !refUsed[it] && eligible[it] }.map { reference[it] }
        val borderlineRef = reference.indices.filter { !refUsed[it] && !eligible[it] }.map { reference[it] }
        val fp = mutableListOf<GradeEvent>()
        val borderlineRun = mutableListOf<GradeEvent>()
        for (x in runtime.indices) {
            if (runUsed[x]) continue
            val run = runtime[x]
            val refPeak = referenceGrade.maxAbsGrade(
                referenceGrade.indicesWithin(run.startDistanceM, run.endDistanceM)
            ) ?: 0.0
            val eligibleOverlap = reference.indices.any { eligible[it] && overlapM(reference[it], run) > 0.0 }
            if (refPeak >= config.runtimeBorderlineReferencePeakGrade - MatchConfig.EPS && !eligibleOverlap) {
                borderlineRun += run
            } else fp += run
        }

        val eligibleCount = eligible.count { it }
        val precision = ratio(tp.size, tp.size + fp.size)
        val recall = ratio(tp.size, tp.size + fn.size)
        val noEventCase = eligibleCount == 0 && tp.isEmpty() && fp.isEmpty()
        val investigate = (precision != null && precision < config.investigationThreshold - MatchConfig.EPS) ||
            (recall != null && recall < config.investigationThreshold - MatchConfig.EPS)
        return RouteMatchReport(
            routeId = routeId,
            eligibleReferenceEvents = eligibleCount,
            runtimeEvents = runtime.size,
            truePositives = tp,
            falsePositives = fp,
            falseNegatives = fn,
            borderlineMatched = borderlineMatched,
            borderlineReferenceUnmatched = borderlineRef,
            borderlineRuntime = borderlineRun,
            unscorableLengthM = unscorableLength(referenceGrade, runtimeGrade),
            precision = precision,
            recall = recall,
            noEventCase = noEventCase,
            requiresInvestigation = investigate
        )
    }

    fun aggregate(routes: List<RouteMatchReport>): DatasetMatchReport {
        val tp = routes.sumOf { it.tp }
        val fp = routes.sumOf { it.fp }
        val fn = routes.sumOf { it.fn }
        val eligible = routes.sumOf { it.eligibleReferenceEvents }
        val precision = ratio(tp, tp + fp)
        val recall = ratio(tp, tp + fn)
        val f1 = if (precision != null && recall != null && precision + recall > 0.0)
            2 * precision * recall / (precision + recall) else null
        val outcome = when {
            eligible < config.minEligibleReferenceEvents -> EventGateOutcome.INCONCLUSIVE_INSUFFICIENT_ELIGIBLE_EVENTS
            precision != null && recall != null &&
                precision >= config.gatePrecision - MatchConfig.EPS &&
                recall >= config.gateRecall - MatchConfig.EPS -> EventGateOutcome.PASS
            else -> EventGateOutcome.FAIL
        }
        val pairs = routes.flatMap { it.truePositives }.map { it.diagnostics }
        return DatasetMatchReport(
            routes = routes,
            totalEligibleReferenceEvents = eligible,
            totalRuntimeEvents = routes.sumOf { it.runtimeEvents },
            tp = tp, fp = fp, fn = fn,
            precision = precision, recall = recall, f1 = f1,
            outcome = outcome,
            routesRequiringInvestigation = routes.filter { it.requiresInvestigation }.map { it.routeId },
            noEventCaseRoutes = routes.filter { it.noEventCase }.map { it.routeId },
            startErrorM = distribution(pairs.map { abs(it.startErrorM) }),
            endErrorM = distribution(pairs.map { abs(it.endErrorM) }),
            maxGradeErrorPp = distribution(pairs.map { abs(it.maxGradeErrorPp) }),
            elevationChangeErrorM = distribution(pairs.map { abs(it.elevationChangeErrorM) }),
            iou = distribution(pairs.map { it.iou })
        )
    }

    /**
     * Eligible (non-BORDERLINE) reference event: (length ≥ 60 m AND max|g| ≥ 4.5 %) OR a sub-run with
     * |g| ≥ 7.5 % in the event direction of length ≥ 12 m (DESIGN §22.4).
     */
    fun isEligible(event: GradeEvent, referenceGrade: GradeProfile): Boolean {
        val standardRobust = event.lengthM >= config.eligibleStandardMinLengthM - MatchConfig.EPS &&
            abs(event.maxGrade) >= config.eligibleStandardMinPeakGrade - MatchConfig.EPS
        if (standardRobust) return true
        val sign = if (event.type == GradeEventType.CLIMB) 1 else -1
        val run = referenceGrade.longestRunM(event.startIndex..event.endIndex, config.eligibleShortGrade, sign)
        return run >= config.eligibleShortMinRunM - MatchConfig.EPS
    }

    private fun diagnostics(ref: GradeEvent, run: GradeEvent, iou: Double) = MatchDiagnostics(
        iou = iou,
        startErrorM = run.startDistanceM - ref.startDistanceM,
        endErrorM = run.endDistanceM - ref.endDistanceM,
        lengthErrorM = run.lengthM - ref.lengthM,
        lengthErrorPct = if (ref.lengthM > 0.0) (run.lengthM - ref.lengthM) / ref.lengthM * 100.0 else null,
        averageGradeErrorPp = (run.averageGrade - ref.averageGrade) * 100.0,
        maxGradeErrorPp = (run.maxGrade - ref.maxGrade) * 100.0,
        elevationChangeErrorM = run.elevationChangeM - ref.elevationChangeM
    )

    private fun unscorableLength(reference: GradeProfile, runtime: GradeProfile?): Double {
        var count = 0
        for (i in 0 until reference.size) {
            val refBad = reference.statusAt(i).isUnscorable()
            val runBad = runtime != null && i < runtime.size && runtime.statusAt(i).isUnscorable()
            if (refBad || runBad) count++
        }
        return count * reference.spacingM
    }

    companion object {
        /** Positions without grade because of missing data or a GPX break window (profile ends are not counted). */
        private fun GradeStatus.isUnscorable() = this == GradeStatus.UNAVAILABLE_DATA || this == GradeStatus.ACROSS_BREAK

        fun iou(a: GradeEvent, b: GradeEvent): Double {
            val union = max(a.endDistanceM, b.endDistanceM) - min(a.startDistanceM, b.startDistanceM)
            if (union <= 0.0) return if (a.startDistanceM == b.startDistanceM) 1.0 else 0.0
            return overlapM(a, b) / union
        }

        fun overlapM(a: GradeEvent, b: GradeEvent): Double =
            max(0.0, min(a.endDistanceM, b.endDistanceM) - max(a.startDistanceM, b.startDistanceM))

        private fun ratio(num: Int, den: Int): Double? = if (den == 0) null else num.toDouble() / den

        /** Nearest-rank median and p95. */
        fun distribution(values: List<Double>): Distribution? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            fun rank(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
            return Distribution(median = rank(0.5), p95 = rank(0.95), count = sorted.size)
        }
    }
}
