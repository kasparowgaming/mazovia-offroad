package pl.mazovia.offroad.ui.riding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

data class CalibrationDevState(
    val isActive: Boolean,
    val windowCount: Int,
    val distanceMeters: Double,
    val lastRms: Double,
    val lastStateCode: Int,
    val lastFlags: Long
)

@Composable
fun CalibrationStatusPanel(
    state: CalibrationDevState,
    modifier: Modifier = Modifier
) {
    if (!state.isActive) return

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text("CALIBRATION DEV", color = Color.Yellow, fontSize = 10.sp)
        Text("Windows: ${state.windowCount}", color = Color.White, fontSize = 12.sp)
        Text("Dist: ${String.format("%.1f", state.distanceMeters)}m", color = Color.White, fontSize = 12.sp)
        Text("Last RMS: ${String.format("%.2f", state.lastRms)}", color = Color.White, fontSize = 12.sp)
        Text("State: ${state.lastStateCode}", color = if (state.lastStateCode == 0) Color.Green else Color.Red, fontSize = 12.sp)
        if (state.lastFlags != 0L) {
            Text("Flags: ${state.lastFlags}", color = Color.Red, fontSize = 12.sp)
        }
    }
}
