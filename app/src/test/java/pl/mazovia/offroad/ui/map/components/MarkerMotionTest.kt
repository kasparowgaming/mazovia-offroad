package pl.mazovia.offroad.ui.map.components

import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.domain.model.GeoPoint

class MarkerMotionTest {
    private val a = GeoPoint(52.20000, 21.00000)
    private val b = GeoPoint(52.20010, 21.00000) // ~11 m north of a
    private val c = GeoPoint(52.20020, 21.00000)

    @Test
    fun firstFixIsShownImmediately() {
        val m = MarkerMotion()
        assertTrue(m.update(a, 0))
        assertEquals(a, m.positionAt(0))
        assertFalse(m.isGliding(0))
    }

    @Test
    fun glidesLinearlyToNextFixInsteadOfJumping() {
        val m = MarkerMotion()
        m.update(a, 0)
        m.update(b, 1_000)
        assertEquals(1_000L, m.durationMs)
        assertEquals(a, m.positionAt(1_000))
        val mid = m.positionAt(1_500)!!
        assertEquals((a.latitude + b.latitude) / 2, mid.latitude, 1e-9)
        assertTrue(m.isGliding(1_500))
        assertEquals(b, m.positionAt(2_000))
        assertFalse(m.isGliding(2_000))
    }

    @Test
    fun glideLengthFollowsGapBetweenFixesWithinBounds() {
        val m = MarkerMotion()
        m.update(a, 0)
        m.update(b, 2_000) // walking: a 3 m gate spaces fixes ~2 s apart
        assertEquals(2_000L, m.durationMs)
        m.update(c, 2_100)
        assertEquals(MARKER_MIN_GLIDE_MS, m.durationMs)
    }

    @Test
    fun longPauseUsesDefaultGlide() {
        val m = MarkerMotion()
        m.update(a, 0)
        m.update(b, 60_000)
        assertEquals(MARKER_DEFAULT_GLIDE_MS, m.durationMs)
    }

    @Test
    fun newFixMidGlideContinuesFromDrawnPosition() {
        val m = MarkerMotion()
        m.update(a, 0)
        m.update(b, 1_000)
        val drawn = m.positionAt(1_500)!!
        m.update(c, 1_500)
        assertEquals(drawn.latitude, m.positionAt(1_500)!!.latitude, 1e-12)
        assertEquals(c, m.positionAt(1_500 + m.durationMs))
    }

    @Test
    fun repeatedSameFixIsNoOpSoCameraAndMarkerShareOneGlide() {
        val m = MarkerMotion()
        m.update(a, 0)
        m.update(b, 1_000)
        assertFalse(m.update(b, 1_300))
        assertEquals(700L, m.remainingMs(1_300))
    }

    @Test
    fun farJumpSnaps() {
        val m = MarkerMotion()
        m.update(a, 0)
        val far = GeoPoint(52.21, 21.0)
        m.update(far, 1_000)
        assertEquals(far, m.positionAt(1_000))
        assertEquals(0L, m.remainingMs(1_000))
    }

    @Test
    fun lostPositionClearsMarker() {
        val m = MarkerMotion()
        m.update(a, 0)
        m.update(b, 1_000)
        assertTrue(m.update(null, 1_200))
        assertNull(m.positionAt(1_200))
        assertFalse(m.isGliding(1_200))
        m.update(c, 5_000)
        assertEquals(c, m.positionAt(5_000))
    }
}
