package pl.mazovia.offroad.reroute

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pl.mazovia.offroad.domain.model.NavigationState
import pl.mazovia.offroad.domain.model.NavigationStatus
import pl.mazovia.offroad.domain.model.RouteSource
import pl.mazovia.offroad.domain.routing.RoutingResult

/** First retry delay after a failed reroute; doubles per consecutive failure up to [REROUTE_RETRY_MAX_MS]. */
internal const val REROUTE_RETRY_START_MS = 10_000L
internal const val REROUTE_RETRY_MAX_MS = 60_000L

/**
 * Starts a reroute when navigation reports OFF_ROUTE on an app-planned route. Imported GPX tracks are never
 * rerouted (they keep their explicit return-to-track flow).
 *
 * A failed reroute leaves navigation in ROUTING_ERROR, which only another reroute can leave, so it is retried with a
 * capped backoff while navigation stays active. At most one reroute runs at a time. Stopping or finishing
 * navigation cancels a running reroute and any pending retry.
 *
 * [scope] must be single-threaded (e.g. `limitedParallelism(1)`): state handling and attempts share mutable fields.
 */
class AutoRerouteController(
    private val state: StateFlow<NavigationState>,
    private val reroute: suspend () -> RoutingResult,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {}
) {
    private var job: Job? = null
    private var consecutiveFailures = 0

    fun start(): Job = scope.launch { state.collect { onState(it) } }

    private fun onState(s: NavigationState) {
        val active = s.route != null && s.route?.source != RouteSource.IMPORTED_GPX &&
            s.status != NavigationStatus.IDLE && s.status != NavigationStatus.ARRIVED
        if (!active) {
            job?.cancel()
            job = null
            consecutiveFailures = 0
            return
        }
        if (job?.isActive == true) return
        when (s.status) {
            NavigationStatus.OFF_ROUTE -> launchReroute(delayMs = 0L)
            NavigationStatus.ROUTING_ERROR -> launchReroute(delayMs = retryDelayMs(consecutiveFailures))
            NavigationStatus.ON_ROUTE -> consecutiveFailures = 0
            else -> Unit
        }
    }

    private fun launchReroute(delayMs: Long) {
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            log("reroute attempt (consecutive failures: $consecutiveFailures)")
            when (val result = reroute()) {
                is RoutingResult.Success -> consecutiveFailures = 0
                is RoutingResult.Error -> {
                    consecutiveFailures++
                    log("reroute failed: ${result.error}")
                }
            }
            // Re-evaluate once this attempt ends: a still-failed state must schedule the next retry even when the
            // state flow does not emit again.
            job = null
            onState(state.value)
        }
    }
}

internal fun retryDelayMs(consecutiveFailures: Int): Long {
    val exp = (consecutiveFailures - 1).coerceIn(0, 10)
    return (REROUTE_RETRY_START_MS shl exp).coerceAtMost(REROUTE_RETRY_MAX_MS)
}
