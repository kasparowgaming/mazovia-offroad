package pl.mazovia.offroad.ui.postride

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import pl.mazovia.offroad.data.repository.FeedbackRepository
import pl.mazovia.offroad.data.repository.RideRepository
import pl.mazovia.offroad.designsystem.components.*
import pl.mazovia.offroad.designsystem.theme.MazoviaColors
import pl.mazovia.offroad.domain.gpx.GpxWriter
import pl.mazovia.offroad.domain.model.GpxData
import pl.mazovia.offroad.domain.model.Ride
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PostRideScreen(
    rideRepository: RideRepository,
    feedbackRepository: FeedbackRepository,
    rideId: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = androidx.lifecycle.viewmodel.compose.viewModel<PostRideViewModel>(key = "postride-$rideId", factory =
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST") return PostRideViewModel(rideRepository, feedbackRepository) as T
            }
        })
    val state by model.state.collectAsState()
    val lastRide = state.ride
    var exportMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(rideId) { model.load(rideId) }

    if (state.feedbackOpen) AlertDialog(
        onDismissRequest = { if (!state.busy) model.closeFeedback() },
        title = { Text(if (state.completed) "Jazda zapisana" else "Oceń drogę") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.completed) Text("Jazda zapisana. Brak pytań do oceny.")
                state.questions.firstOrNull()?.let { question ->
                    Text("Pozostało pytań: ${state.questions.size}")
                    Text("Odcinek: %.4f, %.4f → %.4f, %.4f".format(question.segmentStart.latitude,
                        question.segmentStart.longitude, question.segmentEnd.latitude, question.segmentEnd.longitude))
                    Text(question.question.questionPl)
                    question.question.allowedAnswers.forEach { answer ->
                        TextButton(onClick = { model.answer(answer) }, enabled = !state.busy) { Text(answer.displayPl) }
                    }
                }
                state.error?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = model::closeFeedback, enabled = !state.busy) {
            Text(if (state.completed) "Gotowe" else "Dokończ później")
        } }
    )

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    lastRide?.let { ride ->
                        requireNotNull(context.contentResolver.openOutputStream(it)).use { outStream ->
                            GpxWriter().writeTrack(
                                outputStream = outStream,
                                trackName = "Mazovia Offroad Ride",
                                points = ride.trackPoints
                            )
                        }
                        exportMessage = "Wyeksportowano GPX"
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PostRide", "Export failed", e)
                    exportMessage = pl.mazovia.offroad.ui.RiderMessages.EXPORT
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Text(
                text = "Jazda zakończona",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
        }

        val distanceKm = lastRide?.let { it.distanceMeters / 1000.0 } ?: 0.0
        val durationMins = lastRide?.let { it.durationSeconds / 60 } ?: 0
        val speedKmh = if (durationMins > 0) distanceKm / (durationMins / 60.0) else 0.0
        
        val offroadPct = lastRide?.metrics?.measuredOffRoadPercentage

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MazoviaColors.ForestGreen.copy(alpha = 0.1f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = offroadPct?.let { "%.0f %%".format(it) } ?: "Brak danych",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = MazoviaColors.ForestGreen
                    )
                    Text(
                        text = if (offroadPct == null) "Trasa nie została dopasowana" else "terenu",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Dystans", if (lastRide != null) String.format("%.1f km", distanceKm) else "-- km")
                StatItem("Czas", if (lastRide != null) "$durationMins min" else "-- min")
                StatItem("Śr. prędk.", if (lastRide != null) String.format("%.1f km/h", speedKmh) else "-- km/h")
            }
        }

        exportMessage?.let { msg ->
            item {
                Text(text = msg, color = MazoviaColors.ForestGreen, fontWeight = FontWeight.Bold)
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.error?.let {
                    Text(it)
                    TextButton(onClick = { model.load(rideId) }, enabled = !state.busy) { Text("Ponów") }
                }
                MazoviaButton(
                    text = "Zapisz i oceń drogi",
                    onClick = model::openFeedback,
                    enabled = lastRide != null && !state.busy,
                    modifier = Modifier.fillMaxWidth()
                )
                MazoviaButton(
                    text = "Eksportuj GPX",
                    onClick = {
                        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        exportLauncher.launch("mazovia_${sdf.format(Date())}.gpx")
                    },
                    isSecondary = true,
                    enabled = lastRide != null,
                    modifier = Modifier.fillMaxWidth()
                )
                MazoviaButton(
                    text = "Wróć do planowania",
                    onClick = onDismiss,
                    isSecondary = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
