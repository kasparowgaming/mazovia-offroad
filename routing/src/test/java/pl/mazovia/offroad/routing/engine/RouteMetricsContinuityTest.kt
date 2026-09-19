package pl.mazovia.offroad.routing.engine
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.mazovia.offroad.domain.model.*
class RouteMetricsContinuityTest {
    private fun createSegment(surface: Surface, distance: Double): RouteSegment {
        return RouteSegment(
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0)),
            distanceMeters = distance,
            surface = surface,
            highway = HighwayType.UNKNOWN
        )
    }
    @Test
    fun `CASE E - MIXED UNPAVED SURFACES are treated as one continuous terrain run`() {
        val segments = listOf(
            createSegment(Surface.ASPHALT, 100.0),
            createSegment(Surface.GRAVEL, 200.0),
            createSegment(Surface.DIRT, 300.0),
            createSegment(Surface.SAND, 400.0),
            createSegment(Surface.ASPHALT, 100.0)
        )
        val metrics = RouteMetrics.fromSegments(segments)
        // 200 + 300 + 400 = 900
        assertEquals(900.0, metrics.longestContinuousTerrainMeters, 0.01)
        assertEquals(1, metrics.terrainRunCount)
    }
    @Test
    fun `Multiple separated terrain runs are counted correctly`() {
        val segments = listOf(
            createSegment(Surface.GRAVEL, 200.0),
            createSegment(Surface.ASPHALT, 100.0),
            createSegment(Surface.DIRT, 300.0),
            createSegment(Surface.ASPHALT, 500.0),
            createSegment(Surface.SAND, 400.0)
        )
        val metrics = RouteMetrics.fromSegments(segments)
        assertEquals(400.0, metrics.longestContinuousTerrainMeters, 0.01)
        assertEquals(500.0, metrics.longestAsphaltConnectorMeters, 0.01)
    }

    @Test
    fun `Empty segments result in zero metrics`() {
        val metrics = RouteMetrics.fromSegments(emptyList())
        assertEquals(0.0, metrics.longestContinuousTerrainMeters, 0.0)
        assertEquals(0, metrics.terrainRunCount)
    }
    @Test
    fun `100 percent paved route returns zero terrain runs`() {
        val segments = listOf(
            createSegment(Surface.ASPHALT, 500.0),
            createSegment(Surface.PAVED, 500.0)
        )
        val metrics = RouteMetrics.fromSegments(segments)
        assertEquals(0.0, metrics.longestContinuousTerrainMeters, 0.0)
        assertEquals(0, metrics.terrainRunCount)
    }
    @Test
    fun `100 percent terrain route returns one terrain run`() {
        val segments = listOf(
            createSegment(Surface.GRAVEL, 500.0),
            createSegment(Surface.DIRT, 500.0)
        )
        val metrics = RouteMetrics.fromSegments(segments)
        assertEquals(1000.0, metrics.longestContinuousTerrainMeters, 0.01)
        assertEquals(1, metrics.terrainRunCount)
    }
}
