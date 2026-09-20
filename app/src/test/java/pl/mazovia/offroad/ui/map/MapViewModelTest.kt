package pl.mazovia.offroad.ui.map

import io.mockk.mockk
import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    class AdversarialRoutingEngine : RoutingEngine {
        val requests = mutableListOf<kotlinx.coroutines.CompletableDeferred<RoutingResult>>()
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
            lastRequestedProfile = profile
            val deferred = kotlinx.coroutines.CompletableDeferred<RoutingResult>()
            requests.add(deferred)
            return deferred.await()
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

    private fun createViewModel(engine: AdversarialRoutingEngine): MapViewModel {
        return MapViewModel(
            routingEngine = engine,
            navigationManager = mockk(relaxed = true),
            appModeManager = mockk(relaxed = true),
            locationClient = mockk(relaxed = true),
            placeSearchRepository = mockk(relaxed = true),
            application = mockk(relaxed = true)
        )
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

        assertNull(viewModel.uiState.value.calculatedRoute)
    }

    @Test
    fun `CASE C - profile switch preserves latest result`() = runTest {
        val engine = AdversarialRoutingEngine()
        val viewModel = createViewModel(engine)

        viewModel.setDestination(GeoPoint(1.0, 1.0))
        runCurrent()
        val reqA = engine.requests[0]

        viewModel.selectProfile(RoutingProfile.ODKRYWCZY)
        runCurrent()
        val reqB = engine.requests[1]

        reqB.complete(engine.createRoute(2.0, RoutingProfile.ODKRYWCZY))
        runCurrent()

        reqA.complete(engine.createRoute(1.0, RoutingProfile.TERENOWY))
        runCurrent()

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

        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
    }
}
