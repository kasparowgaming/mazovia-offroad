package pl.mazovia.offroad.domain.navigation

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import pl.mazovia.offroad.domain.model.GeoPoint

class OffRouteDetectorTest {

    private lateinit var detector: OffRouteDetector
    private val routePoints = listOf(
        GeoPoint(52.2297, 21.0122),
        GeoPoint(52.2300, 21.0130),
        GeoPoint(52.2310, 21.0140),
        GeoPoint(52.2320, 21.0150),
        GeoPoint(52.2330, 21.0160)
    )

    @Before
    fun setup() {
        detector = OffRouteDetector(
            offRouteThresholdMeters = 50.0,
            consecutiveReadingsThreshold = 3,
            onRouteThresholdMeters = 30.0
        )
    }

    @Test
    fun `on route position returns ON_ROUTE`() {
        val onRoute = GeoPoint(52.2298, 21.0125)
        val state = detector.checkPosition(onRoute, routePoints)
        assertEquals(OffRouteState.ON_ROUTE, state)
    }

    @Test
    fun `single off-route reading does not trigger OFF_ROUTE due to noise tolerance`() {
        val offRoute = GeoPoint(52.2350, 21.0200) // Far from route
        val state = detector.checkPosition(offRoute, routePoints)
        assertEquals("Single reading should stay ON_ROUTE", OffRouteState.ON_ROUTE, state)
    }

    @Test
    fun `consecutive off-route readings trigger OFF_ROUTE`() {
        val offRoute = GeoPoint(52.24, 21.03) // Very far from route
        
        detector.checkPosition(offRoute, routePoints)
        detector.checkPosition(offRoute, routePoints)
        val state = detector.checkPosition(offRoute, routePoints)
        
        assertEquals(OffRouteState.OFF_ROUTE, state)
    }

    @Test
    fun `returning to route triggers ROUTE_RECOVERED`() {
        val offRoute = GeoPoint(52.24, 21.03)
        
        // Go off route
        repeat(3) { detector.checkPosition(offRoute, routePoints) }
        assertEquals(OffRouteState.OFF_ROUTE, detector.checkPosition(offRoute, routePoints))
        
        // Return to route
        val onRoute = GeoPoint(52.2298, 21.0125)
        val recovered = detector.checkPosition(onRoute, routePoints)
        assertEquals(OffRouteState.ROUTE_RECOVERED, recovered)
    }

    @Test
    fun `empty route returns ON_ROUTE`() {
        val state = detector.checkPosition(GeoPoint(52.0, 21.0), emptyList())
        assertEquals(OffRouteState.ON_ROUTE, state)
    }

    @Test
    fun `reset clears state`() {
        val offRoute = GeoPoint(52.24, 21.03)
        repeat(3) { detector.checkPosition(offRoute, routePoints) }
        
        detector.reset()
        
        val state = detector.checkPosition(offRoute, routePoints)
        assertEquals("After reset, single reading should be ON_ROUTE", OffRouteState.ON_ROUTE, state)
    }
}
