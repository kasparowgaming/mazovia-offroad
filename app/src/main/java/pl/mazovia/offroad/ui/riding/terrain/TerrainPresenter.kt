package pl.mazovia.offroad.ui.riding.terrain

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.NavigationState
import pl.mazovia.offroad.domain.model.NavigationStatus
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.terrain.presentation.CorridorGeometry
import pl.mazovia.offroad.terrain.presentation.CorridorRoute
import pl.mazovia.offroad.terrain.presentation.TerrainPresentationState
import pl.mazovia.offroad.terrain.profile.GradeEvent
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.profile.DisplayElevationProfile
import pl.mazovia.offroad.terrain.profile.RouteTerrainProfile
import pl.mazovia.offroad.terrain.projection.EdgeProjection
import pl.mazovia.offroad.terrain.projection.ProjectionMode
import pl.mazovia.offroad.terrain.projection.ProjectionResult
import pl.mazovia.offroad.terrain.projection.RouteIndex
import pl.mazovia.offroad.terrain.projection.TerrainRouteProjection
import pl.mazovia.offroad.terrainsource.TerrainProfileRequest
import kotlin.math.abs

/** DESIGN §15 TARGETS. All distances use the navigation-compatible route axis. */
object TerrainMotionTargets {
    const val BEHIND_M = 50.0
    const val AHEAD_M = 600.0
    const val SPEED_FACTOR = 1.3
    const val MAX_BACKWARD_JITTER_M = 1.0
    const val PREDICTION_SECONDS = 1.0
    const val MAX_PREDICTION_M = 15.0
    const val POSITION_STALE_SECONDS = 3.0
    const val INDICATOR_SECONDS = 5.0
    const val FRAME_INTERVAL_NANOS = 33_000_000L // approximately 30 fps
    const val REVERSAL_TARGET_DELTA_M = 1.0
    const val SNAP_GAP_M = 50.0 // confirmed projection jump: avoid a prolonged, false strip position
}

enum class TerrainVisualMode { IDLE, ATTACHED, HOLD, OFF_ROUTE, ARRIVED }

/** Immutable output for a future Option C instrument. [target] remains the low-rate authoritative state. */
data class TerrainDisplayState(
    val target: TerrainPresentationState?,
    val mode: TerrainVisualMode,
    val distanceAlongM: Double?,
    val windowStartM: Double?,
    val windowEndM: Double?,
    val distanceToRouteM: Double?,
    val positionStale: Boolean,
    val showStaleIndicator: Boolean,
    val needsAnimation: Boolean,
    /** Schedule a one-shot clock callback for stale transitions even when animation has settled. */
    val nextTimedUpdateNanos: Long?,
    val profile: DisplayElevationProfile? = null,
    /** Grade events of the same profile as [profile] (corridor edge colouring, DESIGN §3.7). */
    val events: List<GradeEvent> = emptyList()
)

