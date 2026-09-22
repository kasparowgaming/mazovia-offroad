package pl.mazovia.offroad.ui.routes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.mazovia.offroad.designsystem.components.*
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.RoutingProfile
import pl.mazovia.offroad.domain.routing.RoutingEngine
import pl.mazovia.offroad.domain.routing.RoutingResult
import pl.mazovia.offroad.navigation.NavigationManager
import pl.mazovia.offroad.state.AppModeManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointToPointScreen(
    onBack: () -> Unit,
    routingEngine: RoutingEngine,
    navigationManager: NavigationManager,
    appModeManager: AppModeManager
) {
    var selectedProfile by remember { mutableStateOf(RoutingProfile.TERENOWY) }
    var destination by remember { mutableStateOf<GeoPoint?>(null) }
    var isCalculating by remember { mutableStateOf(false) }
    var routeResult by remember { mutableStateOf<RoutingResult?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Do punktu") },
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
                    text = "Wybierz profil trasy",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // Profile cards
            item {
                ProfileCard(
                    name = RoutingProfile.BEZPIECZNY.displayNamePl,
                    description = RoutingProfile.BEZPIECZNY.descriptionPl,
                    shortDescription = RoutingProfile.BEZPIECZNY.shortDescriptionPl,
                    icon = Icons.Default.Shield,
                    isSelected = selectedProfile == RoutingProfile.BEZPIECZNY,
                    onClick = { selectedProfile = RoutingProfile.BEZPIECZNY }
                )
            }
            item {
                ProfileCard(
                    name = RoutingProfile.TERENOWY.displayNamePl,
                    description = RoutingProfile.TERENOWY.descriptionPl,
                    shortDescription = RoutingProfile.TERENOWY.shortDescriptionPl,
                    icon = Icons.Default.Terrain,
                    isSelected = selectedProfile == RoutingProfile.TERENOWY,
                    onClick = { selectedProfile = RoutingProfile.TERENOWY }
                )
            }
            item {
                ProfileCard(
                    name = RoutingProfile.ODKRYWCZY.displayNamePl,
                    description = RoutingProfile.ODKRYWCZY.descriptionPl,
                    shortDescription = RoutingProfile.ODKRYWCZY.shortDescriptionPl,
                    icon = Icons.Default.Explore,
                    isSelected = selectedProfile == RoutingProfile.ODKRYWCZY,
                    onClick = { selectedProfile = RoutingProfile.ODKRYWCZY }
                )
            }

            // Route result
            routeResult?.let { result ->
                when (result) {
                    is RoutingResult.Success -> {
                        item {
                            RouteMetricsCard(
                                offRoadPercentage = result.route.metrics.offRoadPercentage,
                                asphaltPercentage = result.route.metrics.asphaltPercentage,
                                totalDistanceKm = result.route.metrics.totalDistanceMeters / 1000,
                                estimatedTimeMinutes = result.route.metrics.estimatedTimeSeconds / 60,
                                longestAsphaltConnectorKm = result.route.metrics.longestAsphaltConnectorMeters / 1000
                            )
                        }
                        item {
                            ProwadzButton(
                                onClick = {
                                    navigationManager.startNavigation(result.route)
                                    appModeManager.switchToRiding()
                                }
                            )
                        }
                    }
                    is RoutingResult.Error -> {
                        item {
                            ErrorView(message = result.error.userMessagePl)
                        }
                    }
                }
            }

            if (isCalculating) {
                item { LoadingView(message = "Obliczanie trasy...") }
            }
        }
    }
}
