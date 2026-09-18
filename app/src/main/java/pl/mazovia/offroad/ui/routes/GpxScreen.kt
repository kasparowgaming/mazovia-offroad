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
import pl.mazovia.offroad.navigation.NavigationManager
import pl.mazovia.offroad.state.AppModeManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpxScreen(
    onBack: () -> Unit,
    navigationManager: NavigationManager,
    appModeManager: AppModeManager
) {
    var importedGpx by remember { mutableStateOf<GpxData?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val parser = remember { GpxParser() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                if (inputStream != null) {
                    val fileName = it.lastPathSegment ?: "import.gpx"
                    when (val result = parser.parse(inputStream, fileName)) {
                        is GpxParser.ParseResult.Success -> {
                            importedGpx = result.data
                            parseError = null
                        }
                        is GpxParser.ParseResult.Error -> {
                            parseError = result.message
                        }
                    }
                }
            } catch (e: Exception) {
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
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = gpx.name ?: gpx.sourceFileName ?: "Ślad GPX",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Punktów: ${gpx.totalPoints}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Dystans: ${String.format("%.1f", gpx.totalDistanceMeters / 1000)} km",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Segmentów: ${gpx.segmentCount}",
                                style = MaterialTheme.typography.bodySmall
                            )

                            ProwadzButton(
                                onClick = {
                                    navigationManager.startGpxFollowing(gpx, null)
                                    appModeManager.switchToRiding()
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
