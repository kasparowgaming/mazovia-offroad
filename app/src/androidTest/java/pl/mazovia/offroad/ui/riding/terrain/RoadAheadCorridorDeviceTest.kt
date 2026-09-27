package pl.mazovia.offroad.ui.riding.terrain

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.HighwayType
import pl.mazovia.offroad.domain.model.Maneuver
import pl.mazovia.offroad.domain.model.ManeuverType
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RouteMetrics
import pl.mazovia.offroad.domain.model.RouteSegment
import pl.mazovia.offroad.domain.model.RouteSource
import pl.mazovia.offroad.domain.model.RoutingProfile
import pl.mazovia.offroad.domain.model.Surface
import pl.mazovia.offroad.terrain.elevation.ElevationSample
import pl.mazovia.offroad.terrain.elevation.ElevationSampler
import pl.mazovia.offroad.terrain.elevation.ElevationSourceMetadata
import pl.mazovia.offroad.terrain.elevation.GeoBounds
import pl.mazovia.offroad.terrain.elevation.UnavailableReason
import pl.mazovia.offroad.terrain.geo.LocalFrame
import pl.mazovia.offroad.terrain.presentation.CorridorGeometry
import pl.mazovia.offroad.terrain.presentation.CorridorRoute
import pl.mazovia.offroad.terrain.presentation.TerrainGrid
import pl.mazovia.offroad.terrain.profile.FilteredElevationProfile
import pl.mazovia.offroad.terrain.profile.GradeEventDetector
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.profile.RawElevationProfile
import pl.mazovia.offroad.terrain.projection.RouteIndex
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * TA-007B device checks (DESIGN §22.5 D, §21 D column): scripted corridor scenes rendered and saved as PNGs to
 * `<externalFilesDir>/corridor-shots/`, and projection + draw time over 360 animated frames.
 * Run with `am instrument` (not connectedAndroidTest, which uninstalls the app and its terrain data).
 */
@RunWith(AndroidJUnit4::class)
class RoadAheadCorridorDeviceTest {
    @get:Rule val rule = createComposeRule()

    private val origin = GeoPoint(52.18, 21.0)

    private fun geo(east: Double, north: Double) = GeoPoint(
        origin.latitude + north / 111_194.93,
        origin.longitude + east / (111_194.93 * cos(Math.toRadians(origin.latitude))))

    /** Polyline from plan points every [step] metres of a parametric path. */
    private fun path(lengthM: Double, step: Double = 5.0, at: (Double) -> Pair<Double, Double>) =
        (0..(lengthM / step).toInt()).map { at(it * step).let { (e, n) -> geo(e, n) } }

