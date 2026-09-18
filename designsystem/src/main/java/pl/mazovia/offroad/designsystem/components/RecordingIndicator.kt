package pl.mazovia.offroad.designsystem.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.designsystem.theme.MazoviaColors

/**
 * Recording indicator - unmistakable, NOT just a red dot.
 * Shows status text + duration + distance + pulsing animation.
 */
@Composable
fun RecordingIndicator(
    isRecording: Boolean,
    isPaused: Boolean,
    durationText: String,
    distanceText: String,
    modifier: Modifier = Modifier
) {
    val statusText = when {
        isPaused -> "PAUZA"
        isRecording -> "NAGRYWANIE"
        else -> return // Don't show if not recording
    }

    val description = "$statusText: $durationText, $distanceText"

    val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPaused) 0.5f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val bgColor = if (isPaused) MazoviaColors.Warning else MazoviaColors.Recording

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Pulsing dot + REC text (not JUST a dot)
        Icon(
            imageVector = Icons.Default.FiberManualRecord,
            contentDescription = null,
            modifier = Modifier
                .size(12.dp)
                .alpha(alpha),
            tint = bgColor
        )

        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = bgColor
        )

        Text(
            text = durationText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = distanceText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val EaseInOutCubic = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
