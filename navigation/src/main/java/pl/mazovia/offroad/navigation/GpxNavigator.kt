package pl.mazovia.offroad.navigation

import pl.mazovia.offroad.domain.model.*

/**
 * Specialized navigator for following GPX traces.
 * Uses proximity-based guidance rather than turn-by-turn.
 */
class GpxNavigator {

    private var gpxPoints: List<GeoPoint> = emptyList()
    private var segmentBreaks: Set<Int> = emptySet()
    private var currentPointIndex = 0
    private val proximityThresholdMeters = 30.0

    fun loadTrack(gpxData: GpxData) {
        val segments = gpxData.tracks.flatMap { track -> track.segments.map { segment ->
            segment.points.map { it.point }
        } }
        gpxPoints = segments.flatten()
        segmentBreaks = segments.dropLast(1).runningFold(0) { count, segment -> count + segment.size }
            .drop(1).toSet()
        currentPointIndex = 0
    }

    /**
     * Update with current position and get guidance.
     */
    fun updatePosition(position: GeoPoint): GpxGuidance {
        if (gpxPoints.isEmpty()) return GpxGuidance.NoTrack

        // Find nearest point on the track
        val nearestIdx = findNearestPointIndex(position)
        if (nearestIdx > currentPointIndex) {
            currentPointIndex = nearestIdx
        }

        val distToTrack = position.distanceTo(gpxPoints[nearestIdx])
        val isOffTrack = distToTrack > proximityThresholdMeters * 3

        // Look ahead for guidance
        val lookAheadIdx = (currentPointIndex + 5).coerceAtMost(gpxPoints.size - 1)
        val lookAheadPoint = gpxPoints[lookAheadIdx]
        val bearingToNext = position.bearingTo(lookAheadPoint)

        // Calculate remaining distance
        val remainingDistance = calculateRemainingDistance(currentPointIndex)

        // Check if arrived (near end of track)
        val isArrived = currentPointIndex == gpxPoints.lastIndex &&
                position.distanceTo(gpxPoints.last()) < proximityThresholdMeters

        return when {
            isArrived -> GpxGuidance.Arrived
            isOffTrack -> GpxGuidance.OffTrack(
                distanceToTrack = distToTrack,
                bearingToTrack = position.bearingTo(gpxPoints[nearestIdx])
            )
            else -> GpxGuidance.OnTrack(
                bearingToFollow = bearingToNext,
                distanceToNextPoint = position.distanceTo(lookAheadPoint),
                remainingDistanceMeters = remainingDistance,
                progress = currentPointIndex.toDouble() / gpxPoints.size
            )
        }
    }

    private fun findNearestPointIndex(position: GeoPoint): Int {
        val searchStart = (currentPointIndex - 5).coerceAtLeast(0)
        val searchEnd = (currentPointIndex + 20).coerceAtMost(gpxPoints.size - 1)

        var minDist = Double.MAX_VALUE
        var nearestIdx = currentPointIndex

        for (i in searchStart..searchEnd) {
            val dist = position.distanceTo(gpxPoints[i])
            if (dist < minDist) {
                minDist = dist
                nearestIdx = i
            }
        }

        return nearestIdx
    }

    private fun calculateRemainingDistance(fromIndex: Int): Double {
        if (fromIndex >= gpxPoints.size - 1) return 0.0
        var distance = 0.0
        for (i in fromIndex until gpxPoints.size - 1) {
            if (i + 1 !in segmentBreaks) distance += gpxPoints[i].distanceTo(gpxPoints[i + 1])
        }
        return distance
    }
}

sealed class GpxGuidance {
    data object NoTrack : GpxGuidance()
    data object Arrived : GpxGuidance()

    data class OnTrack(
        val bearingToFollow: Double,
        val distanceToNextPoint: Double,
        val remainingDistanceMeters: Double,
        val progress: Double
    ) : GpxGuidance()

    data class OffTrack(
        val distanceToTrack: Double,
        val bearingToTrack: Double
    ) : GpxGuidance()
}
