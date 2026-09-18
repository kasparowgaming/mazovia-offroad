package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * Track recording state - this is persistent application state,
 * not just a UI toggle.
 */
@Serializable
data class RecordingState(
    val status: RecordingStatus,
    val trackId: String? = null,
    val startTimeMillis: Long = 0L,
    val distanceMeters: Double = 0.0,
    val durationSeconds: Long = 0L,
    val pointCount: Int = 0,
    val isRecovered: Boolean = false
)

@Serializable
enum class RecordingStatus {
    /** Not recording */
    IDLE,
    /** Actively recording GPS points */
    RECORDING,
    /** Recording paused by user */
    PAUSED,
    /** Recording recovered after process death */
    RECOVERED,
    /** Recording stopped, ready to save */
    STOPPED
}
