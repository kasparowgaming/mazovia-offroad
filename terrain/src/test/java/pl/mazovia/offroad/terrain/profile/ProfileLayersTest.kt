package pl.mazovia.offroad.terrain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.terrain.TestFixtures
import pl.mazovia.offroad.terrain.elevation.ElevationSample
import pl.mazovia.offroad.terrain.elevation.SyntheticElevationSampler
import pl.mazovia.offroad.terrain.elevation.SyntheticProfile
import pl.mazovia.offroad.terrain.elevation.UnavailableReason
import pl.mazovia.offroad.terrain.profile.ProfileFixtures.pipeline
import pl.mazovia.offroad.terrain.profile.ProfileFixtures.raw
import pl.mazovia.offroad.terrain.profile.ProfileFixtures.snapshot
import pl.mazovia.offroad.terrain.projection.RouteIndex

class ProfileLayersTest {

    @Test
    fun `flat terrain has zero grade and no events`() {
        val p = pipeline(raw(SyntheticProfile.flat(), 1000.0))
        assertTrue(p.events.isEmpty())
        for (i in 0 until p.grade.size) p.grade.gradeAt(i)?.let { assertEquals(0.0, it, 1e-12) }
    }

    @Test
    fun `constant plus and minus 10 percent give one event each`() {
        val up = pipeline(raw(SyntheticProfile.Builder().slope(0.10).build(), 1000.0))
        assertEquals(0.10, up.grade.gradeAt(100)!!, 1e-9)
        assertEquals(1, up.events.size)
        assertEquals(GradeEventType.CLIMB, up.events[0].type)
        assertEquals(GradeEventClass.STANDARD, up.events[0].eventClass)
        val down = pipeline(raw(SyntheticProfile.Builder().slope(-0.10).build(), 1000.0))
        assertEquals(-0.10, down.grade.gradeAt(100)!!, 1e-9)
        assertEquals(1, down.events.size)
        assertEquals(GradeEventType.DESCENT, down.events[0].type)
    }

    @Test
    fun `explicit unavailable span stays unavailable and is never zero`() {
        val profile = SyntheticProfile.Builder(120.0).slope(0.08).unavailable(400.0, 450.0).build()
        val p = pipeline(raw(profile, 1000.0))
        for (i in 80..90) {
            assertFalse(p.raw.isAvailable(i))
            assertNull(p.raw.heightAt(i))
            assertEquals(UnavailableReason.NODATA, p.raw.unavailableReasonAt(i))
            assertNull(p.filtered.heightAt(i))
            assertNull(p.grade.gradeAt(i))
            assertEquals(GradeStatus.UNAVAILABLE_DATA, p.grade.statusAt(i))
        }
        // Filtered gaps widen by at most 4 samples per side, never bridged.
        assertTrue(p.filtered.isAvailable(75))
        assertFalse(p.filtered.isAvailable(76))
        assertFalse(p.filtered.isAvailable(94))
        assertTrue(p.filtered.isAvailable(95))
        // Missing data splits the climb into two events that never cover the gap.
        assertEquals(2, p.events.size)
        assertTrue(p.events.all { it.endDistanceM < 400.0 || it.startDistanceM > 450.0 })
    }

    @Test
    fun `raw profile is not mutated by filtering, anomaly detection, grade, events or display`() {
        val profile = SyntheticProfile.Builder().ramp(300.0, 40.0, 0.12).spike(600.0, 1.0, 3.0).step(800.0, 2.0)
            .unavailable(900.0, 920.0).reducedConfidence(100.0, 150.0, 0.6f).build()
        val r = raw(profile, 1200.0)
        val before = snapshot(r)
        val p = pipeline(r)
        DisplayElevationProfile.from(p.filtered)
        FilteredElevationProfile.from(r)
        assertEquals(before, snapshot(r))
        assertEquals(100.0 + 0.12 * 40.0 + 3.0, r.heightAt(120)!!, 1e-9) // the spike is still in raw
    }

