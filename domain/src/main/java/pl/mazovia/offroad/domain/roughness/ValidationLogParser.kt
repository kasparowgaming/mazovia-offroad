package pl.mazovia.offroad.domain.roughness

import java.io.BufferedReader
import java.io.File
import java.io.FileReader

data class ValidationLogMetadata(
    val formatVersion: Int,
    val metadataRaw: String
)

interface ValidationLogHandler {
    fun onMeta(meta: ValidationLogMetadata)
    fun onAccel(timestampNanos: Long, x: Float, y: Float, z: Float)
    fun onOrientation(timestampNanos: Long, source: String, vals: FloatArray)
    fun onLocation(elapsedRealtimeNanos: Long, lat: Double, lon: Double, speed: Float, accuracy: Float, speedAccuracy: Float)
    fun onDrop(count: Int)
}

class ValidationLogParser(private val handler: ValidationLogHandler) {
    fun parse(file: File) {
        var formatVersion = 0
        BufferedReader(FileReader(file)).use { reader ->
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val parts = line.split(",")
                when (parts[0]) {
                    "SESSION_META" -> {
                        val verStr = parts.find { it.startsWith("validationFormatVersion=") }
                        formatVersion = verStr?.substringAfter("=")?.toIntOrNull() ?: 0
                        if (formatVersion != 1) {
                            throw IllegalArgumentException("Unsupported validationFormatVersion: $formatVersion")
                        }
                        handler.onMeta(ValidationLogMetadata(formatVersion, line.substringAfter("validationFormatVersion=1,")))
                    }
                    "ACCEL" -> {
                        if (parts.size >= 5) {
                            handler.onAccel(parts[1].toLong(), parts[2].toFloat(), parts[3].toFloat(), parts[4].toFloat())
                        }
                    }
                    "ORIENTATION" -> {
                        if (parts.size >= 6) { // ORIENTATION,ts,source,v0,v1,v2...
                            val ts = parts[1].toLong()
                            val src = parts[2]
                            val vals = parts.subList(3, parts.size).map { it.toFloat() }.toFloatArray()
                            handler.onOrientation(ts, src, vals)
                        }
                    }
                    "LOCATION" -> {
                        if (parts.size >= 7) {
                            handler.onLocation(
                                parts[1].toLong(), parts[2].toDouble(), parts[3].toDouble(),
                                parts[4].toFloat(), parts[5].toFloat(), parts[6].toFloat()
                            )
                        }
                    }
                    "DROP" -> {
                        if (parts.size >= 2) {
                            handler.onDrop(parts[1].toInt())
                        }
                    }
                }
            }
        }
    }
}
