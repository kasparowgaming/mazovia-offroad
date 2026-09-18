package pl.mazovia.offroad.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mazovia.offroad.designsystem.theme.MazoviaColors

/**
 * Large maneuver display for riding cockpit (v7 redesign).
 */
@Composable
fun ManeuverView(
    maneuverType: String,
    distanceMeters: Double?,
    streetName: String?,
    nextManeuverText: String? = null,
    modifier: Modifier = Modifier
) {
    val icon = maneuverIcon(maneuverType)
    val angleLabel = maneuverAngleLabel(maneuverType)
    
    val distValue = if (distanceMeters == null) "--" else if (distanceMeters >= 1000) String.format("%.1f", distanceMeters / 1000.0) else String.format("%.0f", distanceMeters)
    val distUnit = if (distanceMeters == null) "" else if (distanceMeters >= 1000) "KM" else "M"
    val accessibleDist = "$distValue $distUnit"

    val description = buildString {
        append(maneuverType.replace("_", " "))
        append(" za $accessibleDist")
        streetName?.let { append(" na $it") }
    }

    Row(
        modifier = modifier
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.Top
    ) {
        // Left: Turn Icon Box
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, Color(0xFFC6FF00), RoundedCornerShape(12.dp)) // Yellow-green neon border
                .background(MazoviaColors.ManeuverIconBg)
                .padding(8.dp)
                .width(52.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = Color(0xFFC6FF00) // Neon green tint
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = angleLabel,
                color = Color(0xFFC6FF00),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Center: Distance and Street
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = distValue,
                    color = Color.White,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 42.sp
                )
                Text(
                    text = distUnit,
                    color = Color(0xFFC6FF00),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                )
            }

            if (streetName != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF555500), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = streetName.uppercase(),
                        color = Color(0xFFC6FF00),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Right: Next Maneuver Preview (Optional)
        if (nextManeuverText != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MazoviaColors.RidingBorder, RoundedCornerShape(12.dp))
                    .background(MazoviaColors.RidingCardBackground)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "POTEM",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Color(0xFFFF3D00)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = nextManeuverText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun maneuverIcon(type: String): ImageVector = when (type.uppercase()) {
    "TURN_LEFT" -> Icons.Default.TurnLeft
    "TURN_RIGHT" -> Icons.Default.TurnRight
    "TURN_SLIGHT_LEFT" -> Icons.Default.TurnLeft
    "TURN_SLIGHT_RIGHT" -> Icons.Default.TurnRight
    "TURN_SHARP_LEFT" -> Icons.Default.TurnLeft
    "TURN_SHARP_RIGHT" -> Icons.Default.TurnRight
    "STRAIGHT" -> Icons.Default.Straight
    "ROUNDABOUT" -> Icons.Default.RotateRight
    "U_TURN" -> Icons.Default.UTurnLeft
    "ARRIVE" -> Icons.Default.Flag
    "DEPART" -> Icons.Default.Navigation
    else -> Icons.Default.Straight
}

private fun maneuverAngleLabel(type: String): String = when (type.uppercase()) {
    "TURN_LEFT" -> "LEWO 90°"
    "TURN_RIGHT" -> "PRAWO 90°"
    "TURN_SLIGHT_LEFT" -> "LEWO 45°"
    "TURN_SLIGHT_RIGHT" -> "PRAWO 45°"
    "TURN_SHARP_LEFT" -> "LEWO 135°"
    "TURN_SHARP_RIGHT" -> "PRAWO 135°"
    "STRAIGHT" -> "PROSTO"
    "ROUNDABOUT" -> "RONDO"
    "U_TURN" -> "ZAWRÓĆ"
    "ARRIVE" -> "CEL"
    "DEPART" -> "START"
    else -> type.uppercase()
}
