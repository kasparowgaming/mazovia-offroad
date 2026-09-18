package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * Road surface classification following OSM surface tag taxonomy,
 * extended with Mazovia-specific categories.
 */
@Serializable
enum class Surface(
    val isOffRoad: Boolean,
    val displayNamePl: String
) {
    ASPHALT(false, "Asfalt"),
    CONCRETE(false, "Beton"),
    PAVED(false, "Utwardzona"),
    COBBLESTONE(false, "Bruk"),
    COMPACTED(true, "Utwardzona szutrowa"),
    GRAVEL(true, "Szuter"),
    FINE_GRAVEL(true, "Drobny szuter"),
    DIRT(true, "Grunt"),
    EARTH(true, "Ziemia"),
    SAND(true, "Piasek"),
    MUD(true, "Błoto"),
    GRASS(true, "Trawa"),
    GROUND(true, "Grunt leśny"),
    WOOD(false, "Drewno"),
    UNPAVED(true, "Nieutwardzona"),
    UNKNOWN(true, "Nieznana");

    companion object {
        fun fromOsmTag(tag: String?): Surface = when (tag?.lowercase()) {
            "asphalt" -> ASPHALT
            "concrete", "concrete:plates", "concrete:lanes" -> CONCRETE
            "paved", "sett" -> PAVED
            "cobblestone", "paving_stones" -> COBBLESTONE
            "compacted" -> COMPACTED
            "gravel" -> GRAVEL
            "fine_gravel" -> FINE_GRAVEL
            "dirt" -> DIRT
            "earth" -> EARTH
            "sand" -> SAND
            "mud" -> MUD
            "grass", "grass_paver" -> GRASS
            "ground" -> GROUND
            "wood" -> WOOD
            "unpaved" -> UNPAVED
            else -> UNKNOWN
        }
    }
}