    @Test
    fun `filtering is deterministic and traceable`() {
        val r = raw(SyntheticProfile.Builder().ramp(200.0, 40.0, 0.12).hill(600.0, 80.0, 4.0).build(), 1000.0)
        val a = FilteredElevationProfile.from(r)
        val b = FilteredElevationProfile.from(r)
        for (i in 0 until a.size) {
            assertEquals(a.heightAt(i), b.heightAt(i))
            assertEquals(a.rawSpanAt(i), b.rawSpanAt(i))
        }
        assertEquals(96..104, a.rawSpanAt(100))
        assertEquals(0..0, a.rawSpanAt(0))
        assertEquals(0..4, a.rawSpanAt(1)) // average radius 1 over medians with radii 0, 1, 2
        assertEquals("FilterConfig-v1", a.config.id)
        val g = GradeProfile.from(a, ProfileAnomalyDetector().detect(r))
        assertEquals(93..107, g.rawSpanAt(100))
        assertEquals("FilterConfig-v1", g.configId)
    }

    @Test
    fun `anomaly metadata lowers confidence but does not suppress a real short hill`() {
        // 4 m raised-cosine hill over 40 m: flagged as a suspected bump, but its climb and descent remain events.
        val p = pipeline(raw(SyntheticProfile.Builder().hill(500.0, 40.0, 4.0).build(), 1000.0))
        assertTrue(p.anomalies.any { it.reason == AnomalyReason.SHORT_BUMP && it.kind == AnomalyKind.SUSPECTED_PROFILE_ANOMALY })
        assertTrue(p.anomalies.none { it.reason.name.contains("BRIDGE") || it.reason.name.contains("TUNNEL") })
        assertTrue(p.events.any { it.type == GradeEventType.CLIMB })
        assertTrue(p.events.any { it.type == GradeEventType.DESCENT })
        assertTrue(p.events.all { it.confidence <= 0.5f })
    }

    @Test
    fun `display profile is derived from filtered heights only`() {
        val p = pipeline(raw(SyntheticProfile.Builder(150.0).slope(0.05).build(), 500.0))
        val d = DisplayElevationProfile.from(p.filtered)
        assertEquals(p.filtered.heightAt(0)!!, d.baselineM!!, 1e-9)
        assertEquals((p.filtered.heightAt(50)!! - d.baselineM!!).toFloat(), d.relativeHeightAt(50)!!, 1e-4f)
        assertEquals("FilterConfig-v1", d.filteredConfigId)
    }

    @Test
    fun `sampling along a route index reproduces the synthetic surface`() {
        val route = TestFixtures.calculatedRoute("east", TestFixtures.line(0.0, 0.0, 1000.0, 0.0), segmentLength = 13)
        val index = RouteIndex.build(route)
        val surface = SyntheticProfile.Builder(100.0).ramp(300.0, 100.0, 0.08).build()
        val sampler = SyntheticElevationSampler(TestFixtures.ORIGIN, surface)
        val r = RawElevationProfile.sample(index, sampler)
        assertEquals(RawElevationProfile.DEFAULT_SPACING_M, r.spacingM, 0.0)
        for (i in 0 until r.size) {
            assertEquals(surface.heightAt(r.distanceAt(i))!!, r.heightAt(i)!!, 0.01)
        }
        assertTrue(sampler.sample(TestFixtures.at(350.0).latitude, TestFixtures.at(350.0).longitude) is ElevationSample.Value)
    }

    @Test
    fun `reduced sampler confidence propagates as a low-confidence span and into grade`() {
        val profile = SyntheticProfile.Builder().slope(0.06).reducedConfidence(300.0, 400.0, 0.6f).build()
        val p = pipeline(raw(profile, 1000.0))
        assertTrue(p.anomalies.any { it.kind == AnomalyKind.LOW_CONFIDENCE_SPAN })
        assertEquals(0.6f, p.grade.confidenceAt(70), 1e-6f)
        assertEquals(1f, p.grade.confidenceAt(150), 1e-6f)
        assertTrue(p.events.single().confidence <= 0.6f)
    }
}
