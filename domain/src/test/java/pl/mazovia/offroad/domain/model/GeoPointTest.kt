package pl.mazovia.offroad.domain.model

import org.junit.Assert.*
import org.junit.Test

class GeoPointTest {
    @Test
    fun `distanceTo returns approximate haversine distance`() {
        val warsaw = GeoPoint.WARSAW
        val krakow = GeoPoint(50.0647, 19.9450)
        val distance = warsaw.distanceTo(krakow)
        // Warsaw to Krakow is approximately 252 km
        assertTrue("Distance should be ~252km, was ${distance/1000}km", distance in 240_000.0..265_000.0)
    }

    @Test
    fun `distanceTo self is zero`() {
        val point = GeoPoint(52.0, 21.0)
        assertEquals(0.0, point.distanceTo(point), 0.001)
    }

    @Test
    fun `bearingTo returns correct cardinal directions`() {
        val origin = GeoPoint(52.0, 21.0)
        val north = GeoPoint(53.0, 21.0)
        val east = GeoPoint(52.0, 22.0)
        
        val bearingN = origin.bearingTo(north)
        assertTrue("North bearing should be ~0, was $bearingN", bearingN < 5 || bearingN > 355)
        
        val bearingE = origin.bearingTo(east)
        assertTrue("East bearing should be ~90, was $bearingE", bearingE in 85.0..95.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid latitude throws`() {
        GeoPoint(91.0, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid longitude throws`() {
        GeoPoint(0.0, 181.0)
    }
}
