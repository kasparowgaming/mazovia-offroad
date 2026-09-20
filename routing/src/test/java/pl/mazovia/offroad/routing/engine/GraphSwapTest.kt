package pl.mazovia.offroad.routing.engine

import com.graphhopper.GraphHopper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.domain.routing.RoutingEngine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Files retain their graph identity across renames; no real graph files are used. */
internal class GraphSwapFixture {
    val activePath = File("swap-test/graph").absolutePath
    val tempPath = File("swap-test/temp_graph").absolutePath
    val files = ConcurrentHashMap<String, String>().apply {
        put("graph", "old")
        put("temp_graph", "new")
    }
    val events = CopyOnWriteArrayList<String>()
    val old = mockk<GraphHopper>(relaxed = true)
    val replacement = mockk<GraphHopper>(relaxed = true)
    val validation = mockk<GraphHopper>(relaxed = true)
    var failBackup = false
    var failMove = false
    var failRestore = false
    var failDelete = false
    var initFailure: Throwable? = null
    var beforeInit: () -> Unit = {}
    var afterInstall: () -> Unit = {}
    var validationClosed: () -> Unit = {}
    var oldClosing: () -> Unit = {}
    val engine = object : GraphHopperRoutingEngine() {
        override fun initGraphHopper(graphPath: String): GraphHopper {
            if (graphPath == tempPath) return validation
            check(graphPath == activePath)
            if (files["graph"] == "old") return old
            events.add("init-new")
            beforeInit()
            initFailure?.let { throw it }
            return replacement
        }
    }

    init {
        every { old.baseGraph.nodes } returns 11
        every { replacement.baseGraph.nodes } returns 22
        every { old.close() } answers {
            oldClosing()
            events.add("close-old")
        }
        every { replacement.close() } answers { events.add("close-new"); Unit }
        every { validation.close() } answers { validationClosed() }
        engine.fs = object : GraphHopperRoutingEngine.Filesystem {
            override fun exists(file: File) = files.containsKey(file.name)
            override fun renameTo(src: File, dest: File): Boolean {
                if (src.name == "graph" && failBackup) return false
                if (src.name == "temp_graph" && failMove) return false
                if (src.name == "graph_backup" && failRestore) return false
                if (files.containsKey(dest.name)) return false
                val graph = files.remove(src.name) ?: return false
                files[dest.name] = graph
                events.add("move-${src.name}-${dest.name}")
                if (src.name == "temp_graph") afterInstall()
                return true
            }
            override fun deleteRecursively(file: File): Boolean {
                if (file.name == "graph" && failDelete) return false
                events.add("delete-${file.name}")
                files.remove(file.name)
                return true
            }
        }
    }

    suspend fun loadOld() {
        assertTrue(engine.loadGraph(activePath))
        assertOld(activePath)
    }

    suspend fun assertOld(path: String?) {
        assertTrue(engine.isReady())
        val state = engine.getState()
        assertTrue(state.isGraphLoaded)
        assertEquals(11L, state.nodeCount)
        assertEquals(path, state.graphPath)
        assertFalse(events.contains("close-old"))
    }

    // Observe the exact publication point from close(), while the engine mutex
    // is held. Calling getState() here would re-enter the non-reentrant mutex.
    fun publishedGraph(): GraphHopper? {
        val field = GraphHopperRoutingEngine::class.java.getDeclaredField("engineState")
        field.isAccessible = true
        val state = field.get(engine)
        val graph = state.javaClass.getDeclaredField("graphHopper")
        graph.isAccessible = true
        return graph.get(state) as GraphHopper?
    }
}

