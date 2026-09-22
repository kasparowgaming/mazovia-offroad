package pl.mazovia.offroad.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.RoutingEngine
import pl.mazovia.offroad.domain.routing.RoutingResult
import pl.mazovia.offroad.navigation.NavigationManager
import pl.mazovia.offroad.state.AppModeManager
import pl.mazovia.offroad.ui.RiderMessages

data class MapUiState(
    val currentPosition: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val destinationName: String? = null,
    val searchQuery: String = "",
    val searchResults: List<PlaceSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val selectedProfile: RoutingProfile = RoutingProfile.TERENOWY,
    val routePoints: List<GeoPoint> = emptyList(),
    val routeMetrics: RouteMetrics? = null,
    val showRoutePanel: Boolean = false,
    val showLayers: Boolean = false,
    val isSaved: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val routingError: pl.mazovia.offroad.domain.routing.RoutingError? = null,
    val calculatedRoute: Route? = null
)

/**
 * ViewModel for MapScreen - handles destination selection and route display.
 * Does NOT handle routing logic, GPX, recording, etc.
 */
class MapViewModel(
    private val routingEngine: RoutingEngine,
    private val navigationManager: NavigationManager,
    private val appModeManager: AppModeManager,
    private val locationClient: pl.mazovia.offroad.domain.location.LocationClient,
    private val placeSearchRepository: pl.mazovia.offroad.domain.search.PlaceSearchRepository,
    private val routeRepository: pl.mazovia.offroad.data.repository.RouteRepository,
    private val application: android.app.Application,
    private val hasLocationPermission: () -> Boolean = {
        androidx.core.content.ContextCompat.checkSelfPermission(application, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    },
    private val startRecording: () -> Unit = {
        val intent = android.content.Intent(application, pl.mazovia.offroad.service.TrackRecordingService::class.java).apply {
            action = pl.mazovia.offroad.service.TrackRecordingService.ACTION_START
        }
        androidx.core.content.ContextCompat.startForegroundService(application, intent)
    }
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _centerRequests = MutableStateFlow(0L)
    val centerRequests: StateFlow<Long> = _centerRequests.asStateFlow()

    private var locationJob: Job? = null
    private var searchJob: Job? = null
    private var routeCalculationJob: Job? = null
    private val requestGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private var hasAutoCentered = false

    init {
        startTracking()
    }

    fun startTracking() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            try {
                locationClient.getLocationUpdates(2000L).collect { update ->
                    _uiState.update { it.copy(currentPosition = update.point) }
                    android.util.Log.d("MapLocationPipeline", "LOCATION_RECEIVED: lat=${update.point.latitude}, lon=${update.point.longitude}")
                    if (!hasAutoCentered) {
                        hasAutoCentered = true
                        centerOnPosition()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                android.util.Log.e("MapViewModel", "Location unavailable", e)
                _uiState.update { it.copy(currentPosition = null) }
            }
        }
    }

    fun setDestination(point: GeoPoint) {
        _uiState.update {
            it.copy(
                destination = point,
                destinationName = "${String.format("%.4f", point.latitude)}, ${String.format("%.4f", point.longitude)}"
            )
        }
        calculateRoute()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }

        searchJob?.cancel()

        val normalizedLen = pl.mazovia.offroad.domain.model.StringNormalization.normalizeForSearch(query).length
        if (normalizedLen < 3) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250) // debounce
            performSearch(query)
        }
    }

    private suspend fun performSearch(query: String) {
        _uiState.update { it.copy(isSearching = true) }

        val anchor = _uiState.value.currentPosition ?: GeoPoint.WARSAW

        try {
            val results = placeSearchRepository.searchPlaces(query, anchor)
            _uiState.update { it.copy(searchResults = results, isSearching = false) }
        } catch (e: Exception) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            android.util.Log.e("MapViewModel", "Search error", e)
        }
    }

    fun search() {
        // Triggered by IME action if needed, already handled by debounce
        searchJob?.cancel()
        viewModelScope.launch {
            performSearch(_uiState.value.searchQuery)
        }
    }

    fun clearDestination() {
        requestGeneration.incrementAndGet()
        routeCalculationJob?.cancel()
        _uiState.update {
            it.copy(
                destination = null,
                destinationName = null,
                routePoints = emptyList(),
                routeMetrics = null,
                showRoutePanel = false,
                calculatedRoute = null,
                searchQuery = "",
                isLoading = false
            )
        }
    }

    fun selectProfile(profile: RoutingProfile) {
        _uiState.update { it.copy(selectedProfile = profile) }
        calculateRoute()
    }

    fun startNavigation() {
        val route = _uiState.value.calculatedRoute ?: return

        if (_uiState.value.currentPosition == null) {
            _uiState.update { it.copy(error = RiderMessages.GPS) }
            return
        }

        if (!hasLocationPermission()) {
            _uiState.update { it.copy(error = RiderMessages.PERMISSION) }
            return
        }

        try {
            android.util.Log.d("RideLifecycle", "START_NAVIGATION")

            // 1. Start NavigationManager
            navigationManager.startNavigation(route)

            // 2. Start TrackRecordingService
            startRecording()
            android.util.Log.d("RideLifecycle", "TRACK_RECORDING_SERVICE_START")

            // 3. Switch AppMode to RIDING
            appModeManager.switchToRiding()
        } catch (e: Exception) {
            android.util.Log.e("RideLifecycle", "Failed to start navigation: ${e.message}")
            _uiState.update { it.copy(error = RiderMessages.NAVIGATION) }
            navigationManager.stopNavigation()
        }
    }

    fun dismissRoutePanel() {
        _uiState.update { it.copy(showRoutePanel = false) }
    }

    fun saveCalculatedRoute() {
        val route = _uiState.value.calculatedRoute ?: return
        viewModelScope.launch {
            try {
                routeRepository.saveCalculatedRoute(route)
                _uiState.update { it.copy(isSaved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Nie udało się zapisać trasy. Spróbuj ponownie.") }
            }
        }
    }

    fun toggleLayers() {
        _uiState.update { it.copy(showLayers = !it.showLayers) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, routingError = null) }
    }

    fun centerOnPosition() {
        startTracking()
        android.util.Log.d("MapLocationPipeline", "CURRENT_LOCATION_BUTTON_CLICKED")
        _centerRequests.value = System.currentTimeMillis()
    }

    fun retry() {
        centerOnPosition()
        if (_uiState.value.calculatedRoute != null) startNavigation() else calculateRoute()
    }

    fun previewSavedRoute(route: Route) {
        requestGeneration.incrementAndGet()
        routeCalculationJob?.cancel()
        _uiState.update { it.copy(destination = route.destination, destinationName = "Zapisana trasa",
            calculatedRoute = route, routePoints = route.allPoints, routeMetrics = route.metrics,
            selectedProfile = route.profile, showRoutePanel = true, isLoading = false, error = null, routingError = null,
            isSaved = true) }
    }

    fun calculateRoute() {
        val destination = _uiState.value.destination ?: return

        val currentGen = requestGeneration.incrementAndGet()
        routeCalculationJob?.cancel()

        val origin = _uiState.value.currentPosition
        _uiState.update { it.copy(calculatedRoute = null, routePoints = emptyList(), routeMetrics = null,
            showRoutePanel = false, routingError = null, isSaved = false) }
        if (origin == null) {
            _uiState.update { it.copy(isLoading = false, error = RiderMessages.GPS) }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }

        routeCalculationJob = viewModelScope.launch {
            val result = try { routingEngine.calculateRoute(
                origin = origin,
                destination = destination,
                profile = _uiState.value.selectedProfile
            ) } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                android.util.Log.e("MapViewModel", "Route calculation failed", e)
                RoutingResult.Error(pl.mazovia.offroad.domain.routing.RoutingError.CALCULATION_ERROR)
            }

            if (requestGeneration.get() != currentGen) {
                // Obsolete request, ignore result
                return@launch
            }

            when (result) {
                is RoutingResult.Success -> {
                    _uiState.update {
                        it.copy(
                            routePoints = result.route.allPoints,
                            routeMetrics = result.route.metrics,
                            showRoutePanel = true,
                            isLoading = false,
                            error = null,
                            routingError = null,
                            calculatedRoute = result.route
                        )
                    }
                }
                is RoutingResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = RiderMessages.routing(result.error),
                            routingError = result.error
                        )
                    }
                }
            }
        }
    }
}
