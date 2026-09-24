package pl.mazovia.offroad.terrain.projection

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RouteSource
import pl.mazovia.offroad.terrain.geo.SphericalEarth
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * One route edge between consecutive points of `route.allPoints` (never across a GPX segment gap).
 *
 * `sStartM`/`haversineLengthM` are on the navigation-compatible axis. `kLonM`, `dxM`, `dyM` describe the edge in
 * an edge-local **equirectangular** frame (origin = edge start, x = east, y = north, longitude scale taken at the
 * edge mid-latitude). This is a flat approximation of the local tangent plane, not an ECEF-derived ENU transform;
 * its relative distortion grows with the edge's latitude span, ≈ tan φ · Δφ (≈ 1.4·10⁻⁴ for a 2 km diagonal edge at
 * 52° N, i.e. decimetres along the edge, much less across it). Used only for matching (DESIGN §6.2.3).
 */
class RouteEdge internal constructor(
    val index: Int,
    val fromPointIndex: Int,
    val toPointIndex: Int,
    val segmentIndex: Int,
    val partIndex: Int,
    val sStartM: Double,
    val haversineLengthM: Double,
    internal val latA: Double,
    internal val lonA: Double,
    internal val kLonM: Double,
    internal val dxM: Double,
    internal val dyM: Double
) {
    internal val planarLength2 = dxM * dxM + dyM * dyM
    val isZeroLength: Boolean get() = haversineLengthM == 0.0
    val sEndM: Double get() = sStartM + haversineLengthM

    /** Bearing of the edge direction, degrees [0, 360), 0 = north. */
    val tangentBearingDeg: Double = (Math.toDegrees(atan2(dxM, dyM)) + 360.0) % 360.0
}

/** Orthogonal projection of a point onto one finite edge (edge-local metric frame). */
data class EdgeProjection(
    val edge: RouteEdge,
    val edgeFraction: Double,
    val crossTrackM: Double,
    val distanceAlongM: Double,
    val projected: GeoPoint
)

/**
 * A geometrically continuous part of the route (TA-001A-F1). A calculated route is one part; an IMPORTED_GPX route has
 * one part per track segment. Consecutive parts are separated by a hard break: they may share the same `s` (a GPX gap
 * contributes 0 to the axis) while being far apart on the ground. Profiles never filter or grade across a break.
 */
class RoutePart internal constructor(
    val index: Int,
    val firstPointIndex: Int,
    val lastPointIndex: Int,
    val startS: Double,
    val endS: Double,
    internal val positiveEdges: IntArray
) {
    val hasEdges: Boolean get() = positiveEdges.isNotEmpty()
}

/**
 * Strategy C route index (DESIGN §6.2, TA-000B-C1).
 *
 * - **Progress axis `s`**: cumulative [GeoPoint.distanceTo] over `route.allPoints` in order, with GPX segment
 *   breaks contributing 0 — the same loop as `NavigationManager.initializeRouteData` (NavigationManager.kt:49-60),
 *   including its rule that breaks apply only to [RouteSource.IMPORTED_GPX]. Vertex indices equal `allPoints` indices.
 * - **Candidate search**: coarse lat/lon grid; an acceleration structure only.
 * - **Matching**: per-edge equirectangular frame (see [RouteEdge]); `distanceAlong = sStart + t · haversineLength`.
 * - **Parts**: continuous pieces separated by GPX breaks ([RoutePart]).
 */
