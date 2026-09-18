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

data class MapUiState(
    val currentPosition: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val destinationName: String? = null,
    val searchQuery: String = "",
    val selectedProfile: RoutingProfile = RoutingProfile.TERENOWY,
    val routePoints: List<GeoPoint> = emptyList(),
    val routeMetrics: RouteMetrics? = null,
    val showRoutePanel: Boolean = false,
    val showLayers: Boolean = false,
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
    private val application: android.app.Application
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _centerRequests = MutableStateFlow(0L)
    val centerRequests: StateFlow<Long> = _centerRequests.asStateFlow()
    
    private var locationJob: Job? = null
    private var hasAutoCentered = false

    init {
        startTracking()
    }

    fun startTracking() {
        viewModelScope.launch {
            try {
                locationClient.getLocationUpdates(2000L).collect { update ->
                    _uiState.update { it.copy(currentPosition = update.point) }
                    android.util.Log.d("MapLocationPipeline", "LOCATION_RECEIVED: lat=${update.point.latitude}, lon=${update.point.longitude}")
                    if (!hasAutoCentered) {
                        hasAutoCentered = true
                        centerOnPosition()
                    }
                }
            } catch (e: Exception) {
                // Ignore for now, permissions handled by MainActivity
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
    }

    fun search() {
        // Search implementation - nominatim or local geocoding
        // STATUS: NOT IMPLEMENTED - would use Nominatim API
    }

    fun clearDestination() {
        _uiState.update {
            it.copy(
                destination = null,
                destinationName = null,
                routePoints = emptyList(),
                routeMetrics = null,
                showRoutePanel = false,
                calculatedRoute = null,
                searchQuery = ""
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
            _uiState.update { it.copy(error = "Brak sygnału GPS. Poczekaj na ustalenie lokalizacji.") }
            return
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(application, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            _uiState.update { it.copy(error = "Brak uprawnień do lokalizacji.") }
            return
        }
        
        try {
            android.util.Log.d("RideLifecycle", "START_NAVIGATION")
            
            // 1. Start NavigationManager
            navigationManager.startNavigation(route)
            
            // 2. Start TrackRecordingService
            val intent = android.content.Intent(application, pl.mazovia.offroad.service.TrackRecordingService::class.java).apply {
                action = pl.mazovia.offroad.service.TrackRecordingService.ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(application, intent)
            android.util.Log.d("RideLifecycle", "TRACK_RECORDING_SERVICE_START")
            
            // 3. Switch AppMode to RIDING
            appModeManager.switchToRiding()
        } catch (e: Exception) {
            android.util.Log.e("RideLifecycle", "Failed to start navigation: ${e.message}")
            _uiState.update { it.copy(error = "Nie udało się rozpocząć nawigacji: ${e.message}") }
            navigationManager.stopNavigation()
        }
    }

    fun dismissRoutePanel() {
        _uiState.update { it.copy(showRoutePanel = false) }
    }

    fun toggleLayers() {
        _uiState.update { it.copy(showLayers = !it.showLayers) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, routingError = null) }
    }

    fun centerOnPosition() {
        android.util.Log.d("MapLocationPipeline", "CURRENT_LOCATION_BUTTON_CLICKED")
        _centerRequests.value = System.currentTimeMillis()
    }

    private fun calculateRoute() {
        val destination = _uiState.value.destination ?: return
        val origin = _uiState.value.currentPosition ?: GeoPoint.WARSAW

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val result = routingEngine.calculateRoute(
                origin = origin,
                destination = destination,
                profile = _uiState.value.selectedProfile
            )

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
                            error = result.error.userMessagePl,
                            routingError = result.error
                        )
                    }
                }
            }
        }
    }
}
