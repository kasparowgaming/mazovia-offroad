package pl.mazovia.offroad.terrain.profile

/**
 * Presentation-only heights for the instrument/renderer (DESIGN §12, §14.4).
 *
 * Derived from [FilteredElevationProfile]; heights are relative to the lowest available filtered height so the UI
 * can scale them. Never a source for grade, events, the sampler or any other profile layer. Consumers must not draw a
 * continuous line between samples of different parts ([partIndexAt] / [isBreakAfter]): that is a GPX break.
 */
class DisplayElevationProfile private constructor(
    val filteredConfigId: String,
    val baselineM: Double?,
    private val distanceM: DoubleArray,
    private val relativeHeightM: FloatArray,
    private val partIndex: IntArray
) {
    val size: Int get() = distanceM.size

    fun distanceAt(i: Int): Double = distanceM[i]
    fun partIndexAt(i: Int): Int = partIndex[i]

    /** True when sample [i] and [i]+1 are on different sides of a GPX break. */
    fun isBreakAfter(i: Int): Boolean = i + 1 < size && partIndex[i] != partIndex[i + 1]

    /** Height above [baselineM] in metres, or null where the filtered profile is unavailable. */
    fun relativeHeightAt(i: Int): Float? = relativeHeightM[i].takeUnless { it.isNaN() }

    companion object {
        fun from(filtered: FilteredElevationProfile): DisplayElevationProfile {
            val n = filtered.size
            val baseline = (0 until n).mapNotNull { filtered.heightAt(it) }.minOrNull()
            return DisplayElevationProfile(
                filteredConfigId = filtered.config.id,
                baselineM = baseline,
                distanceM = DoubleArray(n) { filtered.distanceAt(it) },
                relativeHeightM = FloatArray(n) { i ->
                    val h = filtered.heightAt(i)
                    if (h == null || baseline == null) Float.NaN else (h - baseline).toFloat()
                },
                partIndex = IntArray(n) { filtered.partIndexAt(it) }
            )
        }
    }
}