class RouteIndex private constructor(
    val routeId: String,
    val points: List<GeoPoint>,
    private val cumulativeM: DoubleArray,
    val edges: List<RouteEdge>,
    val parts: List<RoutePart>,
    private val positiveEdgeIndices: IntArray,
    private val grid: EdgeGrid?
) {
    val totalLengthM: Double get() = if (cumulativeM.isEmpty()) 0.0 else cumulativeM[cumulativeM.size - 1]

    /** Navigation-compatible cumulative distance at vertex [pointIndex] of `route.allPoints`. */
    fun cumulativeAt(pointIndex: Int): Double = cumulativeM[pointIndex]

    /** Copy of the cumulative distance sequence (one entry per `route.allPoints` element). */
    fun cumulativeCopy(): DoubleArray = cumulativeM.copyOf()

    /** Test/diagnostic access to the acceleration grid. */
    internal val edgeGrid: EdgeGrid? get() = grid

    val lastPositiveEdge: RouteEdge?
        get() = if (positiveEdgeIndices.isEmpty()) null else edges[positiveEdgeIndices[positiveEdgeIndices.size - 1]]

    /** Projects [position] onto [edge] in the edge-local frame. */
    fun project(edge: RouteEdge, position: GeoPoint): EdgeProjection {
        val px = (position.longitude - edge.lonA) * edge.kLonM
        val py = (position.latitude - edge.latA) * SphericalEarth.METERS_PER_DEG_LAT
        val t = if (edge.planarLength2 == 0.0) 0.0
        else ((px * edge.dxM + py * edge.dyM) / edge.planarLength2).coerceIn(0.0, 1.0)
        val qx = t * edge.dxM
        val qy = t * edge.dyM
        return EdgeProjection(
            edge = edge,
            edgeFraction = t,
            crossTrackM = hypot(px - qx, py - qy),
            distanceAlongM = edge.sStartM + t * edge.haversineLengthM,
            projected = GeoPoint(
                latitude = edge.latA + qy / SphericalEarth.METERS_PER_DEG_LAT,
                longitude = edge.lonA + qx / edge.kLonM
            )
        )
    }

    /**
     * All non-zero-length edges whose projection is within [radiusM] of [position], ordered by edge index
     * (deterministic). The grid only proposes candidates; the returned metrics come from [project].
     */
    fun candidates(position: GeoPoint, radiusM: Double): List<EdgeProjection> {
        val g = grid ?: return emptyList()
        return g.query(position, radiusM)
            .map { project(edges[it], position) }
            .filter { it.crossTrackM <= radiusM }
    }

    /**
     * Point at distance [sM] (clamped to [0, total]) searching the whole route. At a GPX break both sides share the
     * same `s`; this returns the side after the break. Use [locateInPart] when the side matters.
     */
    fun locate(sM: Double): EdgeProjection? =
        if (positiveEdgeIndices.isEmpty()) null else locateIn(positiveEdgeIndices, sM.coerceIn(0.0, totalLengthM))

    /** Point at distance [sM] within [part] only (clamped to the part), or null if the part has no edges. */
    fun locateInPart(part: RoutePart, sM: Double): EdgeProjection? =
        if (!part.hasEdges) null else locateIn(part.positiveEdges, sM.coerceIn(part.startS, part.endS))

    private fun locateIn(candidates: IntArray, s: Double): EdgeProjection {
        var lo = 0
        var hi = candidates.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (edges[candidates[mid]].sStartM <= s) lo = mid else hi = mid - 1
        }
        val edge = edges[candidates[lo]]
        val t = ((s - edge.sStartM) / edge.haversineLengthM).coerceIn(0.0, 1.0)
        val a = points[edge.fromPointIndex]
        val b = points[edge.toPointIndex]
        return EdgeProjection(
            edge = edge,
            edgeFraction = t,
            crossTrackM = 0.0,
            distanceAlongM = edge.sStartM + t * edge.haversineLengthM,
            projected = GeoPoint(
                latitude = a.latitude + t * (b.latitude - a.latitude),
                longitude = a.longitude + t * (b.longitude - a.longitude)
            )
        )
    }

    companion object {
        /** Grid cell size (TARGET, DESIGN §6.2). */
        const val GRID_CELL_M = 50.0
        /** Query radius padding factor (TARGET, DESIGN §6.2.3). */
        const val GRID_QUERY_PAD = 1.1

        fun build(route: Route): RouteIndex {
            val points = route.allPoints
            val n = points.size
            val isGpx = route.source == RouteSource.IMPORTED_GPX
            val gpxBreaks: Set<Int> = if (isGpx)
                route.segments.dropLast(1).runningFold(0) { count, segment -> count + segment.points.size }
                    .drop(1).toSet() else emptySet()

            // Navigation-compatible axis: identical loop to NavigationManager.kt:56-60.
            val cumulative = DoubleArray(n)
            var cumulativeDist = 0.0
            for (i in 1 until n) {
                if (i !in gpxBreaks) cumulativeDist += points[i - 1].distanceTo(points[i])
                cumulative[i] = cumulativeDist
            }

            val segmentOfPoint = IntArray(n)
            var p = 0
            route.segments.forEachIndexed { segIdx, segment ->
                repeat(segment.points.size) { if (p < n) segmentOfPoint[p++] = segIdx }
            }
            // Parts: one per GPX segment (breaks are hard), a single part otherwise.
            val partOfPoint = IntArray(n) { if (isGpx) segmentOfPoint[it] else 0 }

            val edges = ArrayList<RouteEdge>(max(0, n - 1))
            for (i in 1 until n) {
                if (i in gpxBreaks) continue
                val a = points[i - 1]
                val b = points[i]
                val kLon = SphericalEarth.metersPerDegLon((a.latitude + b.latitude) / 2.0)
                edges += RouteEdge(
                    index = edges.size,
                    fromPointIndex = i - 1,
                    toPointIndex = i,
                    segmentIndex = segmentOfPoint[i - 1],
                    partIndex = partOfPoint[i - 1],
                    sStartM = cumulative[i - 1],
                    haversineLengthM = cumulative[i] - cumulative[i - 1],
                    latA = a.latitude,
                    lonA = a.longitude,
                    kLonM = kLon,
                    dxM = (b.longitude - a.longitude) * kLon,
                    dyM = (b.latitude - a.latitude) * SphericalEarth.METERS_PER_DEG_LAT
                )
            }
            val positive = edges.filter { !it.isZeroLength }.map { it.index }.toIntArray()

            val parts = mutableListOf<RoutePart>()
            if (n > 0) {
                var start = 0
                for (i in 1..n) {
                    if (i == n || partOfPoint[i] != partOfPoint[start]) {
                        val partIdx = parts.size
                        parts += RoutePart(
                            index = partIdx,
                            firstPointIndex = start,
                            lastPointIndex = i - 1,
                            startS = cumulative[start],
                            endS = cumulative[i - 1],
                            positiveEdges = positive.filter { edges[it].partIndex == partOfPoint[start] }.toIntArray()
                        )
                        start = i
                    }
                }
            }
            val grid = if (positive.isEmpty()) null else EdgeGrid.build(points, edges, positive)
            return RouteIndex(route.id, points, cumulative, edges, parts, positive, grid)
        }
    }
}

