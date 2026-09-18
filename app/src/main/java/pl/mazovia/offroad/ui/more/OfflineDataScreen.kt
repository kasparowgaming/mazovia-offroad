package pl.mazovia.offroad.ui.more

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.mazovia.offroad.domain.routing.RoutingEngine
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineDataScreen(
    routingEngine: RoutingEngine,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(0f) }
    var importStatus by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                importStatus = "Kopiowanie plików..."
                
                val success = importGraphData(context, uri) { progress, currentFile ->
                    importProgress = progress
                    importStatus = "Kopiowanie plików: ${(progress * 100).toInt()}%"
                }
                
                if (success) {
                    importStatus = "Sprawdzanie i ładowanie grafu..."
                    val tempGraphPath = File(context.filesDir, "graph_temp").absolutePath
                    val result = routingEngine.validateAndSwapGraph(tempGraphPath)
                    if (result is RoutingEngine.ImportResult.Success) {
                        importStatus = "Graf załadowany pomyślnie!"
                    } else {
                        val error = (result as RoutingEngine.ImportResult.Error).message
                        importStatus = "Błąd: $error"
                    }
                } else {
                    importStatus = "Błąd kopiowania plików"
                }
                isImporting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dane offline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Importuj dane GraphHopper",
                style = MaterialTheme.typography.titleLarge
            )
            
            Text(
                text = "Wybierz folder zawierający wygenerowane pliki grafu (np. nodes, edges, geometry).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = { launcher.launch(null) },
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Wybierz folder grafu")
            }

            if (isImporting || importStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                if (isImporting) {
                    LinearProgressIndicator(
                        progress = { importProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = importStatus,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private suspend fun importGraphData(
    context: Context,
    sourceUri: Uri,
    onProgress: (Float, String) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    try {
        val destDir = File(context.filesDir, "graph_temp")
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()

        val documentFile = DocumentFile.fromTreeUri(context, sourceUri)
        if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory) {
            return@withContext false
        }

        // Count total files first for progress
        var totalFiles = 0
        fun countFiles(dir: DocumentFile) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) countFiles(f) else totalFiles++
            }
        }
        countFiles(documentFile)

        if (totalFiles == 0) return@withContext false

        var copiedFiles = 0
        suspend fun copyRecursive(source: DocumentFile, destFolder: File) {
            if (!destFolder.exists()) destFolder.mkdirs()
            for (file in source.listFiles()) {
                if (file.isDirectory) {
                    copyRecursive(file, File(destFolder, file.name!!))
                } else {
                    val destFile = File(destFolder, file.name!!)
                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    copiedFiles++
                    withContext(Dispatchers.Main) {
                        onProgress(copiedFiles.toFloat() / totalFiles, file.name ?: "")
                    }
                }
            }
        }
        
        copyRecursive(documentFile, destDir)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
