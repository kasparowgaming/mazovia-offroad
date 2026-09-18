package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * A navigation maneuver (turn instruction).
 */
@Serializable
data class Maneuver(
    val point: GeoPoint,
    val type: ManeuverType,
    val distanceMeters: Double,
    val streetName: String? = null,
    val instruction: String? = null,
    /** Bearing after the maneuver in degrees */
    val exitBearing: Double? = null
)

@Serializable
enum class ManeuverType {
    DEPART,
    TURN_LEFT,
    TURN_RIGHT,
    TURN_SLIGHT_LEFT,
    TURN_SLIGHT_RIGHT,
    TURN_SHARP_LEFT,
    TURN_SHARP_RIGHT,
    STRAIGHT,
    ROUNDABOUT,
    U_TURN,
    FORK_LEFT,
    FORK_RIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    ARRIVE,
    WAYPOINT,
    UNKNOWN
}
