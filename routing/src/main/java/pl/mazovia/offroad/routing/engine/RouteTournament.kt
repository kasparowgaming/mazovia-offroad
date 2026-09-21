package pl.mazovia.offroad.routing.engine

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RoutingProfile
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

internal object RouteTournament {
    /** Observations from the actual eligibility/selection pass, never routing inputs. */
    data class CandidateEvaluation(
        val route: Route,
        val baselineDistanceMeters: Double,
        val detourLimit: Double?,
        val maxAllowedDistanceMeters: Double?,
        val acceptedByDetourGuard: Boolean?,
        val score: Double?,
        val rejectionReason: String?,
        val shortestReferenceDistance: Double? = null,
        val shortestReferenceTerrainDistance: Double? = null,
        val marginalTerrainEfficiency: Double? = null,
        val marginalTerrainEfficiencyLimit: Double? = null
    )
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MIN_MARGINAL_TERRAIN_EFFICIENCY = 0.70
    private const val EQUAL_DISTANCE_EPSILON_METERS = 0.1

    /**
     * Generuje geometryczne korytarze baczne do zbadania rnnorodnoci off-road.
     * Zastpuje to uycie "ALT_ROUTE" z GraphHoppera, ktre sabo dziaa w off-roadzie.
     */
    fun profileDiversityCorridors(
        from: GeoPoint,
        to: GeoPoint,
        profile: RoutingProfile
    ): List<List<GeoPoint>> {
        if (profile == RoutingProfile.BEZPIECZNY) return emptyList()

        val referenceLatitude = Math.toRadians((from.latitude + to.latitude) / 2.0)
        val northMeters = Math.toRadians(to.latitude - from.latitude) * EARTH_RADIUS_METERS
        val eastMeters = Math.toRadians(to.longitude - from.longitude) *
                cos(referenceLatitude) * EARTH_RADIUS_METERS

        val directDistance = hypot(eastMeters, northMeters)
        if (directDistance < 2_000.0) return emptyList()

        val offsets = if (profile == RoutingProfile.TERENOWY) listOf(0.08, 0.16)
        else listOf(0.08, 0.16, 0.24) // ODKRYWCZY

        return offsets.flatMap { offsetFraction ->
            listOf(-1.0, 1.0).flatMap { side ->
                val lateral = (directDistance * offsetFraction)
                    .coerceIn(650.0, if (profile == RoutingProfile.TERENOWY) 5_500.0 else 8_500.0)
                
                listOf(1.0, -1.0).map { bend ->
                    listOf(0.24, 0.50, 0.76).mapIndexed { index, fraction ->
                        val middleScale = if (index == 1) 1.0 + bend * 0.22 else 1.0
                        val pointNorth = northMeters * fraction
                        val pointEast = eastMeters * fraction
                        
                        val perpendicularNorth = eastMeters / directDistance
                        val perpendicularEast = -northMeters / directDistance
                        
                        val offsetNorth = perpendicularNorth * lateral * side * middleScale
                        val offsetEast = perpendicularEast * lateral * side * middleScale

                        pointAtOffset(
                            origin = from,
                            eastMeters = pointEast + offsetEast,
                            northMeters = pointNorth + offsetNorth,
                            referenceLatitude = referenceLatitude
                        )
                    }
                }
            }
        }.distinct()
    }

