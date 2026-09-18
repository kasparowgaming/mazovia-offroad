package pl.mazovia.offroad.navigation

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.*

class NavigationManagerTest {

    private lateinit var manager: NavigationManager
    private lateinit var mockEngine: MockRoutingEngine

    @Before
    fun setup() {
        mockEngine = MockRoutingEngine()
        manager = NavigationManager(mockEngine)
    }

    @Test
    fun `initial state is IDLE`() {
        assertEquals(NavigationStatus.IDLE, manager.navigationState.value.status)
    }

    @Test
    fun `startNavigation sets ON_ROUTE status`() {
        val route = createTestRoute()
        manager.startNavigation(route)
        assertEquals(NavigationStatus.ON_ROUTE, manager.navigationState.value.status)
        assertNotNull(manager.navigationState.value.route)
    }

    @Test
    fun `stopNavigation returns to IDLE`() {
        val route = createTestRoute()
        manager.startNavigation(route)
        manager.stopNavigation()
        assertEquals(NavigationStatus.IDLE, manager.navigationState.value.status)
        assertNull(manager.navigationState.value.route)
    }

    @Test
    fun `restoreSession sets RECOVERED status`() {
        val route = createTestRoute()
        manager.restoreSession(route, segmentIndex = 2)
        assertEquals(NavigationStatus.RECOVERED, manager.navigationState.value.status)
        assertTrue(manager.navigationState.value.isRecovered)
    }

    @Test
    fun `updatePosition near destination sets ARRIVED`() {
        val destination = GeoPoint(52.0, 21.0)
        val route = createTestRoute(destination = destination)
        manager.startNavigation(route)

        // Position very close to destination
        manager.updatePosition(
            position = GeoPoint(52.0001, 21.0001),
            bearing = 0.0,
            speedMps = 5.0
        )

        assertEquals(NavigationStatus.ARRIVED, manager.navigationState.value.status)
    }

    @Test
    fun `reroute returns result from engine`() = runTest {
        val route = createTestRoute()
        manager.startNavigation(route)
        manager.updatePosition(GeoPoint(52.0, 21.0), 0.0, 5.0)

        val result = manager.reroute()
        // MockEngine returns Success
        assertTrue(result is RoutingResult.Success)
    }

    private fun createTestRoute(
        destination: GeoPoint = GeoPoint(52.0, 21.0)
    ): Route {
        val segments = listOf(
            RouteSegment(
                points = listOf(
                    GeoPoint(52.2, 21.0),
                    GeoPoint(52.1, 21.0),
                    destination
                ),
                distanceMeters = 20000.0,
                surface = Surface.GRAVEL,
                highway = HighwayType.TRACK
            )
        )
        return Route(
            id = "test_route",
            origin = GeoPoint(52.2, 21.0),
            destination = destination,
            segments = segments,
            metrics = RouteMetrics.fromSegments(segments),
            profile = RoutingProfile.TERENOWY,
            maneuvers = listOf(
                Maneuver(
                    point = GeoPoint(52.15, 21.0),
                    type = ManeuverType.TURN_RIGHT,
                    distanceMeters = 10000.0,
                    streetName = "Leśna"
                )
            )
        )
    }
}

/**
 * Mock routing engine for testing navigation logic.
 */
class MockRoutingEngine : RoutingEngine {
    override suspend fun isReady(): Boolean = true
    override suspend fun getState(): RoutingEngineState = RoutingEngineState(isGraphLoaded = true)

    override suspend fun calculateRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        waypoints: List<GeoPoint>
    ): RoutingResult {
        val segments = listOf(
            RouteSegment(
                points = listOf(origin, destination),
                distanceMeters = origin.distanceTo(destination),
                surface = Surface.GRAVEL,
                highway = HighwayType.TRACK
            )
        )
        return RoutingResult.Success(
            Route(
                id = "mock_route",
                origin = origin,
                destination = destination,
                segments = segments,
                metrics = RouteMetrics.fromSegments(segments),
                profile = profile
            )
        )
    }

    override suspend fun calculateAlternatives(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        maxAlternatives: Int
    ): List<RoutingResult> = listOf(calculateRoute(origin, destination, profile))

    override suspend fun generateLoopCandidates(
        params: LoopParameters,
        candidateCount: Int
    ): List<LoopCandidate> = emptyList()

    override suspend fun recalculateFromPosition(
        currentPosition: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile
    ): RoutingResult = calculateRoute(currentPosition, destination, profile)

    override suspend fun loadGraph(graphPath: String): Boolean = true

    override suspend fun validateAndSwapGraph(tempGraphPath: String): RoutingEngine.ImportResult = RoutingEngine.ImportResult.Success

    override suspend fun unloadGraph() {}
}
