package pl.mazovia.offroad.terrain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.terrain.TestFixtures
import pl.mazovia.offroad.terrain.TestFixtures.line
import pl.mazovia.offroad.terrain.elevation.SyntheticElevationSampler
import pl.mazovia.offroad.terrain.elevation.SyntheticProfile
import pl.mazovia.offroad.terrain.projection.RouteIndex

/**
 * SR-001 (TA-001A-F1): a GPX segment break has zero length on the navigation axis but is a hard profile boundary.
 * Surfaces are functions of the east coordinate, so the geographic gap produces a real height jump on the axis.
 */
class ProfileBreakTest {

    private fun pipelineFor(route: Route, surface: SyntheticProfile): ProfileFixtures.Pipeline {
        val raw = RawElevationProfile.sample(RouteIndex.build(route), SyntheticElevationSampler(TestFixtures.ORIGIN, surface))
        return ProfileFixtures.pipeline(raw)
    }

    /** Every derived sample and event stays inside one continuous part. */
    private fun assertNothingCrossesBreaks(p: ProfileFixtures.Pipeline) {
        for (i in 0 until p.filtered.size) {
            val span = p.filtered.rawSpanAt(i)
            assertEquals("filtered $i", p.raw.partIndexAt(i), p.raw.partIndexAt(span.first))
            assertEquals("filtered $i", p.raw.partIndexAt(i), p.raw.partIndexAt(span.last))
            if (p.grade.isAvailable(i)) {
                val g = p.grade.rawSpanAt(i)
                assertEquals("grade $i", p.raw.partIndexAt(g.first), p.raw.partIndexAt(g.last))
            }
        }
        for (e in p.events) {
            assertEquals("event $e", p.raw.partIndexAt(e.startIndex), p.raw.partIndexAt(e.endIndex))
            assertEquals("event raw span $e", p.raw.partIndexAt(e.rawSpan.first), p.raw.partIndexAt(e.rawSpan.last))
        }
        for (a in p.anomalies) assertEquals("anomaly $a", p.raw.partIndexAt(a.startIndex), p.raw.partIndexAt(a.endIndex))
    }

    private val gapped = TestFixtures.gpxRoute("gap", listOf(line(0.0, 0.0, 500.0, 0.0), line(800.0, 0.0, 1300.0, 0.0)))

    @Test
    fun `A - geographic gap with a 15 m height difference produces no false event`() {
        // Flat 100 m before the gap, flat 115 m after it: the only height change is inside the 300 m gap.
        val p = pipelineFor(gapped, SyntheticProfile.Builder(100.0).step(650.0, 15.0).build())
        assertEquals(1, p.raw.breaks.size)
        val b = p.raw.breaks.single()
        assertEquals(500.0, b.distanceBeforeM, 1.0)
        assertEquals(b.distanceBeforeM, b.distanceAfterM, 1e-9) // same s on both sides
        assertEquals(300.0, b.geographicGapM!!, 1.0)
        assertEquals(100.0, p.raw.heightAt(b.beforeIndex)!!, 1e-9) // both raw sides preserved
        assertEquals(115.0, p.raw.heightAt(b.afterIndex)!!, 1e-9)
        assertNothingCrossesBreaks(p)
        assertTrue("no event from the gap: ${p.events}", p.events.isEmpty())
        assertTrue(p.anomalies.none { it.reason == AnomalyReason.STEP })
        // Grade whose 25 m window would cross the break is unavailable, not flat and not steep.
        for (i in 0 until p.grade.size) {
            val s = p.grade.distanceAt(i)
            val nearBreak = (p.raw.partIndexAt(i) == 0 && s > 500.0 - 12.5 + 1e-6) || (p.raw.partIndexAt(i) == 1 && s < 500.0 + 12.5 - 1e-6)
            if (nearBreak) assertEquals("sample $i s=$s", GradeStatus.ACROSS_BREAK, p.grade.statusAt(i))
            p.grade.gradeAt(i)?.let { assertEquals(0.0, it, 1e-9) }
        }
        // Display consumers see the discontinuity.
        val d = DisplayElevationProfile.from(p.filtered)
        assertTrue(d.isBreakAfter(b.beforeIndex))
        assertFalse(d.isBreakAfter(b.beforeIndex - 1))
    }

