package pl.mazovia.offroad.ui.map.components

import pl.mazovia.offroad.domain.model.GeoPoint

/** Shortest glide between two fixes. */
internal const val MARKER_MIN_GLIDE_MS = 300L
/** Longest glide; a longer gap between fixes (e.g. standing still) is not stretched further. */
internal const val MARKER_MAX_GLIDE_MS = 2_000L
/** Glide used for the first move and when a fix arrives after a long pause. */
internal const val MARKER_DEFAULT_GLIDE_MS = 1_000L
/** Jumps longer than this are shown immediately instead of gliding across the map. */
internal const val MARKER_SNAP_METERS = 250.0

/**
 * Display-only motion of the position marker: glides linearly from where it is drawn now to each new fix, over
 * roughly the time between fixes, so the dot keeps moving until the next fix arrives. Never alters the fix itself.
 * The follow camera uses the same [durationMs], so the dot stays at the camera focal point while gliding.
 */
internal class MarkerMotion {
    var target: GeoPoint? = null
        private set
    private var from: GeoPoint? = null
    private var startMs = 0L
    var durationMs = 0L
        private set
    private var lastFixMs: Long? = null

    /** Starts a glide to [newTarget]; no-op when it is the current target. Returns true when the target changed. */
    fun update(newTarget: GeoPoint?, nowMs: Long): Boolean {
        if (newTarget == target) return false
        if (newTarget == null) {
            reset()
            return true
        }
        val current = positionAt(nowMs)
        val gap = lastFixMs?.let { nowMs - it }
        lastFixMs = nowMs
        durationMs = when {
            current == null || current.distanceTo(newTarget) > MARKER_SNAP_METERS -> 0L
            gap == null || gap > MARKER_MAX_GLIDE_MS * 2 -> MARKER_DEFAULT_GLIDE_MS
            else -> gap.coerceIn(MARKER_MIN_GLIDE_MS, MARKER_MAX_GLIDE_MS)
        }
        from = current ?: newTarget
        target = newTarget
        startMs = nowMs
        return true
    }

    fun positionAt(nowMs: Long): GeoPoint? {
        val to = target ?: return null
        val start = from ?: return to
        val t = remainingFraction(nowMs).let { 1.0 - it }
        if (t >= 1.0) return to
        return GeoPoint(
            latitude = start.latitude + (to.latitude - start.latitude) * t,
            longitude = start.longitude + (to.longitude - start.longitude) * t
        )
    }

    fun remainingMs(nowMs: Long): Long = (startMs + durationMs - nowMs).coerceIn(0L, durationMs)

    fun isGliding(nowMs: Long): Boolean = target != null && remainingMs(nowMs) > 0

    private fun remainingFraction(nowMs: Long): Double =
        if (durationMs <= 0L) 0.0 else remainingMs(nowMs).toDouble() / durationMs

    fun reset() {
        target = null
        from = null
        durationMs = 0L
        lastFixMs = null
    }
}
