package pl.mazovia.offroad.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.domain.location.LocationClient
import pl.mazovia.offroad.domain.location.LocationUpdate
import pl.mazovia.offroad.domain.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationManagerLocationRecoveryTest {

    /** Mid-route position (route runs 52.2 → 52.0 along 21.0), far from the destination. */
    private val onRoute = LocationUpdate(point = GeoPoint(52.15, 21.0), speedMps = 5.0, bearing = 180.0)

    private sealed interface Mode {
        /** Mirrors AndroidLocationClient throwing before any registration (permission / provider / sync error). */
        data class FailBeforeRegistration(val error: Throwable) : Mode
        /** Registers and delivers whatever the test sends until the test fails or completes it. */
        object Deliver : Mode
    }

    /** Scripted client; each collection is one subscription attempt. Tracks registrations and removals. */
    private class FakeLocationClient(private val clock: () -> Long) : LocationClient {
        var mode: Mode = Mode.Deliver
        val attemptTimes = mutableListOf<Long>()
        val activeAtAttemptStart = mutableListOf<Int>()
        val requestedIntervals = mutableListOf<Long>()
        var active = 0
            private set
        var maxActive = 0
            private set
        var removed = 0
            private set
        private var sink: Channel<LocationUpdate>? = null

        override fun getLocationUpdates(intervalMs: Long): Flow<LocationUpdate> = flow {
            requestedIntervals += intervalMs
            attemptTimes += clock()
            activeAtAttemptStart += active
            when (val current = mode) {
                is Mode.FailBeforeRegistration -> throw current.error
                Mode.Deliver -> {
                    val channel = Channel<LocationUpdate>(Channel.UNLIMITED)
                    sink = channel
                    active++
                    maxActive = maxOf(maxActive, active)
                    try {
                        for (update in channel) emit(update)
                    } finally {
                        active--
                        removed++
                        if (sink === channel) sink = null
                    }
                }
            }
        }

        fun send(update: LocationUpdate) = assertTrue(requireNotNull(sink).trySend(update).isSuccess)

        /** Registered subscription later fails (mode D once AndroidLocationClient closes the flow). */
        fun failActive(error: Throwable) = requireNotNull(sink).close(error)

        fun completeActive() = requireNotNull(sink).close()
    }

    private class Harness(val client: FakeLocationClient, val manager: NavigationManager, val scope: CoroutineScope,
                          val logs: MutableList<String>)

    private fun TestScope.harness(): Harness {
        val client = FakeLocationClient { testScheduler.currentTime }
        val logs = mutableListOf<String>()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val manager = NavigationManager(MockRoutingEngine(), client, scope) { logs += it }
        return Harness(client, manager, scope, logs)
    }

    private fun Harness.assertPositionDelivered() {
        manager.startNavigation(createTestRoute())
        client.send(onRoute)
    }

    @Test
    fun `successful initial subscription delivers location and schedules no retry`() = runTest {
        val h = harness()
        runCurrent()
        h.assertPositionDelivered()
        runCurrent()

        val state = h.manager.navigationState.value
        assertEquals(onRoute.point, state.currentPosition)
        assertEquals(5.0, state.currentSpeedMps!!, 0.0)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, h.client.attemptTimes.size)
        assertEquals(listOf(NavigationManager.LOCATION_INTERVAL_MS), h.client.requestedIntervals)
        assertEquals(1, h.client.active)
        assertEquals(1, h.client.maxActive)
        assertTrue(h.logs.none { it.contains("retry") })
    }

    @Test
    fun `initial subscription failure is captured and retried after the first backoff step`() = runTest {
        val h = harness()
        h.client.mode = Mode.FailBeforeRegistration(LocationClient.LocationException("GPS jest wyłączony"))
        runCurrent()
        assertEquals(1, h.client.attemptTimes.size)
        assertTrue(h.logs.any { it.contains("LocationException") && it.contains("retry #1 in 1000 ms") })

        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, h.client.attemptTimes.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), h.client.attemptTimes)
    }

    @Test
    fun `later successful attempt recovers position and speed in the same manager`() = runTest {
        val h = harness()
        h.client.mode = Mode.FailBeforeRegistration(LocationClient.LocationException("GPS jest wyłączony"))
        runCurrent()
        assertNull(h.manager.navigationState.value.currentPosition)

        h.client.mode = Mode.Deliver
        advanceTimeBy(1_000)
        runCurrent()
        h.assertPositionDelivered()
        runCurrent()

        val state = h.manager.navigationState.value
        assertEquals(onRoute.point, state.currentPosition)
        assertEquals(5.0, state.currentSpeedMps!!, 0.0)
        assertTrue(h.logs.any { it.contains("recovered after 1 failures") })
        assertEquals(1, h.client.active)
        assertEquals(1, h.client.maxActive)
    }

    @Test
    fun `failure after registration cleans up before retry and never exceeds one subscription`() = runTest {
        val h = harness()
        runCurrent()
        h.assertPositionDelivered()
        runCurrent()
        repeat(3) { round ->
            h.client.failActive(IllegalStateException("async registration failed"))
            runCurrent()
            assertEquals("round $round", 0, h.client.active)
            advanceTimeBy(1_000) // a delivered update resets the backoff, so each retry is 1 s
            runCurrent()
            assertEquals(1, h.client.active)
            h.client.send(onRoute)
            runCurrent()
        }
        h.client.completeActive() // mode C: unexpected completion is retried as well
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(5, h.client.attemptTimes.size)
        assertEquals(List(5) { 0 }, h.client.activeAtAttemptStart)
        assertEquals(4, h.client.removed)
        assertEquals(1, h.client.active)
        assertEquals(1, h.client.maxActive)
        assertTrue(h.logs.any { it.contains("flow completed") })
    }

    @Test
    fun `repeated failures follow the capped backoff without busy looping`() = runTest {
        val h = harness()
        h.client.mode = Mode.FailBeforeRegistration(SecurityException("denied"))
        runCurrent()
        advanceTimeBy(60 * 60_000L) // one virtual hour
        runCurrent()

        val gaps = h.client.attemptTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), gaps.take(7))
        assertTrue(gaps.drop(5).all { it == NavigationManager.LOCATION_RETRY_MAX_DELAY_MS })
        // 0,1,3,7,15,31 s then every 30 s up to 3571 s: 6 + 118 attempts; no retry storm, no exhaustion.
        assertEquals(124, h.client.attemptTimes.size)
        assertEquals(0, h.client.maxActive)
    }

    @Test
    fun `retries continue at the cap and a navigation event wakes a pending retry early`() = runTest {
        val h = harness()
        h.client.mode = Mode.FailBeforeRegistration(LocationClient.LocationException("Brak uprawnień do lokalizacji"))
        runCurrent()
        advanceTimeBy(31_000 + 5_000) // attempts at 0,1,3,7,15,31 s; now waiting 30 s at the cap
        runCurrent()
        assertEquals(6, h.client.attemptTimes.size)

        h.client.mode = Mode.Deliver
        h.manager.startNavigation(createTestRoute())
        runCurrent()
        assertEquals(7, h.client.attemptTimes.size)
        assertEquals(36_000L, h.client.attemptTimes.last())
        h.client.send(onRoute)
        runCurrent()
        assertEquals(onRoute.point, h.manager.navigationState.value.currentPosition)
        assertTrue(h.logs.any { it.contains("woken early") })
    }

    @Test
    fun `navigation events during healthy collection do not trigger an immediate retry later`() = runTest {
        val h = harness()
        runCurrent()
        h.assertPositionDelivered()
        h.manager.restoreSession(createTestRoute(), 0)
        h.client.send(onRoute) // collection stays healthy after the event
        runCurrent()
        h.client.failActive(IllegalStateException("lost"))
        runCurrent()
        assertEquals(1, h.client.attemptTimes.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), h.client.attemptTimes)
    }

    @Test
    fun `navigation event after the last update is not discarded when the subscription then fails`() = runTest {
        val h = harness()
        runCurrent()
        h.assertPositionDelivered()
        runCurrent()
        advanceTimeBy(20_000) // stream goes silent
        h.manager.startNavigation(createTestRoute())
        h.client.failActive(IllegalStateException("async registration failed"))
        runCurrent()
        assertEquals(listOf(0L, 20_000L), h.client.attemptTimes)
        assertTrue(h.logs.any { it.contains("woken early") })
        assertEquals(1, h.client.maxActive)
    }

    @Test
    fun `permission or provider refusal from the client is never bypassed`() = runTest {
        val h = harness()
        h.client.mode = Mode.FailBeforeRegistration(LocationClient.LocationException("Brak uprawnień do lokalizacji"))
        runCurrent()
        h.manager.startNavigation(createTestRoute())
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        // Every attempt went through the client and was refused; no position was produced meanwhile.
        assertEquals(h.client.attemptTimes.size, h.client.requestedIntervals.size)
        assertEquals(0, h.client.maxActive)
        assertNull(h.manager.navigationState.value.currentPosition)

        h.client.mode = Mode.Deliver
        advanceTimeBy(NavigationManager.LOCATION_RETRY_MAX_DELAY_MS)
        runCurrent()
        h.client.send(onRoute)
        runCurrent()
        assertEquals(onRoute.point, h.manager.navigationState.value.currentPosition)
    }

    @Test
    fun `cancellation during a pending retry stops all later attempts`() = runTest {
        val h = harness()
        h.client.mode = Mode.FailBeforeRegistration(LocationClient.LocationException("GPS jest wyłączony"))
        runCurrent()
        h.scope.cancel()
        runCurrent()
        h.manager.startNavigation(createTestRoute()) // a wake-up after cancellation must not restart anything
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, h.client.attemptTimes.size)
    }

    @Test
    fun `cancellation stops the active collector and removes its registration`() = runTest {
        val h = harness()
        runCurrent()
        assertEquals(1, h.client.active)
        h.scope.cancel()
        runCurrent()
        assertEquals(0, h.client.active)
        assertEquals(1, h.client.removed)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, h.client.attemptTimes.size)
        assertTrue(h.logs.none { it.contains("retry") })
    }

    @Test
    fun `CancellationException from the location flow is not retried`() = runTest {
        val h = harness()
        h.client.mode = Mode.FailBeforeRegistration(CancellationException("cancelled"))
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, h.client.attemptTimes.size)
        assertTrue(h.logs.none { it.contains("retry") })
    }

    @Test
    fun `location logs never contain coordinates`() = runTest {
        val h = harness()
        h.client.mode = Mode.FailBeforeRegistration(LocationClient.LocationException("GPS jest wyłączony"))
        runCurrent()
        h.client.mode = Mode.Deliver
        advanceTimeBy(1_000)
        runCurrent()
        h.assertPositionDelivered()
        runCurrent()
        assertTrue(h.logs.isNotEmpty())
        assertTrue(h.logs.none { it.contains("52.") || it.contains("21.0") })
    }

    @Test
    fun `retry delay policy is capped`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            listOf(1, 2, 3, 4, 5, 6, 7, 1_000).map(NavigationManager::locationRetryDelayMs))
    }

    private fun createTestRoute(): Route {
        val segments = listOf(
            RouteSegment(
                points = listOf(GeoPoint(52.2, 21.0), GeoPoint(52.1, 21.0), GeoPoint(52.0, 21.0)),
                distanceMeters = 20000.0,
                surface = Surface.GRAVEL,
                highway = HighwayType.TRACK
            )
        )
        return Route(
            id = "test_route",
            origin = GeoPoint(52.2, 21.0),
            destination = GeoPoint(52.0, 21.0),
            segments = segments,
            metrics = RouteMetrics.fromSegments(segments),
            profile = RoutingProfile.TERENOWY
        )
    }
}
