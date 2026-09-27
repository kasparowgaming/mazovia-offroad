package pl.mazovia.offroad.terrain.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.domain.model.HighwayType
import pl.mazovia.offroad.domain.model.Maneuver
import pl.mazovia.offroad.domain.model.ManeuverType
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RouteSegment
import pl.mazovia.offroad.domain.model.Surface
import pl.mazovia.offroad.terrain.TestFixtures
import pl.mazovia.offroad.terrain.geo.LocalFrame
import pl.mazovia.offroad.terrain.profile.DisplayElevationProfile
import pl.mazovia.offroad.terrain.profile.EventDetectorConfig
import pl.mazovia.offroad.terrain.profile.FilteredElevationProfile
import pl.mazovia.offroad.terrain.profile.GradeEvent
import pl.mazovia.offroad.terrain.profile.GradeEventClass
import pl.mazovia.offroad.terrain.profile.GradeEventDetector
import pl.mazovia.offroad.terrain.profile.GradeEventType
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.profile.RawElevationProfile
import pl.mazovia.offroad.terrain.projection.RouteIndex
import kotlin.math.abs

class CorridorGeometryTest {

    /** Straight route due east, [lengthM] long; `s` ≈ east metres. */
    private fun straight(id: String = "straight", lengthM: Double = 2000.0): Route =
        TestFixtures.calculatedRoute(id, TestFixtures.line(0.0, 0.0, lengthM, 0.0))

    /** Heights on the 5 m profile grid for a route of [lengthM]; null = unavailable. */
    private fun layers(id: String, lengthM: Double, height: (Double) -> Double?): Triple<GradeProfile,
        DisplayElevationProfile, List<pl.mazovia.offroad.terrain.profile.GradeEvent>> {
        val raw = RawElevationProfile.fromHeights(id, (0..(lengthM / 5.0).toInt()).map { height(it * 5.0) })
        val grade = GradeProfile.from(FilteredElevationProfile.from(raw), emptyList())
        return Triple(grade, DisplayElevationProfile.from(grade.filtered), GradeEventDetector().detect(grade))
    }

    /** Flat, then a 6 % climb over 600–900 m, then flat. */
    private fun climb(s: Double): Double = 100.0 + 0.06 * (s.coerceIn(600.0, 900.0) - 600.0)

    @Test fun planPointsAgreeWithTheRouteAxis() {
        val route = straight()
        val index = RouteIndex.build(route)
        val (_, display, events) = layers(route.id, index.totalLengthM, ::climb)
        val geometry = CorridorGeometry.build(CorridorRoute.build(route, index), display, events, 300.0)!!

        assertTrue(geometry.startM <= 300.0 - CorridorGeometry.BEHIND_M)
        assertTrue(geometry.endM >= 300.0 + CorridorGeometry.AHEAD_M)
        assertTrue(geometry.covers(300.0, index.totalLengthM))
        val frame = LocalFrame(index.locate(300.0)!!.projected)
        for (i in 0 until geometry.size) {
            val d = geometry.distanceAt(i)
            if (i < geometry.size - 1) assertEquals(0.0, d % CorridorGeometry.STEP_M, 1e-9)
            val p = index.locate(d)!!.projected
            assertEquals(frame.eastM(p), geometry.eastAt(i).toDouble(), 1e-3)
            assertEquals(frame.northM(p), geometry.northAt(i).toDouble(), 1e-3)
            // Straight route due east: plan east offset equals the axis offset from the rider.
            assertEquals(d - 300.0, geometry.eastAt(i).toDouble(), 0.05)
        }
        // Samples stay on the same route points when the rider moves (fixed grid).
        val moved = CorridorGeometry.build(CorridorRoute.build(route, index), display, events, 301.5)!!
        assertTrue((0 until moved.size).any { abs(moved.distanceAt(it) - geometry.distanceAt(10)) < 1e-9 })
    }

    @Test fun windowClampsToTheRouteEnds() {
        val route = straight(lengthM = 400.0)
        val index = RouteIndex.build(route)
        val (_, display, events) = layers(route.id, index.totalLengthM, ::climb)
        val geometry = CorridorGeometry.build(CorridorRoute.build(route, index), display, events, 5.0)!!
        assertEquals(0.0, geometry.startM, 1e-9)
        assertEquals(index.totalLengthM, geometry.endM, 1e-9)
        assertTrue(geometry.covers(5.0, index.totalLengthM))
        assertFalse(geometry.covers(5.0 + CorridorGeometry.REBUILD_M + 1.0, index.totalLengthM))
    }

