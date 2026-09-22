package pl.mazovia.offroad.ui.routes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import pl.mazovia.offroad.domain.location.LocationClient
import pl.mazovia.offroad.designsystem.components.*
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.RoutingEngine
import pl.mazovia.offroad.navigation.NavigationManager
import pl.mazovia.offroad.state.AppModeManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopScreen(
    onBack: () -> Unit,
    routingEngine: RoutingEngine,
    navigationManager: NavigationManager,
    appModeManager: AppModeManager,
    locationClient: LocationClient,
    routeRepository: pl.mazovia.offroad.data.repository.RouteRepository,
    onNavigateToOfflineData: () -> Unit
) {
    var selectedDistance by remember { mutableIntStateOf(50) }
    var selectedProfile by remember { mutableStateOf(RoutingProfile.TERENOWY) }
    var candidates by remember { mutableStateOf<List<LoopCandidate>>(emptyList()) }
    var selectedCandidate by remember { mutableStateOf<LoopCandidate?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

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

    val evaluator = remember { pl.mazovia.offroad.domain.readiness.RidePackEvaluator(context, routingEngine) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pętla") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Distance selection
            item {
                Text(
                    text = "Dystans pętli",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf(10, 25, 50, 100).forEach { dist ->
                        FilterChip(
                            selected = selectedDistance == dist,
                            onClick = { selectedDistance = dist },
                            label = { Text("$dist km") }
                        )
                    }
                }
            }

            // Profile selection
            item {
                Text(
                    text = "Typ trasy",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RoutingProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = selectedProfile == profile,
                            onClick = { selectedProfile = profile },
                            label = { Text(profile.displayNamePl) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Generate button
            item {
                MazoviaButton(
                    text = if (isGenerating) "Generowanie..." else "Generuj pętlę",
                    onClick = {
                        isGenerating = true
                        scope.launch {
                            try {
                                selectedCandidate = null
                                candidates = emptyList()
                                message = null
                                saved = false
                                val location = withTimeoutOrNull(10_000) {
                                    locationClient.getLocationUpdates(1_000).first().point
                                }
                                if (location == null) {
                                    message = "Nie udało się ustalić pozycji startowej. Sprawdź lokalizację i spróbuj ponownie."
                                } else {
                                    candidates = routingEngine.generateLoopCandidates(
                                        LoopParameters(location, selectedDistance, selectedProfile), 3)
                                    if (candidates.isEmpty()) message = "Nie znaleziono pętli o odpowiednim dystansie i przebiegu."
                                }
                            } catch (e: Exception) {
                                message = "Nie udało się wygenerować pętli: ${e.message ?: "błąd routingu"}"
                            } finally {
                                isGenerating = false
                            }
                        }
                    },
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Candidates
            if (candidates.isNotEmpty()) {
                item {
                    Text(
                        text = "Propozycje pętli",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                items(candidates) { candidate ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (candidate.status == "FALLBACK_25_PERCENT") "Dystans awaryjny (do ±25%)" else "Dystans docelowy (±15%)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Powtórzony odcinek: ${"%.1f".format(candidate.retraceDistanceMeters / 1000)} km",
                            style = MaterialTheme.typography.bodySmall
                        )
                        RouteMetricsCard(
                            offRoadPercentage = candidate.route.metrics.offRoadPercentage,
                            asphaltPercentage = candidate.route.metrics.asphaltPercentage,
                            totalDistanceKm = candidate.route.metrics.totalDistanceMeters / 1000.0,
                            estimatedTimeMinutes = candidate.route.metrics.estimatedTimeSeconds / 60,
                            longestAsphaltConnectorKm = candidate.route.metrics.longestAsphaltConnectorMeters / 1000.0,
                            isSelected = selectedCandidate == candidate,
                            onSelect = { selectedCandidate = candidate; saved = false }
                        )
                    }
                }
            }

            selectedCandidate?.let { candidate ->
                item {
                    var readiness by remember { mutableStateOf<pl.mazovia.offroad.domain.readiness.RidePackReadiness?>(null) }
                    LaunchedEffect(candidate.route) {
                        readiness = evaluator.evaluate(candidate.route)
                    }
                    
                    pl.mazovia.offroad.ui.readiness.RidePackReadinessCard(
                        readiness = readiness,
                        onPrepareClicked = onNavigateToOfflineData
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        routeRepository.saveCalculatedRoute(candidate.route, "Pętla ${selectedDistance}km")
                                        saved = true
                                    } catch (e: Exception) {
                                        message = "Błąd zapisu"
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (saved) "Zapisano" else "Zapisz pętlę")
                        }
                        ProwadzButton(
                            onClick = {
                                if (readiness?.hasEssentialDepartureBlocker == true) {
                                    showDepartureBlockerDialog = true
                                } else {
                                    navigationManager.startNavigation(candidate.route)
                                    appModeManager.switchToRiding()
                                }
                            },
                            modifier = Modifier.weight(2f)
                        )
                    }
                }
            }

            if (isGenerating) {
                item { LoadingView(message = "Generowanie pętli...") }
            }
            message?.let { text -> item { Text(text) } }
        }
    }
}
