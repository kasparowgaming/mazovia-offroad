package pl.mazovia.offroad.ui.riding.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.debug.NavigationStateReplayer as Replay
import pl.mazovia.offroad.domain.model.NavigationStatus
import pl.mazovia.offroad.terrain.presentation.CorridorGeometry
import pl.mazovia.offroad.terrain.presentation.SurfaceBand
import pl.mazovia.offroad.terrain.profile.FilteredElevationProfile
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.profile.RawElevationProfile
import pl.mazovia.offroad.terrain.profile.RouteTerrainProfile
import kotlin.math.abs

class RoadAheadCorridorTest {

    // --- projection (DESIGN §22.5 D: known pose → known screen points) ---

    private val camera = CorridorCamera(0f, -18f, 9f, 0f, 75f, 0f, 1000f, 2000f)

    @Test fun lookAtPointProjectsToTheAnchorAndTheAxisToTheCentreColumn() {
        val out = FloatArray(2)
        assertEquals(1050f, camera.focal, 1e-3f)
        assertTrue(camera.project(0f, 75f, 0f, out))
        assertEquals(500f, out[0], 1e-2f)
        assertEquals(2000f * CorridorTargets.LOOK_AT_Y, out[1], 1e-2f)
        // Rider point: on the centre column, below the look-at point.
        assertTrue(camera.project(0f, 0f, 0f, out))
        assertEquals(500f, out[0], 1e-2f)
        assertTrue(out[1] > camera.cy)
        // Known value: depth and vertical offset of the rider in the camera frame.
        val len = kotlin.math.sqrt(93f * 93f + 9f * 9f)
        val depth = (18f * 93f + 9f * 9f) / len
        val up = (18f * 9f - 9f * 93f) / len // (d · u) with u = (0, 9, 93) / len
        assertEquals(camera.cy - camera.focal * up / depth, out[1], 0.05f)
    }

    @Test fun eastIsRightAndHigherIsUp() {
        val a = FloatArray(2)
        val b = FloatArray(2)
        camera.project(0f, 100f, 0f, a)
        camera.project(10f, 100f, 0f, b)
        assertTrue(b[0] > a[0])
        camera.project(0f, 100f, 10f, b)
        assertTrue(b[1] < a[1])
        // Horizon above the look-at point when the camera pitches down.
        assertTrue(camera.horizonY < camera.cy)
    }

    @Test fun nothingBehindTheCameraProjects() {
        val out = floatArrayOf(-1f, -1f)
        assertFalse(camera.project(0f, -20f, 9f, out))
        assertFalse(camera.project(0f, -18f + CorridorTargets.NEAR_M * 0.5f, 9f, out))
        assertEquals(-1f, out[0], 0f)
    }

    @Test fun landscapeKeepsTheRiderOnScreen() {
        val wide = CorridorCamera(0f, -18f, 9f, 0f, 75f, 0f, 2340f, 900f)
        assertEquals(1.05f * CorridorTargets.MAX_FOCAL_ASPECT * 900f, wide.focal, 1e-2f)
        val out = FloatArray(2)
        assertTrue(wide.project(0f, 0f, 0f, out))
        assertTrue(out[1] < 900f)
    }

    // --- chase pose on real geometry ---

    private fun fixtureGrade(heights: (Int) -> Double?): GradeProfile = GradeProfile.from(
        FilteredElevationProfile.from(RawElevationProfile.fromHeights("fixture", (0..200).map(heights))), emptyList())

    @Test fun chaseCameraSitsBehindAndAboveTheRiderAndDrawsNothingBehindIt() {
        val presenter = TerrainPresenter(grade = fixtureGrade { 100.0 + it * .2 })
        val state = Replay.state(Replay.route(), 300.0, 10.0)
        val frame = presenter.onNavigationState(state, 0L)
        val geometry = presenter.corridor(frame)!!
        val rider = frame.distanceAlongM!!
        val chase = CorridorCamera.chase(geometry, rider, 1080f, 1700f)!!
        val at = FloatArray(3)
        geometry.pointAt(rider, at)
        // Route heads east: the camera is 18 m west of the rider and 9 m above the Display height there.
        assertEquals(at[0] - 18f, chase.eyeX, 0.1f)
        assertEquals(at[1], chase.eyeY, 0.1f)
        val behind = FloatArray(3)
        geometry.pointAt(rider - 18.0, behind)
        assertEquals(behind[2] + 9f, chase.eyeZ, 1e-3f)
        val out = FloatArray(2)
        for (i in 0 until geometry.size) {
            val projected = chase.project(geometry.eastAt(i), geometry.northAt(i), geometry.sceneHeightAt(i), out)
            val depth = chase.depth(geometry.eastAt(i), geometry.northAt(i), geometry.sceneHeightAt(i))
            assertEquals(depth >= CorridorTargets.NEAR_M, projected)
            if (geometry.distanceAt(i) < rider - 18.5) assertFalse("sample behind the camera", projected)
        }
    }