    private fun pointAtOffset(
        origin: GeoPoint,
        eastMeters: Double,
        northMeters: Double,
        referenceLatitude: Double
    ): GeoPoint = GeoPoint(
        latitude = origin.latitude + Math.toDegrees(northMeters / EARTH_RADIUS_METERS),
        longitude = origin.longitude + Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * cos(referenceLatitude)))
    )

    fun profileDetourLimit(profile: RoutingProfile): Double = when (profile) {
        RoutingProfile.BEZPIECZNY -> 0.0
        RoutingProfile.TERENOWY -> 0.60
        RoutingProfile.ODKRYWCZY -> 1.00
    }

    fun chooseTournamentWinner(
        candidates: List<Route>,
        baselineDistanceMeters: Double,
        profile: RoutingProfile,
        observations: MutableList<CandidateEvaluation>? = null
    ): Route? {
        if (profile == RoutingProfile.BEZPIECZNY || candidates.isEmpty() || baselineDistanceMeters <= 0.0) {
            // For normal mode, just pick the shortest or direct
            return candidates.minByOrNull { it.totalDistanceMeters }
        }
        
        val limit = profileDetourLimit(profile)
        // 0.1 meter epsilon to absorb floating point inaccuracies around the exact boundary
        val maxAllowedDistance = baselineDistanceMeters * (1.0 + limit) + 0.1

        // The caller supplies successfully routed A-to-B candidates, including the baseline.
        // The shortest reference need not be the profile baseline.
        val shortest = candidates.minByOrNull { it.totalDistanceMeters }!!
        fun marginalEfficiency(route: Route): Double? {
            val extraDistance = route.totalDistanceMeters - shortest.totalDistanceMeters
            if (extraDistance <= EQUAL_DISTANCE_EPSILON_METERS) return null
            return (route.metrics.offRoadDistanceMeters - shortest.metrics.offRoadDistanceMeters) / extraDistance
        }
        fun passesEfficiency(route: Route): Boolean =
            marginalEfficiency(route)?.let { it >= MIN_MARGINAL_TERRAIN_EFFICIENCY } ?: true

        // Both gates apply; preserve the input order for score ties.
        val eligible = candidates.filter { route ->
            route.totalDistanceMeters <= maxAllowedDistance && passesEfficiency(route)
        }

        val scores = if (observations != null) java.util.IdentityHashMap<Route, Double>() else null
        val winner = eligible.maxByOrNull { route ->
            val score = tournamentTerrainValue(route, profile)
            scores?.put(route, score)
            score
        }
        if (observations != null) {
            for (route in candidates) {
                val accepted = route.totalDistanceMeters <= maxAllowedDistance
                val rejectionReason = when {
                    !route.totalDistanceMeters.isFinite() -> "INVALID_CANDIDATE"
                    !accepted -> "DETOUR_LIMIT"
                    !passesEfficiency(route) -> "MARGINAL_TERRAIN_EFFICIENCY"
                    else -> null
                }
                // maxByOrNull skips its selector for a singleton. Its score is
                // still defined by the same authoritative scoring function.
                val score = if (rejectionReason == null) scores!![route] ?: tournamentTerrainValue(route, profile) else null
                observations.add(CandidateEvaluation(
                    route, baselineDistanceMeters, limit, maxAllowedDistance, accepted, score,
                    rejectionReason, shortest.totalDistanceMeters, shortest.metrics.offRoadDistanceMeters,
                    marginalEfficiency(route), MIN_MARGINAL_TERRAIN_EFFICIENCY
                ))
            }
        }
        return winner
    }

    internal fun tournamentTerrainValue(route: Route, profile: RoutingProfile): Double {
        val unpaved = route.metrics.offRoadDistanceMeters
        val asphalt = route.metrics.asphaltDistanceMeters
        val longestTerrainRun = route.metrics.longestContinuousTerrainMeters
        val terrainRunCount = route.metrics.terrainRunCount
        val longestAsphaltConnectorMeters = route.metrics.longestAsphaltConnectorMeters

        val continuityBonus = when (profile) {
            RoutingProfile.BEZPIECZNY -> 0.0
            RoutingProfile.TERENOWY -> longestTerrainRun * 0.65
            RoutingProfile.ODKRYWCZY -> longestTerrainRun * 0.85
        }

        val asphaltPenalty = when (profile) {
            RoutingProfile.BEZPIECZNY -> 0.0
            RoutingProfile.TERENOWY -> asphalt * 0.15
            RoutingProfile.ODKRYWCZY -> asphalt * 0.20
        }

        val fragmentationPenalty = (terrainRunCount - 1).coerceAtLeast(0) * when (profile) {
            RoutingProfile.BEZPIECZNY -> 0.0
            RoutingProfile.TERENOWY -> 240.0
            RoutingProfile.ODKRYWCZY -> 150.0
        }

        val connectorPenalty = longestAsphaltConnectorMeters * when (profile) {
            RoutingProfile.BEZPIECZNY -> 0.0
            RoutingProfile.TERENOWY -> 0.50
            RoutingProfile.ODKRYWCZY -> 0.30
        }

        val base = unpaved + continuityBonus - asphaltPenalty - fragmentationPenalty - connectorPenalty
        
        return base
    }
}
