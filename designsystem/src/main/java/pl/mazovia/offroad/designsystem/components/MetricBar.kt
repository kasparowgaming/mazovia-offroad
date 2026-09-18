package pl.mazovia.offroad.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.designsystem.theme.MazoviaColors

/**
 * Visual bar showing surface distribution of a route.
 * Uses color + pattern + label for accessibility (not color alone).
 */
@Composable
fun SurfaceDistributionBar(
    offRoadPercentage: Double,
    asphaltPercentage: Double,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true
) {
    val description = "Teren: ${offRoadPercentage.toInt()}%, Asfalt: ${asphaltPercentage.toInt()}%"

    Column(
        modifier = modifier.semantics { contentDescription = description }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            if (offRoadPercentage > 0) {
                Box(
                    modifier = Modifier
                        .weight(offRoadPercentage.toFloat().coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(MazoviaColors.ForestGreen)
                )
            }
            if (asphaltPercentage > 0) {
                Box(
                    modifier = Modifier
                        .weight(asphaltPercentage.toFloat().coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(MazoviaColors.Asphalt)
                )
            }
        }
        if (showLabels) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SurfaceLegendItem(
                    color = MazoviaColors.ForestGreen,
                    label = "Teren ${offRoadPercentage.toInt()}%"
                )
                SurfaceLegendItem(
                    color = MazoviaColors.Asphalt,
                    label = "Asfalt ${asphaltPercentage.toInt()}%"
                )
            }
        }
    }
}

@Composable
private fun SurfaceLegendItem(
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Confidence indicator using icon + text + color.
 */
@Composable
fun DataConfidenceIndicator(
    confidencePercentage: Double,
    modifier: Modifier = Modifier
) {
    val (color, label) = when {
        confidencePercentage >= 70 -> MazoviaColors.ConfidenceHigh to "Wysoka pewność"
        confidencePercentage >= 40 -> MazoviaColors.ConfidenceMedium to "Średnia pewność"
        confidencePercentage > 0 -> MazoviaColors.ConfidenceLow to "Niska pewność"
        else -> MazoviaColors.ConfidenceUnknown to "Brak danych"
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Text(
            text = "${confidencePercentage.toInt()}% $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
