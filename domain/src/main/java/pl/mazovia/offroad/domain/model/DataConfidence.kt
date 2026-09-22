package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * Legacy terrain-radar/forest presentation vocabulary.
 * RoadDataConfidence is authoritative for RouteSegment road evidence.
 */
@Serializable
enum class DataConfidence(
    val level: Int,
    val displayNamePl: String
) {
    /** Confirmed by authoritative source (e.g., OSM with explicit tags, GUGiK) */
    CONFIRMED(3, "Potwierdzone"),
    /** Inferred from context (e.g., forest area implies track, BDOT classification) */
    INFERRED(2, "Oszacowane"),
    /** Derived from rider feedback/rides */
    RIDE_DERIVED(2, "Z jazdy"),
    /** Low confidence - minimal data */
    LOW(1, "Niska pewność"),
    /** No data available */
    UNKNOWN(0, "Brak danych");

    val isReliable: Boolean get() = level >= 2
}
