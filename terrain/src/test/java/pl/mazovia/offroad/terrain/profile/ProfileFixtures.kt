package pl.mazovia.offroad.terrain.profile

import pl.mazovia.offroad.terrain.elevation.ElevationSample
import pl.mazovia.offroad.terrain.elevation.SyntheticProfile

/** Profile builders on a 5 m grid. */
object ProfileFixtures {
    const val SPACING = 5.0

    fun raw(profile: SyntheticProfile, lengthM: Double, id: String = "p"): RawElevationProfile {
        val samples = (0..(lengthM / SPACING).toInt()).map { profile.sample(it * SPACING) }
        return RawElevationProfile.fromHeights(
            id,
            samples.map { (it as? ElevationSample.Value)?.heightM },
            confidences = samples.map { (it as? ElevationSample.Value)?.confidence ?: 0f }
        )
    }

    /** Piecewise-constant grade sections (lengthM to grade) starting at 100 m. */
    fun sections(vararg sections: Pair<Double, Double>): SyntheticProfile {
        val b = SyntheticProfile.Builder(100.0)
        var u = 0.0
        for ((len, g) in sections) {
            b.ramp(u, len, g)
            u += len
        }
        return b.build()
    }

    data class Pipeline(
        val raw: RawElevationProfile,
        val anomalies: List<AnomalySpan>,
        val filtered: FilteredElevationProfile,
        val grade: GradeProfile,
        val events: List<GradeEvent>
    )

    fun pipeline(raw: RawElevationProfile): Pipeline {
        val anomalies = ProfileAnomalyDetector().detect(raw)
        val filtered = FilteredElevationProfile.from(raw)
        val grade = GradeProfile.from(filtered, anomalies)
        return Pipeline(raw, anomalies, filtered, grade, GradeEventDetector().detect(grade))
    }

    fun snapshot(raw: RawElevationProfile): List<Any?> =
        (0 until raw.size).flatMap { listOf(raw.distanceAt(it), raw.heightAt(it), raw.unavailableReasonAt(it), raw.confidenceAt(it)) }
}
