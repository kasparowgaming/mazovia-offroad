package pl.mazovia.offroad.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "navigation_sessions")
data class NavigationSessionEntity(
    @PrimaryKey
    val id: String = "active_session",
    val routeDataJson: String,
    val profile: String,
    val destinationLat: Double,
    val destinationLon: Double,
    val originLat: Double,
    val originLon: Double,
    val currentSegmentIndex: Int,
    val isActive: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)
