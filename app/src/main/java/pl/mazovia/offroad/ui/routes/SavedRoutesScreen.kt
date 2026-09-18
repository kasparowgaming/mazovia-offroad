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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRoutesScreen(
    onBack: () -> Unit,
    routeRepository: RouteRepository
) {
    val routes by routeRepository.getAllRoutes().collectAsState(initial = emptyList())

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
                items(routes.size) { index ->
                    val route = routes[index]
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = route.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${String.format("%.1f", route.totalDistanceMeters / 1000)} km | ${route.offRoadPercentage.toInt()}% terenu",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
