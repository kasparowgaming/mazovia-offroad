package pl.mazovia.offroad.terrain.dem

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * U5 (DESIGN §8.4): decodes EVERY tile of the validation archives with the production [BitmapFactoryTileImageDecoder]
 * and compares the decoded RGB bit-for-bit (SHA-256 of the R,G,B byte stream) with the values the pipeline encoded
 * (`u5_expected_*.tsv`). No height tolerance is involved.
 *
 * Inputs are pushed by adb (not packaged, not committed): directory from instrumentation argument `terrainU5Dir`
 * (default /data/local/tmp/ta001b) containing terrain_z15.pmtiles, terrain_z14.pmtiles, u5_synthetic.pmtiles and
 * u5_expected_{z15,z14,synthetic}.tsv. Results are also written as JSON to the test app's external files dir.
 */
@RunWith(AndroidJUnit4::class)
class BitmapFactoryU5Test {
    private val args = InstrumentationRegistry.getArguments()
    private val dir = File(args.getString("terrainU5Dir") ?: "/data/local/tmp/ta001b")
    private val decoder = BitmapFactoryTileImageDecoder()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun rgb(img: DecodedImage): ByteArray {
        val out = ByteArray(img.argb.size * 3)
        for (i in img.argb.indices) {
            val p = img.argb[i]
            out[3 * i] = (p shr 16).toByte(); out[3 * i + 1] = (p shr 8).toByte(); out[3 * i + 2] = p.toByte()
        }
        return out
    }

    private data class Outcome(val archive: String, val tiles: Int, val mismatches: List<String>, val alphaNot255: Int)

    private fun verify(archive: String, expectedTsv: String, terrainDecode: Boolean): Outcome {
        val rows = File(dir, expectedTsv).readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
        val mismatches = ArrayList<String>()
        var alpha = 0
        PmtilesReader.open(File(dir, archive)).use { r ->
            for (e in rows) {
                val key = "${e[1]}/${e[2]}/${e[3]}"
                val bytes = r.getTile(e[1].toInt(), e[2].toInt(), e[3].toInt())
                if (bytes == null) { mismatches += "$key missing"; continue }
                val img = decoder.decode(bytes)
                if (img.width != 256 || img.height != 256) { mismatches += "$key size ${img.width}x${img.height}"; continue }
                alpha += img.argb.count { it ushr 24 != 0xFF }
                val got = sha256(rgb(img))
                if (got != e[4]) {
                    var codeMin = Int.MAX_VALUE; var codeMax = Int.MIN_VALUE
                    img.argb.forEach { val c = it and 0xFFFFFF; if (c != TerrainRgb.NODATA_CODE) { codeMin = minOf(codeMin, c); codeMax = maxOf(codeMax, c) } }
                    mismatches += "$key sha $got != ${e[4]} (codes $codeMin..$codeMax vs ${e[6]}..${e[7]})"
                }
                if (terrainDecode) {
                    val block = TerrainRgbTileDecoder.fromImage(img)
                    if (block.nodataCount != e[5].toInt()) mismatches += "$key nodata ${block.nodataCount} != ${e[5]}"
                }
            }
        }
        return Outcome(archive, rows.size, mismatches, alpha)
    }

    @Test
    fun everyTileDecodesBitExact() {
        assumeTrue("U5 inputs not pushed to $dir", File(dir, "u5_expected_z15.tsv").canRead())
        val outcomes = listOf(
            verify("terrain_z15.pmtiles", "u5_expected_z15.tsv", terrainDecode = true),
            verify("terrain_z14.pmtiles", "u5_expected_z14.tsv", terrainDecode = true),
            verify("u5_synthetic.pmtiles", "u5_expected_synthetic.tsv", terrainDecode = false)
        )
        val timing = timing()
        val json = buildString {
            append("{\n \"device\": {\"model\": \"${Build.MODEL}\", \"manufacturer\": \"${Build.MANUFACTURER}\", ")
            append("\"sdk\": ${Build.VERSION.SDK_INT}, \"release\": \"${Build.VERSION.RELEASE}\", \"abi\": \"${Build.SUPPORTED_ABIS.joinToString()}\", ")
            append("\"fingerprint\": \"${Build.FINGERPRINT}\", \"emulator_hint\": ${Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", true) || Build.HARDWARE.contains("ranchu")}},\n")
            append(" \"u5\": [\n")
            append(outcomes.joinToString(",\n") { o ->
                "  {\"archive\": \"${o.archive}\", \"tiles\": ${o.tiles}, \"mismatches\": ${o.mismatches.size}, \"alpha_not_255_px\": ${o.alphaNot255}, " +
                    "\"examples\": [${o.mismatches.take(5).joinToString { "\"$it\"" }}]}"
            })
            append("\n ],\n \"timing_ms\": $timing\n}\n")
        }
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "u5_results.json")
        out.writeText(json)
        println("U5_RESULTS_JSON_BEGIN\n$json\nU5_RESULTS_JSON_END")
        for (o in outcomes) {
            assertEquals("${o.archive}: ${o.mismatches.take(5)}", 0, o.mismatches.size)
            assertEquals("${o.archive}: non-opaque pixels", 0, o.alphaNot255)
        }
        assertTrue(outcomes.sumOf { it.tiles } > 0)
    }

    /** Device timing of lookup / BitmapFactory decode / RGB→block unpack over every z15 tile (warm-up 3, measure 10). */
    private fun timing(): String {
        val lookup = ArrayList<Long>(); val decode = ArrayList<Long>(); val unpack = ArrayList<Long>(); val total = ArrayList<Long>()
        PmtilesReader.open(File(dir, "terrain_z15.pmtiles")).use { r ->
            val rows = File(dir, "u5_expected_z15.tsv").readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
            repeat(13) { round ->
                for (e in rows) {
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
        }
        fun s(v: List<Long>): String {
            val a = v.sorted()
            fun p(q: Double) = a[(ceil(q * a.size).toInt() - 1).coerceIn(0, a.size - 1)] / 1e6
            return "{\"n\": ${a.size}, \"p50\": ${p(0.5)}, \"p95\": ${p(0.95)}, \"max\": ${a.last() / 1e6}}"
        }
        return "{\"lookup_read\": ${s(lookup)}, \"bitmapfactory_decode\": ${s(decode)}, \"rgb_unpack\": ${s(unpack)}, \"total\": ${s(total)}, " +
            "\"note\": \"debug build, warm page cache, instrumentation process\"}"
    }
}
