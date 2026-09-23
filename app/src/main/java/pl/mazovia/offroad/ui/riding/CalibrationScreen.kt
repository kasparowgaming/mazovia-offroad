package pl.mazovia.offroad.ui.riding

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.MazoviaOffroadApp
import pl.mazovia.offroad.service.TrackRecordingService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    app: MazoviaOffroadApp,
    onBack: () -> Unit
) {
    val isActive by app.roughnessCoordinator.isActive.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kalibracja drgan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wroc")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Proces kalibracji dostosowuje algorytm drgan do uchwytu i roweru. " +
                "Rozpocznij kalibracje bedac w miejscu (stationary). " +
                "Nastepnie przejedz odcinek ok 500m.",
                style = MaterialTheme.typography.bodyLarge
            )

            if (isActive) {
                Button(
                    onClick = {
                        val intent = Intent(app, TrackRecordingService::class.java).apply {
                            action = TrackRecordingService.ACTION_STOP
                        }
                        app.startService(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Zatrzymaj kalibracje")
                }
                
                Text(
                    "Kalibracja w toku...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                
                CalibrationStatusPanel(
                    state = CalibrationDevState(
                        isActive = true,
                        windowCount = 0,
                        distanceMeters = 0.0,
                        lastRms = 0.0,
                        lastStateCode = 0,
                        lastFlags = 0L
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Button(
                    onClick = {
                        val intent = Intent(app, TrackRecordingService::class.java).apply {
                            action = TrackRecordingService.ACTION_START_CALIBRATION
                        }
                        app.startService(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Rozpocznij kalibracje")
                }
                
                val activeProfile by app.roughnessRepository.getActiveCalibrationProfileFlow().collectAsState(initial = null)
                if (activeProfile != null) {
                    Text(
                        "Aktywny profil kalibracji:\n" +
                        "RMS: ${String.format("%.4f", activeProfile!!.baselineVerticalRms)}\n" +
                        "Dystans: ${String.format("%.1f", activeProfile!!.calibrationDistanceMeters)}m\n" +
                        "Probki: ${activeProfile!!.acceptedWindowCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}
