package pl.mazovia.offroad.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.designsystem.theme.MazoviaColors

/**
 * Standardized route metrics card used for route results, alternatives, and loop candidates.
 * Uses IDENTICAL layout so riders can compare immediately.
 */
@Composable
fun RouteMetricsCard(
    offRoadPercentage: Double,
    asphaltPercentage: Double,
    totalDistanceKm: Double,
    estimatedTimeMinutes: Long,
    longestAsphaltConnectorKm: Double,
    dataConfidencePercentage: Double,
    isSelected: Boolean = false,
    profileName: String? = null,
    profileDescription: String? = null,
    onSelect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        onClick = { onSelect?.invoke() },
        enabled = onSelect != null
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Profile name and description (if applicable)
            if (profileName != null) {
                Text(
                    text = profileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                profileDescription?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // PRIMARY METRIC: Off-road percentage - the hero number
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${offRoadPercentage.toInt()}%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MazoviaColors.ForestGreen
                )
                Text(
                    text = "terenu",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // Surface distribution bar
            SurfaceDistributionBar(
                offRoadPercentage = offRoadPercentage,
                asphaltPercentage = asphaltPercentage
            )

            // Secondary metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricItem(
                    value = String.format("%.1f km", totalDistanceKm),
                    label = "dystans"
                )
                MetricItem(
                    value = formatTime(estimatedTimeMinutes),
                    label = "czas"
                )
                MetricItem(
                    value = if (longestAsphaltConnectorKm > 0.1)
                        String.format("%.1f km", longestAsphaltConnectorKm)
                    else "—",
                    label = "łącznik",
                    valueColor = if (longestAsphaltConnectorKm > 5.0)
                        MazoviaColors.AsphaltConnector else null
                )
            }

            // Data confidence
            DataConfidenceIndicator(
                confidencePercentage = dataConfidencePercentage
            )
        }
    }
}

@Composable
private fun MetricItem(
    value: String,
    label: String,
    valueColor: androidx.compose.ui.graphics.Color? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTime(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}h ${mins}min" else "${mins}min"
}
