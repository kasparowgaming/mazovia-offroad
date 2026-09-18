package pl.mazovia.offroad.routing.terrain

import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.domain.model.*

class TerrainRadarCalculatorTest {

    private val calculator = TerrainRadarCalculator(lookAheadMeters = 5000.0)

    @Test
    fun `radar shows high off-road proportion for terrain route`() {
        val route = createRoute(
            listOf(
                createSegment(2000.0, Surface.DIRT, HighwayType.TRACK),
                createSegment(500.0, Surface.ASPHALT, HighwayType.TERTIARY),
                createSegment(1500.0, Surface.GRAVEL, HighwayType.TRACK),
                createSegment(1000.0, Surface.DIRT, HighwayType.TRACK)
            )
        )

        val radar = calculator.calculate(route, currentSegmentIndex = 0)

        assertTrue("Off-road proportion should be > 0.7, was ${radar.offRoadProportion}",
            radar.offRoadProportion > 0.7)
        assertEquals(4, radar.segments.size)
    }

    @Test
    fun `radar detects asphalt connector`() {
        val route = createRoute(
            listOf(
                createSegment(1000.0, Surface.DIRT, HighwayType.TRACK),
                createSegment(2000.0, Surface.ASPHALT, HighwayType.SECONDARY),
                createSegment(1000.0, Surface.GRAVEL, HighwayType.TRACK)
            )
        )

        val radar = calculator.calculate(route, currentSegmentIndex = 0)

        assertNotNull("Should detect asphalt connector", radar.distanceToAsphaltMeters)
        assertEquals(1000.0, radar.distanceToAsphaltMeters!!, 0.01)
        assertEquals(2000.0, radar.asphaltConnectorLengthMeters!!, 0.01)
    }

    @Test
    fun `radar handles empty segments`() {
        val route = createRoute(emptyList())

        val radar = calculator.calculate(route, currentSegmentIndex = 0)

        assertEquals(0, radar.segments.size)
        assertEquals(0.0, radar.offRoadProportion, 0.001)
    }

    @Test
    fun `radar respects current segment index`() {
        val route = createRoute(
            listOf(
                createSegment(2000.0, Surface.ASPHALT, HighwayType.SECONDARY),
                createSegment(1000.0, Surface.DIRT, HighwayType.TRACK),
                createSegment(1000.0, Surface.GRAVEL, HighwayType.TRACK)
            )
        )

        // Start from segment 1 (past the asphalt)
        val radar = calculator.calculate(route, currentSegmentIndex = 1)

        assertEquals(2, radar.segments.size)
        assertEquals(1.0, radar.offRoadProportion, 0.001)
    }

    private fun createSegment(distance: Double, surface: Surface, highway: HighwayType) =
        RouteSegment(
            points = listOf(GeoPoint(52.0, 21.0), GeoPoint(52.01, 21.01)),
            distanceMeters = distance,
            surface = surface,
            highway = highway,
            dataConfidence = DataConfidence.CONFIRMED
        )

    private fun createRoute(segments: List<RouteSegment>) = Route(
        id = "test",
        origin = GeoPoint(52.0, 21.0),
        destination = GeoPoint(52.1, 21.1),
        segments = segments,
        metrics = RouteMetrics.fromSegments(segments),
        profile = RoutingProfile.TERENOWY
    )
}
