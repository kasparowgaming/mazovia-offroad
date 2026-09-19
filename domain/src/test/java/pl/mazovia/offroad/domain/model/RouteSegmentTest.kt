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
}
