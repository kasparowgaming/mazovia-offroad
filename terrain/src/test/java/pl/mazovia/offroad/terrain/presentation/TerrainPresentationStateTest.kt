package pl.mazovia.offroad.terrain.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.mazovia.offroad.terrain.elevation.SyntheticProfile
import pl.mazovia.offroad.terrain.profile.GradeEventType
import pl.mazovia.offroad.terrain.profile.ProfileFixtures
import pl.mazovia.offroad.terrain.projection.ProjectionMode
import pl.mazovia.offroad.terrain.projection.ProjectionResult

class TerrainPresentationStateTest {

    private fun projectionAt(s: Double) =
        ProjectionResult("r", ProjectionMode.ATTACHED, s, 0, 0.0, null, 90.0, 0.0, 1f)

    @Test
    fun `window and next events ahead of the rider`() {
        val p = ProfileFixtures.pipeline(ProfileFixtures.raw(
            SyntheticProfile.Builder().ramp(300.0, 100.0, 0.08).ramp(600.0, 100.0, -0.08).build(), 1500.0
        ))
        val state = TerrainPresentationState.build(projectionAt(100.0), p.grade, p.events)
        assertEquals(50.0, state.windowStartM!!, 0.0)
        assertEquals(700.0, state.windowEndM!!, 0.0)
        assertEquals(0.0, state.currentGrade!!, 1e-9)
        assertEquals(GradeEventType.CLIMB, state.nextClimb!!.type)
        assertEquals(GradeEventType.DESCENT, state.nextDescent!!.type)
        val later = TerrainPresentationState.build(projectionAt(900.0), p.grade, p.events)
        assertNull(later.nextClimb)
        assertNull(later.nextDescent)
    }

    @Test
    fun `no route gives an empty state`() {
        val s = TerrainPresentationState.build(ProjectionResult.NO_ROUTE, null, emptyList())
        assertNull(s.windowStartM)
        assertNull(s.currentGrade)
    }
}
