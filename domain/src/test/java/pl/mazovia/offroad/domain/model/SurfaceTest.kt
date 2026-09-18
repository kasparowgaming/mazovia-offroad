package pl.mazovia.offroad.domain.model

import org.junit.Assert.*
import org.junit.Test

class SurfaceTest {
    @Test
    fun `fromOsmTag maps known surfaces correctly`() {
        assertEquals(Surface.ASPHALT, Surface.fromOsmTag("asphalt"))
        assertEquals(Surface.GRAVEL, Surface.fromOsmTag("gravel"))
        assertEquals(Surface.DIRT, Surface.fromOsmTag("dirt"))
        assertEquals(Surface.SAND, Surface.fromOsmTag("sand"))
    }

    @Test
    fun `fromOsmTag is case insensitive`() {
        assertEquals(Surface.ASPHALT, Surface.fromOsmTag("Asphalt"))
        assertEquals(Surface.GRAVEL, Surface.fromOsmTag("GRAVEL"))
    }

    @Test
    fun `unknown tag returns UNKNOWN`() {
        assertEquals(Surface.UNKNOWN, Surface.fromOsmTag("some_random_value"))
        assertEquals(Surface.UNKNOWN, Surface.fromOsmTag(null))
    }

    @Test
    fun `off-road classification is correct`() {
        assertTrue(Surface.DIRT.isOffRoad)
        assertTrue(Surface.GRAVEL.isOffRoad)
        assertTrue(Surface.SAND.isOffRoad)
        assertFalse(Surface.ASPHALT.isOffRoad)
        assertFalse(Surface.CONCRETE.isOffRoad)
    }
}
