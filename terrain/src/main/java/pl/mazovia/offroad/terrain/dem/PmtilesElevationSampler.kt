package pl.mazovia.offroad.terrain.dem

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import pl.mazovia.offroad.terrain.elevation.ElevationSample
import pl.mazovia.offroad.terrain.elevation.ElevationSampler
import pl.mazovia.offroad.terrain.elevation.ElevationSourceMetadata
import pl.mazovia.offroad.terrain.elevation.GeoBounds
import pl.mazovia.offroad.terrain.elevation.UnavailableReason
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/** Outcome counts of one [PmtilesElevationSampler.prefetchTiles] call. */
data class PrefetchResult(val requested: Int, val alreadyCached: Int, val loaded: Int, val absent: Int, val corrupt: Int)

/**
 * Real [ElevationSampler] over a single-zoom Terrain-RGB PMTiles archive (DESIGN §11, TA-001B).
 *
 * - [sample] is cache-only and never does IO: tiles must be loaded by [prefetch]/[prefetchTiles] first
 *   (otherwise `Unavailable(NOT_LOADED)`).
 * - Interpolation (§11.2): bilinear over the 4 surrounding pixel centres in Web Mercator pixel space, using the
 *   neighbouring tile's block across tile edges (§11.3). If some of the 4 samples are nodata or lie in a tile the archive
 *   does not have (or that is corrupt): the nearest valid one of the 4 with confidence 0.5; none valid → NODATA.
 *   A neighbour tile that is merely not loaded yet → NOT_LOADED (never a timing-dependent fallback value). Corners
 *   with zero bilinear weight (point exactly on a pixel-centre row/column) are not needed and not consulted.
 * - Unavailable reasons: OUT_OF_COVERAGE (outside Web Mercator limits or the archive bounds), NOT_LOADED, NO_TILE
 *   (inside bounds, tile absent), CORRUPT (tile unreadable/undecodable; negatively cached, not retried), NODATA.
 * - IO on [ioDispatcher] (default IO limited to 2), PNG decode on [decodeDispatcher] (default Default limited to 2);
 *   concurrent requests for the same tile share one load; loads run in the sampler's own scope, so cancelling a caller
 *   never leaves a half-loaded tile; [close] cancels outstanding loads and closes the archive.
 * - Never smooths, clamps or substitutes values beyond the rule above; a missing sample is never 0 m.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PmtilesElevationSampler(
    private val reader: PmtilesReader,
    private val tileDecoder: TerrainRgbTileDecoder,
    val cache: DemTileCache = DemTileCache(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2),
    private val diagnostics: (String) -> Unit = {}
) : ElevationSampler, Closeable {

    val archive: DemArchiveMetadata
    val zoom: Int
    override val metadata: ElevationSourceMetadata

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val inFlight = ConcurrentHashMap<TileKey, Deferred<TileState>>()
    private val tilesPerAxis: Long
    private val worldPx: Long

    init {
        val h = reader.header
        if (h.tileType != PmtilesTileType.PNG) throw PmtilesUnsupportedException("tile type ${h.tileType}")
        if (h.tileCompression != PmtilesCompression.NONE && h.tileCompression != PmtilesCompression.GZIP)
            throw PmtilesUnsupportedException("tile compression ${h.tileCompression}")
        archive = DemArchiveMetadata.parse(reader.metadataJson(), h)
        zoom = archive.zoom
        tilesPerAxis = 1L shl zoom
        worldPx = tilesPerAxis * WebMercator.TILE_SIZE
        val midLat = (archive.coverage.south + archive.coverage.north) / 2.0
        metadata = ElevationSourceMetadata(
            sourceId = "gugik-nmt:${archive.buildId}",
            nominalResolutionM = WebMercator.groundSpacingM(zoom, midLat),
            verticalReference = archive.verticalDatum,
            description = "${archive.source} Terrain-RGB ${archive.encoding} z$zoom ${archive.horizontalGrid}, " +
                "source years ${archive.sourceYears ?: "unknown"}, PMTiles v${archive.pmtilesVersion}"
        )
    }

    override fun sample(latitude: Double, longitude: Double): ElevationSample {
        if (!WebMercator.isAddressable(latitude, longitude) || !archive.coverage.contains(latitude, longitude))
            return ElevationSample.Unavailable(UnavailableReason.OUT_OF_COVERAGE)
        val px = WebMercator.worldPxX(longitude, zoom)
        val py = WebMercator.worldPxY(latitude, zoom)
        val primary = tileOfPixel(floor(px).toLong(), floor(py).toLong())
        val primaryState = cache.get(primary) ?: return unavailable(UnavailableReason.NOT_LOADED)
        when (primaryState) {
            TileState.Absent -> return unavailable(UnavailableReason.NO_TILE)
            TileState.Corrupt -> return unavailable(UnavailableReason.CORRUPT)
            is TileState.Loaded -> Unit
        }
        val u = px - 0.5
        val v = py - 0.5
        val i0 = floor(u).toLong()
        val j0 = floor(v).toLong()
        val fx = u - i0
        val fy = v - j0
        // Corners with zero bilinear weight (point exactly on a pixel-centre row/column) are not needed and not read.
        val w00 = (1 - fx) * (1 - fy)
        val w10 = fx * (1 - fy)
        val w01 = (1 - fx) * fy
        val w11 = fx * fy
        val h00 = if (w00 == 0.0) SKIP else corner(i0, j0, primary, primaryState) ?: return unavailable(UnavailableReason.NOT_LOADED)
        val h10 = if (w10 == 0.0) SKIP else corner(i0 + 1, j0, primary, primaryState) ?: return unavailable(UnavailableReason.NOT_LOADED)
        val h01 = if (w01 == 0.0) SKIP else corner(i0, j0 + 1, primary, primaryState) ?: return unavailable(UnavailableReason.NOT_LOADED)
        val h11 = if (w11 == 0.0) SKIP else corner(i0 + 1, j0 + 1, primary, primaryState) ?: return unavailable(UnavailableReason.NOT_LOADED)
        if (!h00.isNaN() && !h10.isNaN() && !h01.isNaN() && !h11.isNaN()) {
            var h = 0.0
            if (w00 != 0.0) h += w00 * h00
            if (w10 != 0.0) h += w10 * h10
            if (w01 != 0.0) h += w01 * h01
            if (w11 != 0.0) h += w11 * h11
            return ElevationSample.Value(h, 1f)
        }
        // Nearest valid needed corner (distance in pixel units; ties resolved in the fixed order 00, 10, 01, 11).
        var best = Double.NaN
        var bestD = Double.MAX_VALUE
        fun consider(h: Double, weight: Double, dx: Double, dy: Double) {
            if (weight == 0.0 || h.isNaN()) return
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = h }
        }
        consider(h00, w00, fx, fy)
        consider(h10, w10, 1 - fx, fy)
        consider(h01, w01, fx, 1 - fy)
        consider(h11, w11, 1 - fx, 1 - fy)
        return if (best.isNaN()) unavailable(UnavailableReason.NODATA)
        else ElevationSample.Value(best, CONFIDENCE_SUBSTITUTED)
    }

    /** Height at global pixel (gi, gj); NaN = nodata / absent / corrupt / outside the world; null = tile not loaded. */
    private fun corner(gi: Long, gj: Long, primary: TileKey, primaryState: TileState): Double? {
        if (gi < 0 || gj < 0 || gi >= worldPx || gj >= worldPx) return Double.NaN
        val tx = (gi / WebMercator.TILE_SIZE).toInt()
        val ty = (gj / WebMercator.TILE_SIZE).toInt()
        val state = if (tx == primary.x && ty == primary.y) primaryState else cache.get(TileKey(zoom, tx, ty)) ?: return null
        return when (state) {
            is TileState.Loaded -> state.block.heightAt((gi % WebMercator.TILE_SIZE).toInt(), (gj % WebMercator.TILE_SIZE).toInt())
            else -> Double.NaN
        }
    }

    private fun tileOfPixel(px: Long, py: Long): TileKey {
        val tx = (px / WebMercator.TILE_SIZE).coerceIn(0, tilesPerAxis - 1).toInt()
        val ty = (py / WebMercator.TILE_SIZE).coerceIn(0, tilesPerAxis - 1).toInt()
        return TileKey(zoom, tx, ty)
    }

    /** Tiles covering [bounds] plus a ring of [ring] tiles (3×3 neighbourhood concept, DESIGN §11.3), clipped to the world. */
    fun tilesFor(bounds: GeoBounds, ring: Int = 1): List<TileKey> {
        val lat0 = bounds.north.coerceIn(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE)
        val lat1 = bounds.south.coerceIn(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE)
        val x0 = floor(WebMercator.worldPxX(bounds.west, zoom) / WebMercator.TILE_SIZE).toLong() - ring
        val x1 = floor(WebMercator.worldPxX(bounds.east, zoom) / WebMercator.TILE_SIZE).toLong() + ring
        val y0 = floor(WebMercator.worldPxY(lat0, zoom) / WebMercator.TILE_SIZE).toLong() - ring
        val y1 = floor(WebMercator.worldPxY(lat1, zoom) / WebMercator.TILE_SIZE).toLong() + ring
        val out = ArrayList<TileKey>()
        for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(tilesPerAxis - 1))
            for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(tilesPerAxis - 1)) out += TileKey(zoom, x.toInt(), y.toInt())
        return out
    }

    /** Loads the tiles for [bounds] (+1 tile ring). Throws [IllegalArgumentException] if they cannot fit the cache. */
    override suspend fun prefetch(bounds: GeoBounds) {
        prefetchTiles(tilesFor(bounds))
    }

    /**
     * Loads [keys] into the cache (bounded parallelism, de-duplicated with concurrent callers). Keys already cached,
     * including negative entries (absent/corrupt), are not re-read but have their LRU recency refreshed before any
     * load starts, so loads of this call cannot evict them. Suspends until all requested tiles are resolved.
     */
    suspend fun prefetchTiles(keys: Collection<TileKey>): PrefetchResult {
        val distinct = keys.distinct()
        require(distinct.size <= cache.maxBlocks) {
            "prefetch of ${distinct.size} tiles exceeds cache capacity ${cache.maxBlocks}; request a smaller window"
        }
        for (key in distinct) require(key.z == zoom) { "tile $key is not at archive zoom $zoom" }
        // cache.get (not contains) is an access: it moves already-cached tiles to the most-recent end (SR-002).
        val missing = distinct.filter { cache.get(it) == null }
        val cached = distinct.size - missing.size
        val pending = ArrayList<Deferred<TileState>>()
        for (key in missing) {
            val d = inFlight.computeIfAbsent(key) { scope.async(start = CoroutineStart.LAZY) { load(key) } }
            d.invokeOnCompletion { inFlight.remove(key, d) }
            d.start()
            pending += d
        }
        val states = pending.awaitAll()
        return PrefetchResult(
            requested = distinct.size, alreadyCached = cached,
            loaded = states.count { it is TileState.Loaded },
            absent = states.count { it == TileState.Absent },
            corrupt = states.count { it == TileState.Corrupt }
        )
    }

    private suspend fun load(key: TileKey): TileState {
        cache.get(key)?.let { return it }
        val bytes = try {
            reader.getTile(key.z, key.x, key.y)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return markCorrupt(key, "read failed: ${e.message}")
        } catch (e: RuntimeException) {
            return markCorrupt(key, "read failed: ${e.message}")
        }
        if (bytes == null) {
            cache.put(key, TileState.Absent)
            return TileState.Absent
        }
        val block = try {
            withContext(decodeDispatcher) { tileDecoder.decode(bytes) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TileDecodeException) {
            return markCorrupt(key, e.message ?: "decode failed")
        } catch (e: RuntimeException) {
            return markCorrupt(key, "decode failed: ${e.message}")
        }
        val state = TileState.Loaded(block)
        cache.put(key, state)
        return state
    }

    private fun markCorrupt(key: TileKey, why: String): TileState {
        diagnostics("terrain tile $key marked corrupt for this session: $why")
        cache.put(key, TileState.Corrupt)
        return TileState.Corrupt
    }

    private fun unavailable(reason: UnavailableReason) = ElevationSample.Unavailable(reason)

    override fun close() {
        scope.cancel()
        reader.close()
    }

    companion object {
        /** Confidence of a nearest-neighbour substituted sample (DESIGN §11.2 TARGET ×0.5). */
        const val CONFIDENCE_SUBSTITUTED = 0.5f

        /** Placeholder for a zero-weight corner (finite, never used in the sum). */
        private const val SKIP = 0.0
    }
}
