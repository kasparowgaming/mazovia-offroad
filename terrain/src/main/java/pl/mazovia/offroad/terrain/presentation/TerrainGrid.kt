package pl.mazovia.offroad.terrain.presentation

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.terrain.elevation.ElevationSample
import pl.mazovia.offroad.terrain.elevation.ElevationSampler
import pl.mazovia.offroad.terrain.elevation.GeoBounds
import pl.mazovia.offroad.terrain.geo.LocalFrame
import pl.mazovia.offroad.terrain.geo.SphericalEarth
import pl.mazovia.offroad.terrain.profile.DisplayElevationProfile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Node and cell counts of one [TerrainGrid] build, plus how far the Display ribbon sits off the DEM (diagnostics). */
data class TerrainGridStats(
    val nodesInBand: Int,
    val nodesAvailable: Int,
    val cellsDrawn: Int,
    /** Cells inside the band dropped because a node had no elevation (holes). */
    val cellsDropped: Int,
    /** Median and p95 of |(DEM − Display) − datum offset| at the corridor samples (m, unexaggerated). */
    val ribbonOffsetMedianM: Double,
    val ribbonOffsetP95M: Double
)

/**
 * C3 shaded terrain grid around the Option D corridor (DESIGN §0.3, §3.7). Pure presentation data, render-only: grid
 * heights never feed Raw/Filtered/Grade profiles or grade events, and nothing here writes to a profile or sampler.
 *
 * Nodes lie on a fixed geographic lattice of [STEP_M] (latitude/longitude steps anchored at 0°, the longitude step fixed
 * per whole-degree latitude band), so a node keeps its ground position and height across corridor rebuilds. Only nodes
 * within [HALF_WIDTH_M] of the corridor samples are kept. Node heights are expressed in the Display profile datum
 * (DEM − the median DEM/Display offset along the corridor), so the renderer places them in any corridor frame of the
 * same profile: scene height = (height − [CorridorGeometry.baseDisplayM]) × exaggeration. An unavailable node is NaN
 * and every cell touching it is dropped (a hole, never 0 m).
 */