/**
 * Coarse lat/lon grid of edges (acceleration only). Each edge is sampled along its length at ≤ half a cell per axis
 * and registered in the sampled cell and its 8 neighbours: any point of the edge lies within ¼ cell of a sample, so its
 * cell is inside that sample's 3×3 block. The grid may return extra edges, never fewer.
 */
internal class EdgeGrid private constructor(
    internal val lat0: Double,
    internal val lon0: Double,
    internal val cellLatDeg: Double,
    internal val cellLonDeg: Double,
    private val cells: Map<Long, IntArray>
) {
    fun query(position: GeoPoint, radiusM: Double): IntArray {
        val r = radiusM * RouteIndex.GRID_QUERY_PAD
        val dLat = r / SphericalEarth.METERS_PER_DEG_LAT
        val dLon = r / SphericalEarth.metersPerDegLon(position.latitude)
        val ix0 = cellX(position.longitude - dLon)
        val ix1 = cellX(position.longitude + dLon)
        val iy0 = cellY(position.latitude - dLat)
        val iy1 = cellY(position.latitude + dLat)
        val found = java.util.TreeSet<Int>()
        for (ix in ix0..ix1) for (iy in iy0..iy1) cells[key(ix, iy)]?.forEach { found += it }
        return found.toIntArray()
    }

    private fun cellX(lon: Double) = floor((lon - lon0) / cellLonDeg).toInt()
    private fun cellY(lat: Double) = floor((lat - lat0) / cellLatDeg).toInt()

    companion object {
        private fun key(ix: Int, iy: Int): Long = (ix.toLong() shl 32) or (iy.toLong() and 0xffffffffL)

        fun build(points: List<GeoPoint>, edges: List<RouteEdge>, positive: IntArray): EdgeGrid {
            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLon = points.minOf { it.longitude }
            val meanLat = (minLat + maxLat) / 2.0
            val cellLat = RouteIndex.GRID_CELL_M / SphericalEarth.METERS_PER_DEG_LAT
            val cellLon = RouteIndex.GRID_CELL_M / (SphericalEarth.METERS_PER_DEG_LAT * cos(Math.toRadians(meanLat)))
            val lat0 = minLat - cellLat
            val lon0 = minLon - cellLon
            val tmp = HashMap<Long, MutableList<Int>>()
            for (ei in positive) {
                val e = edges[ei]
                val a = points[e.fromPointIndex]
                val b = points[e.toPointIndex]
                val span = max(abs(b.latitude - a.latitude) / cellLat, abs(b.longitude - a.longitude) / cellLon)
                val steps = max(1, ceil(span * 2.0).toInt())
                val registered = HashSet<Long>()
                for (k in 0..steps) {
                    val f = k.toDouble() / steps
                    val ix = floor((a.longitude + f * (b.longitude - a.longitude) - lon0) / cellLon).toInt()
                    val iy = floor((a.latitude + f * (b.latitude - a.latitude) - lat0) / cellLat).toInt()
                    for (ox in -1..1) for (oy in -1..1) {
                        val kk = key(ix + ox, iy + oy)
                        if (registered.add(kk)) tmp.getOrPut(kk) { ArrayList(2) } += ei
                    }
                }
            }
            return EdgeGrid(lat0, lon0, cellLat, cellLon, tmp.mapValues { it.value.toIntArray() })
        }
    }
}
