package pl.mazovia.offroad.terrain.presentation

import pl.mazovia.offroad.domain.model.ManeuverType
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RouteSource
import pl.mazovia.offroad.domain.model.Surface
import pl.mazovia.offroad.terrain.geo.LocalFrame
import pl.mazovia.offroad.terrain.profile.DisplayElevationProfile
import pl.mazovia.offroad.terrain.profile.EventDetectorConfig
import pl.mazovia.offroad.terrain.profile.GradeEvent
import pl.mazovia.offroad.terrain.profile.GradeEventClass
import pl.mazovia.offroad.terrain.profile.GradeEventType
import pl.mazovia.offroad.terrain.projection.EdgeProjection
import pl.mazovia.offroad.terrain.projection.RouteIndex
import kotlin.math.abs
import kotlin.math.floor

/** Ribbon colour classes (DESIGN §3.7, §14.4): `surface` only (C7); GPX and untagged segments are UNKNOWN ("nieznana"). */
enum class SurfaceBand { PAVED, GRAVEL, DIRT, SAND, GRASS, UNKNOWN;
    companion object {
        fun of(surface: Surface): SurfaceBand = when (surface) {
            Surface.ASPHALT, Surface.CONCRETE, Surface.PAVED, Surface.COBBLESTONE, Surface.WOOD -> PAVED
            Surface.COMPACTED, Surface.GRAVEL, Surface.FINE_GRAVEL -> GRAVEL
            Surface.DIRT, Surface.EARTH, Surface.GROUND, Surface.UNPAVED, Surface.MUD -> DIRT
            Surface.SAND -> SAND
            Surface.GRASS -> GRASS
            Surface.UNKNOWN -> UNKNOWN
        }
    }
}

/** Edge colouring from [GradeEvent] spans (DESIGN §3.7). Never derived from exaggerated heights. */
enum class GradeBand { NONE, CLIMB, DESCENT, STEEP }

/** A maneuver placed on the route axis `s`. */
data class CorridorManeuver(val distanceM: Double, val type: ManeuverType)

/** A surface band boundary on the route axis: [band] starts at [distanceM]. */
data class CorridorSurfaceChange(val distanceM: Double, val band: SurfaceBand)

/**
 * Route data the corridor needs besides the profile, built once per route (DESIGN §3.7): the navigation-compatible
 * index, the surface band of every route segment and the maneuvers placed on `s`. GPX routes carry no maneuvers
 * (§15.6) and draw every segment as UNKNOWN.
 */
class CorridorRoute private constructor(
    val index: RouteIndex,
    private val segmentBands: Array<SurfaceBand>,
    val maneuvers: List<CorridorManeuver>
) {
    val routeId: String get() = index.routeId

    fun surfaceOfSegment(segmentIndex: Int): SurfaceBand =
        segmentBands.getOrElse(segmentIndex) { SurfaceBand.UNKNOWN }

    companion object {
        /** Maneuvers farther than this from the route polyline are not drawn (TARGET). */
        const val MANEUVER_MATCH_RADIUS_M = 30.0

        private val SIGNLESS = setOf(ManeuverType.DEPART, ManeuverType.STRAIGHT, ManeuverType.UNKNOWN)

        fun build(route: Route, index: RouteIndex): CorridorRoute {
            require(route.id == index.routeId) { "index built for another route" }
            val gpx = route.source == RouteSource.IMPORTED_GPX
            val bands = Array(route.segments.size) {
                if (gpx) SurfaceBand.UNKNOWN else SurfaceBand.of(route.segments[it].surface)
            }
            val maneuvers = ArrayList<CorridorManeuver>()
            if (!gpx) {
                // Route order: when the polyline passes a maneuver point more than once, take the first pass that is
                // not behind the previous maneuver.
                var previousS = 0.0
                for (maneuver in route.maneuvers) {
                    if (maneuver.type in SIGNLESS) continue
                    val candidates = index.candidates(maneuver.point, MANEUVER_MATCH_RADIUS_M)
                    if (candidates.isEmpty()) continue
                    val nearest = candidates.minOf { it.crossTrackM }
                    val close = candidates.filter { it.crossTrackM <= nearest + 2.0 }.sortedBy { it.distanceAlongM }
                    val chosen = close.firstOrNull { it.distanceAlongM >= previousS } ?: close.last()
                    previousS = chosen.distanceAlongM
                    maneuvers += CorridorManeuver(chosen.distanceAlongM, maneuver.type)
                }
            }
            return CorridorRoute(index, bands, maneuvers)
        }
    }
}