    @Test fun cameraExtrapolatesBehindTheRouteStart() {
        val presenter = TerrainPresenter(grade = fixtureGrade { 100.0 })
        val state = Replay.state(Replay.route(), 2.0, 10.0)
        val frame = presenter.onNavigationState(state, 0L)
        val geometry = presenter.corridor(frame)!!
        val chase = CorridorCamera.chase(geometry, frame.distanceAlongM!!, 1080f, 1700f)!!
        val start = FloatArray(3)
        geometry.pointAt(0.0, start)
        assertEquals(start[0] - 16f, chase.eyeX, 0.2f)
    }

    // --- scripted states (DESIGN §16.1 D note, §22.5 D) ---

    @Test fun onRouteGivesALiveCorridorWithHud() {
        val presenter = TerrainPresenter(grade = fixtureGrade { 100.0 + it * .4 })
        val state = Replay.state(Replay.route(), 100.0, 10.0)
        val frame = presenter.onNavigationState(state, 0L)
        val model = terrainInstrumentModel(frame, presenter.corridor(frame)) as TerrainInstrumentModel.Valid
        assertNotNull(model.corridor)
        assertTrue(model.corridor!!.hasHeights)
        assertTrue(model.gradeLabel.endsWith("%"))
        assertEquals(SurfaceBand.UNKNOWN, model.corridor!!.surfaceAt(0))
    }

    @Test fun coverageGapKeepsTheCorridorAndFlagsTheSpan() {
        val presenter = TerrainPresenter(grade = fixtureGrade { if (it in 60..80) null else 100.0 + it * .2 })
        val state = Replay.state(Replay.route(), 100.0, 10.0)
        val frame = presenter.onNavigationState(state, 0L)
        val model = terrainInstrumentModel(frame, presenter.corridor(frame)) as TerrainInstrumentModel.Valid
        val corridor = model.corridor!!
        assertTrue((0 until corridor.size).any { !corridor.isAvailable(it) })
        assertTrue((0 until corridor.size).filter { !corridor.isAvailable(it) }
            .all { abs(corridor.sceneHeightAt(it)) > 1f }) // held above the rider on a climb, never a 0 m dip
    }

    @Test fun noElevationStillShowsTheRouteCorridorFlagged() {
        val presenter = TerrainPresenter(grade = fixtureGrade { null })
        val state = Replay.state(Replay.route(), 100.0, 10.0)
        val frame = presenter.onNavigationState(state, 0L)
        val model = terrainInstrumentModel(frame, presenter.corridor(frame)) as TerrainInstrumentModel.NoData
        assertFalse(model.corridor!!.hasHeights)
        assertEquals(100.0, model.riderM!!, 1.0)
    }

    @Test fun offRouteFreezesTheLastCorridor() {
        val presenter = TerrainPresenter(grade = fixtureGrade { 100.0 })
        val script = Replay.offRoute()
        presenter.onNavigationState(script[0].state, 0L)
        val off = script[1].state
        val frame = presenter.onNavigationState(off, 1_000_000_000L)
        assertEquals(TerrainVisualMode.OFF_ROUTE, frame.mode)
        val model = terrainInstrumentModel(frame, presenter.corridor(frame)) as TerrainInstrumentModel.Detached
        assertNotNull(model.corridor)
        assertEquals(frame.distanceAlongM!!, model.riderM!!, 0.0) // the presenter's frozen progress
        assertEquals(80.0, model.distanceToRouteM!!, 1e-6)
    }

    @Test fun offRouteBeforeAnyAttachmentShowsTheCorridorAtTheNearestRoutePoint() {
        // TEREN opened while already off route: no last attached progress exists.
        val presenter = TerrainPresenter(grade = fixtureGrade { 100.0 })
        val route = Replay.route()
        val frame = presenter.onNavigationState(
            Replay.state(route, 400.0, status = NavigationStatus.OFF_ROUTE, north = 300.0)
                .copy(distanceToGpxMeters = null), 0L)
        assertEquals(TerrainVisualMode.OFF_ROUTE, frame.mode)
        val model = terrainInstrumentModel(frame, presenter.corridor(frame)) as TerrainInstrumentModel.Detached
        assertNotNull(model.corridor)
        assertEquals(400.0, model.riderM!!, 2.0)
        assertEquals(300.0, model.distanceToRouteM!!, 2.0)
        // Back on route: the first attachment still snaps to the attached progress.
        val attached = presenter.onNavigationState(Replay.state(route, 450.0), 1_000_000_000L)
        assertEquals(TerrainVisualMode.ATTACHED, attached.mode)
        assertEquals(450.0, attached.distanceAlongM!!, 2.0)
    }

