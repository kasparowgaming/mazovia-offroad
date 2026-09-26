package pl.mazovia.offroad.ui.map

import io.mockk.*
import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import pl.mazovia.offroad.domain.location.LocationClient
import pl.mazovia.offroad.domain.location.LocationUpdate
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.*
import pl.mazovia.offroad.domain.search.PlaceSearchRepository
import pl.mazovia.offroad.navigation.NavigationManager
import pl.mazovia.offroad.state.AppModeManager

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val store = androidx.lifecycle.ViewModelStore()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
    }

    @After
    fun teardown() {
        store.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    class AdversarialRoutingEngine : RoutingEngine {
        val requests = mutableListOf<kotlinx.coroutines.CompletableDeferred<RoutingResult>>()
        val cancelledCompletions = mutableListOf<kotlinx.coroutines.CompletableDeferred<RoutingResult>>()
        var lastOrigin: GeoPoint? = null
        var lastRequestedProfile: RoutingProfile? = null

        override suspend fun isReady(): Boolean = true
        override suspend fun getState(): RoutingEngineState = RoutingEngineState(true)

        override suspend fun generateLoopCandidates(params: LoopParameters, candidateCount: Int): List<LoopCandidate> = emptyList()
        override suspend fun calculateAlternatives(origin: GeoPoint, destination: GeoPoint, profile: RoutingProfile, maxAlternatives: Int): List<RoutingResult> = emptyList()
        override suspend fun recalculateFromPosition(currentPosition: GeoPoint, destination: GeoPoint, profile: RoutingProfile): RoutingResult = calculateRoute(currentPosition, destination, profile)

        override suspend fun loadGraph(graphPath: String): Boolean = true
        override suspend fun unloadGraph() {}
        override suspend fun validateAndSwapGraph(tempGraphPath: String): RoutingEngine.ImportResult = RoutingEngine.ImportResult.Success

        override suspend fun calculateRoute(
            origin: GeoPoint,
            destination: GeoPoint,
            profile: RoutingProfile,
            waypoints: List<GeoPoint>
        ): RoutingResult {
            lastOrigin = origin
            lastRequestedProfile = profile
            val deferred = kotlinx.coroutines.CompletableDeferred<RoutingResult>()
            requests.add(deferred)
            val result = withContext(NonCancellable) { deferred.await() }
            if (!kotlin.coroutines.coroutineContext.isActive) cancelledCompletions.add(deferred)
            return result
        }

        fun createRoute(distance: Double, profile: RoutingProfile = RoutingProfile.TERENOWY): RoutingResult.Success {
            val r = Route(
                id = distance.toString(),
                origin = GeoPoint(0.0, 0.0),
                destination = GeoPoint(0.0, 0.0),
                segments = emptyList(),
                metrics = RouteMetrics.EMPTY.copy(totalDistanceMeters = distance),
                profile = profile
            )
            return RoutingResult.Success(r)
        }
    }

    private fun liveFeed(vararg updates: LocationUpdate) = kotlinx.coroutines.flow.flow {
        updates.forEach { emit(it) }
        kotlinx.coroutines.awaitCancellation()
    }

    private fun createViewModel(engine: AdversarialRoutingEngine, gps: Boolean = true): MapViewModel {
        val location = mockk<LocationClient>()
        // Live subscriptions stay open (a completed one is resubscribed); no GPS is an open subscription without fixes.
        every { location.getLocationUpdates(any()) } returns if (gps)
            liveFeed(LocationUpdate(GeoPoint(52.1567802, 22.3448868), null, null)) else liveFeed()
        val model = MapViewModel(
            routingEngine = engine,
            navigationManager = mockk(relaxed = true),
            appModeManager = mockk(relaxed = true),
            locationClient = location,
            placeSearchRepository = mockk(relaxed = true),
            routeRepository = mockk(relaxed = true),
            application = mockk(relaxed = true)
        )
        testDispatcher.scheduler.runCurrent()
        return model
    }

    @Test
    fun `CASE A - latest request wins despite completion order`() = runTest {
        val engine = AdversarialRoutingEngine()
        val viewModel = createViewModel(engine)

        viewModel.setDestination(GeoPoint(1.0, 1.0)) // A
        runCurrent()
        val reqA = engine.requests[0]

        viewModel.setDestination(GeoPoint(2.0, 2.0)) // B
        runCurrent()
        val reqB = engine.requests[1]

        reqB.complete(engine.createRoute(2.0))
        runCurrent()

        assertEquals(2.0, viewModel.uiState.value.calculatedRoute?.totalDistanceMeters)

        reqA.complete(engine.createRoute(1.0))
        runCurrent()

        assertTrue(engine.cancelledCompletions.contains(reqA))
        assertEquals(2.0, viewModel.uiState.value.calculatedRoute?.totalDistanceMeters)
    }

    @Test
    fun `CASE B - clearDestination ignores delayed result`() = runTest {
        val engine = AdversarialRoutingEngine()
        val viewModel = createViewModel(engine)

        viewModel.setDestination(GeoPoint(1.0, 1.0))
        runCurrent()
        val reqA = engine.requests[0]

        viewModel.clearDestination()
        runCurrent()

        reqA.complete(engine.createRoute(1.0))
        runCurrent()

        assertTrue(engine.cancelledCompletions.contains(reqA))
        assertNull(viewModel.uiState.value.calculatedRoute)
        assertNull(viewModel.uiState.value.destination)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `CASE C - profile switch preserves latest result`() = runTest {
        val engine = AdversarialRoutingEngine()
        val viewModel = createViewModel(engine)

        viewModel.selectProfile(RoutingProfile.TERENOWY)
        viewModel.setDestination(GeoPoint(1.0, 1.0))
        runCurrent()
        val reqA = engine.requests[0]
        assertEquals(RoutingProfile.TERENOWY, engine.lastRequestedProfile)

        viewModel.selectProfile(RoutingProfile.ODKRYWCZY)
        runCurrent()
        val reqB = engine.requests[1]
        assertEquals(RoutingProfile.ODKRYWCZY, engine.lastRequestedProfile)

        reqB.complete(engine.createRoute(2.0, RoutingProfile.ODKRYWCZY))
        runCurrent()

        reqA.complete(engine.createRoute(1.0, RoutingProfile.TERENOWY))
        runCurrent()

        assertTrue(engine.cancelledCompletions.contains(reqA))
        assertEquals(RoutingProfile.ODKRYWCZY, viewModel.uiState.value.calculatedRoute?.profile)
    }

    @Test
    fun `CASE D - obsolete error does not affect loading`() = runTest {
        val engine = AdversarialRoutingEngine()
        val viewModel = createViewModel(engine)

        viewModel.setDestination(GeoPoint(1.0, 1.0))
        runCurrent()
        val reqA = engine.requests[0]

        viewModel.setDestination(GeoPoint(2.0, 2.0))
        runCurrent()

        assertTrue(viewModel.uiState.value.isLoading)

        reqA.complete(RoutingResult.Error(RoutingError.CALCULATION_ERROR))
        runCurrent()

        assertTrue(engine.cancelledCompletions.contains(reqA))
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
        engine.requests[1].complete(engine.createRoute(2.0))
        runCurrent()
        assertFalse(viewModel.uiState.value.isLoading)
    }
    @Test fun `missing GPS never requests a Warsaw route and leaves no stale preview`() = runTest {
        val engine = AdversarialRoutingEngine()
        val model = createViewModel(engine, gps = false)
        model.previewSavedRoute(engine.createRoute(1000.0).route)
        model.setDestination(GeoPoint(52.2, 22.3))
        runCurrent()
        assertTrue(engine.requests.isEmpty())
        assertNull(model.uiState.value.calculatedRoute)
        assertFalse(model.uiState.value.showRoutePanel)
        assertEquals(pl.mazovia.offroad.ui.RiderMessages.GPS, model.uiState.value.error)
    }

    @Test fun `destination to preview to Prowadz starts navigation recording and riding`() = runTest {
        val engine = AdversarialRoutingEngine()
        val navigation = mockk<NavigationManager>(relaxed = true)
        val mode = AppModeManager()
        val location = mockk<LocationClient>()
        val position = GeoPoint(52.1567802, 22.3448868)
        every { location.getLocationUpdates(any()) } returns liveFeed(LocationUpdate(position, null, null))
        var recordings = 0
        val model = MapViewModel(engine, navigation, mode, location, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), hasLocationPermission = { true }, startRecording = { recordings++ })
        runCurrent()
        model.setDestination(GeoPoint(52.2, 22.3))
        runCurrent()
        val route = engine.createRoute(25331.84).route
        engine.requests.single().complete(RoutingResult.Success(route))
        runCurrent()
        assertEquals(position, engine.lastOrigin)
        assertTrue(model.uiState.value.showRoutePanel)
        model.startNavigation()
        verify { navigation.startNavigation(route) }
        assertEquals(1, recordings)
        assertEquals(AppMode.RIDING, mode.currentMode.value)
        // Reopening a saved route uses exactly that route, without asking the engine to recalculate.
        model.previewSavedRoute(route)
        model.startNavigation()
        assertEquals(1, engine.requests.size)
        verify(exactly = 2) { navigation.startNavigation(route) }
    }

    // --- TASK-MAP-LOC-001: map location feed recovery -------------------------------------------------------------

    /**
     * Each subscription follows the next script (a silent open subscription once scripts run out) and records its
     * virtual start time; [active] counts subscriptions currently being collected.
     */
    private class ScriptedLocationClient(private val scheduler: TestCoroutineScheduler) : LocationClient {
        val scripts = ArrayDeque<suspend kotlinx.coroutines.flow.FlowCollector<LocationUpdate>.() -> Unit>()
        val attemptTimes = mutableListOf<Long>()
        var throwOnCall: Exception? = null
        var active = 0
        var maxActive = 0

        override fun getLocationUpdates(intervalMs: Long): kotlinx.coroutines.flow.Flow<LocationUpdate> {
            attemptTimes += scheduler.currentTime
            throwOnCall?.let { throwOnCall = null; throw it }
            val script = scripts.removeFirstOrNull() ?: { kotlinx.coroutines.awaitCancellation() }
            return kotlinx.coroutines.flow.flow {
                active++
                maxActive = maxOf(maxActive, active)
                try { script() } finally { active-- }
            }
        }
    }

    private val fix = LocationUpdate(GeoPoint(52.1567802, 22.3448868), null, null)
    private val gpsOff = LocationClient.LocationException("GPS jest wyłączony")

    private fun failAtStart(): suspend kotlinx.coroutines.flow.FlowCollector<LocationUpdate>.() -> Unit = { throw gpsOff }
    private fun emitThenHang(): suspend kotlinx.coroutines.flow.FlowCollector<LocationUpdate>.() -> Unit =
        { emit(fix); kotlinx.coroutines.awaitCancellation() }

    private fun trackingViewModel(client: LocationClient): MapViewModel {
        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MapViewModel(
                    AdversarialRoutingEngine(), mockk(relaxed = true), mockk(relaxed = true), client,
                    mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
                ) as T
            }
        }
        return androidx.lifecycle.ViewModelProvider(store, factory)[MapViewModel::class.java]
    }

    @Test fun `successful startup subscribes once, delivers the position and never retries`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply { scripts += emitThenHang() }
        val model = trackingViewModel(client)
        runCurrent()
        assertEquals(fix.point, model.uiState.value.currentPosition)
        assertEquals(1, client.active)
        advanceTimeBy(600_000); runCurrent()
        assertEquals(listOf(0L), client.attemptTimes)
        assertEquals(1, client.maxActive)
    }

    @Test fun `failure thrown by getLocationUpdates itself is retried after the initial delay`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply { throwOnCall = gpsOff; scripts += emitThenHang() }
        val model = trackingViewModel(client)
        runCurrent()
        assertNull(model.uiState.value.currentPosition)
        advanceTimeBy(999); runCurrent()
        assertEquals(listOf(0L), client.attemptTimes)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(0L, 1_000L), client.attemptTimes)
        assertEquals(fix.point, model.uiState.value.currentPosition)
    }

    @Test fun `location off at startup recovers in the same ViewModel once location is back`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply {
            repeat(3) { scripts += failAtStart() }
            scripts += emitThenHang()
        }
        val model = trackingViewModel(client)
        runCurrent()
        assertNull(model.uiState.value.currentPosition)
        assertEquals(0, client.active)
        advanceTimeBy(7_000); runCurrent()
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), client.attemptTimes)
        assertEquals(fix.point, model.uiState.value.currentPosition)
        assertEquals(1, client.active)
        assertEquals(1, client.maxActive)
        verify { android.util.Log.i(MapViewModel.LOCATION_LOG_TAG, "subscription recovered after 3 failures") }
    }

    @Test fun `repeated failures back off to the cap without busy looping or stacking subscriptions`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply { repeat(100) { scripts += failAtStart() } }
        trackingViewModel(client)
        advanceTimeBy(151_000); runCurrent()
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 61_000L, 91_000L, 121_000L, 151_000L),
            client.attemptTimes)
        assertEquals(0, client.active)
        assertTrue(client.maxActive <= 1)
        assertEquals(1_000L, MapViewModel.locationRetryDelayMs(1))
        assertEquals(30_000L, MapViewModel.locationRetryDelayMs(Int.MAX_VALUE))
    }

    @Test fun `late failure clears the position and resubscribes after the reset initial delay`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply {
            scripts += failAtStart()
            scripts += failAtStart()
            scripts += { emit(fix); kotlinx.coroutines.delay(60_000); throw gpsOff }
            scripts += emitThenHang()
        }
        val model = trackingViewModel(client)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(fix.point, model.uiState.value.currentPosition)
        advanceTimeBy(60_000); runCurrent()
        assertNull(model.uiState.value.currentPosition)
        // A delivered update reset the backoff (it would otherwise be 4 s), and the auto-center request made on the
        // first update did not wake the retry early.
        advanceTimeBy(999); runCurrent()
        assertEquals(3, client.attemptTimes.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(0L, 1_000L, 3_000L, 64_000L), client.attemptTimes)
        assertEquals(fix.point, model.uiState.value.currentPosition)
    }

    @Test fun `backoff does not reset for a subscription that fails without delivering a fix`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply {
            scripts += failAtStart()
            scripts += { kotlinx.coroutines.delay(60_000); throw IllegalStateException("registration failed") }
        }
        trackingViewModel(client)
        advanceTimeBy(61_000); runCurrent()
        assertEquals(listOf(0L, 1_000L), client.attemptTimes)
        advanceTimeBy(1_999); runCurrent()
        assertEquals(2, client.attemptTimes.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(0L, 1_000L, 63_000L), client.attemptTimes)
    }

    @Test fun `normal completion keeps the last position and resubscribes`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply { scripts += { emit(fix) }; scripts += emitThenHang() }
        val model = trackingViewModel(client)
        runCurrent()
        assertEquals(fix.point, model.uiState.value.currentPosition)
        assertEquals(0, client.active)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(0L, 1_000L), client.attemptTimes)
        assertEquals(1, client.active)
        verify { android.util.Log.i(MapViewModel.LOCATION_LOG_TAG, "subscription failed: flow completed; retry #1 in 1000 ms") }
    }

    @Test fun `an open subscription without fixes is never treated as failed`() = runTest {
        val client = ScriptedLocationClient(testScheduler)
        val model = trackingViewModel(client)
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(listOf(0L), client.attemptTimes)
        assertEquals(1, client.active)
        assertNull(model.uiState.value.currentPosition)
    }

    @Test fun `repeated startTracking and recenter never add a subscription`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply { scripts += emitThenHang() }
        val model = trackingViewModel(client)
        runCurrent()
        repeat(5) { model.startTracking(); model.centerOnPosition(); runCurrent() }
        advanceTimeBy(600_000); runCurrent()
        assertEquals(listOf(0L), client.attemptTimes)
        assertEquals(1, client.maxActive)
    }

    @Test fun `recenter during a backoff wait retries once immediately`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply {
            repeat(6) { scripts += failAtStart() }
            scripts += emitThenHang()
        }
        val model = trackingViewModel(client)
        advanceTimeBy(31_000); runCurrent()
        assertEquals(6, client.attemptTimes.size) // now waiting 30 s
        advanceTimeBy(5_000)
        repeat(3) { model.centerOnPosition() }
        runCurrent()
        assertEquals(36_000L, client.attemptTimes.last())
        assertEquals(7, client.attemptTimes.size)
        assertEquals(fix.point, model.uiState.value.currentPosition)
        assertEquals(1, client.maxActive)
    }

    @Test fun `clearing the ViewModel cancels the active collection`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply { scripts += emitThenHang() }
        trackingViewModel(client)
        runCurrent()
        assertEquals(1, client.active)
        store.clear()
        runCurrent()
        assertEquals(0, client.active)
        advanceTimeBy(600_000); runCurrent()
        assertEquals(listOf(0L), client.attemptTimes)
    }

    @Test fun `clearing the ViewModel cancels a pending retry and all later attempts`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply { repeat(10) { scripts += failAtStart() } }
        trackingViewModel(client)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf(0L, 1_000L), client.attemptTimes)
        store.clear()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(listOf(0L, 1_000L), client.attemptTimes)
    }

    @Test fun `CancellationException from the subscription is not retried`() = runTest {
        val client = ScriptedLocationClient(testScheduler).apply {
            scripts += { throw kotlinx.coroutines.CancellationException("cancelled upstream") }
        }
        trackingViewModel(client)
        advanceTimeBy(600_000); runCurrent()
        assertEquals(listOf(0L), client.attemptTimes)
        verify(exactly = 0) { android.util.Log.i(MapViewModel.LOCATION_LOG_TAG, match { it.startsWith("subscription failed") }) }
    }

}
