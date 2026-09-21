package pl.mazovia.offroad.routing.engine

import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.domain.model.*

class MarginalTerrainEfficiencyTest {
    private fun route(id: String, distance: Double, terrain: Double) = Route(
        id = id, origin = GeoPoint(0.0, 0.0), destination = GeoPoint(0.0, 0.0),
        segments = emptyList(), profile = RoutingProfile.TERENOWY,
        metrics = RouteMetrics.EMPTY.copy(totalDistanceMeters = distance,
            offRoadDistanceMeters = terrain, asphaltDistanceMeters = distance - terrain,
            longestContinuousTerrainMeters = terrain, terrainRunCount = 1)
    )

    private fun checkEfficiency(efficiency: Double, allowed: Boolean) {
        for (profile in listOf(RoutingProfile.TERENOWY, RoutingProfile.ODKRYWCZY)) {
            val short = route("short", 10000.0, 5000.0)
            val long = route("long", 12000.0, 5000.0 + 2000.0 * efficiency)
            val observations = mutableListOf<RouteTournament.CandidateEvaluation>()
            val candidates = listOf(long, short)
            val winner = RouteTournament.chooseTournamentWinner(candidates, 10000.0, profile, observations)
            val evaluated = observations.first()
            assertEquals(efficiency, evaluated.marginalTerrainEfficiency!!, 1e-12)
            assertEquals(0.70, evaluated.marginalTerrainEfficiencyLimit!!, 0.0)
            assertEquals(true, evaluated.acceptedByDetourGuard)
            if (allowed) {
                assertNull(evaluated.rejectionReason)
                assertNotNull(evaluated.score)
                assertSame(long, winner)
            } else {
                assertEquals("MARGINAL_TERRAIN_EFFICIENCY", evaluated.rejectionReason)
                assertNull(evaluated.score)
                assertSame(short, winner)
            }
            assertNull(observations.last().marginalTerrainEfficiency)
            assertNull(observations.last().rejectionReason)
            assertNotNull(observations.last().score)
            assertSame(winner, RouteTournament.chooseTournamentWinner(candidates, 10000.0, profile))
        }
    }

    @Test fun `3963 basis points rejected`() = checkEfficiency(0.3963, false)
    @Test fun `6625 basis points rejected`() = checkEfficiency(0.6625, false)
    @Test fun `9948 basis points allowed`() = checkEfficiency(0.9948, true)
    @Test fun `11999 basis points allowed without clamping`() = checkEfficiency(1.1999, true)
    @Test fun `exactly seventy percent allowed`() = checkEfficiency(0.70, true)
    @Test fun `slightly below seventy percent rejected`() = checkEfficiency(0.699999, false)
    @Test fun `negative terrain gain rejected`() = checkEfficiency(-0.1, false)

    @Test fun `equal and nearly equal distances skip division but meaningful increase does not`() {
        val short = route("short", 10000.0, 5000.0)
        for (extra in listOf(0.0, 0.000001, 0.05, 0.099)) {
            val equal = route("equal", 10000.0 + extra, 4000.0)
            val observations = mutableListOf<RouteTournament.CandidateEvaluation>()
            RouteTournament.chooseTournamentWinner(listOf(short, equal), 10000.0, RoutingProfile.TERENOWY, observations)
            assertTrue(observations.all { it.rejectionReason == null && it.marginalTerrainEfficiency == null && it.score != null })
        }
        val observations = mutableListOf<RouteTournament.CandidateEvaluation>()
        RouteTournament.chooseTournamentWinner(listOf(short, route("longer", 10000.101, 4000.0)),
            10000.0, RoutingProfile.TERENOWY, observations)
        assertEquals("MARGINAL_TERRAIN_EFFICIENCY", observations.last().rejectionReason)
    }

    @Test fun `shortest corridor instead of baseline controls reference and survives`() {
        val short = route("corridor-4", 10000.0, 7000.0)
        val baseline = route("baseline", 12000.0, 7500.0)
        val long = route("long", 14000.0, 9500.0)
        val observations = mutableListOf<RouteTournament.CandidateEvaluation>()
        assertSame(short, RouteTournament.chooseTournamentWinner(listOf(long, short, baseline),
            12000.0, RoutingProfile.ODKRYWCZY, observations))
        assertTrue(observations.all { it.shortestReferenceDistance == 10000.0 && it.shortestReferenceTerrainDistance == 7000.0 })
        assertEquals(0.625, observations.first().marginalTerrainEfficiency!!, 0.0)
        assertNull(observations[1].rejectionReason)
    }

    @Test fun `passing efficiency cannot bypass detour gate`() {
        val short = route("short", 10000.0, 5000.0)
        for ((profile, distance) in listOf(RoutingProfile.TERENOWY to 16001.0, RoutingProfile.ODKRYWCZY to 20001.0)) {
            val long = route("long", distance, distance - 5000.0)
            val observations = mutableListOf<RouteTournament.CandidateEvaluation>()
            assertSame(short, RouteTournament.chooseTournamentWinner(listOf(long, short), 10000.0, profile, observations))
            assertEquals(1.0, observations.first().marginalTerrainEfficiency!!, 0.0)
            assertEquals("DETOUR_LIMIT", observations.first().rejectionReason)
            assertNull(observations.first().score)
        }
    }

    private fun regression(profile: RoutingProfile, shortDistance: Double, shortTerrain: Double,
                           longDistance: Double, longTerrain: Double, baseline: Double, preserved: Boolean) {
        val short = route("short", shortDistance, shortTerrain)
        val long = route("old-winner", longDistance, longTerrain)
        assertTrue(RouteTournament.tournamentTerrainValue(long, profile) > RouteTournament.tournamentTerrainValue(short, profile))
        assertSame(if (preserved) long else short,
            RouteTournament.chooseTournamentWinner(listOf(long, short), baseline, profile))
    }

    @Test fun `Biardy like terrain rejects old long winner`() = regression(
        RoutingProfile.TERENOWY, 25331.839894, 17893.667473, 32581.367968, 20766.855380, 25331.839894, false)
    @Test fun `Biardy like explorer selects shorter corridor`() = regression(
        RoutingProfile.ODKRYWCZY, 29571.639495, 22703.878153, 50527.204305, 36587.707497, 36048.100118, false)
    @Test fun `Holubla like terrain preserves useful winner`() = regression(
        RoutingProfile.TERENOWY, 19428.022874, 9456.903972, 34388.086769, 24338.722532, 23154.235233, true)
    @Test fun `Holubla like explorer preserves useful winner`() = regression(
        RoutingProfile.ODKRYWCZY, 21662.601500, 11751.275250, 33573.309244, 26043.155823, 24006.971725, true)
}
