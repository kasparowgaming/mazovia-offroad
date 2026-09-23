package pl.mazovia.offroad.ui.more

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import pl.mazovia.offroad.MazoviaOffroadApp
import pl.mazovia.offroad.domain.routing.RoutingEngine
import pl.mazovia.offroad.domain.routing.RoutingEngineState
import java.io.File

@Composable
fun MoreScreen(
    routingEngine: RoutingEngine,
    onNavigateToOfflineData: () -> Unit,
    onNavigateToCalibration: () -> Unit,
    app: MazoviaOffroadApp
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    var engineState by remember { mutableStateOf<RoutingEngineState?>(null) }
    
    val prefs = app.getSharedPreferences("mazovia_prefs", Context.MODE_PRIVATE)
    var rawValidationEnabled by remember { mutableStateOf(prefs.getBoolean("raw_validation_enabled", false)) }

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
            item {
                SettingsSection(
                    icon = Icons.Default.DirectionsBike,
                    title = "Kalibracja drgań",
                    subtitle = "Profiluj sprzęt dla algorytmu",
                    onClick = onNavigateToCalibration
                )
            }
            val isDebug = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (isDebug) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Raw Validation Logging", style = MaterialTheme.typography.titleMedium)
                                Text("Zapis surowych danych IMU 100Hz", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = rawValidationEnabled, onCheckedChange = { 
                                rawValidationEnabled = it
                                prefs.edit().putBoolean("raw_validation_enabled", it).apply()
                            })
                        }
                    }
                }
                item {
                    SettingsSection(
                        icon = Icons.Default.Share,
                        title = "Udostępnij log walidacyjny",
                        subtitle = "Eksportuj z validation_logs",
                        onClick = {
                            val dir = File(app.getExternalFilesDir(null), "validation_logs")
                            val files = dir.listFiles()
                            val latestFile = files?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
                            if (latestFile != null) {
                                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", latestFile)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooser = Intent.createChooser(intent, "Udostępnij log")
                                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                app.startActivity(chooser)
                            } else {
                                android.widget.Toast.makeText(app, "Brak logów", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
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
            Text("GraphHopper: ${if (engineState?.isGraphLoaded == true) "Załadowany" else "Niezaładowany"}")
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
