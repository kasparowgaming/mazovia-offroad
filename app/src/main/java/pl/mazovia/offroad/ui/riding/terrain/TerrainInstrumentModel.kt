package pl.mazovia.offroad.ui.riding.terrain

import pl.mazovia.offroad.terrain.presentation.CorridorGeometry
import pl.mazovia.offroad.terrain.profile.DisplayElevationProfile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class RidingViewMode { MAPA, TEREN }

/**
 * TEREN view model. [corridor] is the Option D window geometry for the same route and profile (DESIGN §3.7); it is
 * null when no route geometry is available, and the corridor view then shows the state label alone.
 */
sealed interface TerrainInstrumentModel {
    object NoRoute : TerrainInstrumentModel
    object Arrived : TerrainInstrumentModel
    data class Detached(
        val distanceToRouteM: Double?, val riderM: Double? = null, val corridor: CorridorGeometry? = null
    ) : TerrainInstrumentModel
    data class NoData(
        val stale: Boolean, val showStaleIndicator: Boolean,
        val riderM: Double? = null, val corridor: CorridorGeometry? = null
    ) : TerrainInstrumentModel
    data class Valid(
        val riderM: Double, val gradeLabel: String, val nextEventLabel: String?,
        val stale: Boolean, val showStaleIndicator: Boolean,
        val corridor: CorridorGeometry? = null
    ) : TerrainInstrumentModel
    /** Route changed (DESIGN §16.1, C3): the last corridor view of the previous route, frozen and greyed while the new
     *  route's profile loads. */
    data class Reloading(val riderM: Double, val corridor: CorridorGeometry) : TerrainInstrumentModel
}

/** Corridor the model draws, if any. */
val TerrainInstrumentModel.shownCorridor: CorridorGeometry?
    get() = when (this) {
        is TerrainInstrumentModel.Valid -> corridor
        is TerrainInstrumentModel.Detached -> corridor
        is TerrainInstrumentModel.NoData -> corridor
        is TerrainInstrumentModel.Reloading -> corridor
        TerrainInstrumentModel.NoRoute, TerrainInstrumentModel.Arrived -> null
    }

/** Rider position on [shownCorridor]'s axis, if any. */
val TerrainInstrumentModel.shownRiderM: Double?
    get() = when (this) {
        is TerrainInstrumentModel.Valid -> riderM
        is TerrainInstrumentModel.Detached -> riderM
        is TerrainInstrumentModel.NoData -> riderM
        is TerrainInstrumentModel.Reloading -> riderM
        TerrainInstrumentModel.NoRoute, TerrainInstrumentModel.Arrived -> null
    }

/**
 * DESIGN §16.1 route changed (C3): while [profileLoading] (the profile of a new route is being built) and [last] showed a
 * corridor of another route, the view stays on that corridor, frozen and greyed ([TerrainInstrumentModel.Reloading]),
 * instead of an empty or flat new corridor. Ends with the first model of the new route after loading, or at once on
 * NO_ROUTE / ARRIVED. Presentation only: navigation state is not touched.
 */
fun reloadingModel(
    last: TerrainInstrumentModel,
    next: TerrainInstrumentModel,
    profileLoading: Boolean
): TerrainInstrumentModel {
    if (!profileLoading || next === TerrainInstrumentModel.NoRoute || next === TerrainInstrumentModel.Arrived) return next
    if (last is TerrainInstrumentModel.Reloading) {
        return if (next.shownCorridor?.routeId == last.corridor.routeId) next else last
    }
    val corridor = last.shownCorridor ?: return next
    val rider = last.shownRiderM ?: return next
    if (next.shownCorridor?.routeId == corridor.routeId) return next
    return TerrainInstrumentModel.Reloading(rider, corridor)
}

fun terrainInstrumentModel(frame: TerrainDisplayState, corridor: CorridorGeometry? = null): TerrainInstrumentModel {
    if (frame.mode == TerrainVisualMode.IDLE) return TerrainInstrumentModel.NoRoute
    if (frame.mode == TerrainVisualMode.ARRIVED) return TerrainInstrumentModel.Arrived
    val profile = frame.profile
    val rider = frame.distanceAlongM
    // A corridor is shown only for the rider's own position and for the profile this frame carries.
    val shown = corridor?.takeIf { rider != null && it.display === profile }
    if (frame.mode == TerrainVisualMode.OFF_ROUTE) {
        return TerrainInstrumentModel.Detached(frame.distanceToRouteM, rider, shown)
    }
    // Terrain is shown when the corridor window around the rider holds any elevation (DESIGN §3.7).
    if (rider == null || profile == null ||
        !hasElevation(profile, rider - CorridorGeometry.BEHIND_M, rider + CorridorGeometry.AHEAD_M)) {
        return TerrainInstrumentModel.NoData(frame.positionStale, frame.showStaleIndicator, rider, shown)
    }
    val grade = frame.target?.currentGrade?.takeIf { it.isFinite() }?.let { "${percent(it)} %" } ?: "—"
    val next = listOfNotNull(frame.target?.nextClimb, frame.target?.nextDescent)
        .minByOrNull { it.startDistanceM }
    val nextLabel = next?.let {
        val name = if (it.type.name == "CLIMB") "PODJAZD" else "ZJAZD"
        val percent = "${percent(abs(it.averageGrade))} %"
        val ahead = max(0.0, it.startDistanceM - rider)
        if (ahead < 1.0) "$name $percent · ${it.lengthM.toInt()} m"
        else "$name $percent za ${ahead.toInt()} m · ${it.lengthM.toInt()} m"
    }
    return TerrainInstrumentModel.Valid(rider, grade, nextLabel, frame.positionStale, frame.showStaleIndicator, shown)
}

/** Whole percent (integer rounding, so a grade that rounds to zero reads "0", never "-0"). */
private fun percent(grade: Double): Int = (grade * 100).roundToInt()

/** True when [profile] has an available height at some sample within [[from], [to]] (no allocation). */
private fun hasElevation(profile: DisplayElevationProfile, from: Double, to: Double): Boolean {
    var lo = 0
    var hi = profile.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (profile.distanceAt(mid) < from) lo = mid + 1 else hi = mid
    }
    for (i in lo until profile.size) {
        if (profile.distanceAt(i) > to) return false
        if (profile.relativeHeightAt(i) != null) return true
    }
    return false
}
