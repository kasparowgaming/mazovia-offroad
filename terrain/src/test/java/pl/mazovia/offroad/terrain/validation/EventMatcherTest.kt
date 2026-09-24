package pl.mazovia.offroad.terrain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.terrain.elevation.SyntheticProfile
import pl.mazovia.offroad.terrain.profile.FilteredElevationProfile
import pl.mazovia.offroad.terrain.profile.GradeEvent
import pl.mazovia.offroad.terrain.profile.GradeEventClass
import pl.mazovia.offroad.terrain.profile.GradeEventType
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.profile.ProfileAnomalyDetector
import pl.mazovia.offroad.terrain.profile.ProfileFixtures

class EventMatcherTest {

    private val matcher = EventMatcher()

    private fun gradeOf(profile: SyntheticProfile, lengthM: Double = 3000.0): GradeProfile {
        val raw = ProfileFixtures.raw(profile, lengthM)
        return GradeProfile.from(FilteredElevationProfile.from(raw), ProfileAnomalyDetector().detect(raw))
    }

    /** Reference grade: 6 % everywhere (≥ 3.5 %, never ≥ 7.5 %). */
    private val slope6 = gradeOf(SyntheticProfile.Builder().slope(0.06).build())
    private val flat = gradeOf(SyntheticProfile.flat())

    private fun ev(
        start: Double, end: Double,
        type: GradeEventType = GradeEventType.CLIMB,
        maxGrade: Double = 0.06
    ): GradeEvent {
        val g = if (type == GradeEventType.CLIMB) maxGrade else -maxGrade
        val len = end - start
        return GradeEvent(
            type = type,
            eventClass = if (len >= 50.0) GradeEventClass.STANDARD else GradeEventClass.SHORT,
            startDistanceM = start, endDistanceM = end, lengthM = len,
            averageGrade = g, maxGrade = g, elevationChangeM = g * len, confidence = 1f,
            startIndex = (start / 5.0).toInt(), endIndex = (end / 5.0).toInt(),
            rawSpan = (start / 5.0).toInt()..(end / 5.0).toInt(), configId = "test"
        )
    }

    private fun route(ref: List<GradeEvent>, run: List<GradeEvent>, grade: GradeProfile = slope6) =
        matcher.matchRoute("r", ref, grade, run)

    @Test
    fun `exact match is one true positive`() {
        val r = route(listOf(ev(100.0, 200.0)), listOf(ev(100.0, 200.0)))
        assertEquals(1, r.tp); assertEquals(0, r.fp); assertEquals(0, r.fn)
        assertEquals(1.0, r.precision!!, 0.0); assertEquals(1.0, r.recall!!, 0.0)
        assertEquals(1.0, r.truePositives[0].diagnostics.iou, 1e-12)
    }

    @Test
    fun `shifted event within thresholds matches with diagnostics`() {
        val r = route(listOf(ev(100.0, 200.0)), listOf(ev(110.0, 210.0, maxGrade = 0.065)))
        assertEquals(1, r.tp)
        val d = r.truePositives[0].diagnostics
        assertEquals(90.0 / 110.0, d.iou, 1e-12)
        assertEquals(10.0, d.startErrorM, 1e-12)
        assertEquals(10.0, d.endErrorM, 1e-12)
        assertEquals(0.5, d.maxGradeErrorPp, 1e-9)
    }

    @Test
    fun `IoU exactly 0_60 matches and just below does not`() {
        assertEquals(1, route(listOf(ev(100.0, 200.0)), listOf(ev(100.0, 160.0))).tp)
        val below = route(listOf(ev(100.0, 200.0)), listOf(ev(100.0, 159.0)))
        assertEquals(0, below.tp); assertEquals(1, below.fn); assertEquals(1, below.fp)
    }

    @Test
    fun `start error exactly 25 m matches and just above does not`() {
        assertEquals(1, route(listOf(ev(100.0, 300.0)), listOf(ev(125.0, 325.0))).tp)
        val above = route(listOf(ev(100.0, 300.0)), listOf(ev(125.1, 325.1)))
        assertEquals(0, above.tp)
    }

    @Test
    fun `type mismatch never matches`() {
        val r = route(listOf(ev(100.0, 200.0, GradeEventType.CLIMB)), listOf(ev(100.0, 200.0, GradeEventType.DESCENT)))
        assertEquals(0, r.tp); assertEquals(1, r.fn); assertEquals(1, r.fp)
    }

    @Test
    fun `one long runtime event matches at most one reference event`() {
        val ref1 = ev(100.0, 250.0)
        val ref2 = ev(110.0, 300.0)
        val r = route(listOf(ref1, ref2), listOf(ev(100.0, 300.0)))
        assertEquals(1, r.tp)
        assertSame(ref2, r.truePositives[0].reference) // IoU 0.95 beats 0.75
        assertEquals(listOf(ref1), r.falseNegatives)
    }

    @Test
    fun `two runtime events against one reference give one TP and one FP`() {
        val run1 = ev(100.0, 280.0)
        val run2 = ev(105.0, 300.0)
        val r = route(listOf(ev(100.0, 300.0)), listOf(run1, run2))
        assertEquals(1, r.tp)
        assertSame(run2, r.truePositives[0].runtime) // IoU 0.975 beats 0.9
        assertEquals(listOf(run1), r.falsePositives) // an eligible reference overlaps → not BORDERLINE
    }

