package pl.mazovia.offroad.terrain.dem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Provenance of a Terrain-RGB archive, from the PMTiles header + the `mazovia_terrain` metadata block written by
 * `tools/terrain/build_terrain.py` (DESIGN §20.3). No accuracy figures are invented here: vertical RMSE lives in the
 * build manifest per source sheet.
 */
data class DemArchiveMetadata(
    val schema: String,
    val buildId: String,
    val source: String,
    val verticalDatum: String,
    val horizontalGrid: String,
    val zoom: Int,
    val tileSize: Int,
    val encoding: String,
    val quantisationM: Double,
    val nodataRgb: List<Int>,
    val sourceYears: IntRange?,
    val attribution: String?,
    val pmtilesVersion: Int,
    val coverage: CoverageBounds
) {
    data class CoverageBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
        fun contains(latitude: Double, longitude: Double) =
            latitude >= south && latitude <= north && longitude >= west && longitude <= east
    }

    companion object {
        const val SCHEMA = "mazovia-terrain-archive-1"

        /** Parses and validates the archive metadata; throws [PmtilesFormatException] if it is not a supported DEM. */
        fun parse(json: String, header: PmtilesHeader): DemArchiveMetadata {
            val root = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                throw PmtilesFormatException("metadata is not a JSON object", e)
            }
            val t = root["mazovia_terrain"] as? JsonObject ?: throw PmtilesFormatException("missing mazovia_terrain metadata")
            fun str(k: String) = (t[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw PmtilesFormatException("metadata field $k missing")
            val schema = str("schema")
            if (schema != SCHEMA) throw PmtilesUnsupportedException("metadata schema $schema")
            val encoding = str("encoding")
            if (encoding != TerrainRgb.ENCODING) throw PmtilesUnsupportedException("encoding $encoding")
            val nodata = t["nodata_rgb"]?.jsonArray?.map { it.jsonPrimitive.int } ?: throw PmtilesFormatException("nodata_rgb missing")
            if (nodata.size != 3 || TerrainRgb.code(nodata[0], nodata[1], nodata[2]) != TerrainRgb.NODATA_CODE)
                throw PmtilesUnsupportedException("nodata_rgb $nodata differs from runtime ${TerrainRgb.NODATA_CODE}")
            val zoom = t["zoom"]?.jsonPrimitive?.intOrNull ?: throw PmtilesFormatException("zoom missing")
            if (zoom != header.minZoom || zoom != header.maxZoom) throw PmtilesFormatException("zoom $zoom vs header ${header.minZoom}..${header.maxZoom}")
            val tileSize = t["tile_size"]?.jsonPrimitive?.intOrNull ?: throw PmtilesFormatException("tile_size missing")
            if (tileSize != WebMercator.TILE_SIZE) throw PmtilesUnsupportedException("tile size $tileSize")
            val years = t["source_years"]?.jsonArray?.map { it.jsonPrimitive.int }
            return DemArchiveMetadata(
                schema = schema,
                buildId = str("build_id"),
                source = str("source"),
                verticalDatum = str("vertical_datum"),
                horizontalGrid = str("horizontal_grid"),
                zoom = zoom,
                tileSize = tileSize,
                encoding = encoding,
                quantisationM = t["quantisation_m"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.1,
                nodataRgb = nodata,
                sourceYears = if (years != null && years.size == 2) years[0]..years[1] else null,
                attribution = (root["attribution"] as? JsonPrimitive)?.content,
                pmtilesVersion = 3,
                coverage = CoverageBounds(header.minLon, header.minLat, header.maxLon, header.maxLat)
            )
        }
    }
}