    @Test
    fun `A - 5 percent terrain on both sides gives two real climbs and no gap cliff`() {
        val p = pipelineFor(gapped, SyntheticProfile.Builder(100.0).slope(0.05).build())
        val b = p.raw.breaks.single()
        assertEquals(125.0, p.raw.heightAt(b.beforeIndex)!!, 0.01)
        assertEquals(140.0, p.raw.heightAt(b.afterIndex)!!, 0.01)
        assertNothingCrossesBreaks(p)
        assertEquals(2, p.events.size)
        assertTrue(p.events.all { it.type == GradeEventType.CLIMB && it.eventClass == GradeEventClass.STANDARD })
        assertTrue("max grade must stay ~5 %: ${p.events.map { it.maxGrade }}", p.events.all { it.maxGrade < 0.051 })
        assertTrue(p.events[0].endDistanceM <= 500.0 && p.events[1].startDistanceM >= 500.0)
        assertTrue(p.events.none { it.eventClass == GradeEventClass.SHORT })
    }

    @Test
    fun `B - identical coordinates at a GPX segment boundary are still a break`() {
        val route = TestFixtures.gpxRoute("same", listOf(line(0.0, 0.0, 500.0, 0.0), line(500.0, 0.0, 1000.0, 0.0)))
        val index = RouteIndex.build(route)
        val boundary = route.segments[0].points.size
        assertEquals(route.allPoints[boundary - 1], route.allPoints[boundary])
        assertFalse(index.edges.any { it.fromPointIndex == boundary - 1 && it.toPointIndex == boundary })
        assertEquals(2, index.parts.size)
        val p = pipelineFor(route, SyntheticProfile.Builder(100.0).slope(0.05).build())
        val b = p.raw.breaks.single()
        assertEquals(0.0, b.geographicGapM!!, 1e-6)
        assertNothingCrossesBreaks(p)
        assertEquals(GradeStatus.ACROSS_BREAK, p.grade.statusAt(b.beforeIndex))
        assertEquals(GradeStatus.ACROSS_BREAK, p.grade.statusAt(b.afterIndex))
        assertEquals(2, p.events.size) // continuous terrain, but events never span the break
    }

    @Test
    fun `C - real climbs on both sides of the break are detected independently`() {
        // Part 1: climb over its last 150 m; part 2: climb over its first 150 m. On the axis they are adjacent
        // (s 450..600 and 600..750); without the break they would merge into one event.
        val route = TestFixtures.gpxRoute("c", listOf(line(0.0, 0.0, 600.0, 0.0), line(900.0, 0.0, 1500.0, 0.0)))
        val p = pipelineFor(route, SyntheticProfile.Builder(100.0).ramp(450.0, 150.0, 0.08).ramp(900.0, 150.0, 0.08).build())
        assertNothingCrossesBreaks(p)
        assertEquals(2, p.events.size)
        assertTrue(p.events.all { it.type == GradeEventType.CLIMB })
        assertTrue(p.events[0].endDistanceM <= 600.0 + 1e-6)
        assertTrue(p.events[1].startDistanceM >= 600.0 - 1e-6)
        assertTrue(p.raw.partIndexAt(p.events[0].startIndex) != p.raw.partIndexAt(p.events[1].startIndex))
    }

    @Test
    fun `filters never use samples from the other side of a direct break`() {
        // Two parts built directly: 20 samples at 100 m then 20 samples at 130 m.
        val raw = RawElevationProfile.fromHeights("direct", List(20) { 100.0 } + List(20) { 130.0 }, partIndices = List(20) { 0 } + List(20) { 1 })
        val p = ProfileFixtures.pipeline(raw)
        assertEquals(1, raw.breaks.size)
        assertEquals(100.0, p.filtered.heightAt(19)!!, 1e-12)
        assertEquals(130.0, p.filtered.heightAt(20)!!, 1e-12)
        assertTrue(p.events.isEmpty())
        assertNothingCrossesBreaks(p)
    }

    @Test
    fun `calculated route keeps a single part and its end sample`() {
        val route = TestFixtures.calculatedRoute("calc", line(0.0, 0.0, 1003.0, 0.0), segmentLength = 7)
        val index = RouteIndex.build(route)
        val raw = RawElevationProfile.sample(index, SyntheticElevationSampler(TestFixtures.ORIGIN, SyntheticProfile.flat()))
        assertEquals(1, index.parts.size)
        assertTrue(raw.breaks.isEmpty())
        assertEquals(index.totalLengthM, raw.distanceAt(raw.size - 1), 1e-9)
        assertEquals(1000.0, raw.distanceAt(raw.size - 2), 1e-6)
    }
}
