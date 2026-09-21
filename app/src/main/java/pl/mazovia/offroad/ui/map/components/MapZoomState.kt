package pl.mazovia.offroad.ui.map.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

class MapZoomState {
    var steps by mutableIntStateOf(0)
        private set
    fun zoomIn() { steps++ }
    fun zoomOut() { steps-- }
}

/** Consumes accumulated button presses once, preserving camera target and follow mode. */
class MapZoomConsumer {
    private var consumed = 0
    fun apply(steps: Int, currentZoom: Double, minZoom: Double, maxZoom: Double, setZoom: (Double) -> Unit) {
        val delta = steps - consumed
        if (delta == 0) return
        setZoom((currentZoom + delta).coerceIn(minZoom, maxZoom))
        consumed = steps
    }
}
