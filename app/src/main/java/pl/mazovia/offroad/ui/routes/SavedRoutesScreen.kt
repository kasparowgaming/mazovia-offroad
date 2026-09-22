package pl.mazovia.offroad.ui.routes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.data.repository.RouteRepository
import pl.mazovia.offroad.designsystem.components.EmptyView
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRoutesScreen(
    onBack: () -> Unit,
    routeRepository: RouteRepository,
    onOpenRoute: (pl.mazovia.offroad.domain.model.Route) -> Unit
) {
    val model = androidx.lifecycle.viewmodel.compose.viewModel<SavedRoutesViewModel>(factory =
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST") return SavedRoutesViewModel(routeRepository) as T
            }
        })
    val routes by model.routes.collectAsState(initial = emptyList())
    val deleteId by model.deleteId.collectAsState()
    val error by model.error.collectAsState()
    val busy by model.busy.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var exportRouteId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        val routeId = exportRouteId
        if (uri != null && routeId != null) scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val route = routeRepository.openRoute(routeId)
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                        pl.mazovia.offroad.domain.gpx.GpxWriter().writeRoute(it, route)
                    }
                }
                message = "Zapisano plik GPX"
            } catch (e: Exception) {
                android.util.Log.e("SavedRoutes", "GPX export failed", e)
                message = pl.mazovia.offroad.ui.RiderMessages.EXPORT
            }
        }
    }
    if (deleteId != null) AlertDialog(
        onDismissRequest = model::cancelDelete,
        title = { Text("Usunąć zapisaną trasę?") },
        text = { Text("Trasa zostanie usunięta z zapisanych tras.") },
        confirmButton = { TextButton(onClick = model::confirmDelete, enabled = !busy) { Text("Usuń") } },
        dismissButton = { TextButton(onClick = model::cancelDelete, enabled = !busy) { Text("Anuluj") } }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zapisane trasy") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                }
            )
        }
    ) { padding ->
        if (routes.isEmpty()) {
            EmptyView(
                message = "Brak zapisanych tras",
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { (error ?: message)?.let { Text(it) } }
                items(routes.size) { index ->
                    val route = routes[index]
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = route.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (route.source == "gpx_import")
                                    "Ślad GPX · ${String.format("%.1f", route.totalDistanceMeters / 1000)} km"
                                else "${String.format("%.1f", route.totalDistanceMeters / 1000)} km | ${route.offRoadPercentage.toInt()}% terenu",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { model.open(route.id, onOpenRoute) }, enabled = !busy) {
                                Text("Otwórz / Prowadź")
                            }
                            TextButton(onClick = { model.open(route.id) {
                                exportRouteId = route.id
                                exporter.launch("mazovia_trasa.gpx")
                            } }, enabled = !busy) { Text("Eksportuj GPX") }
                            TextButton(onClick = { model.requestDelete(route.id) }, enabled = !busy) { Text("Usuń") }
                        }
                    }
                }
            }
        }
    }
}