    @Test fun heightsAreDisplayHeightsRelativeToTheRiderTimesExaggeration() {
        val route = straight()
        val index = RouteIndex.build(route)
        val (_, display, events) = layers(route.id, index.totalLengthM, ::climb)
        val geometry = CorridorGeometry.build(CorridorRoute.build(route, index), display, events, 300.0, 2.5f)!!
        val rider = geometry.indexAtOrBefore(300.0)
        assertEquals(0f, geometry.sceneHeightAt(rider), 1e-3f)
        val top = geometry.indexAtOrBefore(900.0)
        // 18 m climb in Display heights (filtering smooths the corners, not the total) × 2.5.
        assertEquals(18f * 2.5f, geometry.sceneHeightAt(top), 1.5f)
        assertTrue(geometry.hasHeights)
    }

    @Test fun exaggerationNeverReachesGradeOrEvents() {
        val route = straight()
        val index = RouteIndex.build(route)
        val (grade, display, events) = layers(route.id, index.totalLengthM, ::climb)
        val gradesBefore = (0 until grade.size).map { grade.gradeAt(it) }
        val displayBefore = (0 until display.size).map { display.relativeHeightAt(it) }
        val eventsBefore = events.toList()
        val corridorRoute = CorridorRoute.build(route, index)

        val x1 = CorridorGeometry.build(corridorRoute, display, events, 300.0, 1f)!!
        val x4 = CorridorGeometry.build(corridorRoute, display, events, 300.0, 4f)!!

        for (i in 0 until x1.size) {
            assertEquals(x1.sceneHeightAt(i) * 4f, x4.sceneHeightAt(i), 1e-3f)
            assertEquals(x1.gradeBandAt(i), x4.gradeBandAt(i))
            assertEquals(x1.slopeAt(i), x4.slopeAt(i), 0f)
        }
        assertEquals(x1.eventStarts, x4.eventStarts)
        assertEquals(gradesBefore, (0 until grade.size).map { grade.gradeAt(it) })
        assertEquals(displayBefore, (0 until display.size).map { display.relativeHeightAt(it) })
        assertEquals(eventsBefore, events)
        // The 6 % climb is a CLIMB band inside the event and nothing on the flat before it.
        assertEquals(GradeBand.CLIMB, x1.gradeBandAt(x1.indexAtOrBefore(750.0)))
        assertEquals(GradeBand.NONE, x1.gradeBandAt(x1.indexAtOrBefore(400.0)))
        assertEquals(0.06f, x1.slopeAt(x1.indexAtOrBefore(750.0)), 0.005f)
    }

    @Test fun coverageGapIsFlaggedHeldAndNeverZero() {
        val route = straight()
        val index = RouteIndex.build(route)
        // Unavailable across 700–800 m, in the middle of the climb.
        val (_, display, events) = layers(route.id, index.totalLengthM) { s ->
            if (s in 700.0..800.0) null else climb(s)
        }
        val geometry = CorridorGeometry.build(CorridorRoute.build(route, index), display, events, 300.0)!!
        val gap = (0 until geometry.size).filter { !geometry.isAvailable(it) }
        assertTrue(gap.isNotEmpty())
        // The filter windows widen the raw gap by up to ~25 m on each side.
        assertTrue(gap.all { geometry.distanceAt(it) > 670.0 && geometry.distanceAt(it) < 830.0 })
        val before = geometry.sceneHeightAt(gap.first() - 1)
        assertTrue("held height above the rider, not a 0 m dip", before > 1f)
        gap.forEach {
            assertEquals(before, geometry.sceneHeightAt(it), 1e-4f)
            assertEquals(GradeBand.NONE, geometry.gradeBandAt(it))
            assertTrue(geometry.slopeAt(it).isNaN())
        }
        // No event spans the gap.
        assertTrue(events.none { it.startDistanceM < 750.0 && it.endDistanceM > 750.0 })
    }

    @Test fun windowWithoutAnyHeightIsFlatAndFlagged() {
        val route = straight()
        val index = RouteIndex.build(route)
        val (_, display, events) = layers(route.id, index.totalLengthM) { null }
        val geometry = CorridorGeometry.build(CorridorRoute.build(route, index), display, events, 300.0)!!
        assertFalse(geometry.hasHeights)
        assertTrue((0 until geometry.size).none { geometry.isAvailable(it) })
        assertTrue((0 until geometry.size).all { geometry.gradeBandAt(it) == GradeBand.NONE })
        val noProfile = CorridorGeometry.build(CorridorRoute.build(route, index), null, emptyList(), 300.0)!!
        assertFalse(noProfile.hasHeights)
        assertNull(noProfile.display)
    }

