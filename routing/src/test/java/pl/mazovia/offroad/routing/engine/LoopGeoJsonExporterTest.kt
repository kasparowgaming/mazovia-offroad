package pl.mazovia.offroad.routing.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.mazovia.offroad.domain.model.*
import java.io.File

class LoopGeoJsonExporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val start = GeoPoint(52.1567802, 22.3448868)
    private val params = LoopParameters(start, 20, RoutingProfile.TERENOWY)
    private val shape = LoopPlanner.shapes(start, 20, null).first()

    private fun candidate(): LoopCandidate {
        val points = listOf(start, shape.waypoints[0], shape.waypoints[1], start)
        val segment = RouteSegment(points, 20_000.0, Surface.GRAVEL, HighwayType.TRACK)
        val metrics = RouteMetrics.fromSegments(listOf(segment))
        val route = Route("selected-route", start, start, listOf(segment), metrics, RoutingProfile.TERENOWY)
        return LoopCandidate(route, LoopScore.calculate(metrics, 20), shape.id, shape.description,
            retraceDistanceMeters = 0.0, status = "PRIMARY")
    }

    @Test fun `selected geometry and metadata export as valid GeoJSON inside build output`() {
        val build = temporary.newFolder("build")
        val selected = candidate()
        val entry = LoopGeoJsonExporter.exportSelected(build, params, selected)
        assertEquals("loop-20-terenowy.geojson", entry.file.name)
        assertEquals(File(build, "benchmark-reports/loops").canonicalFile, entry.file.parentFile!!.canonicalFile)

        val document = Json.parseToJsonElement(entry.file.readText()).jsonObject
        assertEquals("FeatureCollection", document["type"]!!.jsonPrimitive.content)
        val features = document["features"]!!.jsonArray
        assertEquals(5, features.size)
        val line = features[0].jsonObject
        assertEquals("Feature", line["type"]!!.jsonPrimitive.content)
        assertEquals("LineString", line["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val coordinates = line["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(selected.route.allPoints.size, coordinates.size)
        selected.route.allPoints.forEachIndexed { index, point ->
            val pair = coordinates[index].jsonArray
            assertEquals(point.longitude, pair[0].jsonPrimitive.double, 0.0)
            assertEquals(point.latitude, pair[1].jsonPrimitive.double, 0.0)
        }
        assertEquals(start.longitude, coordinates.first().jsonArray[0].jsonPrimitive.double, 0.0)
        assertEquals(coordinates.first(), coordinates.last())
        assertEquals(listOf("start", "control-1", "control-2", "end"),
            features.drop(1).map { it.jsonObject["properties"]!!.jsonObject["role"]!!.jsonPrimitive.content })
        shape.waypoints.forEachIndexed { index, point ->
            val pair = features[index + 2].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
            assertEquals(point.longitude, pair[0].jsonPrimitive.double, 0.0)
            assertEquals(point.latitude, pair[1].jsonPrimitive.double, 0.0)
        }
        val properties = line["properties"]!!.jsonObject
        assertEquals("TERENOWY", properties["profile"]!!.jsonPrimitive.content)
        assertEquals(20, properties["requestedDistanceKm"]!!.jsonPrimitive.int)
        assertEquals(20.0, properties["actualDistanceKm"]!!.jsonPrimitive.double, 0.0)
        assertEquals(shape.id, properties["selectedCandidateId"]!!.jsonPrimitive.content)
        assertEquals(0, properties["heading"]!!.jsonPrimitive.int)
        assertEquals(false, properties["fallbackUsed"]!!.jsonPrimitive.content.toBoolean())
        val index = LoopGeoJsonExporter.writeIndex(build, listOf(entry))
        assertTrue(index.readText().contains(entry.file.name))
        assertTrue(index.readText().contains("20.00 km"))
        assertTrue(index.readText().contains("<svg"))
    }

    @Test fun `rejected or mismatched candidate cannot produce an export`() {
        val build = temporary.newFolder("build")
        val selected = candidate()
        for (invalid in listOf(
            selected.copy(status = "TARGET_DISTANCE_FAILURE"),
            selected.copy(spikeRejected = true),
            selected.copy(candidateId = "not-a-shape"),
            selected.copy(score = selected.score.copy(targetDistanceError = 0.30)),
            selected.copy(score = selected.score.copy(retraceFraction = 0.21))
        )) {
            try {
                LoopGeoJsonExporter.exportSelected(build, params, invalid)
                fail("Rejected candidate was exported")
            } catch (_: IllegalArgumentException) {
                // Rejection happens before the build-output directory is created.
            }
        }
        assertFalse(LoopGeoJsonExporter.outputDirectory(build).exists())
    }
}
