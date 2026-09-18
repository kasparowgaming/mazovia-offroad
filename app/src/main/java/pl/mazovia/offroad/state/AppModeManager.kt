package pl.mazovia.offroad.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.mazovia.offroad.domain.model.AppMode

/**
 * Manages the explicit application mode.
 * At any time the app is in exactly one mode: PLANNING, RIDING, POST_RIDE.
 */
class AppModeManager {
    private val _currentMode = MutableStateFlow(AppMode.PLANNING)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    fun switchToRiding() {
        android.util.Log.d("RideLifecycle", "RIDING_MODE_ENTERED")
        _currentMode.value = AppMode.RIDING
    }

    fun switchToPlanning() {
        _currentMode.value = AppMode.PLANNING
    }

    fun switchToPostRide() {
        android.util.Log.d("StopTrace", "APP_MODE_CHANGED=POST_RIDE")
        android.util.Log.d("RideLifecycle", "APP_MODE_POST_RIDE")
        _currentMode.value = AppMode.POST_RIDE
    }

    fun restore(mode: AppMode) {
        _currentMode.value = mode
    }
}
