package pl.mazovia.offroad.terrain.dem

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Independent minimal PNG codec for JVM tests and the G-DATA harness (PNG spec: 8-bit truecolour / truecolour+alpha,
 * non-interlaced, filter types 0-4, CRC-checked chunks). Uses only java.util.zip, so it runs where BitmapFactory and
 * javax.imageio are unavailable (Android unit tests). It is never used by the production runtime.
 */
object PurePng {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    fun encodeRgb(width: Int, height: Int, rgb: (col: Int, row: Int) -> Int): ByteArray {
        val raw = ByteArrayOutputStream(height * (1 + width * 3))
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val p = rgb(x, y)
                raw.write((p shr 16) and 0xFF); raw.write((p shr 8) and 0xFF); raw.write(p and 0xFF)
            }
        }
        val deflater = Deflater(9)
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array())
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t); out.write(data)
        val crc = CRC32().apply { update(t); update(data) }
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    class Decoder : TileImageDecoder {
        override fun decode(bytes: ByteArray): DecodedImage = decodePng(bytes)
    }

    /** Ancillary chunk types seen while decoding the last image (diagnostics for colour-management checks). */
    fun chunkTypes(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        var p = 8
        while (p + 8 <= bytes.size) {
            val len = ByteBuffer.wrap(bytes, p, 4).int
            out += String(bytes, p + 4, 4, Charsets.US_ASCII)
            p += 12 + len
        }
        return out
    }

    fun decodePng(bytes: ByteArray): DecodedImage {
        fun fail(msg: String): Nothing = throw TileDecodeException("PNG: $msg")
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) fail("bad signature")
        var p = 8
        var width = -1; var height = -1; var colorType = -1
        val idat = ByteArrayOutputStream()
        var ended = false
        while (!ended) {
            if (p + 8 > bytes.size) fail("truncated chunk header")
            val len = ByteBuffer.wrap(bytes, p, 4).int
            if (len < 0 || p + 12L + len > bytes.size) fail("truncated chunk")
            val type = String(bytes, p + 4, 4, Charsets.US_ASCII)
            val crc = CRC32().apply { update(bytes, p + 4, 4 + len) }
            if (crc.value.toInt() != ByteBuffer.wrap(bytes, p + 8 + len, 4).int) fail("CRC mismatch in $type")
            val d = p + 8
            when (type) {
                "IHDR" -> {
                    val b = ByteBuffer.wrap(bytes, d, len)
                    width = b.int; height = b.int
                    val depth = b.get().toInt(); colorType = b.get().toInt()
                    val comp = b.get().toInt(); val filter = b.get().toInt(); val interlace = b.get().toInt()
                    if (depth != 8 || (colorType != 2 && colorType != 6) || comp != 0 || filter != 0 || interlace != 0)
                        fail("unsupported IHDR depth=$depth colour=$colorType interlace=$interlace")
                    if (width <= 0 || height <= 0 || width.toLong() * height > (1 shl 24)) fail("bad size")
                }
                "IDAT" -> idat.write(bytes, d, len)
                "IEND" -> ended = true
            }
            p += 12 + len
        }
        if (width < 0) fail("missing IHDR")
        val bpp = if (colorType == 6) 4 else 3
        val stride = width * bpp
        val raw = ByteArray(height * (stride + 1))
        val inflater = Inflater()
        try {
            inflater.setInput(idat.toByteArray())
            var n = 0
            while (n < raw.size) {
                val r = inflater.inflate(raw, n, raw.size - n)
                if (r == 0 && (inflater.finished() || inflater.needsInput())) break
                n += r
            }
            if (n != raw.size) fail("image data truncated ($n of ${raw.size})")
        } catch (e: DataFormatException) {
            fail("corrupt zlib stream: ${e.message}")
        } finally {
            inflater.end()
        }
        val cur = ByteArray(stride)
        val prev = ByteArray(stride)
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val off = y * (stride + 1)
            val ft = raw[off].toInt() and 0xFF
            for (i in 0 until stride) {
                val x = raw[off + 1 + i].toInt() and 0xFF
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val v = when (ft) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + ((a + b) ushr 1)
                    4 -> {
                        val pp = a + b - c
                        val pa = kotlin.math.abs(pp - a); val pb = kotlin.math.abs(pp - b); val pc = kotlin.math.abs(pp - c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> fail("filter type $ft")
                }
                cur[i] = v.toByte()
            }
            for (xx in 0 until width) {
                val i = xx * bpp
                val r = cur[i].toInt() and 0xFF; val g = cur[i + 1].toInt() and 0xFF; val bb = cur[i + 2].toInt() and 0xFF
                val al = if (bpp == 4) cur[i + 3].toInt() and 0xFF else 0xFF
                out[y * width + xx] = (al shl 24) or (r shl 16) or (g shl 8) or bb
            }
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        return DecodedImage(width, height, out)
    }
}
