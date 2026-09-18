package pl.mazovia.offroad.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "road_feedback")
data class RoadFeedbackEntity(
    @PrimaryKey
    val id: String,
    val rideId: String,
    val segmentOsmWayId: Long?,
    val segmentStartLat: Double,
    val segmentStartLon: Double,
    val segmentEndLat: Double,
    val segmentEndLon: Double,
    val question: String,
    val answer: String?,
    val timestampMillis: Long,
    val dataSource: String?
)
