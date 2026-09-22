package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * Comprehensive route metrics focused on off-road quality.
 * These are the primary metrics shown to the rider - NOT just distance/ETA.
 */
@Serializable
data class RouteMetrics(
    /** Total route distance in meters */
    val totalDistanceMeters: Double,
    /** Estimated time in seconds */
    val estimatedTimeSeconds: Long,
    /** Off-road distance in meters */
    val asphaltDistanceMeters: Double,
    val offRoadDistanceMeters: Double,
    /** Longest continuous asphalt connector in meters */
    val longestAsphaltConnectorMeters: Double,
    /** Surface distribution as fraction of total distance */
    val surfaceDistribution: Map<Surface, Double>,
    /** Average data confidence across the route (0.0 - 1.0) */
    val dataConfidenceScore: Double,
    /** Fraction of route that retraces/overlaps (for loops) */
    val retraceFraction: Double = 0.0,
    /** Longest continuous off-road section in meters */
    val longestContinuousTerrainMeters: Double = 0.0,
    /** Number of distinct off-road sections separated by asphalt */
    val terrainRunCount: Int = 0,
    /** Fraction of route on previously unridden roads */
    val explorationScore: Double = 0.0,
    /** Route continuity score - fewer breaks in off-road = better */
    val continuitScore: Double = 0.0
) {
    /** Off-road as percentage of total distance */
    val offRoadPercentage: Double
        get() = if (totalDistanceMeters > 0) (offRoadDistanceMeters / totalDistanceMeters) * 100 else 0.0

    /** Asphalt as percentage of total distance */
    val asphaltPercentage: Double
        get() = if (totalDistanceMeters > 0) (asphaltDistanceMeters / totalDistanceMeters) * 100 else 0.0

    /** Data confidence as percentage */
    val dataConfidencePercentage: Double
        get() = dataConfidenceScore * 100

    companion object {
        val EMPTY = RouteMetrics(
            totalDistanceMeters = 0.0,
            estimatedTimeSeconds = 0,
            offRoadDistanceMeters = 0.0,
            asphaltDistanceMeters = 0.0,
            longestAsphaltConnectorMeters = 0.0,
            surfaceDistribution = emptyMap(),
            dataConfidenceScore = 0.0
        )

        /**
         * Calculate metrics from route segments.
         */
        fun fromSegments(segments: List<RouteSegment>): RouteMetrics {
            if (segments.isEmpty()) return EMPTY

            val totalDistance = segments.sumOf { it.distanceMeters }
            val offRoadDistance = segments.filter { it.isOffRoad }.sumOf { it.distanceMeters }
            val asphaltDistance = segments.filter { it.isAsphalt }.sumOf { it.distanceMeters }

            // Calculate longest asphalt connector
            var longestConnector = 0.0
            var currentConnector = 0.0
            for (segment in segments) {
                if (segment.isAsphalt) {
                    currentConnector += segment.distanceMeters
                    longestConnector = maxOf(longestConnector, currentConnector)
                } else {
                    currentConnector = 0.0
                }
            }

            // Surface distribution
            val surfaceDist = segments.groupBy { it.surface }
                .mapValues { (_, segs) -> segs.sumOf { it.distanceMeters } / totalDistance }

            // Data confidence
            // Compatibility projection for the existing route metric and loop score.
            // RoadDataConfidence is authoritative evidence; this preserves the old
            // metric's "path detail present" behavior for route selection.
            val avgConfidence = if (totalDistance > 0.0) {
                segments.sumOf {
                    (if (it.hasSurfaceOrRoadClassDetail) 1.0 else 0.0) *
                        it.distanceMeters
                } / totalDistance
            } else 0.0

            var longestContinuousTerrain = 0.0
            var currentTerrainRun = 0.0
            var terrainRuns = 0
            var wasTerrain = false

            for (segment in segments) {
                if (segment.isOffRoad) {
                    currentTerrainRun += segment.distanceMeters
                    longestContinuousTerrain = maxOf(longestContinuousTerrain, currentTerrainRun)
                    if (!wasTerrain) {
                        terrainRuns++
                        wasTerrain = true
                    }
                } else {
                    wasTerrain = false
                    currentTerrainRun = 0.0
                }
            }

            val continuity = if (segments.size > 1) 1.0 - ((terrainRuns - 1).coerceAtLeast(0).toDouble() / segments.size) else 1.0

            // Estimated time: rough estimate based on surface
            val estimatedTime = segments.sumOf { seg ->
                val speedKmh = when {
                    seg.surface == Surface.ASPHALT -> 60.0
                    seg.surface == Surface.GRAVEL || seg.surface == Surface.COMPACTED -> 35.0
                    seg.surface == Surface.DIRT || seg.surface == Surface.EARTH -> 25.0
                    seg.surface == Surface.SAND || seg.surface == Surface.MUD -> 15.0
                    seg.highway == HighwayType.TRACK -> 30.0
                    seg.highway == HighwayType.PATH -> 20.0
                    else -> 40.0
                }
                (seg.distanceMeters / 1000.0) / speedKmh * 3600.0
            }.toLong()

            return RouteMetrics(
                totalDistanceMeters = totalDistance,
                estimatedTimeSeconds = estimatedTime,
                offRoadDistanceMeters = offRoadDistance,
                asphaltDistanceMeters = asphaltDistance,
                longestAsphaltConnectorMeters = longestConnector,
                surfaceDistribution = surfaceDist,
                dataConfidenceScore = avgConfidence,
                continuitScore = continuity,
                longestContinuousTerrainMeters = longestContinuousTerrain,
                terrainRunCount = terrainRuns
            )
        }
    }
}