/**
 * Option D window geometry (DESIGN §3.7, TA-007B): route plan points in the render-scene ENU frame of the rider plus
 * Display heights and annotations. Pure presentation data: it reads the Display profile and grade events and never
 * writes back to any profile layer.
 *
 * Samples lie on a fixed grid of [STEP_M] on the navigation-compatible axis `s` (aligned to multiples of [STEP_M], so
 * the same route point keeps the same sample while the rider moves), plus the window and GPX part ends. Plan positions
 * come from [RouteIndex.locateInPart], i.e. exactly the point the projection associates with each `s`.
 *
 * Heights: [sceneHeightM] = (Display height − Display height at the anchor) × [exaggeration]. Where the Display profile
 * is unavailable the sample is flagged ([isAvailable] false) and held at the nearest earlier available height in the
 * window (or the first later one) — a visible "brak danych" span, never 0 m. When the whole window is unavailable,
 * [hasHeights] is false and every sample is flat and flagged. Grade bands never cover an unavailable sample.
 */
class CorridorGeometry private constructor(
    val routeId: String,
    /** Display profile the heights came from (identity, for cache invalidation); null when none was available. */
    val display: DisplayElevationProfile?,
    val exaggeration: Float,
    /** `s` of the ENU origin (the rider's position when built). */
    val anchorM: Double,
    private val distance: DoubleArray,
    private val east: FloatArray,
    private val north: FloatArray,
    private val height: FloatArray,
    private val available: BooleanArray,
    private val breakBefore: BooleanArray,
    private val surface: Array<SurfaceBand>,
    private val grade: Array<GradeBand>,
    /** Display grade between this sample and the next (unexaggerated), NaN where unknown; render shading only. */
    private val slope: FloatArray,
    val hasHeights: Boolean,
    val maneuvers: List<CorridorManeuver>,
    /** Grade events starting inside the window (for flags). */
    val eventStarts: List<GradeEvent>,
    val surfaceChanges: List<CorridorSurfaceChange>
) {
    val size: Int get() = distance.size
    val startM: Double get() = distance.first()
    val endM: Double get() = distance.last()

    fun distanceAt(i: Int): Double = distance[i]
    fun eastAt(i: Int): Float = east[i]
    fun northAt(i: Int): Float = north[i]
    fun sceneHeightAt(i: Int): Float = height[i]
    fun isAvailable(i: Int): Boolean = available[i]
    /** True when sample [i] starts a new GPX part: no ribbon may join it to sample [i] − 1. */
    fun isBreakBefore(i: Int): Boolean = breakBefore[i]
    fun surfaceAt(i: Int): SurfaceBand = surface[i]
    fun gradeBandAt(i: Int): GradeBand = grade[i]
    fun slopeAt(i: Int): Float = slope[i]

    /** True while [riderM] still has the full `[s − behind, s + ahead]` window inside this geometry (clamped to the
     *  route) and the rider has not moved far from the ENU origin. */
    fun covers(riderM: Double, routeLengthM: Double): Boolean =
        riderM >= anchorM - REBUILD_M && riderM <= anchorM + REBUILD_M &&
            startM <= maxOf(0.0, riderM - BEHIND_M) + 1e-6 &&
            endM >= minOf(routeLengthM, riderM + AHEAD_M) - 1e-6

    /** Index of the last sample with distance ≤ [d] (0 when [d] precedes the window). */
    fun indexAtOrBefore(d: Double): Int {
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (distance[mid] <= d) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        /** Window around the rider (TARGETS, DESIGN §3.7) and the extra margin that lets one build serve ≥ 30 m of motion. */
        const val BEHIND_M = 20.0
        const val AHEAD_M = 600.0
        const val REBUILD_M = 30.0
        const val STEP_M = 3.0
        const val DEFAULT_EXAGGERATION = 2.5f

        private val STEEP_GRADE = EventDetectorConfig().shortSteepGrade

        /** Colour band of [event]: the one steepness rule for corridor edges and event flags (DESIGN §3.7). */
        fun gradeBandOf(event: GradeEvent): GradeBand = when {
            event.eventClass == GradeEventClass.SHORT || abs(event.averageGrade) >= STEEP_GRADE -> GradeBand.STEEP
            event.type == GradeEventType.CLIMB -> GradeBand.CLIMB
            else -> GradeBand.DESCENT
        }

        fun build(
            route: CorridorRoute,
            display: DisplayElevationProfile?,
            events: List<GradeEvent>,
            riderM: Double,
            exaggeration: Float = DEFAULT_EXAGGERATION
        ): CorridorGeometry? {
            require(exaggeration > 0f && exaggeration.isFinite())
            val index = route.index
            val total = index.totalLengthM
            val origin = index.locate(riderM) ?: return null
            val anchor = origin.distanceAlongM
            val from = maxOf(0.0, floor((anchor - BEHIND_M - REBUILD_M) / STEP_M) * STEP_M)
            val to = minOf(total, anchor + AHEAD_M + REBUILD_M)
            // Per GPX part (one part otherwise): its clamped ends plus the grid points strictly between them, so both
            // sides of a break get a sample at the break distance.
            val grid = ArrayList<Double>()
            val located = ArrayList<EdgeProjection>()
            for (part in index.parts) {
                if (!part.hasEdges) continue
                val lo = maxOf(part.startS, from)
                val hi = minOf(part.endS, to)
                if (lo > hi) continue
                fun add(d: Double) { grid += d; located += index.locateInPart(part, d)!! }
                add(lo)
                var k = floor(lo / STEP_M + 1e-9).toLong() + 1
                while (k * STEP_M < hi - 1e-9) { add(k * STEP_M); k++ }
                if (hi > lo) add(hi)
            }
            val n = grid.size
            if (n == 0) return null
            val frame = LocalFrame(origin.projected)

            val east = FloatArray(n)
            val north = FloatArray(n)
            val parts = IntArray(n)
            val surface = Array(n) { SurfaceBand.UNKNOWN }
            val metres = DoubleArray(n) { Double.NaN }
            for (i in 0 until n) {
                val p = located[i]
                east[i] = frame.eastM(p.projected).toFloat()
                north[i] = frame.northM(p.projected).toFloat()
                parts[i] = p.edge.partIndex
                surface[i] = route.surfaceOfSegment(p.edge.segmentIndex)
                if (display != null) displayHeight(display, grid[i], p.edge.partIndex)?.let { metres[i] = it.toDouble() }
            }
            val breakBefore = BooleanArray(n) { it > 0 && parts[it] != parts[it - 1] }
            val available = BooleanArray(n) { !metres[it].isNaN() }
            val hasHeights = available.any { it }

            // Hold unavailable samples at the last available height (first later one before any value): marked, never 0 m.
            val held = metres.copyOf()
            var last = Double.NaN
            for (i in 0 until n) if (available[i]) last = held[i] else held[i] = last
            var next = Double.NaN
            for (i in n - 1 downTo 0) if (!held[i].isNaN()) next = held[i] else held[i] = next
            val originIndex = grid.indexOfLast { it <= anchor }.coerceAtLeast(0)
            val base = if (hasHeights) held[originIndex] else 0.0
            val height = FloatArray(n) { if (hasHeights) ((held[it] - base) * exaggeration).toFloat() else 0f }

            val slope = FloatArray(n) { i ->
                val run = if (i + 1 < n) grid[i + 1] - grid[i] else 0.0
                if (i + 1 < n && available[i] && available[i + 1] && !breakBefore[i + 1] && run > 1e-6)
                    ((metres[i + 1] - metres[i]) / run).toFloat() else Float.NaN
            }
            val grade = Array(n) { i ->
                if (!available[i]) GradeBand.NONE else {
                    val d = grid[i]
                    val event = events.firstOrNull { d >= it.startDistanceM - 1e-6 && d <= it.endDistanceM + 1e-6 }
                    if (event == null) GradeBand.NONE else gradeBandOf(event)
                }
            }
            val changes = ArrayList<CorridorSurfaceChange>()
            for (i in 1 until n) if (surface[i] != surface[i - 1] && grid[i] > anchor) {
                changes += CorridorSurfaceChange(grid[i], surface[i])
            }
            return CorridorGeometry(
                routeId = route.routeId,
                display = display,
                exaggeration = exaggeration,
                anchorM = anchor,
                distance = grid.toDoubleArray(),
                east = east,
                north = north,
                height = height,
                available = available,
                breakBefore = breakBefore,
                surface = surface,
                grade = grade,
                slope = slope,
                hasHeights = hasHeights,
                maneuvers = route.maneuvers.filter { it.distanceM > anchor - BEHIND_M && it.distanceM <= grid.last() },
                eventStarts = events.filter { it.startDistanceM > from && it.startDistanceM <= grid.last() },
                surfaceChanges = changes
            )
        }

        /**
         * Display height (m above the profile baseline) at [d] within GPX part [part]; linear between the two
         * neighbouring samples of that part, null when either is unavailable or [d] lies outside the part's samples.
         */
        internal fun displayHeight(profile: DisplayElevationProfile, d: Double, part: Int): Float? {
            if (profile.size == 0) return null
            // Samples are ordered by (distance, part): the last one with distance ≤ d and part ≤ [part] is a prefix end.
            var lo = -1
            var hi = profile.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (profile.distanceAt(mid) <= d + EPS && profile.partIndexAt(mid) <= part) lo = mid else hi = mid - 1
            }
            if (lo < 0 || profile.partIndexAt(lo) != part) return null
            val h0 = profile.relativeHeightAt(lo) ?: return null
            val d0 = profile.distanceAt(lo)
            if (d - d0 <= EPS) return h0
            val nextIndex = lo + 1
            if (nextIndex >= profile.size || profile.partIndexAt(nextIndex) != part) return null
            val h1 = profile.relativeHeightAt(nextIndex) ?: return null
            val d1 = profile.distanceAt(nextIndex)
            if (d1 - d0 <= EPS) return h0
            val t = ((d - d0) / (d1 - d0)).toFloat()
            return h0 + (h1 - h0) * t
        }

        private const val EPS = 1e-6
    }
}
