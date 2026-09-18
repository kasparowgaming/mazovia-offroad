package pl.mazovia.offroad.domain.model

import org.junit.Assert.*
import org.junit.Test

class AccessRestrictionTest {
    @Test
    fun `motorcycle tag takes priority over motor_vehicle`() {
        val result = AccessRestriction.fromOsmTags(
            access = "yes",
            motorVehicle = "yes",
            motorcycle = "no"
        )
        assertEquals(AccessRestriction.NO, result)
    }

    @Test
    fun `motor_vehicle takes priority over access`() {
        val result = AccessRestriction.fromOsmTags(
            access = "yes",
            motorVehicle = "no"
        )
        assertEquals(AccessRestriction.NO, result)
    }

    @Test
    fun `agricultural is restricted`() {
        val result = AccessRestriction.fromOsmTags(access = "agricultural")
        assertEquals(AccessRestriction.RESTRICTED, result)
        assertFalse(result.isAccessible)
    }

    @Test
    fun `no tags returns UNKNOWN which is accessible`() {
        val result = AccessRestriction.fromOsmTags()
        assertEquals(AccessRestriction.UNKNOWN, result)
        assertTrue(result.isAccessible)
    }
}
