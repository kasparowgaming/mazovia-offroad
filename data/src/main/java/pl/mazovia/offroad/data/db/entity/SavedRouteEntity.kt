package pl.mazovia.offroad.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_routes")
data class SavedRouteEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val originLat: Double,
    val originLon: Double,
    val destinationLat: Double,
    val destinationLon: Double,
    val profile: String,
    val totalDistanceMeters: Double,
    val offRoadPercentage: Double,
    val routeDataJson: String, // Serialized route geometry
    val source: String = "calculated" // calculated, gpx_import
)
