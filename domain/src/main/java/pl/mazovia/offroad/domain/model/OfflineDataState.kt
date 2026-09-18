package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * State of offline routing/map data.
 */
@Serializable
data class OfflineDataState(
    val routingGraphState: GraphState = GraphState.NOT_INSTALLED,
    val graphRegion: String? = null,
    val graphVersion: String? = null,
    val graphSizeBytes: Long = 0L,
    val mapTilesAvailable: Boolean = false,
    val availableStorageBytes: Long = 0L,
    val downloadProgress: DownloadProgress? = null
)

@Serializable
enum class GraphState {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLING,
    INSTALLED,
    UPDATE_AVAILABLE,
    DOWNLOAD_ERROR,
    CHECKSUM_ERROR,
    INSUFFICIENT_STORAGE,
    CORRUPTED
}

@Serializable
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val isIndeterminate: Boolean = false
) {
    val progressFraction: Float get() = if (totalBytes > 0) {
        (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
    } else 0f
}
