package pl.mazovia.offroad.domain.model

import org.junit.Assert.*
import org.junit.Test

class RouteMetricsTest {

    @Test
    fun `fromSegments calculates off-road percentage correctly`() {
        val segments = listOf(
            createSegment(1000.0, Surface.DIRT, HighwayType.TRACK),
            createSegment(500.0, Surface.ASPHALT, HighwayType.SECONDARY),
            createSegment(1500.0, Surface.GRAVEL, HighwayType.TRACK)
        )
        val metrics = RouteMetrics.fromSegments(segments)
        
        assertEquals(3000.0, metrics.totalDistanceMeters, 0.01)
        assertEquals(2500.0, metrics.offRoadDistanceMeters, 0.01)
        assertEquals(500.0, metrics.asphaltDistanceMeters, 0.01)
        assertTrue("Off-road should be ~83%", metrics.offRoadPercentage in 83.0..84.0)
    }

    @Test
    fun `fromSegments calculates longest asphalt connector`() {
        val segments = listOf(
            createSegment(1000.0, Surface.DIRT, HighwayType.TRACK),
            createSegment(200.0, Surface.ASPHALT, HighwayType.TERTIARY),
            createSegment(300.0, Surface.ASPHALT, HighwayType.SECONDARY),
            createSegment(1000.0, Surface.GRAVEL, HighwayType.TRACK),
            createSegment(100.0, Surface.ASPHALT, HighwayType.RESIDENTIAL)
        )
        val metrics = RouteMetrics.fromSegments(segments)
        
        assertEquals(500.0, metrics.longestAsphaltConnectorMeters, 0.01)
    }

    @Test
    fun `empty segments returns EMPTY metrics`() {
        val metrics = RouteMetrics.fromSegments(emptyList())
        assertEquals(RouteMetrics.EMPTY, metrics)
    }

    private fun createSegment(
        distance: Double,
        surface: Surface,
        highway: HighwayType
    ) = RouteSegment(
        points = listOf(GeoPoint(52.0, 21.0), GeoPoint(52.01, 21.01)),
        distanceMeters = distance,
        surface = surface,
        highway = highway
    )
}
