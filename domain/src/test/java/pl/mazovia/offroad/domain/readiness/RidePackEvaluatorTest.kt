package pl.mazovia.offroad.domain.readiness

import android.content.Context
import android.location.LocationManager
import android.content.pm.PackageManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.RoutingEngine
import java.io.File
import java.nio.file.Files

class RidePackEvaluatorTest {

    private lateinit var context: Context
    private lateinit var routingEngine: RoutingEngine
    private lateinit var evaluator: RidePackEvaluator
    private lateinit var tempDir: File
    private lateinit var locationManager: LocationManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        routingEngine = mockk(relaxed = true)
        locationManager = mockk(relaxed = true)
        
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        
        every { 
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
        } returns PackageManager.PERMISSION_GRANTED
        
        every { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns true

        evaluator = RidePackEvaluator(context, routingEngine)
        
        tempDir = Files.createTempDirectory("mazovia_test").toFile()
        every { context.getExternalFilesDir(null) } returns tempDir
    }

    @Test
    fun `null route returns NOT_READY`() = runBlocking {
        val result = evaluator.evaluate(null)
        assertEquals(ComponentReadiness.NOT_READY, result.overallStatus)
    }

    @Test
    fun `calculated route with local map and graph is PARTIAL due to unknown map coverage`() = runBlocking {
        // Setup map (exists but coverage is UNKNOWN)
        File(tempDir, "mazowieckie_offroad.pmtiles").writeText("dummy data")
        // Setup graph
        coEvery { routingEngine.isReady() } returns true
        
        val route = Route(
            id = "test",
            origin = GeoPoint(0.0, 0.0),
            destination = GeoPoint(1.0, 1.0),
            segments = listOf(RouteSegment(listOf(GeoPoint(0.0, 0.0)), distanceMeters = 100.0, surface = Surface.ASPHALT, highway = HighwayType.UNCLASSIFIED)),
            metrics = RouteMetrics.EMPTY,
            profile = RoutingProfile.TERENOWY,
            source = RouteSource.CALCULATED_ROUTE
        )

        val result = evaluator.evaluate(route)
        assertEquals(ComponentReadiness.UNKNOWN, result.resource.mapResource)
        assertEquals(ComponentReadiness.READY, result.resource.routeArtifact)
        assertEquals(ComponentReadiness.READY, result.resource.offlineGraph)
        assertEquals(ComponentReadiness.READY, result.capability.fullRerouting)
        
        // Due to map coverage being UNKNOWN, overall ride status cannot be full READY, it should be PARTIAL.
        assertEquals(ComponentReadiness.PARTIAL, result.overallStatus)
    }

    @Test
    fun `calculated route missing map file is NOT_READY`() = runBlocking {
        // Map missing
        coEvery { routingEngine.isReady() } returns true
        
        val route = Route(
            id = "test",
            origin = GeoPoint(0.0, 0.0),
            destination = GeoPoint(1.0, 1.0),
            segments = listOf(RouteSegment(listOf(GeoPoint(0.0, 0.0)), distanceMeters = 100.0, surface = Surface.ASPHALT, highway = HighwayType.UNCLASSIFIED)),
            metrics = RouteMetrics.EMPTY,
            profile = RoutingProfile.TERENOWY,
            source = RouteSource.CALCULATED_ROUTE
        )

        val result = evaluator.evaluate(route)
        assertEquals(ComponentReadiness.NOT_READY, result.resource.mapResource)
        assertEquals(ComponentReadiness.NOT_READY, result.overallStatus)
    }

    @Test
    fun `calculated route missing graph is PARTIAL since it can still be followed`() = runBlocking {
        File(tempDir, "mazowieckie_offroad.pmtiles").writeText("dummy data")
        // Graph missing
        coEvery { routingEngine.isReady() } returns false
        
        val route = Route(
            id = "test",
            origin = GeoPoint(0.0, 0.0),
            destination = GeoPoint(1.0, 1.0),
            segments = listOf(RouteSegment(listOf(GeoPoint(0.0, 0.0)), distanceMeters = 100.0, surface = Surface.ASPHALT, highway = HighwayType.UNCLASSIFIED)),
            metrics = RouteMetrics.EMPTY,
            profile = RoutingProfile.TERENOWY,
            source = RouteSource.CALCULATED_ROUTE
        )

        val result = evaluator.evaluate(route)
        assertEquals(ComponentReadiness.NOT_READY, result.resource.offlineGraph)
        assertEquals(ComponentReadiness.NOT_READY, result.capability.fullRerouting)
        assertEquals(ComponentReadiness.PARTIAL, result.overallStatus)
    }

    @Test
    fun `gpx route with map and NO graph is PARTIAL due to unknown map coverage`() = runBlocking {
        File(tempDir, "mazowieckie_offroad.pmtiles").writeText("dummy data")
        coEvery { routingEngine.isReady() } returns false
        
        val route = Route(
            id = "test",
            origin = GeoPoint(0.0, 0.0),
            destination = GeoPoint(1.0, 1.0),
            segments = listOf(RouteSegment(listOf(GeoPoint(0.0, 0.0)), distanceMeters = 100.0, surface = Surface.ASPHALT, highway = HighwayType.UNCLASSIFIED)),
            metrics = RouteMetrics.EMPTY,
            profile = RoutingProfile.TERENOWY,
            source = RouteSource.IMPORTED_GPX,
            originalGpx = GpxData(null, null, emptyList(), emptyList())
        )

        val result = evaluator.evaluate(route)
        assertEquals(ComponentReadiness.UNKNOWN, result.resource.mapResource)
        assertEquals(ComponentReadiness.NOT_REQUIRED, result.resource.offlineGraph)
        assertEquals(ComponentReadiness.PARTIAL, result.capability.recoveryGuidance) // GPX manual recovery
        assertEquals(ComponentReadiness.READY, result.capability.followGeometry)
        
        // UNKNOWN map coverage makes it PARTIAL
        assertEquals(ComponentReadiness.PARTIAL, result.overallStatus)
    }
}
