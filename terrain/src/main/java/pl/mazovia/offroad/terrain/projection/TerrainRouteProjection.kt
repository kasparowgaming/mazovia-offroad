package pl.mazovia.offroad.terrain.projection

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.NavigationState
import pl.mazovia.offroad.domain.model.NavigationStatus
import pl.mazovia.offroad.domain.model.Route
import kotlin.math.abs
import kotlin.math.max

/**
 * TARGET parameters of the projection (DESIGN §6.2–§6.3). Presentation-only; none of them changes NavigationManager
 * semantics, and all may be calibrated later.
 *
 * TA-001A implementation TARGETS (not fixed by DESIGN):
 * - [initialAttachCrossTrackM] (60 m): first attach of a route accepts any candidate within the candidate radius.
 * - [confirmationConsistencyM] (50 m): DESIGN §6.3 requires "consistent" confirming fixes without a number; consecutive
 *   pending candidates must agree within this distance (forward jumps: after advancing by the rider's travel).
 * - [reversalConfirmationProgressM] (3 m): a confirming reversal fix must lie at least this much farther in the new
 *   direction than the previous pending fix; 3 m matches NavigationManager's position hold (fixes closer than 3 m to
 *   the last accepted position are replaced by it), so smaller movements are treated as noise.
 */
data class ProjectionConfig(
    val candidateRadiusM: Double = 60.0,
    val initialAttachCrossTrackM: Double = 60.0,
    val backwardHoldM: Double = 8.0,
    val reversalConfirmFixes: Int = 2,
    val forwardJumpMarginM: Double = 50.0,
    val forwardJumpConfirmFixes: Int = 3,
    val confirmationConsistencyM: Double = 50.0,
    val reversalConfirmationProgressM: Double = 3.0,
    val detachConfirmFixes: Int = 2,
    val reattachCrossTrackM: Double = 30.0,
    val reattachMaxBackM: Double = 100.0,
    val windowBaseM: Double = 30.0,
    val windowSpeedFactor: Double = 0.5,
    val continuityLambda: Double = 0.5
)

/**
 * Presentation-side route projection (DESIGN §6). Consumes [NavigationState] read-only and never writes
 * anything back to navigation. When its geometry disagrees with [NavigationState.status], the status wins
 * (OFF_ROUTE / RECALCULATING / ROUTING_ERROR force [ProjectionMode.DETACHED]).
 *
 * Not thread-safe: one instance per collector.
 */
