package pl.mazovia.offroad.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSegmentTest {
    
    private val dummyPoints = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0))
    
    @Test
    fun `UNKNOWN surface with TRACK highway is off-road`() {
        val segment = RouteSegment(
            points = dummyPoints,
            distanceMeters = 100.0,
            surface = Surface.UNKNOWN,
            highway = HighwayType.TRACK
        )
        assertTrue(segment.isOffRoad)
        assertFalse(segment.isAsphalt)
    }

    @Test
    fun `UNKNOWN surface with PATH highway is off-road`() {
        val segment = RouteSegment(
            points = dummyPoints,
            distanceMeters = 100.0,
            surface = Surface.UNKNOWN,
            highway = HighwayType.PATH
        )
        assertTrue(segment.isOffRoad)
        assertFalse(segment.isAsphalt)
    }

    @Test
    fun `UNKNOWN surface with PRIMARY highway is NOT off-road`() {
        val segment = RouteSegment(
            points = dummyPoints,
            distanceMeters = 100.0,
            surface = Surface.UNKNOWN,
            highway = HighwayType.PRIMARY
        )
        assertFalse(segment.isOffRoad)
        assertTrue(segment.isAsphalt)
    }

    @Test
    fun `UNPAVED DIRT GRAVEL segment counts as terrain regardless of highway`() {
        listOf(Surface.UNPAVED, Surface.DIRT, Surface.GRAVEL).forEach { surface ->
            val segment = RouteSegment(
                points = dummyPoints,
                distanceMeters = 100.0,
                surface = surface,
                highway = HighwayType.PRIMARY // Even if highway is PRIMARY, surface overrides
            )
            assertTrue("Expected $surface to be off-road", segment.isOffRoad)
            assertFalse("Expected $surface not to be asphalt", segment.isAsphalt)
        }
    }
}
