package pl.mazovia.offroad.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import pl.mazovia.offroad.domain.model.Surface

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey
    val id: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long?,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val status: String,
    // Metrics
    val offRoadDistanceMeters: Double = 0.0,
    val asphaltDistanceMeters: Double = 0.0,
    val averageSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val newKilometersMeters: Double = 0.0,
    val explorationPercentage: Double = 0.0,
    val surfaceDistribution: Map<Surface, Double>? = null,
    val routeId: String? = null,
    val pendingFeedback: List<String>? = null,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val terrainClassificationAvailable: Boolean = false
)
