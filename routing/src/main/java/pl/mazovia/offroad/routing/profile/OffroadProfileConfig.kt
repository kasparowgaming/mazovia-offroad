package pl.mazovia.offroad.routing.profile

import pl.mazovia.offroad.domain.model.RoutingProfile

/**
 * Weight configuration for off-road routing profiles.
 * Controls how different road types and surfaces are prioritized.
 */
data class OffroadProfileConfig(
    val name: String,
    /** Weight multiplier for asphalt roads (higher = less preferred) */
    val asphaltPenalty: Double,
    /** Weight multiplier for gravel/dirt (lower = more preferred) */
    val offRoadBonus: Double,
    /** Weight for unknown surface roads */
    val unknownSurfaceWeight: Double,
    /** Bonus for unridden roads (exploration profile) */
    val explorationBonus: Double,
    /** Preference for higher data confidence */
    val confidenceWeight: Double,
    /** Maximum highway class to allow (e.g., avoid trunk roads) */
    val maxHighwayPriority: Int
) {
    companion object {
        fun forProfile(profile: RoutingProfile): OffroadProfileConfig = when (profile) {
            RoutingProfile.BEZPIECZNY -> OffroadProfileConfig(
                name = "motorcycle_safe",
                asphaltPenalty = 1.5,
                offRoadBonus = 0.7,
                unknownSurfaceWeight = 1.8,
                explorationBonus = 1.0,
                confidenceWeight = 1.5,
                maxHighwayPriority = 10 // Allow up to secondary
            )
            RoutingProfile.TERENOWY -> OffroadProfileConfig(
                name = "motorcycle_offroad",
                asphaltPenalty = 3.0,
                offRoadBonus = 0.4,
                unknownSurfaceWeight = 1.0,
                explorationBonus = 1.0,
                confidenceWeight = 0.8,
                maxHighwayPriority = 9 // Avoid primary and above
            )
            RoutingProfile.ODKRYWCZY -> OffroadProfileConfig(
                name = "motorcycle_explore",
                asphaltPenalty = 2.0,
                offRoadBonus = 0.5,
                unknownSurfaceWeight = 0.8, // Prefer unknown = explore
                explorationBonus = 0.3, // Strong bonus for unridden
                confidenceWeight = 0.5,
                maxHighwayPriority = 9
            )
        }
    }
}
