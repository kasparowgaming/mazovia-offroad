package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * Map layer configuration.
 */
@Serializable
enum class MapLayerType(
    val displayNamePl: String,
    val isBaseLayer: Boolean
) {
    OSM_STANDARD("OpenStreetMap", true),
    TOPOGRAPHIC("Topograficzna", true),
    SATELLITE("Satelita", true),
    ORTHOPHOTO("Ortofoto GUGiK", true),
    BDOT_OVERLAY("BDOT10k", false),
    SURFACE_OVERLAY("Nawierzchnie", false),
    TRACK_OVERLAY("Ślady GPS", false);
}

/**
 * Map layer presets for different use cases.
 */
@Serializable
enum class MapPreset(
    val displayNamePl: String,
    val baseLayer: MapLayerType,
    val overlays: List<MapLayerType>
) {
    PLANOWANIE(
        "Planowanie",
        MapLayerType.OSM_STANDARD,
        listOf(MapLayerType.SURFACE_OVERLAY)
    ),
    JAZDA(
        "Jazda",
        MapLayerType.OSM_STANDARD,
        emptyList()
    ),
    INSPEKCJA(
        "Inspekcja",
        MapLayerType.SATELLITE,
        listOf(MapLayerType.BDOT_OVERLAY, MapLayerType.SURFACE_OVERLAY)
    )
}

@Serializable
enum class TileState {
    AVAILABLE,
    LOADING,
    OFFLINE,
    UNAVAILABLE,
    PARTIAL
}