    @Test
    fun `identical candidates are resolved deterministically by list order`() {
        val a = ev(100.0, 200.0)
        val b = ev(100.0, 200.0)
        val first = route(listOf(ev(100.0, 200.0)), listOf(a, b))
        val again = route(listOf(ev(100.0, 200.0)), listOf(a, b))
        assertSame(a, first.truePositives[0].runtime)
        assertEquals(first, again)
    }

    @Test
    fun `plain false positive and false negative`() {
        val r = matcher.matchRoute("r", listOf(ev(100.0, 200.0)), slope6, listOf(ev(1000.0, 1100.0)))
        assertEquals(1, r.fn)
        // The runtime event sits on 6 % reference terrain but no eligible reference overlaps it → BORDERLINE, not FP.
        assertEquals(0, r.fp)
        assertEquals(1, r.borderlineRuntime.size)
        val onFlat = matcher.matchRoute("r", emptyList(), flat, listOf(ev(1000.0, 1100.0)))
        assertEquals(1, onFlat.fp)
        assertEquals(0.0, onFlat.precision!!, 0.0)
        assertNull(onFlat.recall)
        assertFalse(onFlat.noEventCase)
    }

    @Test
    fun `borderline reference is excluded from TP and FN and counted`() {
        // 55 m long (< 60 m) on terrain that never reaches 7.5 % → BORDERLINE.
        val borderline = ev(100.0, 155.0)
        assertFalse(matcher.isEligible(borderline, slope6))
        val unmatched = route(listOf(borderline), emptyList())
        assertEquals(0, unmatched.fn)
        assertEquals(1, unmatched.borderlineReferenceUnmatched.size)
        assertEquals(0, unmatched.eligibleReferenceEvents)
        val matched = route(listOf(borderline), listOf(ev(100.0, 155.0)))
        assertEquals(0, matched.tp)
        assertEquals(1, matched.borderlineMatched.size)
        // Low peak (4.2 %) is BORDERLINE even when long.
        assertFalse(matcher.isEligible(ev(100.0, 400.0, maxGrade = 0.042), slope6))
        assertTrue(matcher.isEligible(ev(100.0, 400.0, maxGrade = 0.046), slope6))
    }

    @Test
    fun `short reference event is eligible only with a robust steep run`() {
        val steep = gradeOf(SyntheticProfile.Builder().ramp(500.0, 100.0, 0.10).build(), 1000.0)
        val short = ev(520.0, 560.0, maxGrade = 0.10)
        assertTrue(matcher.isEligible(short, steep))
        assertFalse(matcher.isEligible(short, slope6))
    }

    @Test
    fun `no events on both sides is NO_EVENT_CASE, not a perfect score`() {
        val r = route(emptyList(), emptyList(), flat)
        assertTrue(r.noEventCase)
        assertNull(r.precision)
        assertNull(r.recall)
        val ds = matcher.aggregate(listOf(r))
        assertEquals(listOf("r"), ds.noEventCaseRoutes)
        assertEquals(EventGateOutcome.INCONCLUSIVE_INSUFFICIENT_ELIGIBLE_EVENTS, ds.outcome)
    }

    @Test
    fun `dataset gate needs 50 eligible reference events`() {
        fun perfectRoute(id: String, n: Int) = matcher.matchRoute(
            id, (0 until n).map { ev(100.0 + it * 200.0, 200.0 + it * 200.0) }, slope6,
            (0 until n).map { ev(100.0 + it * 200.0, 200.0 + it * 200.0) }
        )
        val small = matcher.aggregate(listOf(perfectRoute("a", 5), perfectRoute("b", 5)))
        assertEquals(10, small.totalEligibleReferenceEvents)
        assertEquals(1.0, small.precision!!, 0.0)
        assertEquals(EventGateOutcome.INCONCLUSIVE_INSUFFICIENT_ELIGIBLE_EVENTS, small.outcome)

        val enough = matcher.aggregate((0 until 5).map { perfectRoute("r$it", 10) })
        assertEquals(50, enough.totalEligibleReferenceEvents)
        assertEquals(EventGateOutcome.PASS, enough.outcome)
        assertEquals(1.0, enough.f1!!, 1e-12)

        val missing = matcher.matchRoute(
            "bad", (0 until 10).map { ev(100.0 + it * 200.0, 200.0 + it * 200.0) }, slope6,
            (0 until 5).map { ev(100.0 + it * 200.0, 200.0 + it * 200.0) }
        )
        assertTrue(missing.requiresInvestigation) // recall 0.5 < 0.70
        val failing = matcher.aggregate((0 until 4).map { perfectRoute("r$it", 10) } + missing)
        assertEquals(50, failing.totalEligibleReferenceEvents)
        assertEquals(45.0 / 50.0, failing.recall!!, 1e-12)
        assertEquals(EventGateOutcome.PASS, failing.outcome) // exactly 0.90 is inclusive
        assertEquals(listOf("bad"), failing.routesRequiringInvestigation)

        val worse = matcher.aggregate((0 until 4).map { perfectRoute("r$it", 10) } + missing + matcher.matchRoute(
            "worse", listOf(ev(100.0, 200.0)), slope6, emptyList()
        ))
        assertEquals(EventGateOutcome.FAIL, worse.outcome)
    }

    @Test
    fun `magnitude distributions use nearest rank`() {
        val d = EventMatcher.distribution((1..20).map { it.toDouble() })!!
        assertEquals(10.0, d.median, 0.0)
        assertEquals(19.0, d.p95, 0.0)
        assertNull(EventMatcher.distribution(emptyList()))
    }
}
