package pl.mazovia.offroad.routing.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.*
import java.io.File
import com.graphhopper.GraphHopper
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse

@OptIn(ExperimentalCoroutinesApi::class)
class CalculateAlternativesConcurrencyTest {

    class BlockableGraphHopper : GraphHopper() {
        val inRoute = kotlinx.coroutines.CompletableDeferred<Unit>()
        val routeBlocker = kotlinx.coroutines.CompletableDeferred<Unit>()

        override fun route(request: GHRequest?): GHResponse {
            inRoute.complete(Unit)
            runBlocking {
                routeBlocker.await()
            }
            return GHResponse()
        }

        override fun close() {
        }
    }

    class TestEngine(val gh: BlockableGraphHopper) : GraphHopperRoutingEngine() {
        init {
            fs = object : GraphHopperRoutingEngine.Filesystem {
                override fun exists(file: File) = true
                override fun renameTo(src: File, dest: File) = true
                override fun deleteRecursively(file: File) = true
            }
        }

        override fun initGraphHopper(graphPath: String): GraphHopper {
            if (graphPath == "dummy_path_first") return gh
            return GraphHopper() // Dummy for swap
        }
    }

    @Test
    fun `calculateAlternatives cannot be interleaved by validateAndSwapGraph`() = runTest {
        val gh = BlockableGraphHopper()
        val engine = TestEngine(gh)

        // initialize the engine
        engine.loadGraph("dummy_path_first")

        val calcJob = launch {
            engine.calculateAlternatives(
                GeoPoint(1.0, 1.0),
                GeoPoint(2.0, 2.0),
                RoutingProfile.TERENOWY,
                3
            )
        }

        // wait until we are actually inside gh.route() holding the lock
        gh.inRoute.await()

        // attempt to swap graph
        val swapJob = launch {
            engine.validateAndSwapGraph("dummy_path")
        }

        // verify swap is blocked
        delay(50)
        assertTrue(swapJob.isActive)

        // release the lock
        gh.routeBlocker.complete(Unit)

        // wait for operations to finish
        calcJob.join()
        swapJob.join()

        assertFalse(calcJob.isActive)
        assertFalse(swapJob.isActive)
    }
}
