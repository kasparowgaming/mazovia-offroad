package pl.mazovia.offroad.routing.benchmark

import pl.mazovia.offroad.domain.model.ManeuverType
import pl.mazovia.offroad.domain.model.Route
import kotlin.math.roundToInt

internal data class RouteDiagnostics(
    val detourPercent: Int,
    val uTurnCount: Int,
    val shortManeuverLegCount: Int
)

/** 
 * Ported from legacy RouteDiagnostics.kt.
 * Measurable comparison signals only; this deliberately does not recommend a profile.
 * 
 * Note: Placed in the benchmark test module to avoid polluting production UI logic 
 * as requested in TASK-20260919-006-BENCH constraints.
 */
internal fun routeDiagnostics(route: Route, shortestDistanceMeters: Double): RouteDiagnostics {
    val detour = if (shortestDistanceMeters > 0.0) {
        (((route.totalDistanceMeters / shortestDistanceMeters) - 1.0) * 100.0)
            .roundToInt().coerceAtLeast(0)
    } else 0
    
    val uTurns = route.maneuvers.count { it.type == ManeuverType.U_TURN }
    
    // Legacy: to - from < 60.0. In the modern model, maneuver.distanceMeters holds precisely the leg distance.
    val shortLegs = route.maneuvers.count { 
        it.type != ManeuverType.ARRIVE && it.distanceMeters < SHORT_MANEUVER_LEG_METERS 
    }
    
    return RouteDiagnostics(detour, uTurns, shortLegs)
}

private const val SHORT_MANEUVER_LEG_METERS = 60.0
