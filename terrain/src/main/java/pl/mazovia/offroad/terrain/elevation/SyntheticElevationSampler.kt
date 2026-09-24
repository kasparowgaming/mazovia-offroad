package pl.mazovia.offroad.terrain.elevation

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.terrain.geo.LocalFrame
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic synthetic terrain for tests and the future simulator (DESIGN §11.1). No IO.
 *
 * Height is a function of the signed distance `u` (metres) along an axis through [origin] with bearing
 * [axisBearingDeg] (0 = north, 90 = east). For a straight route laid along the same axis, `u` equals the
 * distance along the route from the origin.
 */
class SyntheticElevationSampler(
    val origin: GeoPoint,
    private val profile: SyntheticProfile,
    val axisBearingDeg: Double = 90.0,
    override val metadata: ElevationSourceMetadata = ElevationSourceMetadata(
        sourceId = "synthetic",
        nominalResolutionM = null,
        verticalReference = "synthetic",
        description = "Deterministic synthetic surface"
    )
) : ElevationSampler {
    private val frame = LocalFrame(origin)
    private val axisE = sin(Math.toRadians(axisBearingDeg))
    private val axisN = cos(Math.toRadians(axisBearingDeg))

    override fun sample(latitude: Double, longitude: Double): ElevationSample {
        val p = GeoPoint(latitude, longitude)
        val u = frame.eastM(p) * axisE + frame.northM(p) * axisN
        return profile.sample(u)
    }
}

/** 1-D synthetic height profile h(u) built from composable features. */
class SyntheticProfile private constructor(
    private val baseM: Double,
    private val features: List<Feature>,
    private val unavailable: List<ClosedFloatingPointRange<Double>>,
    private val reducedConfidence: List<Pair<ClosedFloatingPointRange<Double>, Float>>
) {
    fun sample(u: Double): ElevationSample {
        if (unavailable.any { u in it }) return ElevationSample.Unavailable(UnavailableReason.NODATA)
        val h = baseM + features.sumOf { it.contribution(u) }
        val confidence = reducedConfidence.filter { u in it.first }.minOfOrNull { it.second } ?: 1f
        return ElevationSample.Value(h, confidence)
    }

    fun heightAt(u: Double): Double? = (sample(u) as? ElevationSample.Value)?.heightM

    internal sealed interface Feature { fun contribution(u: Double): Double }

    /** Plateau-to-plateau ramp: 0 before [startU], grade·(u−start) inside, grade·length after. */
    private class Ramp(val startU: Double, val lengthM: Double, val grade: Double) : Feature {
        override fun contribution(u: Double) = grade * (u - startU).coerceIn(0.0, lengthM)
    }

    /** Constant slope everywhere (relative to u = 0). */
    private class Slope(val grade: Double) : Feature {
        override fun contribution(u: Double) = grade * u
    }

    /** Raised-cosine hill of [heightM] centred at [centerU] with total width [widthM]. */
    private class Hill(val centerU: Double, val widthM: Double, val heightM: Double) : Feature {
        override fun contribution(u: Double): Double {
            val x = (u - centerU) / (widthM / 2.0)
            return if (x <= -1.0 || x >= 1.0) 0.0 else heightM * 0.5 * (1.0 + cos(Math.PI * x))
        }
    }

    /** Narrow spike of [heightM] on |u − centerU| < halfWidthM. */
    private class Spike(val centerU: Double, val halfWidthM: Double, val heightM: Double) : Feature {
        override fun contribution(u: Double) = if (kotlin.math.abs(u - centerU) < halfWidthM) heightM else 0.0
    }

    /** Discontinuity: +[heightM] for u ≥ [atU]. */
    private class Step(val atU: Double, val heightM: Double) : Feature {
        override fun contribution(u: Double) = if (u >= atU) heightM else 0.0
    }

    class Builder(private val baseM: Double = 100.0) {
        private val features = mutableListOf<Feature>()
        private val unavailable = mutableListOf<ClosedFloatingPointRange<Double>>()
        private val reduced = mutableListOf<Pair<ClosedFloatingPointRange<Double>, Float>>()

        fun slope(grade: Double) = apply { features += Slope(grade) }
        fun ramp(startU: Double, lengthM: Double, grade: Double) = apply { features += Ramp(startU, lengthM, grade) }
        fun hill(centerU: Double, widthM: Double, heightM: Double) = apply { features += Hill(centerU, widthM, heightM) }
        fun spike(centerU: Double, halfWidthM: Double, heightM: Double) = apply { features += Spike(centerU, halfWidthM, heightM) }
        fun step(atU: Double, heightM: Double) = apply { features += Step(atU, heightM) }
        fun unavailable(fromU: Double, toU: Double) = apply { unavailable += fromU..toU }
        fun reducedConfidence(fromU: Double, toU: Double, confidence: Float) = apply { reduced += (fromU..toU) to confidence }
        fun build() = SyntheticProfile(baseM, features.toList(), unavailable.toList(), reduced.toList())
    }

    companion object {
        fun flat(heightM: Double = 100.0) = Builder(heightM).build()
    }
}
