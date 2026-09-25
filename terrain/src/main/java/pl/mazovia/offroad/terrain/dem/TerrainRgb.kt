package pl.mazovia.offroad.terrain.dem

/**
 * Terrain-RGB "mapbox" value semantics (DESIGN §8.4, §11.4), independent of any image IO.
 *
 * height = −10000 + 0.1 · (R·65536 + G·256 + B); 0.1 m quantisation. Nodata is the reserved code 0xFFFFFF
 * (RGB 255,255,255 — would decode to −10000 + 0.1·16 777 215 = 1 667 721.5 m, far outside terrain); a missing sample is never 0 m. Codes decoding outside
 * [MIN_VALID_M, MAX_VALID_M) are not produced by the pipeline and mark a tile corrupt. Constants and the rounding rule
 * (half to even) match `tools/terrain/terrain_pipeline/terrainrgb.py`.
 */
object TerrainRgb {
    const val NODATA_CODE = 0xFFFFFF
    const val MIN_VALID_M = -500.0
    const val MAX_VALID_M = 9000.0
    const val ENCODING = "mapbox"

    fun code(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    /** Same operation order as the pipeline decoder (`-10000.0 + 0.1 * c`) so values are bit-identical doubles. */
    fun heightOfCode(code: Int): Double = -10000.0 + 0.1 * code

    fun isNodata(code: Int): Boolean = code == NODATA_CODE

    fun isValidHeightCode(code: Int): Boolean {
        if (code < 0 || code >= NODATA_CODE) return false
        val h = heightOfCode(code)
        return h >= MIN_VALID_M && h < MAX_VALID_M
    }

    /** Encoder (tests/tooling parity): round half to even; throws outside the valid range. Null → nodata. */
    fun encode(heightM: Double?): Int {
        if (heightM == null) return NODATA_CODE
        require(heightM.isFinite() && heightM >= MIN_VALID_M && heightM < MAX_VALID_M) { "height $heightM not encodable" }
        return Math.rint((heightM + 10000.0) * 10.0).toInt()
    }
}

/** Decoded RGBA raster in packed ARGB (non-premultiplied), row-major. */
class DecodedImage(val width: Int, val height: Int, val argb: IntArray) {
    init { require(argb.size == width * height) }
}

/** Image bytes could not be decoded into a Terrain-RGB tile. */
class TileDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Platform image decoding (B), separate from Terrain-RGB semantics (A). Android: [BitmapFactoryTileImageDecoder];
 * JVM tests/harness: an ImageIO implementation in test sources. Implementations must not scale, premultiply or
 * colour-convert, and throw [TileDecodeException] for undecodable input.
 */
fun interface TileImageDecoder {
    fun decode(bytes: ByteArray): DecodedImage
}

/**
 * Decoded DEM block of one tile: Terrain-RGB codes (not floats, so the 0.1 m semantics are exact), row-major 256×256.
 * Memory: 262 144 B payload + ~32 B array/object headers.
 */
class DemBlock internal constructor(private val codes: IntArray, val nodataCount: Int) {
    /** Height in metres at pixel ([col], [row]); NaN for nodata. */
    fun heightAt(col: Int, row: Int): Double {
        val c = codes[row * TILE + col]
        return if (c == TerrainRgb.NODATA_CODE) Double.NaN else TerrainRgb.heightOfCode(c)
    }

    fun codeAt(col: Int, row: Int): Int = codes[row * TILE + col]

    companion object {
        const val TILE = WebMercator.TILE_SIZE
        /** Estimated retained bytes per block incl. headers, used for cache accounting. */
        const val RETAINED_BYTES: Long = TILE.toLong() * TILE * 4 + 16 + 32
    }
}

/** Terrain-RGB tile → [DemBlock]. Strict: wrong size, non-opaque pixels or impossible codes → [TileDecodeException]. */
class TerrainRgbTileDecoder(private val imageDecoder: TileImageDecoder) {

    fun decode(bytes: ByteArray): DemBlock {
        val img = try {
            imageDecoder.decode(bytes)
        } catch (e: TileDecodeException) {
            throw e
        } catch (e: Exception) {
            throw TileDecodeException("image decode failed: ${e.message}", e)
        }
        return fromImage(img)
    }

    companion object {
        fun fromImage(img: DecodedImage): DemBlock {
            if (img.width != DemBlock.TILE || img.height != DemBlock.TILE)
                throw TileDecodeException("tile is ${img.width}x${img.height}, expected ${DemBlock.TILE}x${DemBlock.TILE}")
            val codes = IntArray(img.argb.size)
            var nodata = 0
            for (i in codes.indices) {
                val p = img.argb[i]
                if (p ushr 24 != 0xFF) throw TileDecodeException("non-opaque pixel $i (alpha ${p ushr 24})")
                val c = p and 0xFFFFFF
                if (c == TerrainRgb.NODATA_CODE) nodata++
                else if (!TerrainRgb.isValidHeightCode(c)) throw TileDecodeException("pixel $i code $c outside valid height range")
                codes[i] = c
            }
            return DemBlock(codes, nodata)
        }
    }
}
