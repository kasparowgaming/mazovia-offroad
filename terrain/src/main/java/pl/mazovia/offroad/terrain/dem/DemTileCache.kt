package pl.mazovia.offroad.terrain.dem

/** Cached outcome of loading one tile. */
sealed interface TileState {
    class Loaded(val block: DemBlock) : TileState
    /** Archive has no such tile (NO_TILE). */
    data object Absent : TileState
    /** Unreadable/undecodable tile: marked bad for the session, never retried in a loop (DESIGN §11.4). */
    data object Corrupt : TileState
}

/**
 * Bounded LRU of decoded DEM blocks (DESIGN §18.2): positive entries are limited by retained bytes
 * ([DemBlock.RETAINED_BYTES] each; default 16 MiB → 63 blocks), negative entries (Absent/Corrupt) by count.
 * Access order = LRU; eviction is deterministic for a given access sequence. All methods are synchronized; blocks are
 * immutable, so readers may use a returned block without holding the lock.
 */
class DemTileCache(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxNegativeEntries: Int = 4096
) {
    private val loaded = LinkedHashMap<TileKey, TileState.Loaded>(64, 0.75f, true)
    private val negative = object : LinkedHashMap<TileKey, TileState>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, TileState>?) = size > maxNegativeEntries
    }
    private var evictions = 0L

    val maxBlocks: Int get() = (maxBytes / DemBlock.RETAINED_BYTES).toInt()

    init { require(maxBlocks >= 9) { "cache must hold at least a 3x3 neighbourhood" } }

    /** Lookup that counts as an access: refreshes the entry's LRU recency. */
    @Synchronized
    fun get(key: TileKey): TileState? = loaded[key] ?: negative[key]

    @Synchronized
    fun put(key: TileKey, state: TileState) {
        when (state) {
            is TileState.Loaded -> {
                negative.remove(key)
                loaded[key] = state
                while (loaded.size > maxBlocks) {
                    val eldest = loaded.keys.iterator().next()
                    loaded.remove(eldest)
                    evictions++
                }
            }
            else -> {
                loaded.remove(key)
                negative[key] = state
            }
        }
    }

    /** Membership test only: unlike [get], does not refresh the entry's LRU recency. */
    @Synchronized
    fun contains(key: TileKey): Boolean = loaded.containsKey(key) || negative.containsKey(key)

    @Synchronized
    fun clear() {
        loaded.clear()
        negative.clear()
    }

    @Synchronized
    fun stats(): CacheStats = CacheStats(loaded.size, negative.size, loaded.size * DemBlock.RETAINED_BYTES, evictions)

    data class CacheStats(val blocks: Int, val negativeEntries: Int, val retainedBytes: Long, val evictions: Long)

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 16L shl 20
    }
}
