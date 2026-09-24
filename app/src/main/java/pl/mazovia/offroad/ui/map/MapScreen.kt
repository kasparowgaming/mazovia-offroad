package pl.mazovia.offroad.ui.map

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
import pl.mazovia.offroad.ui.confidence.RoadConfidenceUiMapper

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
    placeSearchRepository: pl.mazovia.offroad.domain.search.PlaceSearchRepository,
    routeRepository: pl.mazovia.offroad.data.repository.RouteRepository,
    onNavigateToOfflineData: () -> Unit = {},
    previewRoute: pl.mazovia.offroad.domain.model.Route? = null,
    onPreviewConsumed: () -> Unit = {}
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
                    placeSearchRepository,
                    routeRepository,
                    context.applicationContext as android.app.Application
                ) as T
            }
        }
    )
    
    val uiState by viewModel.uiState.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val confidenceUiState = remember(uiState.calculatedRoute) {
        uiState.calculatedRoute?.let(RoadConfidenceUiMapper::map)
    }
    val centerRequest by viewModel.centerRequests.collectAsState()
    LaunchedEffect(previewRoute) {
        previewRoute?.let { viewModel.previewSavedRoute(it); onPreviewConsumed() }
    }

    var showDepartureBlockerDialog by remember { mutableStateOf(false) }

    if (showDepartureBlockerDialog) {
        AlertDialog(
            onDismissRequest = { showDepartureBlockerDialog = false },
            title = { Text("Nie można rozpocząć jazdy") },
            text = { Text("Brakuje niezbędnych danych offline (np. mapy bazowej lub trasy). Pobierz je przed wyjazdem.") },
            confirmButton = {
                TextButton(onClick = { 
                    showDepartureBlockerDialog = false 
                    onNavigateToOfflineData() 
                }) {
                    Text("Pobierz dane")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDepartureBlockerDialog = false }) {
                    Text("Anuluj")
                }
            }
        )
    }

    // readiness state is calculated below, but we need it for handleNavigate. We have to elevate readiness.
    var readiness by remember { mutableStateOf<pl.mazovia.offroad.domain.readiness.RidePackReadiness?>(null) }
    val evaluator = remember { pl.mazovia.offroad.domain.readiness.RidePackEvaluator(context, routingEngine) }
    
    LaunchedEffect(uiState.calculatedRoute) {
        readiness = evaluator.evaluate(uiState.calculatedRoute)
    }

    val handleNavigate: () -> Unit = {
        if (readiness?.hasEssentialDepartureBlocker == true) {
            showDepartureBlockerDialog = true
        } else {
            viewModel.startNavigation()
        }
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(uiState.showRoutePanel) {
        if (uiState.showRoutePanel) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Map fills the entire screen
        pl.mazovia.offroad.ui.map.components.MapLibrePMTilesPOCContainer(
            currentPosition = uiState.currentPosition,
            destination = uiState.destination,
            routePoints = uiState.routePoints,
            routeSegments = uiState.calculatedRoute?.originalGpx?.tracks?.flatMap { track ->
                track.segments.map { segment -> segment.points.map { it.point } }
            },
            waypointPoints = uiState.calculatedRoute?.originalGpx?.waypoints?.map { it.point } ?: emptyList(),
            centerRequest = centerRequest,
            onLongPress = { point -> viewModel.setDestination(point) },
            modifier = Modifier.fillMaxSize()
        )

        // UI Overlay constrained by a Column
        Column(modifier = Modifier.fillMaxSize()) {
            // Top: Search bar
            SearchBar(
                query = uiState.searchQuery,
                destinationName = uiState.destinationName,
                searchResults = uiState.searchResults,
                isSearching = uiState.isSearching,
                onQueryChange = { viewModel.updateSearchQuery(it) },
                onSearch = { viewModel.search() },
                onClear = { viewModel.clearDestination() },
                onResultSelected = { result ->
                    viewModel.updateSearchQuery("")
                    viewModel.setDestination(result.location)
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth()
            )

            // Flexible space that pushes bottom panels down and allows shrinking when IME opens
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Map controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
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
            }

            // Bottom states: Loading → Graph Error → Other Error → Route Panel
            when {
                // 1. Loading state — route calculation in progress
                uiState.isLoading -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.fillMaxWidth(),
                        shadowElevation = 8.dp,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Brak danych tras",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Nie można wyznaczyć trasy. Wczytaj dane offline dla tego obszaru.",
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
                        modifier = Modifier.fillMaxWidth(),
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
                            TextButton(onClick = viewModel::retry) {
                                Text("Ponów")
                            }
                        }
                    }
                }

                // 4. Route calculated successfully — existing panel
                uiState.showRoutePanel -> {
                    if (uiState.calculatedRoute?.source == pl.mazovia.offroad.domain.model.RouteSource.IMPORTED_GPX) {
                        // Simplified view for GPX in MapScreen
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .then(if (isLandscape) Modifier.width(380.dp).fillMaxHeight() else Modifier.fillMaxWidth())
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Ślad GPX", style = MaterialTheme.typography.titleMedium)
                            Text("${uiState.calculatedRoute?.allPoints?.size ?: 0} punktów")
                            Text("${uiState.calculatedRoute?.waypoints?.size ?: 0} punktów orientacyjnych")
                            
                            pl.mazovia.offroad.ui.readiness.RidePackReadinessCard(
                                readiness = readiness,
                                onPrepareClicked = onNavigateToOfflineData
                            )
                            
                            pl.mazovia.offroad.designsystem.components.ProwadzButton(onClick = handleNavigate)
                            TextButton(onClick = viewModel::dismissRoutePanel) { Text("Zamknij") }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .then(if (isLandscape) Modifier.width(380.dp).fillMaxHeight() else Modifier.fillMaxWidth())
                        ) {
                            RouteResultPanel(
                                metrics = uiState.routeMetrics,
                                confidence = confidenceUiState,
                                profile = uiState.selectedProfile,
                                onProfileSelect = { viewModel.selectProfile(it) },
                                onNavigate = handleNavigate,
                                onSave = if (uiState.calculatedRoute?.source == pl.mazovia.offroad.domain.model.RouteSource.CALCULATED_ROUTE) {
                                    { viewModel.saveCalculatedRoute() }
                                } else null,
                                isSaved = uiState.isSaved,
                                onDismiss = { viewModel.dismissRoutePanel() },
                                readiness = readiness,
                                onPrepareClicked = onNavigateToOfflineData,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

