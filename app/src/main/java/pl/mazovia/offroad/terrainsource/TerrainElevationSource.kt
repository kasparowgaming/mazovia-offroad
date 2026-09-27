package pl.mazovia.offroad.terrainsource

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.mazovia.offroad.terrain.dem.BitmapFactoryTileImageDecoder
import pl.mazovia.offroad.terrain.dem.PmtilesElevationSampler
import pl.mazovia.offroad.terrain.dem.PmtilesReader
import pl.mazovia.offroad.terrain.dem.TerrainRgbTileDecoder
import pl.mazovia.offroad.terrain.profile.RouteTerrainProfile
import pl.mazovia.offroad.terrain.profile.RouteTerrainProfileBuilder
import pl.mazovia.offroad.terrain.projection.RouteIndex
import java.io.Closeable
import java.io.File

/**
 * TA-007 terrain source seam (OD-1): exactly one archive at a fixed path; the header and DEM metadata are validated on
 * open. A missing or invalid archive yields no sampler, and TEREN shows its no-data state; map/navigation are unaffected.
 */
object TerrainElevationSource {
    const val ARCHIVE_NAME = "terrain_mazowsze_z15.pmtiles"
    private const val TAG = "TerrainSource"

    fun archiveFile(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(File(it, "terrain"), ARCHIVE_NAME) }

    /** Opens the archive, or null when it is absent or not a supported DEM. Performs IO: call off the main thread. */
    fun open(context: Context): PmtilesElevationSampler? {
        val file = archiveFile(context)?.takeIf { it.isFile } ?: run {
            Log.i(TAG, "no terrain archive at ${archiveFile(context)}")
            return null
        }
        val reader = try {
            PmtilesReader.open(file)
        } catch (e: Exception) {
            Log.w(TAG, "terrain archive rejected: ${e.message}")
            return null
        }
        return try {
            PmtilesElevationSampler(reader, TerrainRgbTileDecoder(BitmapFactoryTileImageDecoder()),
                diagnostics = { Log.w(TAG, it) })
        } catch (e: Exception) {
            reader.close()
            Log.w(TAG, "terrain archive rejected: ${e.message}")
            null
        }
    }
}

/**
 * Profile work for one route: [TerrainProfileSession] consumes the route index; UI code only passes the request
 * along and keys on [routeId], so it never handles route progress itself.
 */
class TerrainProfileRequest(val index: RouteIndex) {
    val routeId: String get() = index.routeId
}

/**
 * Owns one sampler (reader + tile cache) and builds a profile for the requested route. Single consumer; [close]
 * releases the archive. A failed build returns null (terrain failure never propagates to navigation).
 */
class TerrainProfileSession(private val context: Context) : Closeable {
    private val sampler = LazyCloseable { TerrainElevationSource.open(context) }

    suspend fun build(request: TerrainProfileRequest): RouteTerrainProfile? = withContext(Dispatchers.Default) {
        val route = request.index
        try {
            sampler.withResource { source ->
                val started = System.nanoTime()
                RouteTerrainProfileBuilder.build(route, source).also {
                    Log.i("TerrainSource", "profile ${route.routeId}: ${it.availableSamples}/${it.raw.size} samples " +
                        "with elevation, ${it.events.size} events, ${(System.nanoTime() - started) / 1_000_000} ms")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TerrainSource", "terrain profile build failed", e)
            null
        }
    }

    /** Safe from any thread (the view calls it on dispose, while a build may still run on another thread). */
    override fun close() = sampler.close()
}

/**
 * A resource opened on first [withResource] and used by one consumer at a time. [close] may come from another thread at any
 * moment: it never waits for a use in progress and never closes the resource under it; the use that is running then
 * closes it on exit, including a resource that was still being opened when [close] arrived (no leaked archive).
 */
internal class LazyCloseable<T : Closeable>(private val open: () -> T?) : Closeable {
    private val lock = Any()
    private var value: T? = null
    private var opened = false
    private var busy = false
    private var closed = false

    /** Runs [block] with the resource; null when it is absent (open returned null) or [close] has been called. */
    suspend fun <R> withResource(block: suspend (T) -> R): R? {
        val first = synchronized(lock) {
            if (closed) return null
            check(!busy) { "single consumer" }
            busy = true
            !opened.also { opened = true }
        }
        try {
            if (first) {
                val fresh = open() // IO outside the lock: close() never waits for it
                synchronized(lock) { value = fresh }
            }
            val resource = synchronized(lock) { if (closed) null else value } ?: return null
            return block(resource)
        } finally {
            val orphan = synchronized(lock) {
                busy = false
                if (closed) value.also { value = null } else null
            }
            orphan?.close()
        }
    }

    override fun close() {
        val idle = synchronized(lock) {
            closed = true
            if (busy) null else value.also { value = null }
        }
        idle?.close()
    }
}
