package pl.mazovia.offroad.terrain.profile

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import pl.mazovia.offroad.terrain.dem.PmtilesElevationSampler
import pl.mazovia.offroad.terrain.dem.TileKey
import pl.mazovia.offroad.terrain.elevation.ElevationSample
import pl.mazovia.offroad.terrain.elevation.ElevationSampler
import pl.mazovia.offroad.terrain.elevation.ElevationSourceMetadata
import pl.mazovia.offroad.terrain.elevation.GeoBounds
import pl.mazovia.offroad.terrain.elevation.UnavailableReason
import pl.mazovia.offroad.terrain.projection.RouteIndex

/**
 * Complete terrain layers for one route (TA-007): Raw → Filtered → Grade plus detected events.
 * Bound to [routeId]; a consumer must never apply it to another route (no stale profile after reroute).
 */
class RouteTerrainProfile(
    val routeId: String,
    val raw: RawElevationProfile,
    val grade: GradeProfile,
    val events: List<GradeEvent>
) {
    val availableSamples: Int get() = (0 until raw.size).count { raw.isAvailable(it) }

    companion object {
        /** The standard layer pipeline over an already sampled [raw] profile. */
        fun from(raw: RawElevationProfile): RouteTerrainProfile {
            val grade = GradeProfile.from(FilteredElevationProfile.from(raw), ProfileAnomalyDetector().detect(raw))
            return RouteTerrainProfile(raw.routeId, raw, grade, GradeEventDetector().detect(grade))
        }
    }
}

/**
 * Builds a [RouteTerrainProfile] for a whole route from a PMTiles sampler (TA-007 prototype).
 *
 * [PmtilesElevationSampler.sample] is cache-only and the cache is bounded, so sampling runs in chunks sized by the
 * tile budget (not by kilometres): the sample positions are first enumerated exactly as [RawElevationProfile.sample]
 * produces them, each chunk's tiles are prefetched and its positions sampled, and the profile is then assembled from
 * the recorded results. Positions outside the archive coverage load nothing and stay OUT_OF_COVERAGE (never 0 m).
 * Cancellation is checked between chunks; a cancelled build throws and publishes nothing.
 */
object RouteTerrainProfileBuilder {

    suspend fun build(
        index: RouteIndex,
        sampler: PmtilesElevationSampler,
        spacingM: Double = RawElevationProfile.DEFAULT_SPACING_M
    ): RouteTerrainProfile {
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        RawElevationProfile.sample(index, recorder(sampler.metadata) { la, lo -> lat += la; lon += lo }, spacingM)

        val results = arrayOfNulls<ElevationSample>(lat.size)
        val budget = sampler.cache.maxBlocks
        val coverage = sampler.archive.coverage
        var i = 0
        while (i < lat.size) {
            currentCoroutineContext().ensureActive()
            val keys = LinkedHashSet<TileKey>()
            val start = i
            while (i < lat.size) {
                val need = if (coverage.contains(lat[i], lon[i]))
                    sampler.tilesFor(GeoBounds(lat[i], lon[i], lat[i], lon[i]), ring = 1) else emptyList()
                if (i > start && keys.size + need.count { it !in keys } > budget) break
                keys += need
                i++
            }
            if (keys.isNotEmpty()) sampler.prefetchTiles(keys)
            for (k in start until i) results[k] = sampler.sample(lat[k], lon[k])
        }
        currentCoroutineContext().ensureActive()

        var next = 0
        val raw = RawElevationProfile.sample(index, object : ElevationSampler {
            override val metadata = sampler.metadata
            override fun sample(latitude: Double, longitude: Double): ElevationSample {
                check(latitude == lat[next] && longitude == lon[next]) { "sample positions changed between passes" }
                return results[next++]!!
            }
        }, spacingM)
        check(next == results.size)
        return RouteTerrainProfile.from(raw)
    }

    private fun recorder(meta: ElevationSourceMetadata, record: (Double, Double) -> Unit) = object : ElevationSampler {
        override val metadata = meta
        override fun sample(latitude: Double, longitude: Double): ElevationSample {
            record(latitude, longitude)
            return ElevationSample.Unavailable(UnavailableReason.NOT_LOADED)
        }
    }
}
