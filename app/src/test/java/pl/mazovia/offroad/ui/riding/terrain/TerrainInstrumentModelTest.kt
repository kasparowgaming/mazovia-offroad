package pl.mazovia.offroad.ui.riding.terrain

import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.debug.NavigationStateReplayer as Replay
import pl.mazovia.offroad.terrain.profile.DisplayElevationProfile
import pl.mazovia.offroad.terrain.profile.FilteredElevationProfile
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.profile.RawElevationProfile

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
        assertTrue(valid.samples.any { it.heightM != null })
        assertTrue(valid.gradeLabel.isNotBlank())
        val held = presenter.onNavigationState(
            Replay.state(route, 100.0, 10.0).copy(currentPosition = null), 1_000_000_000L)
        assertEquals(TerrainVisualMode.HOLD, held.mode)
        assertSame(attached.profile, held.profile)
        val newer = presenter.onNavigationState(Replay.state(route, 110.0, 10.0), 2_000_000_000L)
        val advanced = terrainInstrumentModel(presenter.frame(2_033_000_000L)) as TerrainInstrumentModel.Valid
        val heldModel = terrainInstrumentModel(held) as TerrainInstrumentModel.Valid
        assertTrue(advanced.riderM > heldModel.riderM)
        assertTrue(advanced.windowStartM > heldModel.windowStartM)
        assertTrue(advanced.windowEndM > heldModel.windowEndM)
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

    @Test fun longProfileWindowsIncludeOnlyIndexedSamplesAndPreserveBreaks() {
        val count = 50_000
        val breakAt = 25_000
        val raw = RawElevationProfile.fromHeights("long", List(count) { 100.0 + it * .01 },
            partIndices = List(count) { when {
                it == 0 -> 0
                it <= breakAt -> 1
                it < count - 1 -> 2
                else -> 3
            } })
        val profile = DisplayElevationProfile.from(FilteredElevationProfile.from(raw))
        fun window(start: Double, end: Double): TerrainInstrumentModel = terrainInstrumentModel(
            frame(TerrainVisualMode.ATTACHED, profile).copy(
                windowStartM = start, windowEndM = end, distanceAlongM = start))

        val middle = window(124_995.0, 125_010.0) as TerrainInstrumentModel.Valid
        assertEquals(listOf(124_995.0, 125_000.0, 125_005.0, 125_010.0),
            middle.samples.map { it.distanceM })
        assertEquals(listOf(false, true, false, false), middle.samples.map { it.breakAfter })
        val start = window(-10.0, 10.0) as TerrainInstrumentModel.Valid
        assertEquals(listOf(0.0, 5.0, 10.0), start.samples.map { it.distanceM })
        assertEquals(listOf(true, false, false), start.samples.map { it.breakAfter })
        val end = window(249_985.0, 250_010.0) as TerrainInstrumentModel.Valid
        assertEquals(listOf(249_985.0, 249_990.0, 249_995.0), end.samples.map { it.distanceM })
        assertEquals(listOf(false, true, false), end.samples.map { it.breakAfter })
        assertTrue(window(250_000.0, 251_000.0) is TerrainInstrumentModel.NoData)
    }
}
