package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

/**
 * Rider feedback about a road segment collected after a ride.
 */
@Serializable
data class RoadFeedback(
    val id: String,
    val rideId: String,
    val segmentOsmWayId: Long? = null,
    val segmentStart: GeoPoint,
    val segmentEnd: GeoPoint,
    val question: FeedbackQuestion,
    val answer: FeedbackAnswer? = null,
    val timestampMillis: Long,
    val dataSource: String? = null
)

@Serializable
enum class FeedbackQuestion(
    val questionPl: String
) {
    SURFACE_QUALITY("Jaka nawierzchnia?"),
    ROAD_EXISTS("Czy droga istnieje?"),
    ROAD_PASSABLE("Czy droga jest przejezdna motocyklem?"),
    SURFACE_MATCH("Czy nawierzchnia zgadza się z mapą?")
}

@Serializable
enum class FeedbackAnswer(
    val displayPl: String
) {
    YES("Tak"),
    NO("Nie"),
    SURFACE_ASPHALT("Asfalt"),
    SURFACE_GRAVEL("Szuter"),
    SURFACE_DIRT("Grunt"),
    SURFACE_SAND("Piasek"),
    SURFACE_MUD("Błoto"),
    SURFACE_OTHER("Inna"),
    IMPASSABLE("Nieprzejezdna"),
    DIFFICULT("Trudna"),
    EASY("Łatwa")
}
