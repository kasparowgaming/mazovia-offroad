package pl.mazovia.offroad.routing.profile

import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.domain.model.*

class OffroadWeightingHelperTest {

    @Test
    fun `track with dirt surface gets lower weight than asphalt road in TERENOWY profile`() {
        val config = OffroadProfileConfig.forProfile(RoutingProfile.TERENOWY)

        val trackWeight = OffroadWeightingHelper.calculateWeight(
            config = config,
            surface = Surface.DIRT,
            highway = HighwayType.TRACK
        )
        val asphaltWeight = OffroadWeightingHelper.calculateWeight(
            config = config,
            surface = Surface.ASPHALT,
            highway = HighwayType.SECONDARY
        )

        assertTrue("Track should be preferred over asphalt, track=$trackWeight, asphalt=$asphaltWeight",
            trackWeight < asphaltWeight)
    }

    @Test
    fun `BEZPIECZNY profile penalizes unknown surfaces more than TERENOWY`() {
        val safeConfig = OffroadProfileConfig.forProfile(RoutingProfile.BEZPIECZNY)
        val offRoadConfig = OffroadProfileConfig.forProfile(RoutingProfile.TERENOWY)

        val safeWeight = OffroadWeightingHelper.calculateWeight(
            config = safeConfig,
            surface = Surface.UNKNOWN,
            highway = HighwayType.TRACK
        )
        val offRoadWeight = OffroadWeightingHelper.calculateWeight(
            config = offRoadConfig,
            surface = Surface.UNKNOWN,
            highway = HighwayType.TRACK
        )

        assertTrue("BEZPIECZNY should penalize unknown more, safe=$safeWeight, offroad=$offRoadWeight",
            safeWeight > offRoadWeight)
    }

    @Test
    fun `restricted access returns infinite weight`() {
        val config = OffroadProfileConfig.forProfile(RoutingProfile.TERENOWY)

        val weight = OffroadWeightingHelper.calculateWeight(
            config = config,
            surface = Surface.DIRT,
            highway = HighwayType.TRACK,
            access = AccessRestriction.NO
        )

        assertEquals(Double.MAX_VALUE, weight, 0.0)
    }

    @Test
    fun `motorway always gets infinite weight`() {
        val config = OffroadProfileConfig.forProfile(RoutingProfile.TERENOWY)

        val weight = OffroadWeightingHelper.calculateWeight(
            config = config,
            surface = Surface.ASPHALT,
            highway = HighwayType.MOTORWAY
        )

        assertEquals(Double.MAX_VALUE, weight, 0.0)
    }

    @Test
    fun `ODKRYWCZY gives bonus for unridden roads`() {
        val config = OffroadProfileConfig.forProfile(RoutingProfile.ODKRYWCZY)

        val unriddenWeight = OffroadWeightingHelper.calculateWeight(
            config = config,
            surface = Surface.GRAVEL,
            highway = HighwayType.TRACK,
            isRidden = false
        )
        val riddenWeight = OffroadWeightingHelper.calculateWeight(
            config = config,
            surface = Surface.GRAVEL,
            highway = HighwayType.TRACK,
            isRidden = true
        )

        assertTrue("Unridden should be preferred, unridden=$unriddenWeight, ridden=$riddenWeight",
            unriddenWeight < riddenWeight)
    }

    @Test
    fun `motorcycle access check blocks footways`() {
        assertFalse(OffroadWeightingHelper.isMotorcycleAccessible(
            AccessRestriction.YES, HighwayType.FOOTWAY))
    }

    @Test
    fun `motorcycle access allows tracks with permissive access`() {
        assertTrue(OffroadWeightingHelper.isMotorcycleAccessible(
            AccessRestriction.PERMISSIVE, HighwayType.TRACK))
    }
}
