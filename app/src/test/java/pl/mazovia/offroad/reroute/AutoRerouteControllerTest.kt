package pl.mazovia.offroad.reroute

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.mazovia.offroad.domain.model.NavigationState
import pl.mazovia.offroad.domain.model.NavigationStatus
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RouteSource
import pl.mazovia.offroad.domain.routing.RoutingError
import pl.mazovia.offroad.domain.routing.RoutingResult

@OptIn(ExperimentalCoroutinesApi::class)
class AutoRerouteControllerTest {
    private val planned = route(RouteSource.CALCULATED_ROUTE)
    private val gpx = route(RouteSource.IMPORTED_GPX)

    private fun route(source: RouteSource): Route = mockk { every { this@mockk.source } returns source }
    private fun st(status: NavigationStatus, r: Route? = planned) = NavigationState(status = status, route = r)

    /** Fake navigation: reroute() mirrors NavigationManager's status transitions. */
    private inner class Harness(val scope: TestScope) {
        val state = MutableStateFlow(st(NavigationStatus.ON_ROUTE))
        var calls = 0
        var results = ArrayDeque<RoutingResult>()
        var gate: CompletableDeferred<Unit>? = null
        val controller = AutoRerouteController(state, {
            calls++
            state.value = st(NavigationStatus.RECALCULATING)
            gate?.await()
            val r = results.removeFirstOrNull() ?: RoutingResult.Success(planned)
            state.value = st(if (r is RoutingResult.Success) NavigationStatus.ON_ROUTE else NavigationStatus.ROUTING_ERROR)
            r
        }, scope.backgroundScope)
    }

    private fun harness(scope: TestScope) = Harness(scope).also { it.controller.start(); scope.runCurrent() }

    @Test
    fun offRouteOnPlannedRouteReroutesOnce() = runTest(StandardTestDispatcher()) {
        val h = harness(this)
        h.state.value = st(NavigationStatus.OFF_ROUTE)
        runCurrent()
        assertEquals(1, h.calls)
        assertEquals(NavigationStatus.ON_ROUTE, h.state.value.status)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, h.calls)
    }

    @Test
    fun gpxTrackIsNeverRerouted() = runTest(StandardTestDispatcher()) {
        val h = harness(this)
        h.state.value = st(NavigationStatus.OFF_ROUTE, gpx)
        runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, h.calls)
    }

    @Test
    fun noParallelReroutes() = runTest(StandardTestDispatcher()) {
        val h = harness(this)
        h.gate = CompletableDeferred()
        h.state.value = st(NavigationStatus.OFF_ROUTE)
        runCurrent()
        h.state.value = st(NavigationStatus.OFF_ROUTE)
        runCurrent()
        assertEquals(1, h.calls)
        h.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, h.calls)
        assertEquals(NavigationStatus.ON_ROUTE, h.state.value.status)
    }

    @Test
    fun failedRerouteRetriesWithCappedBackoffUntilSuccess() = runTest(StandardTestDispatcher()) {
        val h = harness(this)
        repeat(4) { h.results.addLast(RoutingResult.Error(RoutingError.CALCULATION_ERROR)) }
        h.state.value = st(NavigationStatus.OFF_ROUTE)
        runCurrent()
        assertEquals(1, h.calls)
        assertEquals(NavigationStatus.ROUTING_ERROR, h.state.value.status)

        advanceTimeBy(9_999); runCurrent(); assertEquals(1, h.calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, h.calls)      // +10 s
        advanceTimeBy(20_000); runCurrent(); assertEquals(3, h.calls) // +20 s
        advanceTimeBy(40_000); runCurrent(); assertEquals(4, h.calls) // +40 s
        advanceTimeBy(59_999); runCurrent(); assertEquals(4, h.calls) // capped at 60 s
        advanceTimeBy(1); runCurrent(); assertEquals(5, h.calls)
        assertEquals(NavigationStatus.ON_ROUTE, h.state.value.status)
        advanceTimeBy(300_000); runCurrent(); assertEquals(5, h.calls)
    }

    @Test
    fun stoppingNavigationCancelsPendingRetry() = runTest(StandardTestDispatcher()) {
        val h = harness(this)
        h.results.addLast(RoutingResult.Error(RoutingError.CALCULATION_ERROR))
        h.state.value = st(NavigationStatus.OFF_ROUTE)
        runCurrent()
        h.state.value = NavigationState(status = NavigationStatus.IDLE)
        runCurrent()
        advanceTimeBy(300_000); runCurrent()
        assertEquals(1, h.calls)
    }

    @Test
    fun stoppingNavigationCancelsRunningReroute() = runTest(StandardTestDispatcher()) {
        val h = harness(this)
        h.gate = CompletableDeferred()
        h.state.value = st(NavigationStatus.OFF_ROUTE)
        runCurrent()
        h.state.value = NavigationState(status = NavigationStatus.IDLE)
        runCurrent()
        h.gate!!.complete(Unit)
        runCurrent()
        assertEquals(NavigationStatus.IDLE, h.state.value.status)
        assertEquals(1, h.calls)
    }

    @Test
    fun backoffResetsAfterSuccess() = runTest(StandardTestDispatcher()) {
        val h = harness(this)
        repeat(2) { h.results.addLast(RoutingResult.Error(RoutingError.CALCULATION_ERROR)) }
        h.state.value = st(NavigationStatus.OFF_ROUTE)
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(3, h.calls)
        // Next deviation fails once more: the retry starts again at 10 s.
        h.results.addLast(RoutingResult.Error(RoutingError.CALCULATION_ERROR))
        h.state.value = st(NavigationStatus.OFF_ROUTE)
        runCurrent()
        assertEquals(4, h.calls)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(5, h.calls)
    }

    @Test
    fun retryDelays() {
        assertEquals(listOf(10_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L),
            listOf(0, 1, 2, 3, 4, 20).map { retryDelayMs(it) })
    }
}
