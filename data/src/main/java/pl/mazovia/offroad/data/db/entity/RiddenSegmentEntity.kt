package pl.mazovia.offroad.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ridden_segments",
    indices = [Index("osmWayId"), Index("segmentHash")]
)
data class RiddenSegmentEntity(
    @PrimaryKey
    val segmentHash: String,
    val osmWayId: Long?,
    val firstRiddenMillis: Long,
    val lastRiddenMillis: Long,
    val rideCount: Int,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double
)
