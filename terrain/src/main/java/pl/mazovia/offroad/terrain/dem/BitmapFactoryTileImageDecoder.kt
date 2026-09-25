package pl.mazovia.offroad.terrain.dem

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace

/**
 * Runtime PNG decode through Android [BitmapFactory] (DESIGN §8.4 U5).
 *
 * Options chosen so pixel values are not altered: ARGB_8888, no density scaling (`inScaled = false`), no
 * premultiplication (`inPremultiplied = false`), sRGB target colour space (identity for the pipeline's PNGs, which carry
 * no gAMA/iCCP/sRGB chunks). A bitmap that comes back in another config or colour space is rejected rather than
 * silently converted. Bit-exactness is verified on device by `BitmapFactoryU5Test`.
 */
class BitmapFactoryTileImageDecoder : TileImageDecoder {

    override fun decode(bytes: ByteArray): DecodedImage {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPremultiplied = false
            inMutable = false
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw TileDecodeException("BitmapFactory could not decode ${bytes.size} bytes")
        try {
            if (bitmap.config != Bitmap.Config.ARGB_8888) throw TileDecodeException("decoded config ${bitmap.config}")
            val cs = bitmap.colorSpace
            if (cs != null && cs != ColorSpace.get(ColorSpace.Named.SRGB)) throw TileDecodeException("colour space ${cs.name}")
            val w = bitmap.width
            val h = bitmap.height
            val argb = IntArray(w * h)
            // getPixels returns non-premultiplied ARGB
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            return DecodedImage(w, h, argb)
        } finally {
            bitmap.recycle()
        }
    }
}
