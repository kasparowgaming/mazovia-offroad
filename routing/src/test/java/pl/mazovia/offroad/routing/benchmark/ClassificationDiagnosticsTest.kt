package pl.mazovia.offroad.routing.benchmark

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.fail
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.RoutingProfile
import pl.mazovia.offroad.domain.routing.RoutingResult
import pl.mazovia.offroad.routing.engine.GraphHopperRoutingEngine
import java.io.File

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before

class ClassificationDiagnosticsTest {

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.v(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun diagnoseClassification() {
        runBlocking {
            val envPath = System.getenv("MAZOVIA_GRAPH_PATH")
            val fallbackPath = File(System.getProperty("user.home"), "Desktop/MazoviaOffroad3/graph-cache-enduro-maz-lub").absolutePath
            val graphPath = envPath ?: fallbackPath

            val graphDir = File(graphPath)
            if (!graphDir.exists() || !graphDir.isDirectory) {
                fail("FAIL FAST: Graph cache directory does not exist at $graphPath.")
            }

            val engine = GraphHopperRoutingEngine()
            val loaded = engine.loadGraph(graphDir.absolutePath)
            if (!loaded) fail("Graph failed to load.")

            println("==================================================")
            println("CLASSIFICATION DIAGNOSTICS")
            println("==================================================")

            val origin = GeoPoint(52.1567802, 22.3448868)
            val destination = GeoPoint(52.1218, 22.4435) // Stok-Lacki-Las-Lipiny
            
            System.setProperty("MAZOVIA_DIAGNOSE_GH", "true")
            
            val result = engine.calculateRoute(
                origin = origin,
                destination = destination,
                waypoints = emptyList(),
                profile = RoutingProfile.TERENOWY
            )
            
            System.setProperty("MAZOVIA_DIAGNOSE_GH", "false")
            
            if (result is RoutingResult.Success) {
                val route = result.route
                println("Off-road distance: ${route.metrics.offRoadDistanceMeters}")
                println("Asphalt distance: ${route.metrics.asphaltDistanceMeters}")
                org.junit.Assert.assertTrue("Route should have off-road distance", route.metrics.offRoadDistanceMeters > 0)
            } else {
                fail("Route calculation failed: $result")
            }
        }
    }
}
