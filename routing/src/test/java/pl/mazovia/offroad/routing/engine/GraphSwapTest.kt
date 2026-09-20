package pl.mazovia.offroad.routing.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.domain.routing.RoutingEngine
import java.io.File
import com.graphhopper.GraphHopper
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class GraphSwapTest {

    class MockFilesystem : GraphHopperRoutingEngine.Filesystem {
        val existingFiles = mutableSetOf<String>()
        var renameFails = false
        var restoreFails = false

        override fun exists(file: File): Boolean = existingFiles.contains(file.name)

        override fun renameTo(src: File, dest: File): Boolean {
            if (renameFails && src.name == "temp_graph" && dest.name == "graph") return false
            if (restoreFails && src.name == "graph_backup" && dest.name == "graph") return false

            existingFiles.remove(src.name)
            existingFiles.add(dest.name)
            return true
        }

        override fun deleteRecursively(file: File): Boolean {
            existingFiles.remove(file.name)
            return true
        }
    }

    class TestEngine(val mockFs: MockFilesystem) : GraphHopperRoutingEngine() {
        init {
            fs = mockFs
        }

        var initThrows: Throwable? = null
        var throwOnInitCount: Int? = null
        var initCount = 0

        override fun initGraphHopper(graphPath: String): GraphHopper {
            initCount++
            if (throwOnInitCount == initCount) {
                initThrows?.let { throw it }
            }
            return GraphHopper() // Return dummy
        }
    }

    @Test
    fun `CASE A - backup rename failure leaves old graph active`() = runTest {
        val fs = MockFilesystem()
        fs.existingFiles.add("graph")
        fs.existingFiles.add("temp_graph")

        val engine = TestEngine(fs)

        // This is weird, but we need to trick renameTo failing
        // We can override renameTo to fail if src="graph" and dest="graph_backup"
        val strictFs = object : GraphHopperRoutingEngine.Filesystem {
            override fun exists(file: File) = fs.exists(file)
            override fun deleteRecursively(file: File) = fs.deleteRecursively(file)
            override fun renameTo(src: File, dest: File): Boolean {
                if (src.name == "graph" && dest.name == "graph_backup") return false
                return fs.renameTo(src, dest)
            }
        }
        engine.fs = strictFs

        val res = engine.validateAndSwapGraph("path/to/temp_graph")

        assertTrue(res is RoutingEngine.ImportResult.Error)
        assertTrue(fs.existingFiles.contains("graph"))
    }

    @Test
    fun `CASE B - replacement rename failure restores backup`() = runTest {
        val fs = MockFilesystem()
        fs.existingFiles.add("graph")
        fs.existingFiles.add("temp_graph")
        fs.renameFails = true // fails to move temp_graph -> graph

        val engine = TestEngine(fs)
        val res = engine.validateAndSwapGraph("path/to/temp_graph")

        if (res !is RoutingEngine.ImportResult.Error) {
            fail("Expected Error in CASE B, got $res")
        }
        if (!fs.existingFiles.contains("graph")) {
            fail("Expected graph to exist in CASE B, files: ${fs.existingFiles}")
        }
        if (fs.existingFiles.contains("graph_backup")) {
            fail("Expected graph_backup to NOT exist in CASE B, files: ${fs.existingFiles}")
        }
    }

    @Test
    fun `CASE C - replacement init failure restores backup`() = runTest {
        val fs = MockFilesystem()
        fs.existingFiles.add("graph")
        fs.existingFiles.add("temp_graph")

        val engine = TestEngine(fs)
        // fail on the second init (inside the lock)
        engine.initThrows = IllegalArgumentException("Bad graph")
        engine.throwOnInitCount = 2

        val res = engine.validateAndSwapGraph("path/to/temp_graph")

        assertTrue(res is RoutingEngine.ImportResult.Error)
        assertTrue(fs.existingFiles.contains("graph"))
        assertFalse(fs.existingFiles.contains("graph_backup"))
    }

    @Test
    fun `CASE D - CancellationException causes rollback then rethrows`() = runTest {
        val fs = MockFilesystem()
        fs.existingFiles.add("graph")
        fs.existingFiles.add("temp_graph")

        val engine = TestEngine(fs)
        engine.initThrows = CancellationException("Cancelled")
        engine.throwOnInitCount = 2

        try {
            engine.validateAndSwapGraph("path/to/temp_graph")
            fail("Should throw CancellationException")
        } catch (e: CancellationException) {
            // Expected
        }

        assertTrue(fs.existingFiles.contains("graph"))
        assertFalse(fs.existingFiles.contains("graph_backup"))
    }

    @Test
    fun `CASE E - rollback restore failure reports critical error`() = runTest {
        val fs = MockFilesystem()
        fs.existingFiles.add("graph")
        fs.existingFiles.add("temp_graph")

        val engine = TestEngine(fs)
        engine.initThrows = IllegalArgumentException("Bad graph")
        engine.throwOnInitCount = 2
        fs.restoreFails = true // fail to rename graph_backup -> graph

        val res = engine.validateAndSwapGraph("path/to/temp_graph")

        if (res !is RoutingEngine.ImportResult.Error) {
            fail("Expected Error, got $res")
        }
        val msg = (res as RoutingEngine.ImportResult.Error).message
        if (!msg.contains("CRITICAL")) {
            fail("Expected CRITICAL, got $msg")
        }

        if (!fs.existingFiles.contains("graph_backup")) {
            fail("Expected graph_backup to exist, files: ${fs.existingFiles}")
        }
    }

    @Test
    fun `CASE F - successful swap removes backup and temp`() = runTest {
        val fs = MockFilesystem()
        fs.existingFiles.add("graph")
        fs.existingFiles.add("temp_graph")

        val engine = TestEngine(fs)
        val res = engine.validateAndSwapGraph("path/to/temp_graph")

        assertTrue(res is RoutingEngine.ImportResult.Success)
        assertTrue(fs.existingFiles.contains("graph"))
        assertFalse(fs.existingFiles.contains("graph_backup"))
        assertFalse(fs.existingFiles.contains("temp_graph"))
    }
}
