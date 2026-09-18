package pl.mazovia.offroad.routing.terrain

import pl.mazovia.offroad.domain.model.*

/**
 * Calculates Terrain Radar data from route segments ahead of current position.
 */
class TerrainRadarCalculator(
    private val lookAheadMeters: Double = 5000.0 // 5km default look-ahead
) {

    fun calculate(
        route: Route,
        currentSegmentIndex: Int
    ): TerrainRadar {
        val remainingSegments = route.segments.drop(currentSegmentIndex)
        if (remainingSegments.isEmpty()) {
            return TerrainRadar(
                segments = emptyList(),
                offRoadProportion = 0.0,
                distanceToAsphaltMeters = null,
                asphaltConnectorLengthMeters = null,
                dataConfidence = DataConfidence.UNKNOWN,
                lookAheadMeters = lookAheadMeters
            )
        }

        // Collect segments within look-ahead distance
        var accumulatedDistance = 0.0
        val lookAheadSegments = mutableListOf<RouteSegment>()

        for (segment in remainingSegments) {
            if (accumulatedDistance >= lookAheadMeters) break
            lookAheadSegments.add(segment)
            accumulatedDistance += segment.distanceMeters
        }

        val totalLookAhead = lookAheadSegments.sumOf { it.distanceMeters }

        // Calculate off-road proportion
        val offRoadDistance = lookAheadSegments.filter { it.isOffRoad }.sumOf { it.distanceMeters }
        val offRoadProportion = if (totalLookAhead > 0) offRoadDistance / totalLookAhead else 0.0

        // Find next asphalt connector
        var distToAsphalt: Double? = null
        var asphaltLength: Double? = null
        var currentDist = 0.0

        for (segment in lookAheadSegments) {
            if (segment.isAsphalt && distToAsphalt == null) {
                distToAsphalt = currentDist
                asphaltLength = segment.distanceMeters
            } else if (segment.isAsphalt && distToAsphalt != null) {
                asphaltLength = (asphaltLength ?: 0.0) + segment.distanceMeters
            } else if (!segment.isAsphalt && distToAsphalt != null && asphaltLength != null) {
                break // End of asphalt connector found
            }
            currentDist += segment.distanceMeters
        }

        // Build radar segments
        val radarSegments = lookAheadSegments.map { seg ->
            TerrainRadarSegment(
                surface = seg.surface,
                distanceMeters = seg.distanceMeters,
                confidence = seg.dataConfidence,
                fraction = if (totalLookAhead > 0) seg.distanceMeters / totalLookAhead else 0.0
            )
        }

        // Average confidence
        val avgConfidence = if (lookAheadSegments.isNotEmpty()) {
            val avgLevel = lookAheadSegments.sumOf { it.dataConfidence.level } / lookAheadSegments.size.toDouble()
            when {
                avgLevel >= 2.5 -> DataConfidence.CONFIRMED
                avgLevel >= 1.5 -> DataConfidence.INFERRED
                avgLevel >= 0.5 -> DataConfidence.LOW
                else -> DataConfidence.UNKNOWN
            }
        } else DataConfidence.UNKNOWN

        return TerrainRadar(
            segments = radarSegments,
            offRoadProportion = offRoadProportion,
            distanceToAsphaltMeters = distToAsphalt,
            asphaltConnectorLengthMeters = asphaltLength,
            dataConfidence = avgConfidence,
            lookAheadMeters = lookAheadMeters
        )
    }
}
