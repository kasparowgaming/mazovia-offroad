package pl.mazovia.offroad.routing.engine

import pl.mazovia.offroad.domain.model.*
import kotlin.math.*

/** Loop-only geometry, overlap measurement, and selection. No A-to-B tournament rules apply. */
internal object LoopPlanner {
    data class Shape(val id: String, val description: String, val waypoints: List<GeoPoint>)
    data class Attempt(val shape: Shape, val route: Route?, val failure: String? = null)
    data class Decision(val shape: Shape, val candidate: LoopCandidate?, val status: String)

    // Three scales permit distance correction without changing the requested target. Six headings
    // avoid betting the entire search on one blocked corridor: 18 initial calls. Recovery
    // adds only six shapes, alternating two smaller radii across complementary headings.
    fun shapes(start: GeoPoint, targetKm: Int, preferredDirection: Double?, recovery: Boolean = false): List<Shape> {
        require(targetKm > 0)
        val base = preferredDirection ?: 0.0
        return (if (recovery) listOf(0.16) else listOf(0.22, 0.27, 0.32)).flatMapIndexed { scaleIndex, scale ->
            (0 until 6).map { headingIndex ->
                val heading = (base + headingIndex * 60.0) % 360.0
                val effectiveScale = if (recovery && headingIndex % 2 == 1) 0.18 else scale
                val radius = targetKm * 1000.0 * effectiveScale
                val a = pointAt(start, heading - 34.0, radius)
                val b = pointAt(start, heading + 34.0, radius * 1.07)
                Shape("s${if (recovery) 4 else scaleIndex + 1}-h${headingIndex + 1}",
                    "triangle heading=${heading.toInt()} scale=$effectiveScale", listOf(a, b))
            }
        }
    }

    fun select(attempts: List<Attempt>, targetKm: Int, limit: Int): List<Decision> {
        val prepared = attempts.map { attempt ->
            val route = attempt.route
            if (route == null) return@map Decision(attempt.shape, null, attempt.failure ?: "ROUTING_FAILURE")
            val points = route.allPoints
            if (route.metrics.totalDistanceMeters <= 0 || route.segments.isEmpty() ||
                points.size < 3 || route.origin.distanceTo(route.destination) > 30.0 ||
                points.first().distanceTo(points.last()) > 100.0) {
                return@map Decision(attempt.shape, null, "INVALID_ROUTE")
            }
            val retrace = retraceMeters(route)
            val ratio = retrace / route.metrics.totalDistanceMeters
            val metrics = route.metrics.copy(retraceFraction = ratio)
            val enriched = route.copy(metrics = metrics)
            val score = LoopScore.calculate(metrics, targetKm)
            val spike = localSpike(route, attempt.shape.waypoints)
            val candidate = LoopCandidate(enriched, score, attempt.shape.id, attempt.shape.description,
                retrace, "PRIMARY", spike.distanceMeters, spike.distanceMeters / route.totalDistanceMeters,
                spike.waypointIndex, spikeRejected(spike.distanceMeters, route.totalDistanceMeters))
            val status = when {
                ratio > 0.20 -> "EXCESSIVE_RETRACE"
                candidate.spikeRejected -> "LOCAL_WAYPOINT_SPIKE"
                score.targetDistanceError > 0.25 -> "TARGET_DISTANCE_FAILURE"
                else -> "ELIGIBLE"
            }
            Decision(attempt.shape, candidate, status)
        }.toMutableList()

        // Similar graph geometry can result from different control points. Keep the first route;
        // a route is a duplicate when >=90% of both routes' sampled road cells overlap.
        val unique = mutableListOf<Int>()
        for (index in prepared.indices) {
            val candidate = prepared[index].candidate ?: continue
            if (prepared[index].status != "ELIGIBLE") continue
            val cells = cells(candidate.route)
            val duplicate = unique.any { previous ->
                val other = cells(prepared[previous].candidate!!.route)
                cells.isNotEmpty() && other.isNotEmpty() &&
                    cells.intersect(other).size.toDouble() / max(cells.size, other.size) >= 0.90
            }
            if (duplicate) prepared[index] = prepared[index].copy(status = "DUPLICATE")
            else unique.add(index)
        }

        val eligible = unique.map { prepared[it] }
        val lowRetrace = eligible.filter { it.candidate!!.score.retraceFraction <= 0.10 }
        val primary = lowRetrace.filter { it.candidate!!.score.targetDistanceError <= 0.15 }
        val pool = when {
            primary.isNotEmpty() -> primary
            lowRetrace.isNotEmpty() -> lowRetrace
            else -> eligible.filter { it.candidate!!.score.targetDistanceError <= 0.15 }
                .ifEmpty { eligible }
        }
        val fallback = pool.none { it.candidate!!.score.targetDistanceError <= 0.15 }
        // Hierarchy: retrace <=10%, then primary distance band, then distance in 5% buckets,
        // useful terrain, continuity/connectors, exact error, stable candidate ID. Fallback
        // prioritizes closest distance after the retrace tier.
        val sorted = pool.sortedWith(compareBy<Decision> {
            if (fallback) it.candidate!!.score.targetDistanceError
            else if (it.candidate!!.score.retraceFraction <= 0.10) 0.0 else 1.0
        }.thenBy {
            if (fallback) { if (it.candidate!!.score.retraceFraction <= 0.10) 0.0 else 1.0 }
            else floor(it.candidate!!.score.targetDistanceError / 0.05)
        }.thenByDescending { it.candidate!!.route.metrics.offRoadDistanceMeters - it.candidate.retraceDistanceMeters }
            .thenByDescending { it.candidate!!.route.metrics.longestContinuousTerrainMeters }
            .thenBy { it.candidate!!.route.metrics.longestAsphaltConnectorMeters }
            .thenBy { it.candidate!!.score.targetDistanceError }
            .thenBy { it.shape.id })
        val selectedIds = sorted.take(limit.coerceAtLeast(0)).map { it.shape.id }.toSet()
        return prepared.map { decision ->
            if (decision.shape.id in selectedIds) decision.copy(
                candidate = decision.candidate!!.copy(status = if (fallback) "FALLBACK_25_PERCENT" else "PRIMARY"),
                status = if (fallback) "SELECTED_FALLBACK" else "SELECTED"
            ) else if (decision.status == "ELIGIBLE") decision.copy(status = "NOT_SELECTED") else decision
        }
    }

