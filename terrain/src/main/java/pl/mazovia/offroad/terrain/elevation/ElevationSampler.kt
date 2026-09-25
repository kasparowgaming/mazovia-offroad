package pl.mazovia.offroad.terrain.elevation

/** Why an elevation value is unavailable (DESIGN §11.1, §11.4). Never mapped to 0 m. */
enum class UnavailableReason { NOT_LOADED, NO_TILE, NODATA, CORRUPT, OUT_OF_COVERAGE }

/** Result of sampling the DEM at one location. */
sealed interface ElevationSample {
    /** @param confidence 0..1 (1 = all interpolation neighbours valid, DESIGN §11.5). */
    data class Value(val heightM: Double, val confidence: Float = 1f) : ElevationSample {
        init { require(!heightM.isNaN()) { "Use Unavailable instead of NaN heights" } }
    }
    data class Unavailable(val reason: UnavailableReason) : ElevationSample
}

/** Describes the elevation source behind a sampler. */
data class ElevationSourceMetadata(
    val sourceId: String,
    val nominalResolutionM: Double?,
    val verticalReference: String,
    val description: String
)

/** WGS84 bounding box in degrees (DESIGN §11.1 `prefetch(bounds)`). */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(south.isFinite() && west.isFinite() && north.isFinite() && east.isFinite()) { "non-finite bounds" }
        require(south <= north && west <= east) { "empty bounds" }
        require(south >= -90.0 && north <= 90.0 && west >= -180.0 && east <= 180.0) { "bounds out of range" }
    }

    companion object {
        /** Bounds of [points] (lat, lon pairs) expanded by [marginM] metres (spherical, R = 6 371 km). */
        fun around(latitudes: DoubleArray, longitudes: DoubleArray, marginM: Double = 0.0): GeoBounds {
            require(latitudes.isNotEmpty() && latitudes.size == longitudes.size)
            val s = latitudes.min()
            val n = latitudes.max()
            val dLat = Math.toDegrees(marginM / 6_371_000.0)
            val maxAbsLat = maxOf(kotlin.math.abs(s), kotlin.math.abs(n)) + dLat
            val dLon = dLat / kotlin.math.cos(Math.toRadians(minOf(maxAbsLat, 89.0)))
            return GeoBounds(
                (s - dLat).coerceAtLeast(-90.0), (longitudes.min() - dLon).coerceAtLeast(-180.0),
                (n + dLat).coerceAtMost(90.0), (longitudes.max() + dLon).coerceAtMost(180.0)
            )
        }
    }
}

/**
 * Renderer-independent elevation access (DESIGN §11).
 *
 * Contract (TA-000B-C1 / C2): returns what the source contains or [ElevationSample.Unavailable]; never smooths,
 * clamps or invents values. Nothing downstream writes back into a sampler.
 *
 * [sample] never blocks on IO: a file-backed sampler answers from its cache and returns
 * `Unavailable(NOT_LOADED)` for data that has not been prefetched. [prefetch] (TA-001B) loads what [sample] will need for
 * [bounds] off the caller's thread; in-memory samplers need no prefetch (default no-op).
 */
interface ElevationSampler {
    val metadata: ElevationSourceMetadata
    fun sample(latitude: Double, longitude: Double): ElevationSample
    suspend fun prefetch(bounds: GeoBounds) {}
}
