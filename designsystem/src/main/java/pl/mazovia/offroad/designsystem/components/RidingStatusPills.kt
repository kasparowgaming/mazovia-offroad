package pl.mazovia.offroad.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.mazovia.offroad.designsystem.theme.MazoviaColors
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun RidingStatusPills(
    hasGpsFix: Boolean,
    isRecording: Boolean,
    navStatus: pl.mazovia.offroad.domain.model.NavigationStatus = pl.mazovia.offroad.domain.model.NavigationStatus.ON_ROUTE,
    modifier: Modifier = Modifier
) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            currentTime = LocalTime.now()
        }
    }

    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // GPS Pill
            if (hasGpsFix) {
                StatusPill(
                    text = "GPS FIX",
                    dotColor = MazoviaColors.StatusGpsGreen,
                    textColor = MazoviaColors.StatusGpsGreen
                )
            }

            // REC Pill
            if (isRecording) {
                StatusPill(
                    text = "REC GPX",
                    dotColor = MazoviaColors.StatusRecRed,
                    textColor = MazoviaColors.StatusRecRed
                )
            }

            // Nav Status Pills
            when (navStatus) {
                pl.mazovia.offroad.domain.model.NavigationStatus.OFF_ROUTE -> {
                    StatusPill(
                        text = "OFF ROUTE",
                        dotColor = MazoviaColors.StatusRecRed,
                        textColor = MazoviaColors.StatusRecRed
                    )
                }
                pl.mazovia.offroad.domain.model.NavigationStatus.ARRIVED -> {
                    StatusPill(
                        text = "ARRIVED",
                        dotColor = MazoviaColors.StatusGpsGreen,
                        textColor = MazoviaColors.StatusGpsGreen
                    )
                }
                else -> {}
            }
        }

        // Clock
        Text(
            text = currentTime.format(timeFormatter),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}

@Composable
private fun StatusPill(
    text: String,
    dotColor: Color,
    textColor: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, MazoviaColors.RidingBorder, RoundedCornerShape(6.dp))
            .background(MazoviaColors.RidingCardBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}
