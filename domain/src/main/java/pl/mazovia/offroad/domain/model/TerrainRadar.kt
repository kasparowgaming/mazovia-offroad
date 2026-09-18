package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * Terrain Radar data - signature Mazovia Offroad feature.
 * Answers: "How much real off-road is ahead?" and
 * "How long is the asphalt connector I must accept?"
 */
@Serializable
data class TerrainRadar(
    /** Upcoming segments with surface info */
    val segments: List<TerrainRadarSegment>,
    /** Overall off-road proportion ahead (0.0 - 1.0) */
    val offRoadProportion: Double,
    /** Distance to next asphalt connector in meters, null if none ahead */
    val distanceToAsphaltMeters: Double?,
    /** Length of next asphalt connector in meters */
    val asphaltConnectorLengthMeters: Double?,
    /** Average data confidence ahead */
    val dataConfidence: DataConfidence,
    /** Look-ahead distance in meters */
    val lookAheadMeters: Double
)

@Serializable
data class TerrainRadarSegment(
    val surface: Surface,
    val distanceMeters: Double,
    val confidence: DataConfidence,
    /** Fraction of the look-ahead this segment occupies */
    val fraction: Double
)
