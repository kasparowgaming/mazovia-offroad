package pl.mazovia.offroad.routing.engine

import com.graphhopper.GHResponse
import com.graphhopper.ResponsePath
import com.graphhopper.util.InstructionList
import com.graphhopper.util.PointList
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CalculateAlternativesConcurrencyTest {
    @Test
    fun `successful swap waits for alternatives conversion before closing active graph`() = runTest {
        val f = GraphSwapFixture()
        f.loadOld()
        val converting = CompletableDeferred<Unit>()
        val releaseConversion = CountDownLatch(1)
        val validated = CompletableDeferred<Unit>()
        val swapFinished = CountDownLatch(1)
        val points = PointList().apply { add(52.0, 21.0); add(52.01, 21.01) }
        val path = mockk<ResponsePath>()
        every { path.hasErrors() } returns false
        every { path.errors } returns emptyList()
        every { path.points } answers {
            // gh.route has already returned. The graph must remain protected
            // through conversion as well, not just the synchronous route call.
            converting.complete(Unit)
            releaseConversion.awaitBounded()
            assertFalse(f.events.contains("close-old"))
            points
        }
        every { path.pathDetails } returns emptyMap()
        every { path.distance } returns 1500.0
        every { path.instructions } returns InstructionList(null)
        every { f.old.route(any()) } returns GHResponse().apply { add(path) }
        f.validationClosed = { validated.complete(Unit) }
        val calc = async {
            f.engine.calculateAlternatives(GeoPoint(52.0, 21.0), GeoPoint(52.01, 21.01), RoutingProfile.TERENOWY, 3)
        }
        withContext(Dispatchers.Default) { withTimeout(10_000) { converting.await() } }
        val swap = async(Dispatchers.Default) {
            try {
                f.engine.validateAndSwapGraph(f.tempPath)
            } finally {
                swapFinished.countDown()
            }
        }
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { validated.await() } }
            // Real-time bounded observation: both operations use real IO threads.
            assertFalse(swapFinished.await(200, TimeUnit.MILLISECONDS))
            assertFalse(f.events.contains("close-old"))
            assertEquals("old", f.files["graph"])
            assertFalse(f.files.containsKey("graph_backup"))
        } finally {
            releaseConversion.countDown()
        }
        val results = calc.await()
        assertEquals(1, results.size)
        assertTrue(results.single() is RoutingResult.Success)
        assertTrue(swap.await() is RoutingEngine.ImportResult.Success)
        assertTrue(f.events.contains("close-old"))
        assertSame(f.replacement, f.publishedGraph())
        assertTrue(f.engine.isReady())
        assertEquals(22L, f.engine.getState().nodeCount)
        assertEquals(f.activePath, f.engine.getState().graphPath)
        assertNull(f.engine.getState().lastError)
        assertEquals(mapOf("graph" to "new"), f.files)
    }
}
