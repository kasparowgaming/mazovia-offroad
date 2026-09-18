package pl.mazovia.offroad.routing.profile

import pl.mazovia.offroad.domain.model.*

/**
 * Helper to calculate edge weights for off-road routing.
 * Used by the routing profiles to score roads appropriately.
 */
object OffroadWeightingHelper {

    /**
     * Calculate weight factor for a road segment.
     * Lower weight = more preferred by router.
     */
    fun calculateWeight(
        config: OffroadProfileConfig,
        surface: Surface,
        highway: HighwayType,
        trackType: TrackType = TrackType.UNKNOWN,
        access: AccessRestriction = AccessRestriction.UNKNOWN,
        isRidden: Boolean = false
    ): Double {
        // Access restriction check - blocked roads get infinite weight
        if (!access.isAccessible) return Double.MAX_VALUE
        if (highway == HighwayType.MOTORWAY) return Double.MAX_VALUE

        var weight = 1.0

        // Surface-based weight
        weight *= when {
            surface == Surface.ASPHALT || surface == Surface.CONCRETE -> config.asphaltPenalty
            surface == Surface.PAVED || surface == Surface.COBBLESTONE -> config.asphaltPenalty * 0.8
            surface.isOffRoad -> config.offRoadBonus
            surface == Surface.UNKNOWN -> config.unknownSurfaceWeight
            else -> 1.0
        }

        // Highway type weight
        weight *= when (highway) {
            HighwayType.TRACK -> 0.6
            HighwayType.PATH -> 0.7
            HighwayType.BRIDLEWAY -> 0.8
            HighwayType.SERVICE -> 1.2
            HighwayType.UNCLASSIFIED -> 1.0
            HighwayType.RESIDENTIAL -> 1.3
            HighwayType.TERTIARY -> 1.5
            HighwayType.SECONDARY -> 2.0
            HighwayType.PRIMARY -> 3.0
            HighwayType.TRUNK -> 5.0
            else -> 1.0
        }

        // Track type bonus
        weight *= when (trackType) {
            TrackType.GRADE1 -> 0.9
            TrackType.GRADE2 -> 0.7
            TrackType.GRADE3 -> 0.6
            TrackType.GRADE4 -> 0.7
            TrackType.GRADE5 -> 0.8
            TrackType.UNKNOWN -> 1.0
        }

        // Exploration bonus for unridden roads
        if (!isRidden && config.explorationBonus < 1.0) {
            weight *= config.explorationBonus
        }

        return weight.coerceAtLeast(0.1) // Minimum weight to avoid zero-cost edges
    }

    /**
     * Check if a road should be accessible for motorcycle routing.
     */
    fun isMotorcycleAccessible(
        access: AccessRestriction,
        highway: HighwayType
    ): Boolean {
        if (!access.isAccessible) return false
        return highway != HighwayType.MOTORWAY &&
                highway != HighwayType.FOOTWAY // Motorcycles shouldn't use footways
    }
}