internal fun CountDownLatch.awaitBounded() {
    check(await(10, TimeUnit.SECONDS)) { "Timed out waiting for test gate" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GraphSwapTest {
    @Test
    fun `successful swap publishes before closing old and clears error then unload clears state`() = runTest {
        val f = GraphSwapFixture()
        f.loadOld()
        f.failBackup = true
        assertTrue(f.engine.validateAndSwapGraph(f.tempPath) is RoutingEngine.ImportResult.Error)
        assertNotNull(f.engine.getState().lastError)
        f.failBackup = false
        f.oldClosing = {
            assertSame(f.replacement, f.publishedGraph())
            assertEquals("old", f.files["graph_backup"])
        }
        assertTrue(f.engine.validateAndSwapGraph(f.tempPath) is RoutingEngine.ImportResult.Success)
        assertTrue(f.engine.isReady())
        val state = f.engine.getState()
        assertEquals(22L, state.nodeCount)
        assertEquals(f.activePath, state.graphPath)
        assertNull(state.lastError)
        assertEquals(mapOf("graph" to "new"), f.files)
        assertTrue(f.events.indexOf("close-old") < f.events.indexOf("delete-graph_backup"))
        f.engine.unloadGraph()
        assertFalse(f.engine.isReady())
        val unloaded = f.engine.getState()
        assertFalse(unloaded.isGraphLoaded)
        assertNull(unloaded.graphPath)
        assertNull(unloaded.lastError)
        assertTrue(f.events.contains("close-new"))
    }

    @Test
    fun `backup rename failure keeps active old graph`() = runTest {
        val f = GraphSwapFixture()
        f.loadOld()
        f.failBackup = true
        assertTrue(f.engine.validateAndSwapGraph(f.tempPath) is RoutingEngine.ImportResult.Error)
        f.assertOld(f.activePath)
        assertEquals("old", f.files["graph"])
        assertFalse(f.files.containsKey("graph_backup"))
    }

    @Test
    fun `replacement move failure restores active old graph`() = runTest {
        val f = GraphSwapFixture()
        f.loadOld()
        f.failMove = true
        assertTrue(f.engine.validateAndSwapGraph(f.tempPath) is RoutingEngine.ImportResult.Error)
        f.assertOld(f.activePath)
        assertEquals("old", f.files["graph"])
        assertFalse(f.files.containsKey("graph_backup"))
        assertNotNull(f.engine.getState().lastError)
        assertFalse(f.events.contains("init-new"))
    }

    @Test
    fun `replacement init failure restores active old graph`() = runTest {
        val f = GraphSwapFixture()
        f.loadOld()
        f.initFailure = IllegalArgumentException("Bad replacement")
        assertTrue(f.engine.validateAndSwapGraph(f.tempPath) is RoutingEngine.ImportResult.Error)
        f.assertOld(f.activePath)
        assertEquals("old", f.files["graph"])
        assertFalse(f.files.containsKey("graph_backup"))
        assertTrue(f.engine.getState().lastError!!.contains("Bad replacement"))
        f.initFailure = null
        f.files["graph"] = "new"
        assertTrue(f.engine.loadGraph(f.activePath))
        assertEquals(22L, f.engine.getState().nodeCount)
        assertNull(f.engine.getState().lastError)
    }

    @Test
    fun `real cancellation during synchronous init disposes replacement and rolls back before rethrow`() = runTest {
        val f = GraphSwapFixture()
        f.loadOld()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        f.beforeInit = { entered.complete(Unit); release.awaitBounded() }
        val observed = CompletableDeferred<Unit>()
        val job = launch {
            try {
                f.engine.validateAndSwapGraph(f.tempPath)
                fail("Cancelled swap returned normally")
            } catch (expected: CancellationException) {
                assertEquals("old", f.files["graph"])
                assertFalse(f.files.containsKey("graph_backup"))
                assertSame(f.old, f.publishedGraph())
                assertTrue(f.events.contains("close-new"))
                observed.complete(Unit)
            }
        }
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()
        assertTrue(observed.isCompleted)
        f.assertOld(f.activePath)
        assertTrue(f.events.indexOf("close-new") < f.events.indexOf("delete-graph"))
    }

    @Test
    fun `cancellation after install before init rolls back without creating replacement`() = runTest {
        val f = GraphSwapFixture()
        f.loadOld()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        f.afterInstall = { entered.complete(Unit); release.awaitBounded() }
        val job = launch { f.engine.validateAndSwapGraph(f.tempPath) }
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()
        f.assertOld(f.activePath)
        assertEquals("old", f.files["graph"])
        assertFalse(f.events.contains("init-new"))
    }

    @Test
    fun `failed rollback preserves backup and exposes unknown path and lifecycle error`() = runTest {
        for (failure in listOf("move", "init", "delete")) {
            val f = GraphSwapFixture()
            f.loadOld()
            f.failMove = failure == "move"
            f.initFailure = IllegalArgumentException("Bad replacement")
            f.failRestore = failure != "delete"
            f.failDelete = failure == "delete"
            assertTrue(f.engine.validateAndSwapGraph(f.tempPath) is RoutingEngine.ImportResult.Error)
            f.assertOld(null)
            assertTrue(f.engine.getState().lastError!!.contains("CRITICAL"))
            assertEquals("old", f.files["graph_backup"])
            assertFalse(f.events.contains("delete-graph_backup"))
            f.engine.unloadGraph()
            assertFalse(f.engine.isReady())
            assertNull(f.engine.getState().graphPath)
            assertNull(f.engine.getState().lastError)
        }
    }
}
