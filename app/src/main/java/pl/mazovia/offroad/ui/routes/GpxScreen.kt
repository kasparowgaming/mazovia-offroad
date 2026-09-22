package pl.mazovia.offroad.ui.routes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.designsystem.components.*
import pl.mazovia.offroad.domain.gpx.GpxParser
import pl.mazovia.offroad.domain.model.GpxData
import pl.mazovia.offroad.domain.gpx.GpxRoute
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.data.repository.RouteRepository
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import pl.mazovia.offroad.ui.map.components.MapLibrePMTilesPOCContainer
import pl.mazovia.offroad.navigation.NavigationManager
import pl.mazovia.offroad.state.AppModeManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpxScreen(
    onBack: () -> Unit,
    navigationManager: NavigationManager,
    routeRepository: RouteRepository,
    appModeManager: AppModeManager
) {
    var importedGpx by remember { mutableStateOf<GpxData?>(null) }
    var previewRoute by remember { mutableStateOf<Route?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val parser = remember { GpxParser() }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                if (inputStream != null) inputStream.use { stream ->
                    val fileName = it.lastPathSegment ?: "import.gpx"
                    when (val result = parser.parse(stream, fileName)) {
                        is GpxParser.ParseResult.Success -> {
                            try {
                                previewRoute = GpxRoute.create(result.data)
                                importedGpx = result.data
                                parseError = null
                                saved = false
                            } catch (e: IllegalArgumentException) {
                                importedGpx = null
                                previewRoute = null
                                parseError = e.message
                            }
                        }
                        is GpxParser.ParseResult.Error -> {
                            importedGpx = null
                            previewRoute = null
                            parseError = result.message
                        }
                    }
                }
            } catch (e: Exception) {
                importedGpx = null
                previewRoute = null
                parseError = "Błąd otwierania pliku: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GPX") },
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
                MazoviaButton(
                    text = "Importuj plik GPX",
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            parseError?.let { error ->
                item { ErrorView(message = error) }
            }

            importedGpx?.let { gpx ->
                item {
                    val route = previewRoute ?: return@item
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Oryginalny ślad GPX",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = gpx.name ?: gpx.sourceFileName ?: "Ślad GPX",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Śladów: ${gpx.tracks.size} · Odcinków: ${gpx.segmentCount} · Punktów trasy: ${gpx.totalPoints}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Punktów orientacyjnych: ${gpx.waypoints.size}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Dystans: ${String.format("%.1f", gpx.totalDistanceMeters / 1000)} km",
                                style = MaterialTheme.typography.bodySmall
                            )
                            MapLibrePMTilesPOCContainer(
                                currentPosition = null,
                                destination = route.destination,
                                routePoints = route.allPoints,
                                routeSegments = route.segments.map { it.points },
                                waypointPoints = gpx.waypoints.map { it.point },
                                centerRequest = 0L,
                                onLongPress = {},
                                modifier = Modifier.fillMaxWidth().height(260.dp)
                            )

                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        routeRepository.saveImportedGpx(gpx)
                                        saved = true
                                    } catch (e: Exception) {
                                        parseError = "Nie zapisano GPX. Oryginalny ślad jest nadal dostępny w podglądzie."
                                    }
                                }
                            }) { Text(if (saved) "Zapisano GPX" else "Zapisz ślad") }

                            ProwadzButton(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context,
                                            android.Manifest.permission.ACCESS_FINE_LOCATION
                                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        parseError = "Brak dostępu do GPS. Zezwól na lokalizację i spróbuj ponownie; ślad GPX jest bezpieczny."
                                    } else try {
                                        navigationManager.startGpxFollowing(gpx, route)
                                        ContextCompat.startForegroundService(context,
                                            android.content.Intent(context, pl.mazovia.offroad.service.TrackRecordingService::class.java).apply {
                                                action = pl.mazovia.offroad.service.TrackRecordingService.ACTION_START
                                            })
                                        appModeManager.switchToRiding()
                                    } catch (e: Exception) {
                                        navigationManager.stopNavigation()
                                        parseError = "Nie rozpoczęto jazdy. Oryginalny ślad GPX jest nadal dostępny; spróbuj ponownie."
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (importedGpx == null && parseError == null) {
                item {
                    EmptyView(
                        message = "Importuj plik GPX aby nawigować po śladzie",
                        icon = Icons.Default.FileOpen
                    )
                }
            }
        }
    }
}
