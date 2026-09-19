package pl.mazovia.offroad.routing.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.RoutingProfile

class RouteTournamentCorridorsTest {

    @Test
    fun `BEZPIECZNY profile returns empty corridors`() {
        val corridors = RouteTournament.profileDiversityCorridors(
            from = GeoPoint(52.2297, 21.0122), // Warsaw
            to = GeoPoint(52.4064, 16.9252),   // Poznan
            profile = RoutingProfile.BEZPIECZNY
        )
        assertTrue(corridors.isEmpty())
    }

    @Test
    fun `Short routes under 2km return empty corridors`() {
        // Distance is roughly ~1km
        val corridors = RouteTournament.profileDiversityCorridors(
            from = GeoPoint(52.2297, 21.0122),
            to = GeoPoint(52.2380, 21.0122),
            profile = RoutingProfile.TERENOWY
        )
        assertTrue(corridors.isEmpty())
    }

    @Test
    fun `TERENOWY profile generates correctly bounded corridors`() {
        // Warsaw to Poznan
        val corridors = RouteTournament.profileDiversityCorridors(
            from = GeoPoint(52.2297, 21.0122),
            to = GeoPoint(52.4064, 16.9252),
            profile = RoutingProfile.TERENOWY
        )
        
        // TERENOWY has 2 offsets * 2 sides * 2 bends = 8 combinations
        // If deduplication works properly, it might be 8 or fewer
        assertTrue(corridors.isNotEmpty())
        assertTrue(corridors.size <= 8)
        
        // Verify all corridors have exactly 3 waypoints
        corridors.forEach { corridor ->
            assertEquals(3, corridor.size)
        }
    }

    @Test
    fun `ODKRYWCZY profile generates correctly bounded corridors`() {
        // Warsaw to Poznan
        val corridors = RouteTournament.profileDiversityCorridors(
            from = GeoPoint(52.2297, 21.0122),
            to = GeoPoint(52.4064, 16.9252),
            profile = RoutingProfile.ODKRYWCZY
        )
        
        // ODKRYWCZY has 3 offsets * 2 sides * 2 bends = 12 combinations
        assertTrue(corridors.isNotEmpty())
        assertTrue(corridors.size <= 12)
    }

    @Test
    fun `Duplicate corridors are filtered out`() {
        // Generate a route that might result in lateral clamping
        // If distance is e.g. 5000m, lateral for TERENOWY is clamped to min 650m
        // 5000 * 0.08 = 400 (clamped to 650)
        // 5000 * 0.16 = 800 (not clamped)
        // Let's pick an even shorter distance so both offsets get clamped to 650m!
        // distance ~ 2500m
        val corridors = RouteTournament.profileDiversityCorridors(
            from = GeoPoint(52.2297, 21.0122),
            to = GeoPoint(52.2520, 21.0122), // ~2.4km
            profile = RoutingProfile.TERENOWY
        )
        
        // normally 8 combinations, but both offsets clamp to the SAME lateral distance of 650m.
        // So offset=0.08 and offset=0.16 will produce IDENTICAL geometry!
        // We expect exactly 4 unique corridors (2 sides * 2 bends).
        assertEquals(4, corridors.size)
    }
}
