package pl.mazovia.offroad.terrain.profile

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class GradeEventType { CLIMB, DESCENT }

enum class GradeEventClass { STANDARD, SHORT }

/**
 * Grade event (DESIGN §12.5). Grades are signed fractions; [maxGrade] is the signed grade with the largest
 * magnitude; [averageGrade] = [elevationChangeM] / [lengthM]; [elevationChangeM] = filtered end − start height.
 * Distances are on the navigation-compatible axis; [startIndex]/[endIndex] index the grade profile.
 */
data class GradeEvent(
    val type: GradeEventType,
    val eventClass: GradeEventClass,
    val startDistanceM: Double,
    val endDistanceM: Double,
    val lengthM: Double,
    val averageGrade: Double,
    val maxGrade: Double,
    val elevationChangeM: Double,
    val confidence: Float,
    val startIndex: Int,
    val endIndex: Int,
    val rawSpan: IntRange,
    val configId: String
)

/** Event thresholds (TARGETS, DESIGN §12.5, frozen for G-DATA). Grades as fractions. */
data class EventDetectorConfig(
    val id: String = "GradeEvents-v1",
    val startGrade: Double = 0.04,
    val endGrade: Double = 0.025,
    val standardMinLengthM: Double = 50.0,
    val shortSteepGrade: Double = 0.07,
    val shortSteepMinLengthM: Double = 10.0
)

/**
 * Detects CLIMB/DESCENT events on a [GradeProfile] (DESIGN §12.5):
 * - candidate span: starts at |g| ≥ [EventDetectorConfig.startGrade], continues while the grade keeps its sign and
 *   |g| ≥ [EventDetectorConfig.endGrade]; unavailable grade or a GPX break ([ProfileBreak]) ends the span (no bridging);
 * - short-steep run length is the first-to-last sample distance of consecutive qualifying samples (3 samples at 5 m
 *   = 10 m);
 * - event if span length ≥ standard minimum, or the span contains a sub-run with |g| ≥ short-steep grade of length
 *   ≥ short-steep minimum; class SHORT when length < standard minimum.
 * Anomaly metadata never suppresses an event; it only lowers confidence via the grade profile.
 */
class GradeEventDetector(val config: EventDetectorConfig = EventDetectorConfig()) {

    fun detect(grade: GradeProfile): List<GradeEvent> {
        val events = mutableListOf<GradeEvent>()
        var i = 0
        val n = grade.size
        while (i < n) {
            val g = grade.gradeAt(i)
            if (g == null || abs(g) < config.startGrade - GradeProfile.EPS) { i++; continue }
            val sign = if (g > 0) 1 else -1
            var end = i
            while (end + 1 < n) {
                if (grade.partIndexAt(end + 1) != grade.partIndexAt(end)) break // never across a GPX break
                val next = grade.gradeAt(end + 1) ?: break
                if (next * sign < config.endGrade - GradeProfile.EPS) break
                end++
            }
            toEvent(grade, i, end, sign)?.let { events += it }
            i = end + 1
        }
        return events
    }

    private fun toEvent(grade: GradeProfile, start: Int, end: Int, sign: Int): GradeEvent? {
        val startS = grade.distanceAt(start)
        val endS = grade.distanceAt(end)
        val length = endS - startS
        val shortRun = grade.longestRunM(start..end, config.shortSteepGrade, sign)
        val isStandard = length >= config.standardMinLengthM - GradeProfile.EPS
        val isShortSteep = shortRun >= config.shortSteepMinLengthM - GradeProfile.EPS
        if (!isStandard && !isShortSteep) return null

        val filtered = grade.filtered
        val hStart = filtered.heightAt(start) ?: return null
        val hEnd = filtered.heightAt(end) ?: return null
        var maxGrade = 0.0
        var confidence = 1f
        var rawLo = Int.MAX_VALUE
        var rawHi = Int.MIN_VALUE
        for (k in start..end) {
            val g = grade.gradeAt(k)!!
            if (abs(g) > abs(maxGrade)) maxGrade = g
            confidence = min(confidence, grade.confidenceAt(k))
            rawLo = min(rawLo, grade.rawSpanAt(k).first)
            rawHi = max(rawHi, grade.rawSpanAt(k).last)
        }
        val change = hEnd - hStart
        return GradeEvent(
            type = if (sign > 0) GradeEventType.CLIMB else GradeEventType.DESCENT,
            eventClass = if (isStandard) GradeEventClass.STANDARD else GradeEventClass.SHORT,
            startDistanceM = startS,
            endDistanceM = endS,
            lengthM = length,
            averageGrade = if (length > 0.0) change / length else 0.0,
            maxGrade = maxGrade,
            elevationChangeM = change,
            confidence = confidence,
            startIndex = start,
            endIndex = end,
            rawSpan = rawLo..rawHi,
            configId = "${config.id}/${grade.configId}"
        )
    }
}
