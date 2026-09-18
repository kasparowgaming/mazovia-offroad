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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mazovia.offroad.designsystem.theme.MazoviaColors

@Composable
fun TerrainRadarView(
    segments: List<TerrainRadarSegmentUi>,
    offRoadProportion: Double,
    asphaltConnectorKm: Double?,
    confidenceLabel: String,
    lookAheadKm: Double = 10.0,
    modifier: Modifier = Modifier
) {
    val description = buildString {
        append("Radar terenu: ${(offRoadProportion * 100).toInt()}% terenu przed Tobą.")
        asphaltConnectorKm?.let {
            append(" Łącznik asfaltowy: ${String.format("%.1f", it)} km.")
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description }
            .padding(vertical = 4.dp)
    ) {
        // Segment visualization bar
        if (segments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            ) {
                segments.forEach { segment ->
                    Box(
                        modifier = Modifier
                            .weight(segment.fraction.toFloat().coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(segment.color)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Labels under the bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Take up to 4 major segments to avoid crowding
                val displaySegments = segments.sortedByDescending { it.fraction }.take(4)
                // Re-sort by original order conceptually, but for now just display them
                displaySegments.forEach { segment ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = segment.distanceLabel,
                            color = segment.color,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = segment.label.uppercase(),
                            color = segment.color.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

data class TerrainRadarSegmentUi(
    val fraction: Double,
    val color: Color,
    val label: String,
    val distanceLabel: String = ""
)