    @Test fun staleAndArrivedStates() {
        val presenter = TerrainPresenter(grade = fixtureGrade { 100.0 })
        val state = Replay.state(Replay.route(), 100.0, 10.0)
        presenter.onNavigationState(state, 0L)
        val stale = presenter.frame(6_000_000_000L)
        val model = terrainInstrumentModel(stale, presenter.corridor(stale)) as TerrainInstrumentModel.Valid
        assertTrue(model.showStaleIndicator)
        assertNotNull(model.corridor)

        val arrivedPresenter = TerrainPresenter(grade = fixtureGrade { 100.0 })
        val script = Replay.arrived()
        arrivedPresenter.onNavigationState(script[0].state, 0L)
        val arrived = arrivedPresenter.onNavigationState(script[1].state, 1_000_000_000L)
        assertSame(TerrainInstrumentModel.Arrived, terrainInstrumentModel(arrived, null))
    }

    @Test fun gpxCorridorIsUnknownSurfaceWithoutManeuvers() {
        val presenter = TerrainPresenter()
        val emission = Replay.gpx()[1].state
        assertEquals(NavigationStatus.FOLLOWING_GPX, emission.status)
        val frame = presenter.onNavigationState(emission, 0L)
        val geometry = presenter.corridor(frame)!!
        assertTrue((0 until geometry.size).all { geometry.surfaceAt(it) == SurfaceBand.UNKNOWN })
        assertTrue(geometry.maneuvers.isEmpty())
        assertTrue((0 until geometry.size).any { geometry.isBreakBefore(it) } || geometry.startM >= 100.0)
        val model = terrainInstrumentModel(frame, geometry)
        assertTrue(model is TerrainInstrumentModel.NoData) // no profile installed: flat, flagged corridor
    }

    @Test fun aCorridorForAnotherProfileIsNeverShown() {
        val presenter = TerrainPresenter(grade = fixtureGrade { 100.0 })
        val state = Replay.state(Replay.route(), 100.0, 10.0)
        val frame = presenter.onNavigationState(state, 0L)
        // Same route and position, but built by a presenter without a profile.
        val other = TerrainPresenter()
        val stale = other.corridor(other.onNavigationState(state, 0L))!!
        val model = terrainInstrumentModel(frame, stale) as TerrainInstrumentModel.Valid
        assertNull(model.corridor)
    }

    @Test fun presenterReusesTheCorridorUntilTheRiderLeavesItOrTheProfileChanges() {
        val route = Replay.route()
        val presenter = TerrainPresenter()
        val first = presenter.onNavigationState(Replay.state(route, 300.0, 0.0), 0L)
        assertFalse(presenter.corridorIsCurrent(first))
        val built = presenter.corridor(first)!!
        val near = first.copy(distanceAlongM = first.distanceAlongM!! + 10.0)
        assertTrue(presenter.corridorIsCurrent(near))
        assertSame(built, presenter.corridor(near))
        val far = first.copy(distanceAlongM = first.distanceAlongM!! + 100.0)
        assertFalse(presenter.corridorIsCurrent(far))
        assertNotSame(built, presenter.corridor(far))

        val raw = RawElevationProfile.fromHeights(route.id, (0..400).map { 100.0 + it * .1 })
        presenter.setProfile(RouteTerrainProfile.from(raw))
        val withProfile = presenter.frame(3_000_000_000L)
        assertNotNull(withProfile.profile)
        assertFalse(presenter.corridorIsCurrent(withProfile))
        assertSame(withProfile.profile, presenter.corridor(withProfile)!!.display)
    }

    @Test fun aProfileArrivingLaterUpdatesTheTargetWithoutANewEmission() {
        val route = Replay.route()
        val presenter = TerrainPresenter()
        val before = presenter.onNavigationState(Replay.state(route, 300.0, 0.0), 0L)
        assertNull(before.target!!.currentGrade)
        assertEquals(route.id, presenter.profileRequest!!.routeId)
        assertSame(presenter.profileRequest, presenter.profileRequest)
        // 0.1 m per 5 m sample: a 2 % climb.
        presenter.setProfile(RouteTerrainProfile.from(
            RawElevationProfile.fromHeights(route.id, (0..400).map { 100.0 + it * .1 })))
        val after = presenter.frame(1L)
        assertEquals(0.02, after.target!!.currentGrade!!, 0.005)
        assertEquals(before.distanceAlongM!!, after.distanceAlongM!!, 1e-9)
    }
}
