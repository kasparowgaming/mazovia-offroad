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
import kotlinx.coroutines.flow.flowOf
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun teardown() {
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

    private fun createViewModel(engine: AdversarialRoutingEngine, gps: Boolean = true): MapViewModel {
        val location = mockk<LocationClient>()
        every { location.getLocationUpdates(any()) } returns if (gps)
            flowOf(LocationUpdate(GeoPoint(52.1567802, 22.3448868), null, null)) else kotlinx.coroutines.flow.emptyFlow()
        val model = MapViewModel(
            routingEngine = engine,
            navigationManager = mockk(relaxed = true),
            appModeManager = mockk(relaxed = true),
            locationClient = location,
            placeSearchRepository = mockk(relaxed = true),
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
        every { location.getLocationUpdates(any()) } returns flowOf(LocationUpdate(position, null, null))
        var recordings = 0
        val model = MapViewModel(engine, navigation, mode, location, mockk(relaxed = true),
            mockk(relaxed = true), hasLocationPermission = { true }, startRecording = { recordings++ })
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

}
