package pl.mazovia.offroad.domain.model

/**
 * Explicit application mode controlling available UI and behavior.
 * The app must always be in exactly one mode.
 */
enum class AppMode {
    /** Route planning, map exploration, destination selection */
    PLANNING,
    /** Active navigation/riding - simplified cockpit UI */
    RIDING,
    /** Post-ride summary, feedback, statistics */
    POST_RIDE
}
