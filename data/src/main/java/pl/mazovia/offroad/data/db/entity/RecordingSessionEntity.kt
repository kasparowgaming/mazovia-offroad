package pl.mazovia.offroad.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recording_sessions")
data class RecordingSessionEntity(
    @PrimaryKey
    val id: String = "active_recording",
    val trackId: String,
    val status: String,
    val startTimeMillis: Long,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val pointCount: Int,
    val updatedAtMillis: Long
)
