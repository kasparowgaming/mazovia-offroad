package pl.mazovia.offroad.routing.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.LoopCandidate
import pl.mazovia.offroad.domain.model.LoopParameters
import java.io.File
import java.util.Locale
import kotlin.math.cos

/** Benchmark-only exporter. Call with a candidate returned by generateLoopCandidates. */
internal object LoopGeoJsonExporter {
    data class Entry(val file: File, val params: LoopParameters, val candidate: LoopCandidate)

    private val json = Json { prettyPrint = true }

    fun outputDirectory(buildDir: File): File {
        require(buildDir.name == "build") { "Loop exports belong under a module build directory" }
        return File(buildDir, "benchmark-reports/loops")
    }

    fun filename(params: LoopParameters): String =
        "loop-${params.targetDistanceKm}-${params.profile.name.lowercase(Locale.ROOT)}.geojson"

    fun exportSelected(buildDir: File, params: LoopParameters, selected: LoopCandidate): Entry {
        require(selected.status == "PRIMARY" || selected.status == "FALLBACK_25_PERCENT")
        require(selected.route.profile == params.profile)
        require(selected.route.origin == params.startPoint && selected.route.destination == params.startPoint)
        require(selected.score.targetDistanceError <= 0.25 && selected.score.retraceFraction <= 0.20)
        require(!selected.spikeRejected)
        val shape = (LoopPlanner.shapes(params.startPoint, params.targetDistanceKm, params.preferredDirection) + LoopPlanner.shapes(params.startPoint, params.targetDistanceKm, params.preferredDirection, recovery = true))
            .singleOrNull { it.id == selected.candidateId }
            ?: throw IllegalArgumentException("Selected candidate has no matching control-point geometry")
        require(shape.description == selected.geometry) { "Candidate geometry does not match its control points" }
        val points = selected.route.allPoints
        require(points.size >= 3 && points.first().distanceTo(points.last()) <= 100.0)

        val properties = buildJsonObject {
            put("profile", JsonPrimitive(params.profile.name))
            put("requestedDistanceKm", JsonPrimitive(params.targetDistanceKm))
            put("actualDistanceKm", JsonPrimitive(selected.route.totalDistanceMeters / 1000.0))
            put("distanceErrorPercent", JsonPrimitive(selected.score.targetDistanceError * 100.0))
            put("terrainDistanceKm", JsonPrimitive(selected.route.metrics.offRoadDistanceMeters / 1000.0))
            put("terrainPercent", JsonPrimitive(selected.route.metrics.offRoadPercentage))
            put("longestTerrainKm", JsonPrimitive(selected.route.metrics.longestContinuousTerrainMeters / 1000.0))
            put("retraceDistanceKm", JsonPrimitive(selected.retraceDistanceMeters / 1000.0))
            put("retracePercent", JsonPrimitive(selected.score.retraceFraction * 100.0))
            put("localSpikeDistanceMeters", selected.localSpikeDistanceMeters)
            put("localSpikeRatio", selected.localSpikeRatio)
            put("spikeRejected", selected.spikeRejected)
            selected.spikeWaypointIndex?.let { put("spikeWaypointIndex", it) }
            put("rejectionReason", "NONE")
            put("selectedCandidateId", JsonPrimitive(selected.candidateId))
            put("heading", JsonPrimitive(shape.description.substringAfter("heading=").substringBefore(' ').toInt()))
            put("fallbackUsed", JsonPrimitive(selected.status == "FALLBACK_25_PERCENT"))
        }
        val features = buildJsonArray {
            add(feature(buildJsonObject {
                put("type", JsonPrimitive("LineString"))
                put("coordinates", buildJsonArray { points.forEach { add(coordinate(it)) } })
            }, properties))
            add(pointFeature("start", points.first()))
            shape.waypoints.forEachIndexed { index, point -> add(pointFeature("control-${index + 1}", point)) }
            add(pointFeature("end", points.last()))
        }
        val collection = buildJsonObject {
            put("type", JsonPrimitive("FeatureCollection"))
            put("features", features)
        }
        val directory = outputDirectory(buildDir)
        directory.mkdirs()
        val file = File(directory, filename(params))
        file.writeText(json.encodeToString(JsonObject.serializer(), collection), Charsets.UTF_8)
        return Entry(file, params, selected)
    }