    /** Repeated road cells, rather than point intersections, count as retrace. 12 m cells and
     * direction bins keep parallel crossings distinct; consecutive samples in one cell count once. */
    fun retraceMeters(route: Route): Double {
        val visits = mutableMapOf<Cell, Int>()
        var previous: Cell? = null
        for (cell in sampled(route)) {
            if (cell != previous) visits[cell] = (visits[cell] ?: 0) + 1
            previous = cell
        }
        return visits.values.sumOf { (it - 1).coerceAtLeast(0) } * 12.0
    }

    private data class Cell(val x: Int, val y: Int, val direction: Int)
    data class Spike(val distanceMeters: Double, val waypointIndex: Int?)

    // Current graph: rejected short-loop winners 4.56–8.78%; accepted long-loop
    // winners 0.59–1.15%. Use one scale-independent breakpoint in that gap.
    // The absolute floor tolerates short junction detours even on small loops.
    fun spikeRejected(distanceMeters: Double, routeDistanceMeters: Double) =
        distanceMeters > 300.0 && distanceMeters / routeDistanceMeters > 0.02

    /** Longest immediate reverse overlap, measured on one side of an interior turn.
     * Compare equal arc-length positions, not vertex indices: segmentation and direction do
     * not matter. 18 m tolerance absorbs snapping/noise; 12 m steps bound measurement error.
     * The route is not wrapped, so common start/end access is not an interior turnback. */
    fun localSpike(route: Route, waypoints: List<GeoPoint>): Spike {
        val points = route.allPoints.distinctUntilChanged()
        if (points.size < 3) return Spike(0.0, null)
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + points[i - 1].distanceTo(points[i])
        fun at(distance: Double): GeoPoint {
            val found = cumulative.binarySearch(distance)
            if (found >= 0) return points[found]
            val right = (-found - 1).coerceIn(1, points.lastIndex)
            val left = right - 1
            val t = (distance - cumulative[left]) / (cumulative[right] - cumulative[left])
            return GeoPoint(points[left].latitude + t * (points[right].latitude - points[left].latitude),
                points[left].longitude + t * (points[right].longitude - points[left].longitude))
        }
        var longest = 0.0
        var tip: GeoPoint? = null
        for (i in 1 until points.lastIndex) {
            val center = cumulative[i]
            val available = min(center, cumulative.last() - center)
            var offset = 24.0 // Tiny overlap at a junction is not a spike.
            var matched = 0.0
            while (offset <= available) {
                if (at(center - offset).distanceTo(at(center + offset)) > 18.0) break
                matched = offset
                offset += 12.0
            }
            if (matched > longest) { longest = matched; tip = points[i] }
        }
        return Spike(longest, tip?.let { p -> waypoints.indices.minByOrNull { waypoints[it].distanceTo(p) } })
    }

    private fun List<GeoPoint>.distinctUntilChanged() = filterIndexed { index, point ->
        index == 0 || point.distanceTo(this[index - 1]) > 0.01
    }
    private fun cells(route: Route) = sampled(route).toSet()
    private fun sampled(route: Route): List<Cell> {
        val points = route.segments.flatMap { it.points }
        if (points.size < 2) return emptyList()
        val origin = route.origin
        val cosLat = cos(Math.toRadians(origin.latitude))
        fun xy(point: GeoPoint) = Pair((point.longitude - origin.longitude) * 111_195.0 * cosLat,
            (point.latitude - origin.latitude) * 111_195.0)
        val result = ArrayList<Cell>()
        for (i in 0 until points.lastIndex) {
            val (x1, y1) = xy(points[i]); val (x2, y2) = xy(points[i + 1])
            val length = hypot(x2 - x1, y2 - y1)
            if (length < 1.0) continue
            val steps = ceil(length / 12.0).toInt()
            // Center the undirected angle bins on cardinal directions. A tiny sideways
            // displacement must not move an otherwise identical road into a new bin.
            val direction = floor(((atan2(y2 - y1, x2 - x1) + PI) % PI) / PI * 12 + 0.5).toInt() % 12
            for (step in 0 until steps) {
                val t = (step + 0.5) / steps
                result.add(Cell(floor((x1 + (x2 - x1) * t) / 12).toInt(),
                    floor((y1 + (y2 - y1) * t) / 12).toInt(), direction))
            }
        }
        return result
    }

    private fun pointAt(origin: GeoPoint, bearing: Double, distance: Double): GeoPoint {
        val angle = Math.toRadians(bearing)
        val latitude = origin.latitude + Math.toDegrees(distance * cos(angle) / 6_371_000.0)
        val longitude = origin.longitude + Math.toDegrees(distance * sin(angle) /
            (6_371_000.0 * cos(Math.toRadians(origin.latitude))))
        return GeoPoint(latitude, longitude)
    }
}