    private fun segment(points: List<GeoPoint>, surface: Surface = Surface.GRAVEL) = RouteSegment(
        points, points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }, surface, HighwayType.TRACK)

    private fun route(id: String, segments: List<RouteSegment>, maneuvers: List<Maneuver> = emptyList(),
                      source: RouteSource = RouteSource.CALCULATED_ROUTE): Route {
        val all = segments.flatMap { it.points }
        return Route(id, all.first(), all.last(), segments, RouteMetrics.fromSegments(segments),
            RoutingProfile.TERENOWY, maneuvers = maneuvers, source = source)
    }

    /** Corridor for [route] with Display heights from [height] (by `s`; null = unavailable). */
    private fun corridor(route: Route, riderM: Double, height: (Double) -> Double?): CorridorGeometry {
        val index = RouteIndex.build(route)
        val raw = RawElevationProfile.fromHeights(route.id,
            (0..(index.totalLengthM / 5.0).toInt()).map { height(it * 5.0) })
        val grade = GradeProfile.from(FilteredElevationProfile.from(raw), emptyList())
        val display = pl.mazovia.offroad.terrain.profile.DisplayElevationProfile.from(grade.filtered)
        return CorridorGeometry.build(CorridorRoute.build(route, index), display,
            GradeEventDetector().detect(grade), riderM)!!
    }

    private fun valid(c: CorridorGeometry, riderM: Double, grade: String = "0 %", next: String? = null,
                      stale: Boolean = false) = TerrainInstrumentModel.Valid(riderM, grade, next,
        stale, stale, c)

    private fun ramp(s: Double, vararg sections: Triple<Double, Double, Double>): Double =
        100.0 + sections.sumOf { (from, to, g) -> g * (s.coerceIn(from, to) - from) }

    private var scene by mutableStateOf<TerrainInstrumentModel>(TerrainInstrumentModel.NoRoute)
    private var sceneGrid by mutableStateOf<TerrainGrid?>(null)
    private var composed = false

    private fun shoot(name: String, model: TerrainInstrumentModel, grid: TerrainGrid? = null): Bitmap {
        scene = model
        sceneGrid = grid
        if (!composed) {
            composed = true
            rule.setContent {
                RoadAheadCorridor(scene, Modifier.fillMaxWidth().height(400.dp).testTag("shot"), grid = sceneGrid)
            }
        }
        rule.waitForIdle()
        val bitmap = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "corridor-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("TerrainCorridorTest", "saved $name.png ${bitmap.width}x${bitmap.height}")
        return bitmap
    }

    /** Synthetic DEM: [height] of (east, north) metres from [origin]; null = unavailable (NODATA). */
    private inner class Dem(private val height: (Double, Double) -> Double?) : ElevationSampler {
        private val frame = LocalFrame(origin)
        override val metadata = ElevationSourceMetadata("test", 3.0, "test", "synthetic corridor test surface")
        override fun sample(latitude: Double, longitude: Double): ElevationSample {
            val p = GeoPoint(latitude, longitude)
            return height(frame.eastM(p), frame.northM(p))?.let { ElevationSample.Value(it) }
                ?: ElevationSample.Unavailable(UnavailableReason.NODATA)
        }
        override suspend fun prefetch(bounds: GeoBounds) = Unit
    }

    private fun grid(c: CorridorGeometry, dem: (Double, Double) -> Double?): TerrainGrid =
        TerrainGrid.sample(c, Dem(dem))!!.also { assertTrue(it.fits(c)) }

    @Test fun scriptedScenes() {
        val straight = route("straight", listOf(segment(path(1200.0) { it to 0.0 })))
        shoot("01_straight_climb", valid(corridor(straight, 30.0) {
            ramp(it, Triple(150.0, 350.0, 0.06)) }, 30.0, "0 %", "PODJAZD 6 % za 120 m · 200 m"))
        shoot("02_crest_occlusion", valid(corridor(straight, 30.0) {
            ramp(it, Triple(40.0, 160.0, 0.08), Triple(160.0, 320.0, -0.08)) }, 30.0, "8 %",
            "ZJAZD 8 % za 130 m · 160 m"))

        val turnPoints = path(300.0) { it to 0.0 } + path(600.0) { 300.0 to it }.drop(1)
        val turn = route("turn90", listOf(segment(turnPoints.take(61), Surface.ASPHALT),
            segment(turnPoints.drop(60), Surface.SAND)),
            maneuvers = listOf(Maneuver(geo(300.0, 0.0), ManeuverType.TURN_LEFT, 300.0)))
        shoot("03_turn_90", valid(corridor(turn, 120.0) { 100.0 }, 120.0))

        val sbends = route("sbends", listOf(segment(path(1500.0) { s -> s to 60.0 * sin(2 * PI * s / 400.0) })))
        shoot("04_s_bends", valid(corridor(sbends, 30.0) { ramp(it, Triple(200.0, 500.0, 0.04)) }, 30.0))

        val gap = corridor(straight, 30.0) { s -> if (s in 180.0..300.0) null else ramp(s, Triple(100.0, 400.0, 0.05)) }
        assertTrue((0 until gap.size).any { !gap.isAvailable(it) })
        shoot("05_coverage_gap", valid(gap, 30.0))

        val frozen = corridor(sbends, 200.0) { 100.0 }
        shoot("06_off_route", TerrainInstrumentModel.Detached(42.0, 200.0, frozen))
        shoot("07_stale", valid(corridor(straight, 30.0) { 100.0 }, 30.0, stale = true))

        val gpx = route("gpx", listOf(segment(path(400.0) { it to 0.0 }, Surface.UNKNOWN),
            segment(path(800.0) { (400.0 + it) to 80.0 }, Surface.UNKNOWN)), source = RouteSource.IMPORTED_GPX)
        shoot("08_gpx_break", valid(corridor(gpx, 300.0) { 100.0 }, 300.0))

        val noHeights = corridor(sbends, 30.0) { null }
        shoot("09_no_elevation", TerrainInstrumentModel.NoData(false, false, 30.0, noHeights))
    }

    /** DESIGN §22.5 C3 grid scenes (TA-007C). */
    @Test fun gridScenes() {
        // Valley across the route with a side slope; the route profile is the DEM along the route axis.
        val valley: (Double, Double) -> Double = { e, n -> 100.0 + 0.06 * abs(e - 400.0) * 0.5 + 0.08 * n }
        val straight = route("straight", listOf(segment(path(1200.0) { it to 0.0 })))
        val full = corridor(straight, 30.0) { valley(it, 0.0) }
        val fullGrid = grid(full, valley)
        assertEquals(0, fullGrid.stats.cellsDropped)
        shoot("10_grid_full", valid(full, 30.0), fullGrid)

        // No DEM within 60 m of a point 180 m ahead, 90 m left: a hole, never a 0 m pit.
        val holed: (Double, Double) -> Double? = { e, n -> if (hypot(e - 210.0, n - 90.0) < 60.0) null else valley(e, n) }
        val holedGrid = grid(full, holed)
        assertTrue(holedGrid.stats.cellsDropped > 0)
        shoot("11_grid_hole", valid(full, 30.0), holedGrid)

        // Crest 150 m ahead, then a descent and a left turn beyond it: the hidden route is a translucent line.
        val crest: (Double, Double) -> Double = { e, _ -> 100.0 + 0.08 * min(e, 180.0) - 0.08 * (e - 180.0).coerceIn(0.0, 300.0) }
        val turnPoints = path(300.0) { it to 0.0 } + path(600.0) { 300.0 to it }.drop(1)
        val turn = route("turn90", listOf(segment(turnPoints)),
            maneuvers = listOf(Maneuver(geo(300.0, 0.0), ManeuverType.TURN_LEFT, 300.0)))
        val beyond = corridor(turn, 30.0) { s -> crest(min(s, 300.0), max(0.0, s - 300.0)) }
        shoot("12_grid_crest_turn", valid(beyond, 30.0, "8 %"), grid(beyond, crest))

        // Grid disabled / not fitting (another route's grid): pixel-identical to the TA-007B corridor.
        val plain = shoot("13_grid_off", valid(full, 30.0))
        val foreign = shoot("13_grid_foreign", valid(full, 30.0), grid(beyond, crest))
        assertTrue("a grid that does not fit must not be drawn", plain.sameAs(foreign))

        // Off route and route change: the grid is greyed with the frozen corridor.
        shoot("14_grid_off_route", TerrainInstrumentModel.Detached(42.0, 30.0, full), fullGrid)
        shoot("15_grid_reloading", TerrainInstrumentModel.Reloading(30.0, full), fullGrid)
    }

    @Test fun drawTimeOverAnimatedFrames() = benchmark(withGrid = false)

    /** C3: the same animation with the terrain grid, rebuilt (sampled from a synthetic DEM) with every window. */
    @Test fun drawTimeOverAnimatedFramesWithGrid() = benchmark(withGrid = true)

    private fun benchmark(withGrid: Boolean) {
        val route = route("bench", listOf(segment(path(3000.0) { s -> s to 80.0 * sin(2 * PI * s / 700.0) })),
            maneuvers = listOf(Maneuver(geo(900.0, 80.0 * sin(2 * PI * 900.0 / 700.0)), ManeuverType.TURN_RIGHT, 900.0)))
        val index = RouteIndex.build(route)
        val raw = RawElevationProfile.fromHeights(route.id, (0..(index.totalLengthM / 5.0).toInt()).map {
            100.0 + 12.0 * sin(2 * PI * it * 5.0 / 500.0) })
        val grade = GradeProfile.from(FilteredElevationProfile.from(raw), emptyList())
        val display = pl.mazovia.offroad.terrain.profile.DisplayElevationProfile.from(grade.filtered)
        val events = GradeEventDetector().detect(grade)
        val data = CorridorRoute.build(route, index)
        // Hills matching the profile's wavelength plus a side slope (grid only).
        val dem = Dem { e, n -> 100.0 + 12.0 * sin(2 * PI * e / 500.0) + 0.05 * n }
        val buildNanos = ArrayList<Long>()
        fun gridFor(c: CorridorGeometry): TerrainGrid? {
            if (!withGrid) return null
            val started = System.nanoTime()
            return TerrainGrid.sample(c, dem).also { buildNanos += System.nanoTime() - started }
        }
        val times = java.util.Collections.synchronizedList(ArrayList<Long>())
        val frames = 360
        var rider by mutableStateOf(50.0)
        var geometry by mutableStateOf(CorridorGeometry.build(data, display, events, 50.0)!!)
        var terrain by mutableStateOf(gridFor(geometry))
        rule.setContent {
            RoadAheadCorridor(valid(geometry, rider, "4 %", "PODJAZD 6 % za 120 m · 90 m"),
                Modifier.fillMaxWidth().height(400.dp), onDrawNanos = { times += it }, grid = terrain)
        }
        rule.waitUntil(5_000) { times.isNotEmpty() }
        // One rider step per drawn frame (≈ 12 m/s at 30 fps); the window is rebuilt as in TerrainPane. The grid
        // build runs here on the test thread (TerrainPane builds it on Default), so it never counts as draw time.
        repeat(frames) {
            val before = times.size
            var next: CorridorGeometry? = null
            rule.runOnIdle {
                rider += 0.4
                if (!geometry.covers(rider, index.totalLengthM)) {
                    next = CorridorGeometry.build(data, display, events, rider)!!
                    geometry = next!!
                }
            }
            next?.let { c -> gridFor(c).let { rule.runOnIdle { terrain = it } } }
            rule.waitUntil(2_000) { times.size > before }
        }
        val warm = times.drop(30).sorted()
        val p50 = warm[warm.size / 2] / 1e6
        val p95 = warm[(warm.size * 95) / 100] / 1e6
        val max = warm.last() / 1e6
        val label = if (withGrid) "draw ms with grid" else "draw ms"
        Log.i("TerrainCorridorTest", "$label over ${warm.size} frames: p50=%.2f p95=%.2f max=%.2f".format(p50, p95, max))
        if (withGrid) {
            val builds = buildNanos.sorted()
            Log.i("TerrainCorridorTest", "grid sample ms over ${builds.size} builds: p50=%.2f p95=%.2f max=%.2f, %d cells".format(
                builds[builds.size / 2] / 1e6, builds[(builds.size * 95) / 100] / 1e6, builds.last() / 1e6,
                terrain?.cellCount ?: 0))
            assertTrue(terrain != null && terrain!!.cellCount > 0)
            // Steady state: the same window sampled repeatedly (a ride rebuilds every 30 m, so the code stays warm).
            val warmBuilds = (0 until 40).map {
                val started = System.nanoTime()
                TerrainGrid.sample(geometry, dem)
                System.nanoTime() - started
            }.drop(10).sorted()
            Log.i("TerrainCorridorTest", "grid sample ms warm over ${warmBuilds.size} builds: p50=%.2f p95=%.2f".format(
                warmBuilds[warmBuilds.size / 2] / 1e6, warmBuilds[(warmBuilds.size * 95) / 100] / 1e6))
        }
        assertEquals(true, warm.size >= frames - 30)
    }
}