    fun writeIndex(buildDir: File, entries: List<Entry>): File {
        val directory = outputDirectory(buildDir)
        directory.mkdirs()
        val rows = entries.joinToString("\n") { entry ->
            val candidate = entry.candidate
            "<tr><td>${entry.params.targetDistanceKm} km</td><td>${entry.params.profile.name}</td>" +
                "<td>${svgPreview(entry)}</td>" +
                "<td><a href=\"${entry.file.name}\">${entry.file.name}</a></td>" +
                "<td>${format(candidate.route.totalDistanceMeters / 1000.0)} km</td>" +
                "<td>${format(candidate.score.targetDistanceError * 100.0)}%</td>" +
                "<td>${format(candidate.route.metrics.offRoadDistanceMeters / 1000.0)} km / " +
                "${format(candidate.route.metrics.offRoadPercentage)}%</td>" +
                "<td>${format(candidate.retraceDistanceMeters / 1000.0)} km / " +
                "${format(candidate.score.retraceFraction * 100.0)}%</td>" +
                "<td>${format(candidate.localSpikeDistanceMeters)} m / ${format(candidate.localSpikeRatio * 100)}%</td>" +
                "<td>${candidate.candidateId}</td><td>${if (candidate.status == "PRIMARY") "Primary" else "Fallback"}</td></tr>"
        }
        val index = File(directory, "index.html")
        index.writeText("""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Mazovia loop benchmark routes</title>
<style>body{font:16px system-ui;margin:2rem}table{border-collapse:collapse}th,td{border:1px solid #bbb;padding:.5rem;text-align:left}th{background:#eee}</style>
</head><body><h1>Selected loop routes</h1><p>Shape previews have no basemap. GeoJSON files contain complete selected geometry, start, control waypoints, and end.</p>
<table><thead><tr><th>Target</th><th>Profile</th><th>Shape</th><th>GeoJSON</th><th>Actual</th><th>Error</th><th>Terrain</th><th>Retrace</th><th>Local spike (one way)</th><th>ID</th><th>Band</th></tr></thead>
<tbody>$rows</tbody></table></body></html>
""", Charsets.UTF_8)
        return index
    }

    private fun feature(geometry: JsonObject, properties: JsonObject) = buildJsonObject {
        put("type", JsonPrimitive("Feature"))
        put("geometry", geometry)
        put("properties", properties)
    }

    private fun pointFeature(role: String, point: GeoPoint) = feature(buildJsonObject {
        put("type", JsonPrimitive("Point"))
        put("coordinates", coordinate(point))
    }, buildJsonObject { put("role", JsonPrimitive(role)) })

    private fun coordinate(point: GeoPoint): JsonArray = buildJsonArray {
        add(JsonPrimitive(point.longitude))
        add(JsonPrimitive(point.latitude))
        point.elevation?.let { add(JsonPrimitive(it)) }
    }

    private fun format(value: Double) = String.format(Locale.US, "%.2f", value)

    private fun svgPreview(entry: Entry): String {
        val points = entry.candidate.route.allPoints
        val start = entry.params.startPoint
        val longitudeScale = cos(Math.toRadians(start.latitude))
        fun x(point: GeoPoint) = (point.longitude - start.longitude) * longitudeScale
        fun y(point: GeoPoint) = point.latitude - start.latitude
        val minX = points.minOf(::x)
        val maxX = points.maxOf(::x)
        val minY = points.minOf(::y)
        val maxY = points.maxOf(::y)
        val scale = minOf(176.0 / (maxX - minX).coerceAtLeast(1e-9),
            116.0 / (maxY - minY).coerceAtLeast(1e-9))
        fun position(point: GeoPoint): String = String.format(Locale.US, "%.1f,%.1f",
            12.0 + (x(point) - minX) * scale,
            128.0 - (y(point) - minY) * scale)
        val line = points.joinToString(" ", transform = ::position)
        val waypoints = (LoopPlanner.shapes(start, entry.params.targetDistanceKm, entry.params.preferredDirection) + LoopPlanner.shapes(start, entry.params.targetDistanceKm, entry.params.preferredDirection, recovery = true))
            .single { it.id == entry.candidate.candidateId }.waypoints
        val markers = waypoints.joinToString("") { point ->
            val pair = position(point).split(',')
            "<circle cx=\"${pair[0]}\" cy=\"${pair[1]}\" r=\"3\" fill=\"#ea9b12\"/>"
        }
        val origin = position(points.first()).split(',')
        return "<svg viewBox=\"0 0 200 140\" width=\"200\" height=\"140\" role=\"img\" " +
            "aria-label=\"Loop route shape\"><polyline points=\"$line\" fill=\"none\" " +
            "stroke=\"#1768a8\" stroke-width=\"2\"/>$markers" +
            "<circle cx=\"${origin[0]}\" cy=\"${origin[1]}\" r=\"4\" fill=\"#168447\"/></svg>"
    }
}