class TerrainRouteProjection(
    private val config: ProjectionConfig = ProjectionConfig(),
    private val indexBuilder: (Route) -> RouteIndex = RouteIndex::build
) {
    var index: RouteIndex? = null
        private set

    private var accepted: EdgeProjection? = null
    private var acceptedConfidence = 0f
    private var attached = false
    private var direction = 1
    private var lastAttachedS: Double? = null
    private var lastFixNanos: Long? = null
    private var detachCount = 0
    private var reversalCount = 0
    private var reversalS: Double? = null
    private var forwardCount = 0
    private var forwardS: Double? = null

    fun onNavigationState(state: NavigationState, nowNanos: Long): ProjectionResult {
        val route = state.route
        if (route == null || state.status == NavigationStatus.IDLE) {
            index = null
            resetTracking()
            return ProjectionResult.NO_ROUTE
        }
        val idx = index?.takeIf { it.routeId == route.id } ?: indexBuilder(route).also {
            index = it
            resetTracking()
        }
        val position = state.currentPosition ?: return hold(route.id)

        val result = when (state.status) {
            NavigationStatus.ARRIVED -> arrived(idx, route.id)
            NavigationStatus.OFF_ROUTE,
            NavigationStatus.RECALCULATING,
            NavigationStatus.ROUTING_ERROR -> detached(idx, route.id, position)
            NavigationStatus.RECOVERED -> {
                resetTracking()
                track(idx, route.id, state, position, nowNanos)
            }
            else -> track(idx, route.id, state, position, nowNanos)
        }
        lastFixNanos = nowNanos
        return result
    }

    private fun track(
        idx: RouteIndex, routeId: String, state: NavigationState, position: GeoPoint, nowNanos: Long
    ): ProjectionResult {
        val candidates = idx.candidates(position, config.candidateRadiusM)
        if (!attached) return attach(idx, routeId, position, candidates)

        val previous = accepted!!
        if (candidates.isEmpty()) {
            detachCount++
            return if (detachCount >= config.detachConfirmFixes) detached(idx, routeId, position) else hold(routeId)
        }
        detachCount = 0

        val speed = sanitizeSpeed(state.currentSpeedMps)
        val dt = lastFixNanos?.let { ((nowNanos - it) / 1e9).coerceAtLeast(0.0) } ?: 0.0
        val travel = speed * dt
        val sPrev = previous.distanceAlongM
        val sExpected = sPrev + direction * travel
        val window = config.windowBaseM + travel * config.windowSpeedFactor
        val order = compareBy<EdgeProjection>(
            { it.crossTrackM + config.continuityLambda * max(0.0, abs(it.distanceAlongM - sExpected) - window) },
            { it.crossTrackM },
            { it.edge.index }
        )
        val best = candidates.minWith(order)
        val forwardLimit = travel + config.forwardJumpMarginM
        val delta = (best.distanceAlongM - sPrev) * direction

        return when {
            delta < -config.backwardHoldM -> {
                clearForward()
                // A confirming fix must continue the reversal hypothesis: within the consistency bound AND at least
                // reversalConfirmationProgressM farther in the new (opposite) direction than the previous pending fix.
                val pending = reversalS
                val coherent = pending != null &&
                    abs(best.distanceAlongM - pending) <= config.confirmationConsistencyM &&
                    (pending - best.distanceAlongM) * direction >= config.reversalConfirmationProgressM
                reversalCount = if (coherent) reversalCount + 1 else 1
                reversalS = best.distanceAlongM
                if (reversalCount >= config.reversalConfirmFixes) {
                    direction = -direction
                    clearReversal()
                    accept(routeId, best)
                } else hold(routeId)
            }
            delta < 0.0 -> {
                clearReversal(); clearForward()
                hold(routeId)
            }
            delta > forwardLimit -> {
                clearReversal()
                val expectedNext = forwardS?.plus(direction * travel)
                forwardCount = if (expectedNext != null && abs(best.distanceAlongM - expectedNext) <= config.confirmationConsistencyM)
                    forwardCount + 1 else 1
                forwardS = best.distanceAlongM
                if (forwardCount >= config.forwardJumpConfirmFixes) {
                    clearForward()
                    accept(routeId, best)
                } else {
                    val constrained = candidates.filter {
                        val d = (it.distanceAlongM - sPrev) * direction
                        d >= 0.0 && d <= forwardLimit
                    }.minWithOrNull(order)
                    if (constrained != null) accept(routeId, constrained) else hold(routeId)
                }
            }
            else -> {
                clearReversal(); clearForward()
                accept(routeId, best)
            }
        }
    }

    private fun attach(
        idx: RouteIndex, routeId: String, position: GeoPoint, candidates: List<EdgeProjection>
    ): ProjectionResult {
        val last = lastAttachedS
        val limit = if (last == null) config.initialAttachCrossTrackM else config.reattachCrossTrackM
        val best = candidates
            .filter { it.crossTrackM <= limit && (last == null || (it.distanceAlongM - last) * direction >= -config.reattachMaxBackM) }
            .minWithOrNull(compareBy({ it.crossTrackM }, { it.distanceAlongM }, { it.edge.index }))
            ?: return detached(idx, routeId, position)
        attached = true
        detachCount = 0
        return accept(routeId, best)
    }

    private fun accept(routeId: String, projection: EdgeProjection): ProjectionResult {
        accepted = projection
        lastAttachedS = projection.distanceAlongM
        acceptedConfidence = (1.0 - projection.crossTrackM / config.candidateRadiusM).coerceIn(0.0, 1.0).toFloat()
        return result(routeId, ProjectionMode.ATTACHED, projection, projection.crossTrackM, acceptedConfidence)
    }

    private fun hold(routeId: String): ProjectionResult {
        val p = accepted ?: return ProjectionResult(routeId, ProjectionMode.HOLD, null, null, null, null, null, null, 0f)
        return result(routeId, ProjectionMode.HOLD, p, p.crossTrackM, acceptedConfidence)
    }

    private fun detached(idx: RouteIndex, routeId: String, position: GeoPoint): ProjectionResult {
        attached = false
        detachCount = 0
        clearReversal(); clearForward()
        val nearestCrossTrack = idx.candidates(position, config.candidateRadiusM).minOfOrNull { it.crossTrackM }
        val frozen = accepted
        return ProjectionResult(
            routeId = routeId,
            mode = ProjectionMode.DETACHED,
            distanceAlongM = lastAttachedS,
            edgeIndex = frozen?.edge?.index,
            edgeFraction = frozen?.edgeFraction,
            projected = frozen?.projected,
            tangentBearingDeg = frozen?.let { bearing(it) },
            crossTrackM = nearestCrossTrack,
            confidence = 0f
        )
    }

    private fun arrived(idx: RouteIndex, routeId: String): ProjectionResult {
        val end = idx.locate(idx.totalLengthM)
            ?: return ProjectionResult(routeId, ProjectionMode.HOLD, idx.totalLengthM, null, null, null, null, null, 0f)
        accepted = end
        lastAttachedS = end.distanceAlongM
        return result(routeId, ProjectionMode.HOLD, end, 0.0, acceptedConfidence)
    }

    private fun result(routeId: String, mode: ProjectionMode, p: EdgeProjection, crossTrackM: Double, confidence: Float) =
        ProjectionResult(
            routeId = routeId,
            mode = mode,
            distanceAlongM = p.distanceAlongM,
            edgeIndex = p.edge.index,
            edgeFraction = p.edgeFraction,
            projected = p.projected,
            tangentBearingDeg = bearing(p),
            crossTrackM = crossTrackM,
            confidence = confidence
        )

    private fun bearing(p: EdgeProjection): Double =
        if (direction >= 0) p.edge.tangentBearingDeg else (p.edge.tangentBearingDeg + 180.0) % 360.0

    /** Current travel direction along `s` (+1 increasing, −1 after a confirmed reversal). Test/diagnostic access. */
    internal val travelDirection: Int get() = direction

    /** Non-finite or negative speed counts as 0 so it can never disable the forward-jump limit (SR-010). */
    private fun sanitizeSpeed(speedMps: Double?): Double =
        if (speedMps == null || !speedMps.isFinite() || speedMps < 0.0) 0.0 else speedMps

    private fun clearReversal() { reversalCount = 0; reversalS = null }
    private fun clearForward() { forwardCount = 0; forwardS = null }

    private fun resetTracking() {
        accepted = null
        acceptedConfidence = 0f
        attached = false
        direction = 1
        lastAttachedS = null
        lastFixNanos = null
        detachCount = 0
        clearReversal()
        clearForward()
    }
}
