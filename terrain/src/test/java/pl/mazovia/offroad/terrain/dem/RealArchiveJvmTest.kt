package pl.mazovia.offroad.terrain.dem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Opt-in checks on the real validation archives (`-Pterrain.gdata.workDir=<work dir>`):
 * 1. every tile decoded by the JVM PNG path equals the ENCODER's RGB (u5_expected_z*.tsv SHA-256, written by the
 *    pipeline from the arrays it encoded) — so harness runtime heights are the encoder values;
 * 2. DEVELOPMENT timing on the desktop JVM (lookup / PNG decode / Terrain-RGB unpack), NOT a device measurement;
 * 3. memory accounting of the reader and decoded blocks.
 */
class RealArchiveJvmTest {
    private val work = System.getProperty("terrain.gdata.workDir")?.let(::File)
    private val outDir = System.getProperty("terrain.gdata.outDir")?.let(::File)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun rgbBytes(img: DecodedImage): ByteArray {
        val out = ByteArray(img.argb.size * 3)
        for (i in img.argb.indices) {
            val p = img.argb[i]
            out[3 * i] = (p shr 16).toByte(); out[3 * i + 1] = (p shr 8).toByte(); out[3 * i + 2] = p.toByte()
        }
        return out
    }

    private fun pct(sortedNs: List<Long>, p: Double) = sortedNs[(ceil(p * sortedNs.size).toInt() - 1).coerceIn(0, sortedNs.size - 1)] / 1e6

    @Test
    fun decodeEqualsEncoderAndDevTiming() {
        assumeTrue("terrain.gdata.workDir not set", work != null)
        val results = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for (z in listOf(15, 14)) {
            val dir = File(work, "output/validation")
            val expected = File(dir, "u5_expected_z$z.tsv").readLines().drop(1).map { it.split('\t') }
            val decoder = PurePng.Decoder()
            PmtilesReader.open(File(dir, "terrain_z$z.pmtiles")).use { r ->
                var ancillary = 0
                for (e in expected) {
                    val bytes = r.getTile(e[1].toInt(), e[2].toInt(), e[3].toInt())!!
                    assertEquals(TileId.fromZxy(e[1].toInt(), e[2].toInt(), e[3].toInt()), e[0].toLong())
                    val types = PurePng.chunkTypes(bytes)
                    if (types.any { it in setOf("gAMA", "cHRM", "sRGB", "iCCP", "tRNS", "PLTE") }) ancillary++
                    val img = decoder.decode(bytes)
                    assertEquals("tile ${e[1]}/${e[2]}/${e[3]}", e[4], sha256(rgbBytes(img)))
                    val block = TerrainRgbTileDecoder.fromImage(img)
                    assertEquals(e[5].toInt(), block.nodataCount)
                }
                assertEquals("colour/alpha ancillary chunks present", 0, ancillary)
                // timing: warm-up, then measured rounds over every tile
                val lookup = ArrayList<Long>(); val decode = ArrayList<Long>(); val unpack = ArrayList<Long>(); val total = ArrayList<Long>()
                repeat(3 + 20) { round ->
                    for (e in expected) {
                        val t0 = System.nanoTime()
                        val b = r.getTile(e[1].toInt(), e[2].toInt(), e[3].toInt())!!
                        val t1 = System.nanoTime()
                        val img = decoder.decode(b)
                        val t2 = System.nanoTime()
                        TerrainRgbTileDecoder.fromImage(img)
                        val t3 = System.nanoTime()
                        if (round >= 3) { lookup += t1 - t0; decode += t2 - t1; unpack += t3 - t2; total += t3 - t0 }
                    }
                }
                fun stats(v: List<Long>) = buildJsonObject {
                    val s = v.sorted(); put("n", s.size); put("p50_ms", pct(s, 0.5)); put("p95_ms", pct(s, 0.95)); put("max_ms", s.last() / 1e6)
                }
                results["z$z"] = buildJsonObject {
                    put("tiles_verified_against_encoder", expected.size)
                    put("environment", "desktop JVM ${System.getProperty("java.version")} ${System.getProperty("os.name")} - DEVELOPMENT timing, pure-Kotlin PNG decoder (not BitmapFactory), warm OS file cache")
                    put("lookup_read", stats(lookup)); put("png_decode", stats(decode)); put("rgb_unpack", stats(unpack)); put("total", stats(total))
                    put("root_directory_bytes", r.header.rootLength); put("leaf_directory_bytes", r.header.leafDirectoriesLength)
                    put("archive_bytes", r.fileSize)
                }
            }
        }
        results["memory"] = buildJsonObject {
            put("decoded_block_payload_bytes", 256 * 256 * 4)
            put("decoded_block_retained_bytes_estimate", DemBlock.RETAINED_BYTES)
            put("cache_max_bytes", DemTileCache.DEFAULT_MAX_BYTES)
            put("cache_max_blocks", DemTileCache().maxBlocks)
            put("transient_per_decode_bytes_estimate", "ARGB_8888 bitmap 262144 + getPixels IntArray 262144 + PNG bytes (~22 KB) = ~546 KB per in-flight decode; 2 concurrent decodes -> ~1.1 MB")
        }
        val json = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(results))
        File(work, "gdata/jvm_decode_results.json").writeText(json)
        outDir?.let { File(it, "jvm_decode_results.json").apply { parentFile.mkdirs() }.writeText(json + "\n") }
        println(json)
    }
}
