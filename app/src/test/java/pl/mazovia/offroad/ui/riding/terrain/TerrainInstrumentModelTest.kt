package pl.mazovia.offroad.ui.riding.terrain

import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.debug.NavigationStateReplayer as Replay
import pl.mazovia.offroad.terrain.presentation.CorridorGeometry
import pl.mazovia.offroad.terrain.presentation.CorridorRoute
import pl.mazovia.offroad.terrain.profile.DisplayElevationProfile
import pl.mazovia.offroad.terrain.profile.FilteredElevationProfile
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.profile.RawElevationProfile
import pl.mazovia.offroad.terrain.projection.RouteIndex

class TerrainInstrumentModelTest {
    private fun grade(available: Boolean = true): GradeProfile {
        val heights = (0..200).map { if (available) 100.0 + it * .2 else null }
        return GradeProfile.from(FilteredElevationProfile.from(
            RawElevationProfile.fromHeights("fixture", heights)), emptyList())
    }

    private fun frame(mode: TerrainVisualMode, profile: DisplayElevationProfile? = null,
                      stale: Boolean = false, indicator: Boolean = false, distance: Double? = null) =
        TerrainDisplayState(null, mode, 100.0, 50.0, 700.0, distance,
            stale, indicator, false, null, profile)

    @Test fun defaultAndNavigationPrecedence() {
        assertEquals(RidingViewMode.MAPA, RidingViewMode.entries.first())
        assertSame(TerrainInstrumentModel.NoRoute, terrainInstrumentModel(frame(TerrainVisualMode.IDLE)))
        assertSame(TerrainInstrumentModel.Arrived,
            terrainInstrumentModel(frame(TerrainVisualMode.ARRIVED, stale = true, indicator = true)))
        val detached = terrainInstrumentModel(frame(TerrainVisualMode.OFF_ROUTE,
            stale = true, indicator = true, distance = 42.0)) as TerrainInstrumentModel.Detached
        assertEquals(42.0, detached.distanceToRouteM!!, .01)
        assertNull((terrainInstrumentModel(frame(TerrainVisualMode.OFF_ROUTE)) as
            TerrainInstrumentModel.Detached).distanceToRouteM)
    }

    @Test fun noDataNeverTurnsUnavailableElevationIntoZero() {
        assertTrue(terrainInstrumentModel(frame(TerrainVisualMode.ATTACHED)) is TerrainInstrumentModel.NoData)
        val missing = DisplayElevationProfile.from(grade(false).filtered)
        assertTrue(terrainInstrumentModel(frame(TerrainVisualMode.ATTACHED, missing)) is TerrainInstrumentModel.NoData)
        assertTrue((0 until missing.size).all { missing.relativeHeightAt(it) == null })
    }

    @Test fun validAndHeldUsePresenterSnapshot() {
        val presenter = TerrainPresenter(grade = grade())
        val route = Replay.route()
        val attached = presenter.onNavigationState(Replay.state(route, 100.0, 10.0), 0L)
        val valid = terrainInstrumentModel(attached) as TerrainInstrumentModel.Valid
        assertTrue(valid.gradeLabel.isNotBlank())
        val held = presenter.onNavigationState(
            Replay.state(route, 100.0, 10.0).copy(currentPosition = null), 1_000_000_000L)
        assertEquals(TerrainVisualMode.HOLD, held.mode)
        assertSame(attached.profile, held.profile)
        val newer = presenter.onNavigationState(Replay.state(route, 110.0, 10.0), 2_000_000_000L)
        val advanced = terrainInstrumentModel(presenter.frame(2_033_000_000L)) as TerrainInstrumentModel.Valid
        val heldModel = terrainInstrumentModel(held) as TerrainInstrumentModel.Valid
        assertTrue(advanced.riderM > heldModel.riderM)
        assertTrue(newer.target!!.projection.distanceAlongM!! > held.target!!.projection.distanceAlongM!!)
        assertSame(attached.profile, newer.profile)
        assertNull(TerrainPresenter().frame(0).profile)
    }

    @Test fun holdPassesStaleFlagsThrough() {
        val profile = DisplayElevationProfile.from(grade().filtered)
        val model = terrainInstrumentModel(frame(TerrainVisualMode.HOLD, profile, true, true)) as TerrainInstrumentModel.Valid
        assertTrue(model.stale)
        assertTrue(model.showStaleIndicator)
    }

