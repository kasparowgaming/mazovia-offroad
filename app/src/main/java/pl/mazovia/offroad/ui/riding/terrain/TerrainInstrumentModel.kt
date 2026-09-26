package pl.mazovia.offroad.ui.riding.terrain

import kotlin.math.max

enum class RidingViewMode { MAPA, TEREN }

data class InstrumentSample(val distanceM: Double, val heightM: Float?, val breakAfter: Boolean)

sealed interface TerrainInstrumentModel {
    object NoRoute : TerrainInstrumentModel
    object Arrived : TerrainInstrumentModel
    data class Detached(val distanceToRouteM: Double?, val samples: List<InstrumentSample>) : TerrainInstrumentModel
    data class NoData(val stale: Boolean, val showStaleIndicator: Boolean) : TerrainInstrumentModel
    data class Valid(
        val samples: List<InstrumentSample>, val windowStartM: Double, val windowEndM: Double,
        val riderM: Double, val minHeightM: Float, val maxHeightM: Float,
        val gradeLabel: String, val nextEventLabel: String?,
        val stale: Boolean, val showStaleIndicator: Boolean
    ) : TerrainInstrumentModel
}

fun terrainInstrumentModel(frame: TerrainDisplayState): TerrainInstrumentModel {
    if (frame.mode == TerrainVisualMode.IDLE) return TerrainInstrumentModel.NoRoute
    if (frame.mode == TerrainVisualMode.ARRIVED) return TerrainInstrumentModel.Arrived
    val start = frame.windowStartM
    val end = frame.windowEndM
    val profile = frame.profile
    val samples = if (profile != null && start != null && end != null && start <= end) buildList {
        // Lower and upper bounds include every sample at a repeated distance across a GPX break.
        fun bound(value: Double, afterEqual: Boolean): Int {
            var lo = 0
            var hi = profile.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val distance = profile.distanceAt(mid)
                if (distance < value || (afterEqual && distance == value)) lo = mid + 1 else hi = mid
            }
            return lo
        }
        val first = bound(start, false)
        val afterLast = bound(end, true)
        for (i in first until afterLast) {
            add(InstrumentSample(profile.distanceAt(i), profile.relativeHeightAt(i), profile.isBreakAfter(i)))
        }
    } else emptyList()
    if (frame.mode == TerrainVisualMode.OFF_ROUTE) {
        return TerrainInstrumentModel.Detached(frame.distanceToRouteM, samples)
    }
    val heights = samples.mapNotNull { it.heightM }
    val rider = frame.distanceAlongM
    if (heights.isEmpty() || start == null || end == null || rider == null) {
        return TerrainInstrumentModel.NoData(frame.positionStale, frame.showStaleIndicator)
    }
    val low = heights.min()
    val high = heights.max()
    val midpoint = (low + high) / 2f
    val span = max(10f, high - low)
    val grade = frame.target?.currentGrade?.let { "%.0f%%".format(it * 100) } ?: "—"
    val next = listOfNotNull(frame.target?.nextClimb, frame.target?.nextDescent)
        .minByOrNull { it.startDistanceM }
    val nextLabel = next?.let {
        val name = if (it.type.name == "CLIMB") "PODJAZD" else "ZJAZD"
        "$name +${max(0.0, it.startDistanceM - rider).toInt()} m"
    }
    return TerrainInstrumentModel.Valid(samples, start, end, rider,
        midpoint - span / 2, midpoint + span / 2, grade, nextLabel,
        frame.positionStale, frame.showStaleIndicator)
}
