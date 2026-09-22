package pl.mazovia.offroad.ui.map.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.ui.confidence.RoadConfidenceUiState
import pl.mazovia.offroad.ui.confidence.SurfaceCoverageRow
import pl.mazovia.offroad.ui.confidence.SurfaceDataAvailability
import java.util.Locale

@Composable
fun RoadConfidenceSection(state: RoadConfidenceUiState) {
    var showDetails by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Dane o nawierzchni", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            if (state.availability != SurfaceDataAvailability.NO_DISTANCE) {
                TextButton(onClick = { showDetails = true }) { Text("Szczegóły") }
            }
        }
        when (state.availability) {
            SurfaceDataAvailability.NO_DISTANCE ->
                Text("Brak danych do analizy", style = MaterialTheme.typography.bodyMedium)
            SurfaceDataAvailability.ALL_UNKNOWN ->
                Text("Brak danych o nawierzchni dla tej trasy", style = MaterialTheme.typography.bodyMedium)
            SurfaceDataAvailability.AVAILABLE -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.rows.forEach { row ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${row.percent}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(row.category.label, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text("Dane o nawierzchni") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.rows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.category.label)
                        Text("${row.percent}% · ${formatDistance(row)}")
                    }
                }
                state.roadDataNote?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Text("Dane opisują nawierzchnię w mapie, nie warunki przejazdu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { showDetails = false }) { Text("Zamknij") }
        }
    )
}

private fun formatDistance(row: SurfaceCoverageRow): String =
    if (row.distanceMeters < 1000.0) "${row.distanceMeters.toInt()} m"
    else String.format(Locale.forLanguageTag("pl-PL"), "%.1f km", row.distanceMeters / 1000.0)
