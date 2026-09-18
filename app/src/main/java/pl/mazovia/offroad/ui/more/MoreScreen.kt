package pl.mazovia.offroad.ui.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.mazovia.offroad.domain.routing.RoutingEngine
import pl.mazovia.offroad.domain.routing.RoutingEngineState

@Composable
fun MoreScreen(
    routingEngine: RoutingEngine,
    onNavigateToOfflineData: () -> Unit
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    var engineState by remember { mutableStateOf<RoutingEngineState?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        engineState = routingEngine.getState()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Więcej",
                style = MaterialTheme.typography.headlineLarge
            )
        }

        // Offline data
        item {
            SettingsSection(
                icon = Icons.Default.CloudDownload,
                title = "Dane offline",
                subtitle = if (engineState?.isGraphLoaded == true)
                    "Graf routing załadowany" else "Graf routing niedostępny",
                onClick = { onNavigateToOfflineData() }
            )
        }

        // Map settings
        item {
            SettingsSection(
                icon = Icons.Default.Map,
                title = "Mapa",
                subtitle = "Warstwy, styl, cache",
                onClick = { }
            )
        }

        // Ride settings
        item {
            SettingsSection(
                icon = Icons.Default.DirectionsBike,
                title = "Jazda",
                subtitle = "Ustawienia nawigacji i nagrywania",
                onClick = { }
            )
        }

        // Data
        item {
            SettingsSection(
                icon = Icons.Default.Storage,
                title = "Dane",
                subtitle = "Routing, GUGiK, BDOT10k",
                onClick = { }
            )
        }

        // Diagnostics
        item {
            SettingsSection(
                icon = Icons.Default.BugReport,
                title = "Diagnostyka",
                subtitle = "Stan systemu, logi, kalibracja",
                onClick = { showDiagnostics = !showDiagnostics }
            )
        }

        if (showDiagnostics) {
            item {
                DiagnosticsPanel(engineState = engineState)
            }
        }

        // About
        item {
            SettingsSection(
                icon = Icons.Default.Info,
                title = "O aplikacji",
                subtitle = "Mazovia Offroad v1.0.0",
                onClick = { }
            )
        }
    }
}

@Composable
private fun SettingsSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsPanel(engineState: RoutingEngineState?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("DIAGNOSTYKA", style = MaterialTheme.typography.labelMedium)
            Text("GraphHopper: ${if (engineState?.isGraphLoaded == true) "Załadowany" else "Niezaladowany"}")
            engineState?.graphPath?.let { Text("Ścieżka: $it") }
            engineState?.let {
                Text("Węzły: ${it.nodeCount}")
                Text("Krawędzie: ${it.edgeCount}")
                Text("Pamięć: ${it.memoryUsageBytes / 1024 / 1024} MB")
            }
            engineState?.lastError?.let { Text("Błąd: $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}
