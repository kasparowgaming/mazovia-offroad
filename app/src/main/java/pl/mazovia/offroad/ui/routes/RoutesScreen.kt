package pl.mazovia.offroad.ui.routes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.data.repository.RouteRepository
import pl.mazovia.offroad.designsystem.components.*
import pl.mazovia.offroad.domain.model.RoutingProfile
import pl.mazovia.offroad.domain.location.LocationClient
import pl.mazovia.offroad.domain.routing.RoutingEngine
import pl.mazovia.offroad.navigation.NavigationManager
import pl.mazovia.offroad.state.AppModeManager

/**
 * TRASY tab - routing workflows.
 * Do punktu, Pętla, Las, Zapisane trasy, GPX
 */
@Composable
fun RoutesScreen(
    routingEngine: RoutingEngine,
    routeRepository: RouteRepository,
    navigationManager: NavigationManager,
    appModeManager: AppModeManager,
    locationClient: LocationClient,
    onPointToPoint: () -> Unit,
    onOpenRoute: (pl.mazovia.offroad.domain.model.Route) -> Unit,
    onNavigateToOfflineData: () -> Unit
) {
    var selectedSection by remember { mutableStateOf<RoutesSection?>(null) }

    when (selectedSection) {
        RoutesSection.DO_PUNKTU -> LaunchedEffect(Unit) { onPointToPoint() }
        RoutesSection.PETLA -> LoopScreen(
            onBack = { selectedSection = null },
            routingEngine = routingEngine,
            navigationManager = navigationManager,
            appModeManager = appModeManager,
            locationClient = locationClient
        )
        RoutesSection.LAS -> ForestScreen(
            onBack = { selectedSection = null },
            routingEngine = routingEngine,
            navigationManager = navigationManager,
            appModeManager = appModeManager
        )
        RoutesSection.GPX -> GpxScreen(
            onBack = { selectedSection = null },
            navigationManager = navigationManager,
            routingEngine = routingEngine,
            routeRepository = routeRepository,
            appModeManager = appModeManager,
            onNavigateToOfflineData = onNavigateToOfflineData
        )
        RoutesSection.ZAPISANE -> SavedRoutesScreen(
            onBack = { selectedSection = null },
            routeRepository = routeRepository,
            onOpenRoute = onOpenRoute
        )
        null -> RoutesMenu(onSelect = { if (it == RoutesSection.DO_PUNKTU) onPointToPoint() else selectedSection = it })
    }
}

enum class RoutesSection {
    DO_PUNKTU, PETLA, LAS, GPX, ZAPISANE
}

@Composable
private fun RoutesMenu(onSelect: (RoutesSection) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Trasy",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        item {
            RouteSectionCard(
                title = "Do punktu",
                description = "Wyznacz trasę terenową do wybranego celu",
                icon = Icons.Default.Place,
                onClick = { onSelect(RoutesSection.DO_PUNKTU) }
            )
        }

        item {
            RouteSectionCard(
                title = "Pętla",
                description = "Wygeneruj rekreacyjną pętlę terenową",
                icon = Icons.Default.Loop,
                onClick = { onSelect(RoutesSection.PETLA) }
            )
        }

        item {
            RouteSectionCard(
                title = "Las",
                description = "Znajdź lasy z drogami terenowymi",
                icon = Icons.Default.Forest,
                onClick = { onSelect(RoutesSection.LAS) }
            )
        }

        item {
            RouteSectionCard(
                title = "Zapisane trasy",
                description = "Twoje zapisane trasy i trasy z GPX",
                icon = Icons.Default.Bookmarks,
                onClick = { onSelect(RoutesSection.ZAPISANE) }
            )
        }

        item {
            RouteSectionCard(
                title = "GPX",
                description = "Importuj i nawiguj po śladzie GPX",
                icon = Icons.Default.InsertDriveFile,
                onClick = { onSelect(RoutesSection.GPX) }
            )
        }
    }
}

@Composable
private fun RouteSectionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
