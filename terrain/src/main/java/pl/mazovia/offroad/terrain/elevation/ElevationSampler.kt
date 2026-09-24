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

/**
 * Renderer-independent elevation access (DESIGN §11).
 *
 * Contract (TA-000B-C1 / C2): returns what the source contains or [ElevationSample.Unavailable]; never smooths,
 * clamps or invents values. Nothing downstream writes back into a sampler.
 */
interface ElevationSampler {
    val metadata: ElevationSourceMetadata
    fun sample(latitude: Double, longitude: Double): ElevationSample
}
