package pl.mazovia.offroad.routing.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.mazovia.offroad.domain.model.*

class RouteDiagnosticsTest {

    @Test
    fun testDetourCalculation() {
        val route = Route(
            id = "r1",
            origin = GeoPoint(0.0, 0.0),
            destination = GeoPoint(0.0, 0.0),
            segments = emptyList(),
            profile = RoutingProfile.BEZPIECZNY,
            metrics = RouteMetrics(
                totalDistanceMeters = 1500.0,
                estimatedTimeSeconds = 600,
                offRoadDistanceMeters = 0.0,
                asphaltDistanceMeters = 0.0,
                longestContinuousTerrainMeters = 0.0,
                terrainRunCount = 0,
                longestAsphaltConnectorMeters = 0.0,
                surfaceDistribution = emptyMap(),
                retraceFraction = 0.0,
                continuitScore = 0.0,
                dataConfidenceScore = 0.0,
                explorationScore = 0.0
            )
        )
        
        val diag1 = routeDiagnostics(route, shortestDistanceMeters = 1000.0)
        assertEquals(50, diag1.detourPercent) // 1500 is 50% more than 1000
        
        val diag2 = routeDiagnostics(route, shortestDistanceMeters = 2000.0)
        assertEquals(0, diag2.detourPercent) // coerced to 0
    }

    @Test
    fun testUTurnDetection() {
        val route = Route(
            id = "r1",
            origin = GeoPoint(0.0, 0.0),
            destination = GeoPoint(0.0, 0.0),
            segments = emptyList(),
            profile = RoutingProfile.BEZPIECZNY,
            metrics = mockMetrics(),
            maneuvers = listOf(
                Maneuver(GeoPoint(0.0,0.0), ManeuverType.TURN_RIGHT, 100.0),
                Maneuver(GeoPoint(0.0,0.0), ManeuverType.U_TURN, 50.0),
                Maneuver(GeoPoint(0.0,0.0), ManeuverType.U_TURN, 20.0),
                Maneuver(GeoPoint(0.0,0.0), ManeuverType.ARRIVE, 0.0)
            )
        )
        
        val diag = routeDiagnostics(route, 1000.0)
        assertEquals(2, diag.uTurnCount)
    }

    @Test
    fun testShortManeuverLegs() {
        val route = Route(
            id = "r1",
            origin = GeoPoint(0.0, 0.0),
            destination = GeoPoint(0.0, 0.0),
            segments = emptyList(),
            profile = RoutingProfile.BEZPIECZNY,
            metrics = mockMetrics(),
            maneuvers = listOf(
                Maneuver(GeoPoint(0.0,0.0), ManeuverType.TURN_RIGHT, 100.0), // not short
                Maneuver(GeoPoint(0.0,0.0), ManeuverType.TURN_LEFT, 50.0), // < 60
                Maneuver(GeoPoint(0.0,0.0), ManeuverType.TURN_RIGHT, 20.0), // < 60
                Maneuver(GeoPoint(0.0,0.0), ManeuverType.ARRIVE, 10.0) // arrive ignored
            )
        )
        
        val diag = routeDiagnostics(route, 1000.0)
        assertEquals(2, diag.shortManeuverLegCount)
    }

    private fun mockMetrics() = RouteMetrics(
        totalDistanceMeters = 1000.0,
        estimatedTimeSeconds = 600,
        offRoadDistanceMeters = 0.0,
        asphaltDistanceMeters = 0.0,
        longestContinuousTerrainMeters = 0.0,
        terrainRunCount = 0,
        longestAsphaltConnectorMeters = 0.0,
        surfaceDistribution = emptyMap(),
        retraceFraction = 0.0,
        continuitScore = 0.0,
        dataConfidenceScore = 0.0,
        explorationScore = 0.0
    )
}
