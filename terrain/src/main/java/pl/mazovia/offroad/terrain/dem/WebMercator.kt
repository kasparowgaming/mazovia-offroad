package pl.mazovia.offroad.terrain.dem

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * WGS84 → Web Mercator XYZ addressing for DEM lookup only (DESIGN §9.6, §10.2). Never a route distance.
 *
 * World pixel coordinates at zoom z span [0, 256·2^z). Pixel (i, j) covers [i, i+1) × [j, j+1) and its value is the
 * terrain at the pixel centre (i + 0.5, j + 0.5) — the same convention as the offline pipeline
 * (`tools/terrain/terrain_pipeline/tiling.py`, identical formula).
 */
object WebMercator {
    const val TILE_SIZE = 256
    const val MAX_LATITUDE = 85.05112877980659
    const val MAX_ZOOM = 26

    /** Ground metres per pixel at the equator for zoom 0 (2π·6378137 / 256). */
    const val EQUATOR_M_PER_PX_Z0 = 156543.03392804097

    fun worldSizePx(zoom: Int): Double {
        require(zoom in 0..MAX_ZOOM) { "zoom $zoom" }
        return (TILE_SIZE.toLong() shl zoom).toDouble()
    }

    fun worldPxX(longitude: Double, zoom: Int): Double = (longitude + 180.0) / 360.0 * worldSizePx(zoom)

    fun worldPxY(latitude: Double, zoom: Int): Double {
        val s = sin(Math.toRadians(latitude))
        return (0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * PI)) * worldSizePx(zoom)
    }

    fun longitudeOfWorldPx(x: Double, zoom: Int): Double = x / worldSizePx(zoom) * 360.0 - 180.0

    fun latitudeOfWorldPx(y: Double, zoom: Int): Double =
        Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(PI * (1.0 - 2.0 * y / worldSizePx(zoom)))))

    /** True when the position can be addressed (finite, |lat| ≤ Web Mercator limit, lon in [−180, 180]). */
    fun isAddressable(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude >= -MAX_LATITUDE && latitude <= MAX_LATITUDE &&
            longitude >= -180.0 && longitude <= 180.0

    fun groundSpacingM(zoom: Int, latitude: Double): Double =
        EQUATOR_M_PER_PX_Z0 / (1L shl zoom) * cos(Math.toRadians(latitude))
}

/** XYZ tile address. */
data class TileKey(val z: Int, val x: Int, val y: Int) {
    init {
        require(z in 0..WebMercator.MAX_ZOOM) { "zoom $z" }
        val n = 1L shl z
        require(x in 0 until n && y in 0 until n) { "tile $z/$x/$y out of range" }
    }

    override fun toString(): String = "$z/$x/$y"
}

/**
 * PMTiles v3 tile IDs: cumulative position on the Hilbert curves of zoom 0..z (spec §4.1), written from the spec's
 * definition (Hilbert xy↔d as in the standard iterative formulation).
 */
object TileId {
    /** Number of tiles on all zoom levels below [z]: (4^z − 1) / 3. */
    fun zoomBase(z: Int): Long {
        require(z in 0..WebMercator.MAX_ZOOM)
        return ((1L shl (2 * z)) - 1) / 3
    }

    fun fromZxy(z: Int, x: Int, y: Int): Long {
        val n = 1L shl z
        require(z in 0..WebMercator.MAX_ZOOM && x >= 0 && y >= 0 && x < n && y < n) { "tile $z/$x/$y out of range" }
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if (tx and s != 0L) 1L else 0L
            val ry = if (ty and s != 0L) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) {
                    tx = n - 1 - tx
                    ty = n - 1 - ty
                }
                val t = tx; tx = ty; ty = t
            }
            s /= 2
        }
        return zoomBase(z) + d
    }

    fun fromKey(key: TileKey): Long = fromZxy(key.z, key.x, key.y)

    fun toKey(tileId: Long): TileKey {
        require(tileId >= 0) { "negative tile id" }
        var z = 0
        while (z < WebMercator.MAX_ZOOM && zoomBase(z + 1) <= tileId) z++
        require(tileId - zoomBase(z) < (1L shl (2 * z))) { "tile id $tileId beyond zoom ${WebMercator.MAX_ZOOM}" }
        val n = 1L shl z
        var t = tileId - zoomBase(z)
        var x = 0L
        var y = 0L
        var s = 1L
        while (s < n) {
            val rx = 1L and (t / 2)
            val ry = 1L and (t xor rx)
            if (ry == 0L) {
                if (rx == 1L) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val tmp = x; x = y; y = tmp
            }
            x += s * rx
            y += s * ry
            t /= 4
            s *= 2
        }
        return TileKey(z, x.toInt(), y.toInt())
    }
}
