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
import pl.mazovia.offroad.routing.forest.ForestAreaFinder
import pl.mazovia.offroad.state.AppModeManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForestScreen(
    onBack: () -> Unit,
    routingEngine: RoutingEngine,
    navigationManager: NavigationManager,
    appModeManager: AppModeManager
) {
    var candidates by remember { mutableStateOf<List<ForestArea>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val forestFinder = remember { ForestAreaFinder(routingEngine) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Las") },
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
            item {
                Text(
                    text = "Znajdź lasy do jazdy",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Szukamy lasów z drogami terenowymi w okolicy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                MazoviaButton(
                    text = if (isSearching) "Szukanie..." else "Szukaj lasów",
                    onClick = {
                        isSearching = true
                        scope.launch {
                            candidates = forestFinder.findCandidates(
                                userPosition = GeoPoint.WARSAW
                            )
                            isSearching = false
                        }
                    },
                    enabled = !isSearching,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (candidates.isNotEmpty()) {
                items(candidates) { forest ->
                    ForestCandidateCard(
                        forest = forest,
                        onNavigate = {
                            forest.routeToForest?.let { route ->
                                navigationManager.startNavigation(route)
                                appModeManager.switchToRiding()
                            }
                        }
                    )
                }
            }

            if (isSearching) {
                item { LoadingView(message = "Szukanie lasów...") }
            }

            if (!isSearching && candidates.isEmpty()) {
                item {
                    EmptyView(message = "Naciśnij \"Szukaj lasów\" aby znaleźć tereny leśne")
                }
            }
        }
    }
}

@Composable
private fun ForestCandidateCard(
    forest: ForestArea,
    onNavigate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = forest.name ?: "Las (${String.format("%.1f", forest.distanceFromUserMeters / 1000)} km)",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Odległość: ${String.format("%.1f", forest.distanceFromUserMeters / 1000)} km",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Pewność: ${forest.dataConfidence.displayNamePl}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (!forest.hasConfirmedAccess) {
                Text(
                    text = "⚠ Dostęp niepotwierdzony - sprawdź lokalne przepisy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            forest.routeToForest?.let { route ->
                SurfaceDistributionBar(
                    offRoadPercentage = route.metrics.offRoadPercentage,
                    asphaltPercentage = route.metrics.asphaltPercentage
                )
            }

            MazoviaButton(
                text = "Prowadź do lasu",
                onClick = onNavigate,
                enabled = forest.routeToForest != null,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
