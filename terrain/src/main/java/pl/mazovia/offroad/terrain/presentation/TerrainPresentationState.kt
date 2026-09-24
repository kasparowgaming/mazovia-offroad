package pl.mazovia.offroad.terrain.presentation

import pl.mazovia.offroad.terrain.profile.GradeEvent
import pl.mazovia.offroad.terrain.profile.GradeEventType
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.projection.ProjectionMode
import pl.mazovia.offroad.terrain.projection.ProjectionResult

/**
 * Immutable, renderer-independent presentation state (DESIGN §4.3). Built from derived data only.
 *
 * @property windowStartM / windowEndM profile window around the rider on the haversine axis (TARGET −50 / +600 m)
 * @property currentGrade grade at the rider position, or null when unavailable (never "flat" by default)
 */
data class TerrainPresentationState(
    val projection: ProjectionResult,
    val windowStartM: Double?,
    val windowEndM: Double?,
    val currentGrade: Double?,
    val nextClimb: GradeEvent?,
    val nextDescent: GradeEvent?
) {
    companion object {
        const val BEHIND_M = 50.0
        const val AHEAD_M = 600.0

        fun build(
            projection: ProjectionResult,
            grade: GradeProfile?,
            events: List<GradeEvent>,
            behindM: Double = BEHIND_M,
            aheadM: Double = AHEAD_M
        ): TerrainPresentationState {
            val s = projection.distanceAlongM
            if (s == null || projection.mode == ProjectionMode.NO_ROUTE) {
                return TerrainPresentationState(projection, null, null, null, null, null)
            }
            val windowEnd = s + aheadM
            val ahead = events.filter { it.endDistanceM >= s && it.startDistanceM <= windowEnd }
            return TerrainPresentationState(
                projection = projection,
                windowStartM = s - behindM,
                windowEndM = windowEnd,
                currentGrade = grade?.takeIf { it.size > 0 }?.let { it.gradeAt(it.indexNearest(s)) },
                nextClimb = ahead.firstOrNull { it.type == GradeEventType.CLIMB },
                nextDescent = ahead.firstOrNull { it.type == GradeEventType.DESCENT }
            )
        }
    }
}
