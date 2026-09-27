package pl.mazovia.offroad.ui.map.components

import org.junit.Assert.*
import org.junit.Test

class MapCameraPaddingTest {
    /** MapLibre places the camera target at the centre of the padded area. */
    private fun focal(viewHeight: Int, p: MapPadding) = p.top + (viewHeight - p.top - p.bottom) / 2.0

    @Test
    fun planningPaddingIsUnchangedScreenFraction() {
        for (view in listOf(0, 780, 1884, 2340)) {
            assertEquals(MapPadding(0, 0, 0, 936), cameraPadding(2340, view, null, 90))
        }
        assertEquals(MapPadding(0, 0, 0, (1600 * 0.4).toInt()), cameraPadding(1600, 1500, null, 90))
    }

    @Test
    fun ridingFocalIsBelowTopOverlayInsideActualView() {
        val p = cameraPadding(screenHeightPx = 2340, viewHeightPx = 1100, safeTopInsetPx = 190, clearancePx = 90)
        val y = focal(1100, p)
        assertEquals(190 + 0.6 * (1100 - 190), y, 1.0)
        assertTrue(y >= 190 + 90)
        assertTrue(y <= 1100 - 90)
        assertEquals(0, p.left); assertEquals(0, p.right)
    }

    @Test
    fun legacyScreenPaddingPutsRiderUnderOverlayButRidingPaddingDoesNot() {
        // The pre-fix formula: 40 % of the screen as bottom padding in a map view much shorter than the screen.
        val legacy = cameraPadding(2340, 1100, null, 90)
        assertTrue(focal(1100, legacy) < 190)
        assertTrue(focal(1100, cameraPadding(2340, 1100, 190, 90)) >= 190 + 90)
    }

    @Test
    fun ridingPaddingDependsOnViewNotScreen() {
        for (screen in listOf(800, 1080, 2340, 3120)) {
            assertEquals(cameraPadding(2340, 1400, 180, 80), cameraPadding(screen, 1400, 180, 80))
        }
    }

    @Test
    fun ridingPaddingIsValidForAnyGeometry() {
        for (view in 1..3000 step 37) for (inset in 0..500 step 23) for (clearance in listOf(0, 40, 96)) {
            val p = cameraPadding(2340, view, inset, clearance)
            val msg = "view=$view inset=$inset clearance=$clearance -> $p"
            assertTrue(msg, p.left == 0 && p.right == 0 && p.top >= 0 && p.bottom >= 0)
            assertTrue(msg, p.top == 0 || p.bottom == 0)
            assertTrue(msg, p.top + p.bottom < view)
            val y = focal(view, p)
            assertTrue(msg, y >= 0 && y < view)
            if (view > inset + 2 * clearance) {
                assertTrue(msg, y >= inset + clearance - 0.5)
                assertTrue(msg, y <= view - clearance + 0.5)
            }
        }
    }

    @Test
    fun overlayTallerThanViewStillYieldsUsableViewport() {
        val p = cameraPadding(2340, 300, 400, 90)
        assertTrue(p.top + p.bottom < 300)
        assertTrue(focal(300, p) < 300)
    }

    @Test
    fun unmeasuredViewHasNoPadding() {
        assertEquals(MapPadding(0, 0, 0, 0), cameraPadding(2340, 0, 190, 90))
        assertEquals(MapPadding(0, 0, 0, 0), cameraPadding(2340, 1, 190, 90))
        assertEquals(MapPadding(0, 0, 0, 0), cameraPadding(2340, -5, 190, 90))
    }

    @Test
    fun unmeasuredOverlayStillPlacesFocalInLowerPart() {
        val y = focal(1100, cameraPadding(2340, 1100, 0, 90))
        assertEquals(0.6 * 1100, y, 1.0)
    }
}