    @Test fun gpxBreakSplitsTheRibbonAndUsesEachPartsHeights() {
        // Two GPX parts; the second starts 200 m north (a gap the axis does not count).
        val route = TestFixtures.gpxRoute("gpx", listOf(
            TestFixtures.line(0.0, 0.0, 500.0, 0.0),
            TestFixtures.line(500.0, 200.0, 1200.0, 200.0)))
        val index = RouteIndex.build(route)
        val parts = index.parts
        assertEquals(2, parts.size)
        val breakS = parts[1].startS
        val raw = RawElevationProfile.sample(index, object : pl.mazovia.offroad.terrain.elevation.ElevationSampler {
            override val metadata = pl.mazovia.offroad.terrain.elevation.ElevationSourceMetadata(
                "two-level", null, "synthetic", "100 m south of the gap, 150 m north of it")
            override fun sample(latitude: Double, longitude: Double) =
                pl.mazovia.offroad.terrain.elevation.ElevationSample.Value(
                    if (latitude > TestFixtures.at(0.0, 100.0).latitude) 150.0 else 100.0, 1f)
        })
        val grade = GradeProfile.from(FilteredElevationProfile.from(raw), emptyList())
        val display = DisplayElevationProfile.from(grade.filtered)
        val geometry = CorridorGeometry.build(CorridorRoute.build(route, index), display,
            GradeEventDetector().detect(grade), breakS - 100.0, 1f)!!
        val breaks = (0 until geometry.size).filter { geometry.isBreakBefore(it) }
        assertEquals(1, breaks.size)
        val b = breaks.single()
        assertEquals(breakS, geometry.distanceAt(b), 1e-6)
        assertEquals(0f, geometry.sceneHeightAt(b - 1), 1e-3f)
        assertEquals(50f, geometry.sceneHeightAt(b), 1e-3f)
        assertTrue((0 until geometry.size).all { geometry.surfaceAt(it) == SurfaceBand.UNKNOWN })
        assertTrue(geometry.maneuvers.isEmpty())
    }

    @Test fun surfaceBandsAndManeuversSitOnTheAxis() {
        val first = TestFixtures.line(0.0, 0.0, 600.0, 0.0)
        val second = TestFixtures.line(600.0, 0.0, 600.0, 800.0)
        fun segment(points: List<pl.mazovia.offroad.domain.model.GeoPoint>, surface: Surface) = RouteSegment(
            points, points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }, surface, HighwayType.TRACK)
        val route = TestFixtures.calculatedRoute("turn", TestFixtures.path(first, second)).copy(
            segments = listOf(segment(first, Surface.ASPHALT), segment(second, Surface.SAND)),
            maneuvers = listOf(
                Maneuver(TestFixtures.at(0.0), ManeuverType.DEPART, 0.0),
                Maneuver(TestFixtures.at(600.0), ManeuverType.TURN_LEFT, 600.0),
                Maneuver(TestFixtures.at(600.0, 800.0), ManeuverType.ARRIVE, 800.0)))
        val index = RouteIndex.build(route)
        val corridorRoute = CorridorRoute.build(route, index)
        assertEquals(listOf(ManeuverType.TURN_LEFT, ManeuverType.ARRIVE), corridorRoute.maneuvers.map { it.type })
        val turn = corridorRoute.maneuvers.first()
        assertEquals(600.0, turn.distanceM, 0.5)

        val geometry = CorridorGeometry.build(corridorRoute, null, emptyList(), 300.0)!!
        assertEquals(SurfaceBand.PAVED, geometry.surfaceAt(geometry.indexAtOrBefore(500.0)))
        assertEquals(SurfaceBand.SAND, geometry.surfaceAt(geometry.indexAtOrBefore(700.0)))
        val change = geometry.surfaceChanges.single()
        assertEquals(SurfaceBand.SAND, change.band)
        assertEquals(600.0, change.distanceM, CorridorGeometry.STEP_M + 1e-6)
        assertNotNull(geometry.maneuvers.firstOrNull { it.type == ManeuverType.TURN_LEFT })
        // After the turn the corridor heads north: plan points leave the east axis.
        val after = geometry.indexAtOrBefore(800.0)
        assertEquals(300.0, geometry.eastAt(after).toDouble(), 0.5)
        assertEquals(geometry.distanceAt(after) - 600.0, geometry.northAt(after).toDouble(), 0.5)
    }

    @Test fun oneSteepnessRuleForEdgesAndFlags() {
        fun event(type: GradeEventType, eventClass: GradeEventClass, average: Double) = GradeEvent(type, eventClass,
            0.0, 100.0, 100.0, average, average, average * 100, 1f, 0, 10, 0..10, "test")
        val steep = EventDetectorConfig().shortSteepGrade
        assertEquals(GradeBand.CLIMB, CorridorGeometry.gradeBandOf(
            event(GradeEventType.CLIMB, GradeEventClass.STANDARD, steep - 0.01)))
        assertEquals(GradeBand.DESCENT, CorridorGeometry.gradeBandOf(
            event(GradeEventType.DESCENT, GradeEventClass.STANDARD, -(steep - 0.01))))
        assertEquals(GradeBand.STEEP, CorridorGeometry.gradeBandOf(
            event(GradeEventType.DESCENT, GradeEventClass.STANDARD, -steep)))
        // A SHORT event is steep by class even when its average grade is below the threshold.
        assertEquals(GradeBand.STEEP, CorridorGeometry.gradeBandOf(
            event(GradeEventType.CLIMB, GradeEventClass.SHORT, 0.05)))
    }
}