/** View-scoped, single-threaded presenter. Call [onNavigationState] on emissions and [frame] only while needed. */
class TerrainPresenter(
    private val projection: TerrainRouteProjection = TerrainRouteProjection(),
    grade: GradeProfile? = null,
    events: List<GradeEvent> = emptyList()
) {
    private val fixedGrade = grade
    private val fixedEvents = events
    private val fixedDisplay = grade?.filtered?.let(DisplayElevationProfile::from)
    /** Runtime profile (TA-007); used only while its routeId equals the projected route, so a reroute never shows it. */
    private var loaded: RouteTerrainProfile? = null
    private var loadedDisplay: DisplayElevationProfile? = null
    private val current: RouteTerrainProfile? get() = loaded?.takeIf { it.routeId == routeId }
    private val grade: GradeProfile? get() = current?.grade ?: fixedGrade
    private val events: List<GradeEvent> get() = current?.events ?: fixedEvents
    private val displayProfile: DisplayElevationProfile? get() = if (current != null) loadedDisplay else fixedDisplay
    private var routeId: String? = null
    /** Route of the latest emission; the corridor reads its segments and maneuvers (DESIGN §3.7). */
    private var route: Route? = null
    private val activeIndex: RouteIndex? get() = projection.index?.takeIf { it.routeId == routeId }
    private var request: TerrainProfileRequest? = null

    /** Profile work for the route the presenter currently projects onto; one instance per route. */
    val profileRequest: TerrainProfileRequest?
        get() {
            val index = activeIndex ?: return null
            return request?.takeIf { it.index === index } ?: TerrainProfileRequest(index).also { request = it }
        }

    /** Installs [profile] (or clears it with null) and re-derives the current target from the last projection, so
     *  grade and next-event values follow the new profile without re-feeding a navigation state. Must be called on
     *  the presenter's single thread. */
    fun setProfile(profile: RouteTerrainProfile?) {
        if (profile === loaded) return
        loaded = profile
        loadedDisplay = profile?.grade?.filtered?.let(DisplayElevationProfile::from)
        targetResult?.let { target = TerrainPresentationState.build(it, grade, events) }
    }

    // Option D corridor (TA-007B): route data once per route, window geometry rebuilt only when the rider leaves the
    // built window or the route/profile changes (DESIGN §3.7, §17.1).
    private var corridorRoute: CorridorRoute? = null
    private var corridor: CorridorGeometry? = null
    /** A build that produced nothing: not retried until the route, the profile or ≥ REBUILD_M of progress changes. */
    private var missRouteId: String? = null
    private var missProfile: DisplayElevationProfile? = null
    private var missS = Double.NaN

    private fun corridorFits(frame: TerrainDisplayState, s: Double, index: RouteIndex): Boolean {
        val cached = corridor ?: return false
        return cached.routeId == index.routeId && cached.display === frame.profile &&
            cached.covers(s, index.totalLengthM)
    }

    /** True when [corridor] answers [frame] without building (cheap; the caller may stay on the main thread). */
    fun corridorIsCurrent(frame: TerrainDisplayState): Boolean {
        val s = frame.distanceAlongM ?: return true
        val index = activeIndex ?: return true
        if (corridorFits(frame, s, index)) return true
        return missRouteId == index.routeId && missProfile === frame.profile &&
            abs(s - missS) <= CorridorGeometry.REBUILD_M
    }

    /**
     * Option D window geometry for [frame] (DESIGN §3.7): the cached one while it covers the rider, otherwise a new
     * build (milliseconds: call off the main thread when [corridorIsCurrent] is false). Null without a route position.
     */
    fun corridor(frame: TerrainDisplayState): CorridorGeometry? {
        val s = frame.distanceAlongM ?: return null
        val index = activeIndex ?: return null
        if (corridorFits(frame, s, index)) return corridor
        if (corridorIsCurrent(frame)) return null
        val data = corridorRoute?.takeIf { it.routeId == index.routeId }
            ?: route?.takeIf { it.id == index.routeId }?.let { CorridorRoute.build(it, index) }
        corridorRoute = data
        val built = data?.let { CorridorGeometry.build(it, frame.profile, frame.events, s) }
        corridor = built
        missRouteId = if (built == null) index.routeId else null
        missProfile = frame.profile
        missS = s
        return built
    }

    private var target: TerrainPresentationState? = null
    /** Projection the current [target] was built from; [setProfile] rebuilds the target from it. */
    private var targetResult: ProjectionResult? = null
    private var displayS: Double? = null
    /** Last ACCEPTED (ATTACHED) route-progress target; projection HOLD never replaces it. */
    private var targetS: Double? = null
    /** Time of the accepted target: the prediction clock. HOLD emissions do not restart it. */
    private var targetFixNanos: Long? = null
    /** Speed reported with the accepted target; prediction uses it only while the latest speed is > 0. */
    private var targetSpeed = 0.0
    /** Ordinary (against travel direction) correction is limited to 1 m per accepted target update. */
    private var backwardBudgetM = TerrainMotionTargets.MAX_BACKWARD_JITTER_M
    private var direction = 1
    /** DETACHED was reported since the last accepted target: the next ATTACHED target is a re-attachment (§6.3). */
    private var detachedSinceAccepted = false
    /** Converging to a re-attached target: ≤ 1 m per frame (§15.2) until reached, without the per-target budget. */
    private var reattaching = false
    /** Latest emitted speed: drives the §15.4 freeze and the §15.5 moving/stationary distinction. */
    private var speed = 0.0
    /** Freshness clock: every emission (HOLD included) refreshes it for the 3 s / 5 s stale rules. */
    private var lastEmissionNanos: Long? = null
    private var lastFrameNanos: Long? = null
    private var routeEndM = 0.0
    private var mode = TerrainVisualMode.IDLE
    private var distanceToRouteM: Double? = null
    /** OFF_ROUTE before any attachment (e.g. TEREN opened while already off route): the route point nearest the
     *  rider, so the greyed corridor still shows where the route is. Never a motion target. */
    private var offRouteAnchorS: Double? = null

    fun onNavigationState(state: NavigationState, nowNanos: Long): TerrainDisplayState {
        require(nowNanos >= 0 && (lastFrameNanos == null || nowNanos >= lastFrameNanos!!))
        frame(nowNanos) // finish the previous interval before replacing its target
        val result = projection.onNavigationState(state, nowNanos)
        val newId = result.routeId
        if (newId != routeId || result.mode == ProjectionMode.NO_ROUTE) {
            routeId = newId
            displayS = null
            targetS = null
            targetFixNanos = null
            targetSpeed = 0.0
            backwardBudgetM = TerrainMotionTargets.MAX_BACKWARD_JITTER_M
            direction = 1
            detachedSinceAccepted = false
            reattaching = false
        }
        route = state.route
        lastEmissionNanos = nowNanos
        lastFrameNanos = nowNanos
        routeEndM = projection.index?.totalLengthM ?: 0.0
        speed = state.currentSpeedMps?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        mode = when {
            result.mode == ProjectionMode.NO_ROUTE -> TerrainVisualMode.IDLE
            state.status == NavigationStatus.ARRIVED -> TerrainVisualMode.ARRIVED
            result.mode == ProjectionMode.DETACHED -> TerrainVisualMode.OFF_ROUTE
            result.mode == ProjectionMode.HOLD -> TerrainVisualMode.HOLD
            else -> TerrainVisualMode.ATTACHED
        }
        if (mode == TerrainVisualMode.ARRIVED) {
            // §6.4 ARRIVED = HOLD at end; navigation status wins. Without a currentPosition the projection re-reports
            // the last accepted s, so the route end is imposed here: a terminal transition with no prediction.
            val atEnd = if (result.distanceAlongM == routeEndM) result else result.copy(
                mode = ProjectionMode.HOLD, distanceAlongM = routeEndM, edgeIndex = null, edgeFraction = null,
                projected = null, tangentBearingDeg = null, crossTrackM = 0.0)
            target = TerrainPresentationState.build(atEnd, grade, events)
            targetResult = atEnd
            distanceToRouteM = null
            targetS = routeEndM
            displayS = routeEndM
            targetFixNanos = null
            targetSpeed = 0.0
            backwardBudgetM = TerrainMotionTargets.MAX_BACKWARD_JITTER_M
            direction = 1
            detachedSinceAccepted = false
            reattaching = false
            return snapshot(nowNanos)
        }
        target = TerrainPresentationState.build(result, grade, events)
        targetResult = result
        val nearest = if (mode == TerrainVisualMode.OFF_ROUTE && result.distanceAlongM == null && displayS == null)
            state.currentPosition?.let { position -> projection.index?.let { nearestPoint(it, position) } } else null
        offRouteAnchorS = nearest?.distanceAlongM
        distanceToRouteM = if (mode == TerrainVisualMode.OFF_ROUTE)
            state.distanceToGpxMeters ?: result.crossTrackM ?: nearest?.crossTrackM else null
        if (mode == TerrainVisualMode.OFF_ROUTE) {
            detachedSinceAccepted = true
            reattaching = false
        }

        val incoming = result.distanceAlongM
        // HOLD re-reports the previous accepted progress: it refreshes freshness only, never the motion target
        // (a HOLD value is adopted solely when no target exists yet, without a prediction clock).
        val accepted = result.mode == ProjectionMode.ATTACHED
        if (incoming != null && mode != TerrainVisualMode.OFF_ROUTE && (accepted || targetS == null)) {
            val previousTarget = targetS
            // §6.3 re-attachment may land up to 100 m behind the last attached s: a new target, not a reversal.
            val reattach = result.mode == ProjectionMode.ATTACHED && detachedSinceAccepted && previousTarget != null
            if (result.mode == ProjectionMode.ATTACHED && previousTarget != null && !reattach) {
                val delta = incoming - previousTarget
                if (abs(delta) > TerrainMotionTargets.REVERSAL_TARGET_DELTA_M && delta * direction < 0.0) {
                    direction = -direction // projection already confirmed the opposing ATTACHED target
                }
            }
            val firstAttach = displayS == null
            val gap = if (previousTarget == null) 0.0 else incoming - previousTarget
            // A re-attachment behind converges at ≤ 1 m per frame; only a forward re-attachment may snap.
            val confirmedJump = if (reattach) gap * direction > TerrainMotionTargets.SNAP_GAP_M
                else abs(gap) > TerrainMotionTargets.SNAP_GAP_M
            targetS = incoming.coerceIn(0.0, routeEndM)
            if (accepted) {
                targetFixNanos = nowNanos
                targetSpeed = speed
                backwardBudgetM = TerrainMotionTargets.MAX_BACKWARD_JITTER_M
                detachedSinceAccepted = false
                reattaching = reattaching || reattach // later targets keep converging until the display reaches them
            }
            if (firstAttach || confirmedJump) {
                // First attach, confirmed large jump, and reversal gap snap to the authoritative axis (arrival is handled above).
                displayS = targetS
                reattaching = false
            }
        }
        return snapshot(nowNanos)
    }

    /** Nearest route point to [position] over the whole route (linear scan; only while detached and never attached). */
    private fun nearestPoint(index: RouteIndex, position: GeoPoint): EdgeProjection? =
        index.edges.filter { it.haversineLengthM > 0.0 }.map { index.project(it, position) }
            .minWithOrNull(compareBy({ it.crossTrackM }, { it.distanceAlongM }))

    /** Prediction speed: the accepted target's speed, zeroed while the latest emission reports standstill (§15.4). */
    private val predictionSpeed: Double get() = if (speed > 0.0) targetSpeed else 0.0

    /** Seconds since the accepted target, or null when no prediction clock exists. */
    private fun targetAge(nowNanos: Long): Double? =
        targetFixNanos?.let { ((nowNanos - it) / 1e9).coerceAtLeast(0.0) }

    /** Cheap deterministic frame step; no projection, grade, DEM, IO, or navigation writes. */
    fun frame(nowNanos: Long): TerrainDisplayState {
        require(nowNanos >= 0 && (lastFrameNanos == null || nowNanos >= lastFrameNanos!!))
        val previousFrame = lastFrameNanos
        lastFrameNanos = nowNanos
        val s = displayS
        val age = targetAge(nowNanos)
        val v = predictionSpeed
        if (s != null && age != null && v > 0.0 &&
            (mode == TerrainVisualMode.ATTACHED || mode == TerrainVisualMode.HOLD) && previousFrame != null) {
            val dt = (nowNanos - previousFrame) / 1e9
            val desired = predictedTarget(age)!!
            // Directional step: forward ≤ v·dt·1.3; against travel ≤ 1 m per frame and ≤ the per-target budget
            // (a re-attachment converges at ≤ 1 m per frame without the budget).
            val maxBackward = if (reattaching) TerrainMotionTargets.MAX_BACKWARD_JITTER_M
                else minOf(TerrainMotionTargets.MAX_BACKWARD_JITTER_M, backwardBudgetM)
            val step = ((desired - s) * direction).coerceIn(-maxBackward, v * dt * TerrainMotionTargets.SPEED_FACTOR)
            if (!reattaching) backwardBudgetM -= (-step).coerceAtLeast(0.0)
            val next = (s + direction * step).coerceIn(0.0, routeEndM)
            displayS = next
            if (reattaching && (desired - next) * direction >= -1e-6) reattaching = false
        }
        return snapshot(nowNanos)
    }

    /** Accepted target plus conservative prediction: ≤ 1.0 s, ≤ +15 m, clamped to the route (§15.2). */
    private fun predictedTarget(ageSeconds: Double): Double? = targetS?.let { t ->
        val predictionDistance = (predictionSpeed * ageSeconds.coerceIn(0.0, TerrainMotionTargets.PREDICTION_SECONDS))
            .coerceAtMost(TerrainMotionTargets.MAX_PREDICTION_M)
        (t + direction * predictionDistance).coerceIn(0.0, routeEndM)
    }

    /** Where [frame] can still move the display: the predicted target, limited by the remaining backward budget
     *  (unlimited while re-attaching). */
    private fun reachable(s: Double, desired: Double): Double =
        if (reattaching || (desired - s) * direction >= 0.0) desired
        else s - direction * minOf(backwardBudgetM, abs(desired - s))

    private fun snapshot(nowNanos: Long): TerrainDisplayState {
        val age = lastEmissionNanos?.let { (nowNanos - it) / 1e9 } ?: 0.0
        val active = mode == TerrainVisualMode.ATTACHED || mode == TerrainVisualMode.HOLD
        val stale = active && speed > 0.0 && age >= TerrainMotionTargets.POSITION_STALE_SECONDS
        val s = displayS ?: offRouteAnchorS.takeIf { mode == TerrainVisualMode.OFF_ROUTE }
        val fixAge = targetAge(nowNanos)
        val predicting = s != null && fixAge != null && active && predictionSpeed > 0.0
        val moving = predicting && abs(reachable(s!!, predictedTarget(fixAge!!)!!) - s) > 1e-6
        // A future frame loop can stop at the prediction horizon; a new accepted target restarts it.
        val futureMovement = predicting && fixAge!! < TerrainMotionTargets.PREDICTION_SECONDS &&
            abs(reachable(s!!, predictedTarget(TerrainMotionTargets.PREDICTION_SECONDS)!!) - s) > 1e-6
        val animate = moving || futureMovement
        val nextTimed = if (active && speed > 0.0 && lastEmissionNanos != null) when {
            age < TerrainMotionTargets.POSITION_STALE_SECONDS ->
                lastEmissionNanos!! + (TerrainMotionTargets.POSITION_STALE_SECONDS * 1e9).toLong()
            age < TerrainMotionTargets.INDICATOR_SECONDS ->
                lastEmissionNanos!! + (TerrainMotionTargets.INDICATOR_SECONDS * 1e9).toLong()
            else -> null
        } else null
        return TerrainDisplayState(target, mode, s,
            s?.minus(TerrainMotionTargets.BEHIND_M), s?.plus(TerrainMotionTargets.AHEAD_M),
            distanceToRouteM, stale,
            stale && age >= TerrainMotionTargets.INDICATOR_SECONDS, animate, nextTimed, displayProfile,
            if (displayProfile != null) events else emptyList())
    }
}
