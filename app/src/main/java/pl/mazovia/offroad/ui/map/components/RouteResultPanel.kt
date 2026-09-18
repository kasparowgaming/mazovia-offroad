package pl.mazovia.offroad.ui.map.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.designsystem.components.*
import pl.mazovia.offroad.designsystem.theme.MazoviaColors
import pl.mazovia.offroad.domain.model.RouteMetrics
import pl.mazovia.offroad.domain.model.RoutingProfile

/**
 * Bottom panel showing route result with the three critical metrics:
 * - OFF-ROAD %
 * - ASPHALT CONNECTOR
 * - DATA CONFIDENCE
 * and the PROWADŹ CTA.
 */
@Composable
fun RouteResultPanel(
    metrics: RouteMetrics?,
    profile: RoutingProfile,
    onProfileSelect: (RoutingProfile) -> Unit,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (metrics == null) return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Route metrics card
            RouteMetricsCard(
                offRoadPercentage = metrics.offRoadPercentage,
                asphaltPercentage = metrics.asphaltPercentage,
                totalDistanceKm = metrics.totalDistanceMeters / 1000.0,
                estimatedTimeMinutes = metrics.estimatedTimeSeconds / 60,
                longestAsphaltConnectorKm = metrics.longestAsphaltConnectorMeters / 1000.0,
                dataConfidencePercentage = metrics.dataConfidencePercentage
            )

            // Profile selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RoutingProfile.entries.forEach { p ->
                    FilterChip(
                        selected = profile == p,
                        onClick = { onProfileSelect(p) },
                        label = { Text(p.displayNamePl, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // PROWADŹ button
            ProwadzButton(
                onClick = onNavigate
            )
        }
    }
}