    @Test fun dataStateFollowsTheCorridorWindowAroundTheRider() {
        // Elevation only near the start of the route.
        val partial = DisplayElevationProfile.from(FilteredElevationProfile.from(RawElevationProfile.fromHeights(
            "partial", List(400) { if (it < 100) 100.0 + it * .1 else null })))
        fun at(rider: Double) = terrainInstrumentModel(
            frame(TerrainVisualMode.ATTACHED, partial).copy(distanceAlongM = rider))
        val last = partial.distanceAt((0 until partial.size).last { partial.relativeHeightAt(it) != null })
        assertTrue(at(100.0) is TerrainInstrumentModel.Valid)
        // The last height lies inside [rider - BEHIND_M, rider + AHEAD_M] 15 m past it, not 25 m past it.
        assertTrue(at(last + 15.0) is TerrainInstrumentModel.Valid)
        assertTrue(at(last + 25.0) is TerrainInstrumentModel.NoData)
        assertTrue(at(1_500.0) is TerrainInstrumentModel.NoData)

        val long = DisplayElevationProfile.from(FilteredElevationProfile.from(RawElevationProfile.fromHeights(
            "long", List(50_000) { 100.0 + it * .01 })))
        fun longAt(rider: Double) = terrainInstrumentModel(
            frame(TerrainVisualMode.ATTACHED, long).copy(distanceAlongM = rider))
        assertTrue(longAt(125_000.0) is TerrainInstrumentModel.Valid)
        assertTrue(longAt(-300.0) is TerrainInstrumentModel.Valid)
        assertTrue(longAt(250_010.0) is TerrainInstrumentModel.Valid)
        assertTrue(longAt(250_100.0) is TerrainInstrumentModel.NoData)
    }

    private fun corridorOf(id: String, riderM: Double = 100.0): CorridorGeometry {
        val route = Replay.route(id)
        val index = RouteIndex.build(route)
        val display = DisplayElevationProfile.from(FilteredElevationProfile.from(RawElevationProfile.fromHeights(
            id, List(201) { 100.0 + it * .2 })))
        return CorridorGeometry.build(CorridorRoute.build(route, index), display, emptyList(), riderM)!!
    }

    private fun valid(corridor: CorridorGeometry?, riderM: Double = 100.0) =
        TerrainInstrumentModel.Valid(riderM, "0 %", null, false, false, corridor)

    @Test fun routeChangeFreezesTheLastCorridorWhileTheNewProfileLoads() {
        val old = corridorOf("old")
        val shown = valid(old, 120.0)
        // The new route's first frames: no corridor yet, or a flat one of the new route.
        val frozen = reloadingModel(shown, TerrainInstrumentModel.NoData(false, false, 0.0), profileLoading = true)
        assertEquals(TerrainInstrumentModel.Reloading(120.0, old), frozen)
        assertSame(frozen, reloadingModel(frozen, valid(corridorOf("new")), profileLoading = true))
        assertSame(frozen, reloadingModel(frozen, TerrainInstrumentModel.Detached(30.0), profileLoading = true))
        // Loading finished: the new route's model replaces the frozen view.
        val next = valid(corridorOf("new"))
        assertSame(next, reloadingModel(frozen, next, profileLoading = false))
    }

    @Test fun noFreezeWithoutAnEarlierCorridorOrForTheSameRoute() {
        val next = TerrainInstrumentModel.NoData(false, false, 0.0)
        assertSame(next, reloadingModel(TerrainInstrumentModel.NoRoute, next, profileLoading = true))
        assertSame(next, reloadingModel(TerrainInstrumentModel.NoData(false, false), next, profileLoading = true))
        val old = corridorOf("old")
        val same = valid(old, 130.0)
        assertSame(same, reloadingModel(valid(old), same, profileLoading = true))
        assertSame(next, reloadingModel(valid(old), next, profileLoading = false))
    }

    @Test fun noRouteAndArrivalEndTheFreezeAtOnce() {
        val frozen = TerrainInstrumentModel.Reloading(120.0, corridorOf("old"))
        assertSame(TerrainInstrumentModel.NoRoute,
            reloadingModel(frozen, TerrainInstrumentModel.NoRoute, profileLoading = true))
        assertSame(TerrainInstrumentModel.Arrived,
            reloadingModel(valid(corridorOf("old")), TerrainInstrumentModel.Arrived, profileLoading = true))
        assertSame(frozen.corridor, frozen.shownCorridor)
        assertEquals(120.0, frozen.shownRiderM!!, 0.0)
    }
}
