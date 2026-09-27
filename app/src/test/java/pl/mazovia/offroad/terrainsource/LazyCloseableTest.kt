package pl.mazovia.offroad.terrainsource

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class LazyCloseableTest {
    private class Resource : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun opensOnceAndClosesWhenIdle() = runTest {
        var opens = 0
        val resource = Resource()
        val lazy = LazyCloseable { opens++; resource }
        assertEquals(1, lazy.withResource { 1 })
        assertEquals(2, lazy.withResource { 2 })
        assertEquals(1, opens)
        assertFalse(resource.closed)
        lazy.close()
        assertTrue(resource.closed)
    }

    @Test fun closeDuringUseWaitsForTheUseToFinish() = runTest {
        val resource = Resource()
        val lazy = LazyCloseable { resource }
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val use = async { lazy.withResource { inside.complete(Unit); release.await(); it.closed } }
        inside.await()
        lazy.close()
        assertFalse(resource.closed) // not closed under the running build
        release.complete(Unit)
        assertEquals(false, use.await())
        assertTrue(resource.closed) // closed by the use on exit
    }

    @Test fun closeDuringOpenClosesTheResourceAndSkipsTheBlock() = runTest {
        val resource = Resource()
        lateinit var lazy: LazyCloseable<Resource>
        lazy = LazyCloseable { lazy.close(); resource } // close() arrives while the archive is being opened
        var ran = false
        assertNull(lazy.withResource { ran = true })
        assertFalse(ran)
        assertTrue(resource.closed) // no leaked archive
    }

    @Test fun useAfterCloseNeverOpens() = runTest {
        var opens = 0
        val lazy = LazyCloseable { opens++; Resource() }
        lazy.close()
        assertNull(lazy.withResource { 1 })
        assertEquals(0, opens)
    }

    @Test fun absentResourceGivesNull() = runTest {
        val lazy = LazyCloseable<Resource> { null }
        assertNull(lazy.withResource { 1 })
        lazy.close()
    }
}
