package pl.mazovia.offroad.terrain.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.terrain.TestFixtures
import pl.mazovia.offroad.terrain.TestFixtures.offset
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** SR-004 / SR-005 (TA-001A-F1). */
class GridOracleTest {

    private val radius = 60.0

    /** Meander plus long single edges: diagonal, horizontal, vertical, near-horizontal, near-vertical. */
    private fun mixedRoute() = TestFixtures.calculatedRoute("mixed", run {
        val pts = TestFixtures.meanderingPoints(1_500).toMutableList()
        fun add(eastM: Double, northM: Double) { pts += offset(pts.last(), eastM, northM) }
        add(1060.0, 1060.0)   // ~1.5 km diagonal, crosses many cells
        add(1200.0, 0.0)      // horizontal
        add(0.0, -1200.0)     // vertical
        add(1000.0, 5.0)      // near-horizontal
        add(5.0, 1000.0)      // near-vertical
        add(-700.0, 350.0)    // back towards the route, several edges per cell nearby
        pts
    }, segmentLength = 400)

    @Test
    fun `coarse grid returns every edge within the search radius - brute-force oracle`() {
        val index = RouteIndex.build(mixedRoute())
        val grid = index.edgeGrid!!
        val positive = index.edges.filter { !it.isZeroLength }
        val rnd = Random(20260924)
        var probes = 0
        var extras = 0L
        fun check(p: GeoPoint) {
            val relevant = positive.filter { index.project(it, p).crossTrackM <= radius }.map { it.index }.toSet()
            val proposed = grid.query(p, radius).toSet()
            assertTrue("grid missed ${relevant - proposed} at $p", proposed.containsAll(relevant))
            extras += (proposed - relevant).size
            probes++
        }
        repeat(6_000) {
            val e = positive[rnd.nextInt(positive.size)]
            val a = index.points[e.fromPointIndex]
            val b = index.points[e.toPointIndex]
            val t = rnd.nextDouble()
            val base = GeoPoint(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
            val angle = rnd.nextDouble() * 2 * Math.PI
            val d = rnd.nextDouble() * 80.0
            val p = offset(base, d * sin(angle), d * cos(angle))
            check(p)
            // Same probe snapped exactly onto the nearest cell boundaries (both axes, and each axis alone).
            val latB = grid.lat0 + Math.round((p.latitude - grid.lat0) / grid.cellLatDeg) * grid.cellLatDeg
            val lonB = grid.lon0 + Math.round((p.longitude - grid.lon0) / grid.cellLonDeg) * grid.cellLonDeg
            check(GeoPoint(latB, lonB))
            check(GeoPoint(latB, p.longitude))
            check(GeoPoint(p.latitude, lonB))
        }
        println("GRID ORACLE | probes=$probes edges=${positive.size} extra candidates/probe=${"%.1f".format(extras.toDouble() / probes)} (allowed)")
    }

    @Test
    fun `2 km diagonal edge - equirectangular matching error stays within the derived tolerance`() {
        val a = TestFixtures.ORIGIN
        val b = offset(a, 1414.2, 1414.2)
        val route = TestFixtures.calculatedRoute("diag", listOf(a, b))
        val index = RouteIndex.build(route)
        val edge = index.edges.single()
        val r = 6_371_000.0
        var maxAlongErr = 0.0
        var maxCrossErr = 0.0
        for (f in listOf(0.1, 0.5, 0.9)) for (xt in listOf(-60.0, -30.0, 0.0, 30.0, 60.0)) {
            val base = GeoPoint(a.latitude + f * (b.latitude - a.latitude), a.longitude + f * (b.longitude - a.longitude))
            val p = offset(base, xt * cos(Math.toRadians(45.0)), -xt * sin(Math.toRadians(45.0)))
            // Great-circle oracle from domain primitives (independent of the equirectangular frame).
            val d13 = a.distanceTo(p) / r
            val dTheta = Math.toRadians(a.bearingTo(p) - a.bearingTo(b))
            val crossOracle = abs(asin(sin(d13) * sin(dTheta)) * r)
            val alongOracle = acos(cos(d13) / cos(crossOracle / r)) * r
            val projected = index.project(edge, p)
            maxAlongErr = maxOf(maxAlongErr, abs(projected.distanceAlongM - alongOracle))
            maxCrossErr = maxOf(maxCrossErr, abs(projected.crossTrackM - crossOracle))
        }
        println("DIAGONAL 2 km | max along error=${"%.3f".format(maxAlongErr)} m, max cross-track error=${"%.3f".format(maxCrossErr)} m")
        // DERIVED budget: tan(52.2°)·Δφ with Δφ ≈ 707 m / R → ≈ 1.4e-4 relative → ≤ 0.3 m along 2 km; cross-track far less.
        assertTrue("along error $maxAlongErr", maxAlongErr <= 0.5)
        assertTrue("cross error $maxCrossErr", maxCrossErr <= 0.2)
        assertEquals(2000.0, edge.haversineLengthM, 2.0)
    }
}
