package pl.mazovia.offroad.terrain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.terrain.elevation.SyntheticProfile
import pl.mazovia.offroad.terrain.profile.ProfileFixtures.pipeline
import pl.mazovia.offroad.terrain.profile.ProfileFixtures.raw
import pl.mazovia.offroad.terrain.profile.ProfileFixtures.sections

class GradeEventDetectorTest {

    @Test
    fun `no event on flat terrain`() {
        assertTrue(pipeline(raw(SyntheticProfile.flat(), 1000.0)).events.isEmpty())
    }

    @Test
    fun `standard climb and descent`() {
        val up = pipeline(raw(sections(300.0 to 0.0, 200.0 to 0.06, 300.0 to 0.0), 800.0)).events.single()
        assertEquals(GradeEventType.CLIMB, up.type)
        assertEquals(GradeEventClass.STANDARD, up.eventClass)
        val down = pipeline(raw(sections(300.0 to 0.0, 200.0 to -0.06, 300.0 to 0.0), 800.0)).events.single()
        assertEquals(GradeEventType.DESCENT, down.type)
        assertEquals(GradeEventClass.STANDARD, down.eventClass)
        assertEquals(-0.06, down.maxGrade, 1e-9)
    }

    @Test
    fun `short-steep climb and descent are SHORT events`() {
        val up = pipeline(raw(SyntheticProfile.Builder().ramp(400.0, 25.0, 0.15).build(), 1000.0)).events.single()
        assertEquals(GradeEventType.CLIMB, up.type)
        assertEquals(GradeEventClass.SHORT, up.eventClass)
        assertTrue(up.lengthM < 50.0)
        val down = pipeline(raw(SyntheticProfile.Builder().ramp(400.0, 25.0, -0.15).build(), 1000.0)).events.single()
        assertEquals(GradeEventType.DESCENT, down.type)
        assertEquals(GradeEventClass.SHORT, down.eventClass)
    }

    @Test
    fun `span below the short-steep rule and shorter than 50 m is not an event`() {
        // +5 % over 30 m: never reaches 7 % and the 4 % span is < 50 m.
        assertTrue(pipeline(raw(SyntheticProfile.Builder().ramp(400.0, 30.0, 0.05).build(), 1000.0)).events.isEmpty())
    }

    @Test
    fun `start threshold is 4 percent`() {
        assertTrue(pipeline(raw(sections(200.0 to 0.0, 300.0 to 0.039, 200.0 to 0.0), 700.0)).events.isEmpty())
        assertEquals(1, pipeline(raw(sections(200.0 to 0.0, 300.0 to 0.041, 200.0 to 0.0), 700.0)).events.size)
    }

    @Test
    fun `hysteresis keeps the event through 3 percent and ends below 2_5 percent`() {
        val p = pipeline(raw(sections(200.0 to 0.0, 150.0 to 0.05, 150.0 to 0.03, 200.0 to 0.01), 700.0))
        val e = p.events.single()
        assertEquals(200.0, e.startDistanceM, 15.0)
        assertEquals(500.0, e.endDistanceM, 15.0)
        // A 3 % section alone never starts an event.
        assertTrue(pipeline(raw(sections(200.0 to 0.0, 300.0 to 0.03, 200.0 to 0.0), 700.0)).events.isEmpty())
    }

    @Test
    fun `event boundaries and magnitudes are consistent`() {
        val e = pipeline(raw(SyntheticProfile.Builder().ramp(400.0, 100.0, 0.08).build(), 1000.0)).events.single()
        assertEquals(400.0, e.startDistanceM, 15.0)
        assertEquals(500.0, e.endDistanceM, 15.0)
        assertEquals(e.endDistanceM - e.startDistanceM, e.lengthM, 1e-9)
        assertEquals(e.elevationChangeM / e.lengthM, e.averageGrade, 1e-12)
        assertEquals(0.08, e.maxGrade, 1e-9)
        assertTrue(e.rawSpan.first <= e.startIndex && e.rawSpan.last >= e.endIndex)
        assertEquals("GradeEvents-v1/FilterConfig-v1", e.configId)
    }

    @Test
    fun `isolated spike produces no event`() {
        assertTrue(pipeline(raw(SyntheticProfile.Builder().spike(500.0, 1.0, 3.0).build(), 1000.0)).events.isEmpty())
    }

    @Test
    fun `missing data splits events`() {
        val p = pipeline(raw(SyntheticProfile.Builder().slope(0.06).unavailable(495.0, 505.0).build(), 1000.0))
        assertEquals(2, p.events.size)
        assertTrue(p.events[0].endDistanceM < 495.0 && p.events[1].startDistanceM > 505.0)
    }

    @Test
    fun `confidence propagates from raw samples and suspected anomalies`() {
        val low = pipeline(raw(SyntheticProfile.Builder().ramp(400.0, 100.0, 0.08).reducedConfidence(420.0, 440.0, 0.7f).build(), 1000.0))
        assertEquals(0.7f, low.events.single().confidence, 1e-6f)
        val suspected = pipeline(raw(SyntheticProfile.Builder().ramp(400.0, 100.0, 0.08).spike(450.0, 1.0, 3.0).build(), 1000.0))
        assertTrue(suspected.anomalies.any { it.reason == AnomalyReason.ISOLATED_SPIKE })
        assertEquals(0.5f, suspected.events.single().confidence, 1e-6f)
    }
}
