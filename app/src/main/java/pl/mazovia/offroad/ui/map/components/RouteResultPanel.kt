package pl.mazovia.offroad.ui.map.components

import android.content.res.Configuration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.designsystem.components.*
import pl.mazovia.offroad.domain.model.RouteMetrics
import pl.mazovia.offroad.domain.model.RoutingProfile
import pl.mazovia.offroad.ui.confidence.RoadConfidenceUiState

/**
 * Route preview: terrain composition and surface-data coverage remain separate.
 */
@Composable
fun RouteResultPanel(
    metrics: RouteMetrics?,
    confidence: RoadConfidenceUiState?,
    profile: RoutingProfile,
    onProfileSelect: (RoutingProfile) -> Unit,
    onNavigate: () -> Unit,
    onSave: (() -> Unit)? = null,
    isSaved: Boolean = false,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (metrics == null) return
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxHeight = configuration.screenHeightDp.dp * 0.58f

    Surface(
        modifier = modifier,
        shape = if (isLandscape) RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
            else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = (if (isLandscape) Modifier.fillMaxHeight() else Modifier.heightIn(max = maxHeight))
            .padding(if (isLandscape) 12.dp else 20.dp)) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RouteMetricsCard(
                    offRoadPercentage = metrics.offRoadPercentage,
                    asphaltPercentage = metrics.asphaltPercentage,
                    totalDistanceKm = metrics.totalDistanceMeters / 1000.0,
                    estimatedTimeMinutes = metrics.estimatedTimeSeconds / 60,
                    longestAsphaltConnectorKm = metrics.longestAsphaltConnectorMeters / 1000.0
                )
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
            }
            confidence?.let { RoadConfidenceSection(it) }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSave != null) {
                    TextButton(onClick = onSave, modifier = Modifier.weight(1f), enabled = !isSaved) {
                        Text(if (isSaved) "Zapisano" else "Zapisz trasę")
                    }
                }
                ProwadzButton(onClick = onNavigate, modifier = Modifier.weight(if (onSave != null) 2f else 1f))
            }
        }
    }
}
