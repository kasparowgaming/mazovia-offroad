package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * OSM highway classification relevant for off-road routing.
 */
@Serializable
enum class HighwayType(
    val routingPriority: Int, // lower = more preferred for off-road
    val isOffRoadCandidate: Boolean
) {
    TRACK(1, true),
    PATH(2, true),
    BRIDLEWAY(3, true),
    CYCLEWAY(4, false),
    FOOTWAY(5, false),
    SERVICE(6, false),
    UNCLASSIFIED(7, false),
    RESIDENTIAL(8, false),
    TERTIARY(9, false),
    SECONDARY(10, false),
    PRIMARY(11, false),
    TRUNK(12, false),
    MOTORWAY(13, false),
    UNKNOWN(99, false);

    companion object {
        fun fromOsmTag(tag: String?): HighwayType = when (tag?.lowercase()) {
            "track" -> TRACK
            "path" -> PATH
            "bridleway" -> BRIDLEWAY
            "cycleway" -> CYCLEWAY
            "footway" -> FOOTWAY
            "service" -> SERVICE
            "unclassified" -> UNCLASSIFIED
            "residential" -> RESIDENTIAL
            "tertiary", "tertiary_link" -> TERTIARY
            "secondary", "secondary_link" -> SECONDARY
            "primary", "primary_link" -> PRIMARY
            "trunk", "trunk_link" -> TRUNK
            "motorway", "motorway_link" -> MOTORWAY
            else -> UNKNOWN
        }
    }
}

/**
 * OSM tracktype classification (grade1 = best, grade5 = worst).
 */
@Serializable
enum class TrackType(val grade: Int) {
    GRADE1(1),
    GRADE2(2),
    GRADE3(3),
    GRADE4(4),
    GRADE5(5),
    UNKNOWN(0);

    companion object {
        fun fromOsmTag(tag: String?): TrackType = when (tag?.lowercase()) {
            "grade1" -> GRADE1
            "grade2" -> GRADE2
            "grade3" -> GRADE3
            "grade4" -> GRADE4
            "grade5" -> GRADE5
            else -> UNKNOWN
        }
    }
}

/**
 * OSM smoothness classification.
 */
@Serializable
enum class Smoothness {
    EXCELLENT, GOOD, INTERMEDIATE, BAD, VERY_BAD, HORRIBLE, VERY_HORRIBLE, IMPASSABLE, UNKNOWN;

    companion object {
        fun fromOsmTag(tag: String?): Smoothness = when (tag?.lowercase()) {
            "excellent" -> EXCELLENT
            "good" -> GOOD
            "intermediate" -> INTERMEDIATE
            "bad" -> BAD
            "very_bad" -> VERY_BAD
            "horrible" -> HORRIBLE
            "very_horrible" -> VERY_HORRIBLE
            "impassable" -> IMPASSABLE
            else -> UNKNOWN
        }
    }
}

/**
 * Access restriction for motorcycle riding.
 */
@Serializable
enum class AccessRestriction {
    /** Explicitly allowed */
    YES,
    /** No restriction data - assume allowed */
    PERMISSIVE,
    /** Explicitly forbidden */
    NO,
    /** Private road - no public access */
    PRIVATE,
    /** Restricted but may have exceptions */
    RESTRICTED,
    /** Unknown/no data */
    UNKNOWN;

    val isAccessible: Boolean get() = this == YES || this == PERMISSIVE || this == UNKNOWN

    companion object {
        /**
         * Determine motorcycle access from OSM tags.
         * Priority: motorcycle > motor_vehicle > vehicle > access
         */
        fun fromOsmTags(
            access: String? = null,
            motorVehicle: String? = null,
            motorcycle: String? = null
        ): AccessRestriction {
            val effectiveTag = motorcycle ?: motorVehicle ?: access
            return when (effectiveTag?.lowercase()) {
                "yes", "designated" -> YES
                "permissive" -> PERMISSIVE
                "no" -> NO
                "private" -> PRIVATE
                "restricted", "customers", "delivery", "agricultural", "forestry" -> RESTRICTED
                null -> UNKNOWN
                else -> UNKNOWN
            }
        }
    }
}
