package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * The three Mazovia Offroad routing profiles.
 * Each represents a different riding philosophy.
 */
@Serializable
enum class RoutingProfile(
    val displayNamePl: String,
    val descriptionPl: String,
    val shortDescriptionPl: String
) {
    /**
     * Safer off-road: prefers well-classified, higher-confidence roads.
     * Good for: first rides in unknown areas, less experienced riders.
     */
    BEZPIECZNY(
        displayNamePl = "Bezpieczniejszy",
        descriptionPl = "Trasa terenowa z preferencją dobrze sklasyfikowanych, pewnych dróg",
        shortDescriptionPl = "Pewne drogi terenowe"
    ),

    /**
     * Maximum terrain: aggressively maximize off-road, minimize asphalt.
     * Good for: experienced riders who want maximum dirt.
     */
    TERENOWY(
        displayNamePl = "Terenowy",
        descriptionPl = "Maksymalnie dużo terenu, minimum asfaltu",
        shortDescriptionPl = "Maks. teren, min. asfalt"
    ),

    /**
     * Exploration: prefer roads not previously ridden.
     * Good for: riders who want to discover new areas.
     */
    ODKRYWCZY(
        displayNamePl = "Odkrywczy",
        descriptionPl = "Preferuje drogi jeszcze nie przejechane, odkrywaj nowe tereny",
        shortDescriptionPl = "Nowe, nieodkryte drogi"
    )
}
