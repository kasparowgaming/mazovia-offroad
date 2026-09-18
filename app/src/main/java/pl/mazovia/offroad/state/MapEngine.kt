package pl.mazovia.offroad.state

/**
 * Engine selector to allow side-by-side verification of MapLibre Native
 * against the existing OSMDroid implementation.
 */
enum class MapEngine {
    OSMDROID,
    MAPLIBRE,
    MAPLIBRE_PMTILES_POC
}
