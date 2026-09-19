package pl.mazovia.offroad.routing.engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.mazovia.offroad.domain.model.*
class RouteTournamentTest {
    private fun createMockRoute(
        distanceMeters: Double,
        offRoadMeters: Double,
        asphaltMeters: Double,
        longestContinuousTerrain: Double,
        terrainRuns: Int,
        longestConnector: Double
    ): Route {
        return Route(
            id = "test",
            origin = GeoPoint(0.0, 0.0),
            destination = GeoPoint(0.0, 0.0),
            segments = emptyList(),
            metrics = RouteMetrics(
                totalDistanceMeters = distanceMeters,
                estimatedTimeSeconds = 0,
                offRoadDistanceMeters = offRoadMeters,
                asphaltDistanceMeters = asphaltMeters,
                longestAsphaltConnectorMeters = longestConnector,
                surfaceDistribution = emptyMap(),
                dataConfidenceScore = 1.0,
                longestContinuousTerrainMeters = longestContinuousTerrain,
                terrainRunCount = terrainRuns
            ),
            profile = RoutingProfile.TERENOWY,
            maneuvers = emptyList()
        )
    }
    @Test
    fun `CASE A - CONTINUITY prefers one long continuous section over fragmented ones`() {
        // Candidate A: 45km total, 20km dirt, 1 run of 15km
        val candidateA = createMockRoute(
            distanceMeters = 45000.0,
            offRoadMeters = 20000.0,
            asphaltMeters = 25000.0,
            longestContinuousTerrain = 15000.0,
            terrainRuns = 2,
            longestConnector = 10000.0
        )
        // Candidate B: 43km total, 22km dirt, 10 runs of 2km
        val candidateB = createMockRoute(
            distanceMeters = 43000.0,
            offRoadMeters = 22000.0,
            asphaltMeters = 21000.0,
            longestContinuousTerrain = 2200.0,
            terrainRuns = 10,
            longestConnector = 2000.0
        )
        val winner = RouteTournament.chooseTournamentWinner(
            candidates = listOf(candidateA, candidateB),
            baselineDistanceMeters = 40000.0,
            profile = RoutingProfile.TERENOWY
        )
        assertEquals(candidateA, winner)
    }
    @Test
    fun `CASE B - EXTREME DETOUR rejects candidate exceeding 60 percent limit for TERENOWY`() {
        val baselineDistance = 40000.0 // 40km
        // 64001m is exactly 60.0025% detour, which exceeds 0.60 limit
        val extremeDetour = createMockRoute(
            distanceMeters = 64001.0,
            offRoadMeters = 64001.0,
            asphaltMeters = 0.0,
            longestContinuousTerrain = 64001.0,
            terrainRuns = 1,
            longestConnector = 0.0
        )
        // 63999m is 59.9975% detour, which is within 0.60 limit
        val validDetour = createMockRoute(
            distanceMeters = 63999.0,
            offRoadMeters = 63999.0,
            asphaltMeters = 0.0,
            longestContinuousTerrain = 63999.0,
            terrainRuns = 1,
            longestConnector = 0.0
        )
        val winner = RouteTournament.chooseTournamentWinner(
            candidates = listOf(extremeDetour, validDetour),
            baselineDistanceMeters = baselineDistance,
            profile = RoutingProfile.TERENOWY
        )
        assertEquals(validDetour, winner)
        val rejectedWinner = RouteTournament.chooseTournamentWinner(
            candidates = listOf(extremeDetour),
            baselineDistanceMeters = baselineDistance,
            profile = RoutingProfile.TERENOWY
        )
        assertNull(rejectedWinner)
    }
    @Test
    fun `CASE C - ASPHALT CONNECTOR penalizes long asphalt links`() {
        // Candidate A: 5km asphalt in one long connector
        val candidateA = createMockRoute(
            distanceMeters = 20000.0,
            offRoadMeters = 15000.0,
            asphaltMeters = 5000.0,
            longestContinuousTerrain = 7500.0,
            terrainRuns = 2,
            longestConnector = 5000.0
        )
        // Candidate B: 5km asphalt split into small chunks
        val candidateB = createMockRoute(
            distanceMeters = 20000.0,
            offRoadMeters = 15000.0,
            asphaltMeters = 5000.0,
            longestContinuousTerrain = 7500.0,
            terrainRuns = 2,
            longestConnector = 500.0
        )
        val winner = RouteTournament.chooseTournamentWinner(
            candidates = listOf(candidateA, candidateB),
            baselineDistanceMeters = 20000.0,
            profile = RoutingProfile.TERENOWY
        )
        assertEquals(candidateB, winner)
    }
    @Test
    fun `CASE D - FRAGMENTATION penalizes higher terrain run count`() {
        val fragmented = createMockRoute(
            distanceMeters = 10000.0,
            offRoadMeters = 5000.0,
            asphaltMeters = 5000.0,
            longestContinuousTerrain = 1000.0,
            terrainRuns = 5,
            longestConnector = 1000.0
        )
        val contiguous = createMockRoute(
            distanceMeters = 10000.0,
            offRoadMeters = 5000.0,
            asphaltMeters = 5000.0,
            longestContinuousTerrain = 1000.0, // Same max run for fair fragmentation comparison
            terrainRuns = 1,
            longestConnector = 1000.0
        )
        val winner = RouteTournament.chooseTournamentWinner(
            candidates = listOf(fragmented, contiguous),
            baselineDistanceMeters = 10000.0,
            profile = RoutingProfile.TERENOWY
        )
        assertEquals(contiguous, winner)
    }
    @Test
    fun `ODKRYWCZY profile allows 100 percent detour`() {
        val baselineDistance = 40000.0 // 40km
        // 79999m is 99.997% detour, which is within 1.00 limit for ODKRYWCZY
        val extremeDetour = createMockRoute(
            distanceMeters = 79999.0,
            offRoadMeters = 79999.0,
            asphaltMeters = 0.0,
            longestContinuousTerrain = 79999.0,
            terrainRuns = 1,
            longestConnector = 0.0
        )
        val winner = RouteTournament.chooseTournamentWinner(
            candidates = listOf(extremeDetour),
            baselineDistanceMeters = baselineDistance,
            profile = RoutingProfile.ODKRYWCZY
        )
        assertEquals(extremeDetour, winner)
    }
    @Test
    fun `Baseline route is selected when all alternatives exceed detour cap`() {
        val baselineDistance = 40000.0 // 40km
        val baseline = createMockRoute(
            distanceMeters = 40000.0,
            offRoadMeters = 5000.0,
            asphaltMeters = 35000.0,
            longestContinuousTerrain = 5000.0,
            terrainRuns = 1,
            longestConnector = 35000.0
        )
        // 65000m exceeds 60% limit for TERENOWY
        val invalidDetour = createMockRoute(
            distanceMeters = 65000.0,
            offRoadMeters = 65000.0,
            asphaltMeters = 0.0,
            longestContinuousTerrain = 65000.0,
            terrainRuns = 1,
            longestConnector = 0.0
        )
        val winner = RouteTournament.chooseTournamentWinner(
            candidates = listOf(invalidDetour, baseline),
            baselineDistanceMeters = baselineDistance,
            profile = RoutingProfile.TERENOWY
        )
        // Invalid detour is filtered out, baseline survives
        assertEquals(baseline, winner)
    }
}
