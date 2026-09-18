package pl.mazovia.offroad.data.db.converter

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pl.mazovia.offroad.domain.model.Surface

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromSurfaceDistribution(value: Map<Surface, Double>?): String? {
        return value?.let { json.encodeToString(it) }
    }

    @TypeConverter
    fun toSurfaceDistribution(value: String?): Map<Surface, Double>? {
        return value?.let { json.decodeFromString(it) }
    }

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return value?.let { json.encodeToString(it) }
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        return value?.let { json.decodeFromString(it) }
    }
}
