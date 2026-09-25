package pl.mazovia.offroad.terrain.dem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Kotlin reader vs the REFERENCE PMTiles implementation (protomaps `pmtiles` Python writer + reader).
 * Opt-in: fixtures are generated outside the repository by `build_terrain.py fixtures` and passed with
 * `-Pterrain.fixtures.dir=<work>/fixtures`; skipped when absent.
 */
class ReferenceFixturesTest {
    private val dir = System.getProperty("terrain.fixtures.dir")?.let(::File)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun everyTileMatchesReferenceReader() {
        assumeTrue("terrain.fixtures.dir not set", dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.name.endsWith(".expected.json") }!!.sortedBy { it.name }
        assumeTrue(files.isNotEmpty())
        var checkedArchives = 0
        var leafArchives = 0
        for (f in files) {
            val exp = Json.parseToJsonElement(f.readText()).jsonObject
            val archive = exp["archive_path"]?.jsonPrimitive?.content?.let(::File)
                ?: File(dir, exp["archive"]!!.jsonPrimitive.content)
            val h = exp["header"]!!.jsonObject
            PmtilesReader.open(archive).use { r ->
                assertEquals(h.l("root_offset"), r.header.rootOffset)
                assertEquals(h.l("root_length"), r.header.rootLength)
                assertEquals(h.l("leaf_directory_length"), r.header.leafDirectoriesLength)
                assertEquals(h.l("tile_data_length"), r.header.tileDataLength)
                assertEquals(h.l("addressed_tiles_count"), r.header.addressedTiles)
                assertEquals(h["clustered"]!!.jsonPrimitive.boolean, r.header.clustered)
                assertEquals(h["internal_compression"]!!.jsonPrimitive.int, r.header.internalCompression.code)
                assertEquals(h["tile_type"]!!.jsonPrimitive.int, r.header.tileType.code)
                assertEquals(h["min_zoom"]!!.jsonPrimitive.int, r.header.minZoom)
                assertEquals(h["max_zoom"]!!.jsonPrimitive.int, r.header.maxZoom)
                assertEquals(h["min_lon_e7"]!!.jsonPrimitive.int, r.header.minLonE7)
                assertEquals(h["max_lat_e7"]!!.jsonPrimitive.int, r.header.maxLatE7)
                if (r.header.leafDirectoriesLength > 0) leafArchives++
                val meta = Json.parseToJsonElement(r.metadataJson()).jsonObject
                assertEquals(exp["metadata"] as JsonObject, meta)
                for (t in exp["tiles"]!!.jsonArray) {
                    val a = t.jsonArray
                    val id = a[0].jsonPrimitive.long
                    val key = TileKey(a[1].jsonPrimitive.int, a[2].jsonPrimitive.int, a[3].jsonPrimitive.int)
                    assertEquals("tile id of $key", id, TileId.fromKey(key))
                    assertEquals(key, TileId.toKey(id))
                    val bytes = r.getTile(key.z, key.x, key.y)
                    assertTrue("missing $key in ${archive.name}", bytes != null)
                    assertEquals("sha of $key in ${archive.name}", a[4].jsonPrimitive.content, sha256(bytes!!))
                }
                for (m in exp["missing"]!!.jsonArray) assertNull(r.getTile(m.jsonPrimitive.long))
            }
            checkedArchives++
        }
        assertTrue(checkedArchives >= 2)
        assertTrue("fixtures must include a leaf-directory archive", leafArchives >= 1)
        println("ReferenceFixturesTest: $checkedArchives archives verified, $leafArchives with leaf directories")
    }

    private fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.long
}
