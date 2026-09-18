package pl.mazovia.offroad.domain.routing

import pl.mazovia.offroad.domain.model.*

/**
 * Core routing engine abstraction.
 * Implementations: GraphHopperRoutingEngine (real), MockRoutingEngine (testing)
 */
interface RoutingEngine {

    /** Whether the routing engine is ready (graph loaded) */
    suspend fun isReady(): Boolean

    /** Get current engine state for diagnostics */
    suspend fun getState(): RoutingEngineState

    /** Calculate a route between two points with a given profile */
    suspend fun calculateRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        waypoints: List<GeoPoint> = emptyList()
    ): RoutingResult

    /** Calculate alternative routes */
    suspend fun calculateAlternatives(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        maxAlternatives: Int = 3
    ): List<RoutingResult>

    /** Generate loop candidates from a start point */
    suspend fun generateLoopCandidates(
        params: LoopParameters,
        candidateCount: Int = 3
    ): List<LoopCandidate>

    /** Recalculate route from current position */
    suspend fun recalculateFromPosition(
        currentPosition: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile
    ): RoutingResult

    /** Load routing graph from file */
    suspend fun loadGraph(graphPath: String): Boolean

    /** Release routing graph from memory */
    suspend fun unloadGraph()

    /** Validate temp graph and replace active if successful */
    suspend fun validateAndSwapGraph(tempGraphPath: String): ImportResult

    sealed class ImportResult {
        object Success : ImportResult()
        data class Error(val message: String) : ImportResult()
    }
}

/**
 * Result of a routing calculation.
 */
sealed class RoutingResult {
    data class Success(val route: Route) : RoutingResult()
    data class Error(val error: RoutingError) : RoutingResult()
}

enum class RoutingError(
    val userMessagePl: String
) {
    GRAPH_NOT_LOADED("Dane routingu nie są załadowane"),
    NO_ROUTE_FOUND("Nie znaleziono trasy"),
    POINT_NOT_FOUND("Nie znaleziono punktu na mapie drogowej"),
    ORIGIN_NOT_FOUND("Nie znaleziono punktu startowego"),
    DESTINATION_NOT_FOUND("Nie znaleziono punktu docelowego"),
    CALCULATION_ERROR("Błąd obliczania trasy"),
    MEMORY_ERROR("Niewystarczająca pamięć do obliczenia trasy"),
    TIMEOUT("Przekroczono czas obliczania trasy"),
    GRAPH_CORRUPTED("Dane routingu są uszkodzone")
}

/**
 * Routing engine diagnostic state.
 */
data class RoutingEngineState(
    val isGraphLoaded: Boolean,
    val graphPath: String? = null,
    val graphVersion: String? = null,
    val nodeCount: Long = 0,
    val edgeCount: Long = 0,
    val memoryUsageBytes: Long = 0,
    val supportedProfiles: List<RoutingProfile> = emptyList(),
    val lastError: String? = null
)