class TerrainGrid private constructor(
    val routeId: String,
    /** Display profile whose datum the heights use (identity, like [CorridorGeometry.display]). */
    val display: DisplayElevationProfile,
    val exaggeration: Float,
    /** Plan origin of the node coordinates (the ENU origin of the corridor the grid was built for). */
    val origin: GeoPoint,
    val cols: Int,
    val rows: Int,
    private val east: FloatArray,
    private val north: FloatArray,
    private val height: FloatArray,
    private val cells: IntArray,
    private val light: FloatArray,
    val stats: TerrainGridStats
) {
    val nodeCount: Int get() = east.size
    val cellCount: Int get() = cells.size

    /** Metres east/north of [origin] of node [node] (row-major: rows go north, columns east). */
    fun eastAt(node: Int): Float = east[node]
    fun northAt(node: Int): Float = north[node]
    /** Display-datum height of [node], NaN where unavailable or outside the band. */
    fun heightAt(node: Int): Float = height[node]

    /** South-west node of drawable cell [cell]; its corners are that node, +1 (east), +[cols] (north), +[cols] + 1. */
    fun cellNode(cell: Int): Int = cells[cell]

    /** Lambert brightness factor of [cell] (≈ 0.35 in shadow … 1.25 facing the light), from exaggerated normals. */
    fun lightAt(cell: Int): Float = light[cell]

    /** True when the grid can be drawn under [corridor]: same route, same Display profile, same exaggeration. */
    fun fits(corridor: CorridorGeometry): Boolean =
        corridor.hasHeights && corridor.routeId == routeId && corridor.display === display &&
            corridor.exaggeration == exaggeration

    /**
     * Writes the node positions in [corridor]'s scene frame into [x], [y], [z] (each ≥ [nodeCount]): plan shift
     * between the two ENU origins, scene height = (height − [CorridorGeometry.baseDisplayM]) × exaggeration; NaN
     * heights stay NaN. The grid may come from an earlier window of the same profile. Requires [fits].
     */
    fun placeIn(corridor: CorridorGeometry, x: FloatArray, y: FloatArray, z: FloatArray) {
        require(fits(corridor)) { "grid does not fit the corridor" }
        val frame = LocalFrame(corridor.origin)
        val dx = frame.eastM(origin).toFloat()
        val dy = frame.northM(origin).toFloat()
        val base = corridor.baseDisplayM
        val scale = corridor.exaggeration
        for (k in 0 until nodeCount) {
            x[k] = east[k] + dx
            y[k] = north[k] + dy
            z[k] = ((height[k] - base) * scale).toFloat()
        }
    }

    companion object {
        /** Lattice step and band half-width around the corridor (TARGETS, DESIGN §3.7 C3). */
        const val STEP_M = 25.0
        const val HALF_WIDTH_M = 200.0

        /** Light from the south-west, 45° up (unit vector); brightness = AMBIENT + DIFFUSE × max(0, n·l). */
        private const val LIGHT_X = -0.5f
        private const val LIGHT_Y = -0.5f
        private const val LIGHT_Z = 0.70710677f
        private const val AMBIENT = 0.35f
        private const val DIFFUSE = 0.9f

        /** True when [corridor] carries heights a grid can be aligned to. */
        fun canBuild(corridor: CorridorGeometry): Boolean =
            corridor.hasHeights && corridor.display != null && corridor.size > 0

        /** Geographic bounds of the band around [corridor] (the tiles [build] prefetches). */
        fun bounds(corridor: CorridorGeometry, halfWidthM: Double = HALF_WIDTH_M, stepM: Double = STEP_M): GeoBounds {
            var minE = Float.MAX_VALUE; var maxE = -Float.MAX_VALUE
            var minN = Float.MAX_VALUE; var maxN = -Float.MAX_VALUE
            for (i in 0 until corridor.size) {
                minE = minOf(minE, corridor.eastAt(i)); maxE = maxOf(maxE, corridor.eastAt(i))
                minN = minOf(minN, corridor.northAt(i)); maxN = maxOf(maxN, corridor.northAt(i))
            }
            val frame = LocalFrame(corridor.origin)
            val margin = halfWidthM + stepM
            val sw = frame.toGeo(minE - margin, minN - margin)
            val ne = frame.toGeo(maxE + margin, maxN + margin)
            return GeoBounds(sw.latitude, sw.longitude, ne.latitude, ne.longitude)
        }

        /** Prefetches the band around [corridor] and samples it; null when [canBuild] is false or no datum is found. */
        suspend fun build(
            corridor: CorridorGeometry,
            sampler: ElevationSampler,
            stepM: Double = STEP_M,
            halfWidthM: Double = HALF_WIDTH_M
        ): TerrainGrid? {
            if (!canBuild(corridor)) return null
            sampler.prefetch(bounds(corridor, halfWidthM, stepM))
            return sample(corridor, sampler, stepM, halfWidthM)
        }

        /** Samples the grid from [sampler]'s cache (no IO; [build] prefetches first). */
        fun sample(
            corridor: CorridorGeometry,
            sampler: ElevationSampler,
            stepM: Double = STEP_M,
            halfWidthM: Double = HALF_WIDTH_M
        ): TerrainGrid? {
            require(stepM > 0.0 && halfWidthM > 0.0)
            if (!canBuild(corridor)) return null
            val display = corridor.display!!
            val frame = LocalFrame(corridor.origin)
            val exaggeration = corridor.exaggeration

            // Datum: median of DEM − Display over the available corridor samples.
            val offsets = ArrayList<Double>()
            for (i in 0 until corridor.size) {
                if (!corridor.isAvailable(i)) continue
                val p = frame.toGeo(corridor.eastAt(i).toDouble(), corridor.northAt(i).toDouble())
                val s = sampler.sample(p.latitude, p.longitude)
                if (s is ElevationSample.Value) {
                    offsets += s.heightM - (corridor.baseDisplayM + corridor.sceneHeightAt(i) / exaggeration)
                }
            }
            if (offsets.isEmpty()) return null
            offsets.sort()
            val datum = offsets[offsets.size / 2]
            val residual = offsets.map { abs(it - datum) }.sorted()

            // Lattice anchored at 0°; the longitude step uses the whole-degree latitude band, so it does not drift
            // with the corridor origin. When the origin crosses a whole degree (52° / 53° N in Mazowsze) the columns
            // shift once: every node is resampled in that single rebuild.
            val latStep = stepM / SphericalEarth.METERS_PER_DEG_LAT
            val lonStep = stepM / SphericalEarth.metersPerDegLon(floor(corridor.origin.latitude) + 0.5)
            val box = bounds(corridor, halfWidthM, stepM)
            val r0 = floor(box.south / latStep).toLong()
            val r1 = ceil(box.north / latStep).toLong()
            val c0 = floor(box.west / lonStep).toLong()
            val c1 = ceil(box.east / lonStep).toLong()
            val rows = (r1 - r0 + 1).toInt()
            val cols = (c1 - c0 + 1).toInt()
            val n = rows * cols
            val east = FloatArray(n)
            val north = FloatArray(n)
            val height = FloatArray(n) { Float.NaN }
            val inBand = BooleanArray(n)
            var bandCount = 0
            var available = 0
            val half2 = (halfWidthM * halfWidthM).toFloat()
            val pathM = planPathLengths(corridor)
            for (r in 0 until rows) for (c in 0 until cols) {
                val k = r * cols + c
                val lat = (r0 + r) * latStep
                val lon = (c0 + c) * lonStep
                val p = GeoPoint(lat, lon)
                val e = frame.eastM(p).toFloat()
                val nn = frame.northM(p).toFloat()
                east[k] = e
                north[k] = nn
                // Band: within halfWidth of a corridor sample (samples are CorridorGeometry.STEP_M = 3 m apart, so at
                // most 1.5 m short of the polyline distance).
                if (!withinBand(corridor, pathM, e, nn, halfWidthM, half2)) continue
                inBand[k] = true
                bandCount++
                val s = sampler.sample(lat, lon)
                if (s is ElevationSample.Value) {
                    height[k] = (s.heightM - datum).toFloat()
                    available++
                }
            }

            val cells = ArrayList<Int>()
            var dropped = 0
            for (r in 0 until rows - 1) for (c in 0 until cols - 1) {
                val a = r * cols + c
                val b = a + 1
                val d = a + cols
                val e = d + 1
                if (!inBand[a] || !inBand[b] || !inBand[d] || !inBand[e]) continue
                if (height[a].isNaN() || height[b].isNaN() || height[d].isNaN() || height[e].isNaN()) dropped++
                else cells += a
            }
            val cellArray = cells.toIntArray()
            val light = FloatArray(cellArray.size) { shade(cellArray[it], cols, east, north, height, exaggeration) }
            return TerrainGrid(
                routeId = corridor.routeId,
                display = display,
                exaggeration = exaggeration,
                origin = corridor.origin,
                cols = cols,
                rows = rows,
                east = east,
                north = north,
                height = height,
                cells = cellArray,
                light = light,
                stats = TerrainGridStats(
                    nodesInBand = bandCount,
                    nodesAvailable = available,
                    cellsDrawn = cellArray.size,
                    cellsDropped = dropped,
                    ribbonOffsetMedianM = residual[residual.size / 2],
                    ribbonOffsetP95M = residual[((residual.size - 1) * 95.0 / 100.0).roundToInt()]
                )
            )
        }

        /** Cumulative plan length of [corridor]'s sample polyline, including the jumps at GPX part breaks (m). */
        private fun planPathLengths(corridor: CorridorGeometry): DoubleArray {
            val path = DoubleArray(corridor.size)
            for (i in 1 until corridor.size) {
                val dx = (corridor.eastAt(i) - corridor.eastAt(i - 1)).toDouble()
                val dy = (corridor.northAt(i) - corridor.northAt(i - 1)).toDouble()
                path[i] = path[i - 1] + sqrt(dx * dx + dy * dy)
            }
            return path
        }

        /**
         * True when some corridor sample lies within [halfWidthM] of (e, n); the same answer as testing every sample.
         * A sample j is at most path[j] − path[i] from sample i, so while that is shorter than (distance to i −
         * [halfWidthM]) sample j is still outside the band and is skipped without a distance test.
         */
        private fun withinBand(corridor: CorridorGeometry, path: DoubleArray, e: Float, n: Float,
                               halfWidthM: Double, half2: Float): Boolean {
            val size = corridor.size
            var i = 0
            while (i < size) {
                val dx = corridor.eastAt(i) - e
                val dy = corridor.northAt(i) - n
                val d2 = dx * dx + dy * dy
                if (d2 <= half2) return true
                // Margin against float rounding of the sample coordinates: skip a little less than allowed.
                val reach = path[i] + sqrt(d2.toDouble()) - halfWidthM - SKIP_MARGIN_M
                var lo = i + 1
                var hi = size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (path[mid] < reach) lo = mid + 1 else hi = mid
                }
                i = lo
            }
            return false
        }

        private const val SKIP_MARGIN_M = 0.01

        /** Lambert brightness of the cell at south-west node [a], on exaggerated heights. */
        private fun shade(a: Int, cols: Int, x: FloatArray, y: FloatArray, h: FloatArray, exaggeration: Float): Float {
            val b = a + 1
            val c = a + cols
            val d = c + 1
            // Diagonals (d − a) × (b − c) point up in a right-handed east/north/up frame.
            val ux = x[d] - x[a]; val uy = y[d] - y[a]; val uz = (h[d] - h[a]) * exaggeration
            val vx = x[c] - x[b]; val vy = y[c] - y[b]; val vz = (h[c] - h[b]) * exaggeration
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            if (nz < 0f) { nx = -nx; ny = -ny; nz = -nz }
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-6f) return AMBIENT + DIFFUSE * LIGHT_Z
            return AMBIENT + DIFFUSE * max(0f, (nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z) / len)
        }
    }
}
