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
    appModeManager: AppModeManager
) {
    var selectedDistance by remember { mutableIntStateOf(50) }
    var selectedProfile by remember { mutableStateOf(RoutingProfile.TERENOWY) }
    var candidates by remember { mutableStateOf<List<LoopCandidate>>(emptyList()) }
    var selectedCandidate by remember { mutableStateOf<LoopCandidate?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LoopParameters.DISTANCE_OPTIONS.forEach { km ->
                        FilterChip(
                            selected = selectedDistance == km,
                            onClick = { selectedDistance = km },
                            label = { Text("$km km") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Generate button
            item {
                MazoviaButton(
                    text = if (isGenerating) "Generowanie..." else "Generuj pętle",
                    onClick = {
                        isGenerating = true
                        scope.launch {
                            candidates = routingEngine.generateLoopCandidates(
                                params = LoopParameters(
                                    startPoint = GeoPoint.WARSAW,
                                    targetDistanceKm = selectedDistance,
                                    profile = selectedProfile
                                ),
                                candidateCount = 3
                            )
                            isGenerating = false
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
                    RouteMetricsCard(
                        offRoadPercentage = candidate.route.metrics.offRoadPercentage,
                        asphaltPercentage = candidate.route.metrics.asphaltPercentage,
                        totalDistanceKm = candidate.route.metrics.totalDistanceMeters / 1000,
                        estimatedTimeMinutes = candidate.route.metrics.estimatedTimeSeconds / 60,
                        longestAsphaltConnectorKm = candidate.route.metrics.longestAsphaltConnectorMeters / 1000,
                        dataConfidencePercentage = candidate.route.metrics.dataConfidencePercentage,
                        isSelected = selectedCandidate == candidate,
                        onSelect = { selectedCandidate = candidate }
                    )
                }

                selectedCandidate?.let { candidate ->
                    item {
                        ProwadzButton(
                            onClick = {
                                navigationManager.startNavigation(candidate.route)
                                appModeManager.switchToRiding()
                            }
                        )
                    }
                }
            }

            if (isGenerating) {
                item { LoadingView(message = "Generowanie pętli...") }
            }
        }
    }
}
