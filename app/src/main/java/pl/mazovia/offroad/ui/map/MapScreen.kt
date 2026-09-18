package pl.mazovia.offroad.ui.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.RoutingProfile
import pl.mazovia.offroad.domain.routing.RoutingEngine
import pl.mazovia.offroad.domain.routing.RoutingError
import pl.mazovia.offroad.navigation.NavigationManager
import pl.mazovia.offroad.state.AppModeManager
import pl.mazovia.offroad.ui.map.components.MapViewContainer
import pl.mazovia.offroad.ui.map.components.RouteResultPanel
import pl.mazovia.offroad.ui.map.components.SearchBar

/**
 * Main map screen - PLANNING mode only.
 * Map dominates the screen. Minimal permanent UI.
 *
 * This screen composes UI only. It does NOT contain routing logic,
 * GPX management, recording control, etc.
 */
@Composable
fun MapScreen(
    routingEngine: RoutingEngine,
    navigationManager: NavigationManager,
    appModeManager: AppModeManager,
    locationClient: pl.mazovia.offroad.domain.location.LocationClient,
    onNavigateToOfflineData: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: MapViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MapViewModel(
                    routingEngine, 
                    navigationManager, 
                    appModeManager, 
                    locationClient,
                    context.applicationContext as android.app.Application
                ) as T
            }
        }
    )
    
    val uiState by viewModel.uiState.collectAsState()
    val centerRequest by viewModel.centerRequests.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        // Map fills the entire screen
        MapViewContainer(
            currentPosition = uiState.currentPosition,
            destination = uiState.destination,
            routePoints = uiState.routePoints,
            centerRequest = centerRequest,
            onLongPress = { point -> viewModel.setDestination(point) },
            modifier = Modifier.fillMaxSize()
        )

        // Top: Search bar
        SearchBar(
            query = uiState.searchQuery,
            destinationName = uiState.destinationName,
            onQueryChange = { viewModel.updateSearchQuery(it) },
            onSearch = { viewModel.search() },
            onClear = { viewModel.clearDestination() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .fillMaxWidth()
        )

        // Map controls (one layer button + my location)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(
                onClick = { viewModel.toggleLayers() },
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Icon(Icons.Default.Layers, contentDescription = "Warstwy")
            }
            FloatingActionButton(
                onClick = { viewModel.centerOnPosition() },
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Moja pozycja")
            }
        }

        // Bottom states: Loading → Graph Error → Other Error → Route Panel
        when {
            // 1. Loading state — route calculation in progress
            uiState.isLoading -> {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Wyznaczanie trasy\u2026",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            // 2. Graph not loaded — persistent actionable state
            uiState.routingError == RoutingError.GRAPH_NOT_LOADED -> {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Brak danych routingu",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Aby wyznacza\u0107 trasy, wczytaj graf GraphHopper.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = {
                                viewModel.clearError()
                                onNavigateToOfflineData()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("Wczytaj dane")
                        }
                    }
                }
            }

            // 3. Other routing error — dismissible banner
            uiState.error != null -> {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.error ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("OK")
                        }
                    }
                }
            }

            // 4. Route calculated successfully — existing panel
            uiState.showRoutePanel -> {
                RouteResultPanel(
                    metrics = uiState.routeMetrics,
                    profile = uiState.selectedProfile,
                    onProfileSelect = { viewModel.selectProfile(it) },
                    onNavigate = { viewModel.startNavigation() },
                    onDismiss = { viewModel.dismissRoutePanel() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }
        }
    }
}
