package pl.mazovia.offroad.ui.riding.terrain

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.mazovia.offroad.domain.model.ManeuverType
import pl.mazovia.offroad.terrain.presentation.CorridorGeometry
import pl.mazovia.offroad.terrain.presentation.GradeBand
import pl.mazovia.offroad.terrain.presentation.SurfaceBand
import pl.mazovia.offroad.terrain.presentation.TerrainGrid
import pl.mazovia.offroad.terrain.profile.GradeEventType
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Option D TARGETS (DESIGN §3.7), tuned on the S25 in TA-007B. */
object CorridorTargets {
    const val CAMERA_BEHIND_M = 18.0
    const val CAMERA_HEIGHT_M = 9f
    const val LOOK_AHEAD_M = 75.0
    const val FOCAL_FACTOR = 1.05f
    /** Landscape: the focal length is limited by the height so the rider stays on screen. */
    const val MAX_FOCAL_ASPECT = 1.2f
    /** Screen y of the look-at point as a fraction of the height. */
    const val LOOK_AT_Y = 0.42f
    const val NEAR_M = 1.0f
    const val ROAD_WIDTH_M = 4.8f
    const val RIBBON_LIFT_M = 0.15f
    const val SHOULDER_M = 26f
    const val POST_SPACING_M = 25.0
    const val GATE_SPACING_M = 100.0
    const val GATE_LABEL_MAX_M = 400.0
    const val FOG_START_M = 120.0
    const val FOG_END_M = 640.0
    /** C3 grid: route segments hidden behind a crest are drawn over the terrain with this opacity. */
    const val CREST_ROUTE_ALPHA = 0.55f
    /** Grid cells whose centre lies farther than this behind the near plane cannot reach the view. */
    const val GRID_BEHIND_M = 30f
    /** §16.1 route changed: the "ładowanie" label appears only when the frozen view lasts longer than this. */
    const val RELOAD_LABEL_DELAY_MS = 1000L
}

/**
 * Chase-camera pose and pinhole projection (DESIGN §3.7, §15.1). Scene frame: x = east, y = north, z = up (metres,
 * exaggerated heights). Points closer than [CorridorTargets.NEAR_M] along the view direction are behind the camera
 * for drawing purposes: [project] rejects them and callers clip at that plane, so nothing behind the camera is drawn.
 */
class CorridorCamera(
    val eyeX: Float, val eyeY: Float, val eyeZ: Float,
    targetX: Float, targetY: Float, targetZ: Float,
    val width: Float, val height: Float
) {
    val focal = CorridorTargets.FOCAL_FACTOR * min(width, CorridorTargets.MAX_FOCAL_ASPECT * height)
    val cx = width / 2f
    val cy = height * CorridorTargets.LOOK_AT_Y
    private val fx: Float
    private val fy: Float
    private val fz: Float
    private val rx: Float
    private val ry: Float
    private val ux: Float
    private val uy: Float
    private val uz: Float

    init {
        var dx = targetX - eyeX
        var dy = targetY - eyeY
        var dz = targetZ - eyeZ
        val plan = sqrt(dx * dx + dy * dy)
        if (plan < 1e-3f) { dx = 0f; dy = 1f; dz = 0f } // degenerate look-at: face north, level
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        fx = dx / len; fy = dy / len; fz = dz / len
        // right = forward × up(0, 0, 1), normalised in plan
        val rl = sqrt(fy * fy + fx * fx)
        rx = fy / rl; ry = -fx / rl
        // up = right × forward
        ux = ry * fz
        uy = -rx * fz
        uz = rx * fy - ry * fx
    }

    /** Distance of a point in front of the camera along the view direction. */
    fun depth(x: Float, y: Float, z: Float): Float = (x - eyeX) * fx + (y - eyeY) * fy + (z - eyeZ) * fz

    /** Projects a scene point into [out] at [at] (x, y); false (and [out] untouched) behind the near plane. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray, at: Int = 0): Boolean {
        val dx = x - eyeX
        val dy = y - eyeY
        val dz = z - eyeZ
        val d = dx * fx + dy * fy + dz * fz
        if (d < CorridorTargets.NEAR_M) return false
        out[at] = cx + focal * (dx * rx + dy * ry) / d
        out[at + 1] = cy - focal * (dx * ux + dy * uy + dz * uz) / d
        return true
    }

    /** Screen y of the horizon (directions parallel to the ground plane). */
    val horizonY: Float get() {
        val pl = sqrt(fx * fx + fy * fy)
        val hx = fx / pl
        val hy = fy / pl
        val forward = hx * fx + hy * fy
        return cy - focal * (hx * ux + hy * uy) / forward
    }

    companion object {
        /** The §3.7 chase pose for [riderM] on [geometry]; null when the geometry cannot place the camera. */
        fun chase(geometry: CorridorGeometry, riderM: Double, width: Float, height: Float): CorridorCamera? {
            if (geometry.size < 2 || width <= 0f || height <= 0f) return null
            val eye = FloatArray(3)
            val target = FloatArray(3)
            geometry.pointAt(riderM - CorridorTargets.CAMERA_BEHIND_M, eye)
            geometry.pointAt(riderM + CorridorTargets.LOOK_AHEAD_M, target)
            return CorridorCamera(eye[0], eye[1], eye[2] + CorridorTargets.CAMERA_HEIGHT_M,
                target[0], target[1], target[2], width, height)
        }
    }
}

/**
 * Scene point (east, north, scene height) at [d] on the corridor axis, linear between samples and extrapolated along
 * the first/last sample pair beyond the window (the route start/end), so the camera stays behind the rider there.
 */
fun CorridorGeometry.pointAt(d: Double, out: FloatArray) {
    val i = indexAtOrBefore(d).coerceIn(0, size - 2)
    var j = i + 1
    var a = i
    // A zero-length pair (a GPX break) gives no direction: step to the previous sample.
    if (distanceAt(j) - distanceAt(a) < 1e-6 && a > 0) { a -= 1; j = a + 1 }
    val span = distanceAt(j) - distanceAt(a)
    val t = if (span < 1e-6) 0f else ((d - distanceAt(a)) / span).toFloat()
    val clamped = when {
        a == 0 && t < 0f -> t
        j == size - 1 && t > 1f -> t
        else -> t.coerceIn(0f, 1f)
    }
    out[0] = eastAt(a) + (eastAt(j) - eastAt(a)) * clamped
    out[1] = northAt(a) + (northAt(j) - northAt(a)) * clamped
    out[2] = sceneHeightAt(a) + (sceneHeightAt(j) - sceneHeightAt(a)) * clamped
}

private object CorridorColors {
    val skyTop = Color(0xFF08131B)
    val fog = Color(0xFF2A4050)
    val ground = Color(0xFF2E4A37)
    val gapFill = Color(0xFF30363D)
    val gapOutline = Color(0xFFC9D1D9)
    val edge = Color(0xFFF2F5F7)
    val climb = Color(0xFFFFC53D)
    val steep = Color(0xFFFF5147)
    val descent = Color(0xFF4FB3FF)
    val route = Color(0xFF00E5FF)
    val routeOutline = Color(0xFF06141B)
    val post = Color(0xFFE6EBEF)
    val sign = Color(0xFF0E2A36)
    /** C3 terrain grid base colour, scaled by the cell light. */
    val terrain = Color(0xFF4F7A55)

    fun surface(band: SurfaceBand): Color = when (band) {
        SurfaceBand.PAVED -> Color(0xFF50565E)
        SurfaceBand.GRAVEL -> Color(0xFF8B7B62)
        SurfaceBand.DIRT -> Color(0xFF7A5A3C)
        SurfaceBand.SAND -> Color(0xFFB59A62)
        SurfaceBand.GRASS -> Color(0xFF5E7F3E)
        SurfaceBand.UNKNOWN -> Color(0xFF6B7178)
    }

    fun grade(band: GradeBand): Color = when (band) {
        GradeBand.NONE -> edge
        GradeBand.CLIMB -> climb
        GradeBand.STEEP -> steep
        GradeBand.DESCENT -> descent
    }
}

internal fun surfaceLabel(band: SurfaceBand): String = when (band) {
    SurfaceBand.PAVED -> "utwardzona"
    SurfaceBand.GRAVEL -> "szuter"
    SurfaceBand.DIRT -> "grunt"
    SurfaceBand.SAND -> "piasek"
    SurfaceBand.GRASS -> "trawa"
    SurfaceBand.UNKNOWN -> "nieznana"
}

internal fun maneuverGlyph(type: ManeuverType): String = when (type) {
    ManeuverType.TURN_LEFT, ManeuverType.TURN_SHARP_LEFT -> "↰"
    ManeuverType.TURN_RIGHT, ManeuverType.TURN_SHARP_RIGHT -> "↱"
    ManeuverType.TURN_SLIGHT_LEFT, ManeuverType.FORK_LEFT, ManeuverType.KEEP_LEFT -> "↖"
    ManeuverType.TURN_SLIGHT_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.KEEP_RIGHT -> "↗"
    ManeuverType.ROUNDABOUT -> "⟳"
    ManeuverType.U_TURN -> "↶"
    ManeuverType.ARRIVE -> "⚑"
    ManeuverType.WAYPOINT -> "◆"
    ManeuverType.DEPART, ManeuverType.STRAIGHT, ManeuverType.UNKNOWN -> "↑"
}

/**
 * Rolling draw-time statistics, logged every [LOG_EVERY] frames in debuggable builds only. It measures the CPU time of
 * the draw lambda (projection + recording of the display list) on the UI thread; RenderThread and GPU time are not
 * included, so it is a lower bound for the DESIGN §21 D column budget (projection + draw p95 ≤ 10 ms). Frame-level
 * evidence comes from `adb shell dumpsys gfxinfo <package>` during a ride.
 */
private class DrawTimer(private val log: Boolean) {
    private val samples = LongArray(LOG_EVERY)
    private var count = 0

    fun record(nanos: Long) {
        if (!log) return
        samples[count++] = nanos
        if (count == LOG_EVERY) {
            val sorted = samples.sortedArray()
            Log.i("TerrainCorridor", "draw CPU ms p50=%.2f p95=%.2f max=%.2f (n=%d)".format(
                sorted[LOG_EVERY / 2] / 1e6, sorted[(LOG_EVERY * 95) / 100] / 1e6, sorted.last() / 1e6, LOG_EVERY))
            count = 0
        }
    }

    companion object { const val LOG_EVERY = 120 }
}

/**
 * Screen-space triangle batch drawn with one `Canvas.drawVertices` call (API 29+ with hardware acceleration).
 * Triangles keep submission order, so a batch preserves the painter's order of the polygons and lines put into it.
 */
private class TriangleBatch {
    private var verts = FloatArray(6 * 2048)
    private var colors = IntArray(3 * 2048)
    private var vertices = 0
    private val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }

    private fun room(extra: Int) {
        if ((vertices + extra) * 2 <= verts.size) return
        val size = max(verts.size * 2, (vertices + extra) * 2)
        verts = verts.copyOf(size)
        colors = colors.copyOf(size / 2)
    }

    fun triangle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, color: Int) {
        room(3)
        var k = vertices * 2
        verts[k++] = ax; verts[k++] = ay; verts[k++] = bx; verts[k++] = by; verts[k++] = cx; verts[k] = cy
        colors[vertices++] = color; colors[vertices++] = color; colors[vertices++] = color
    }

    /** Line of constant screen [width], extended by half the width at both ends so consecutive segments join. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-3f) return
        val ux = dx / len * width / 2f
        val uy = dy / len * width / 2f
        val ax = x0 - ux - uy; val ay = y0 - uy + ux
        val bx = x0 - ux + uy; val by = y0 - uy - ux
        val cx = x1 + ux + uy; val cy = y1 + uy - ux
        val ex = x1 + ux - uy; val ey = y1 + uy + ux
        triangle(ax, ay, bx, by, cx, cy, color)
        triangle(ax, ay, cx, cy, ex, ey, color)
    }

    fun flush(canvas: android.graphics.Canvas) {
        if (vertices == 0) return
        canvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, vertices * 2, verts, 0, null, 0,
            colors, 0, null, 0, 0, paint)
        vertices = 0
    }
}

/**
 * Per-view painter: preallocated scratch buffers, per-geometry plan normals, cached text layouts.
 * Painter's order far → near per ~3 m segment (DESIGN §3.7). Two passes: (1) ground shoulders and ribbon for every
 * segment, so nearer terrain covers what lies behind a crest; (2) ribbon and lines again, then objects, for segments not hidden
 * behind a crest, so a nearer segment's wide shoulder never covers the road beyond a bend.
 * With a C3 terrain grid (DESIGN §0.3) the grid is drawn first as the ground, far → near by cell depth; pass 1 is then
 * replaced by a translucent route line for the segments behind a crest, and pass 2 follows unchanged.
 */
private class CorridorPainter(private val textMeasurer: TextMeasurer, logTimes: Boolean) {
    private var normalsFor: CorridorGeometry? = null
    private var nx = FloatArray(0)
    private var ny = FloatArray(0)
    private var screenY = FloatArray(0)
    private var visible = BooleanArray(0)
    private val path = Path()
    private val batch = TriangleBatch()
    /** Ground, ribbons and lines go into [batch] (one draw call per pass) where the platform supports it. */
    private val useBatch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private var batching = false
    private val p = FloatArray(16)
    private val clip = FloatArray(24)
    private val screen = FloatArray(16)
    private val q = FloatArray(12)
    private val tmp = FloatArray(3)
    private val labels = HashMap<String, TextLayoutResult>()
    val timer = DrawTimer(logTimes)
    // Label/sign queue (see tags()).
    private val tagText = arrayOfNulls<TextLayoutResult>(MAX_TAGS)
    private val tagBackground = arrayOfNulls<Color>(MAX_TAGS)
    /** Text colour applied at draw time (layouts are measured once, independent of fog). */
    private val tagColor = arrayOfNulls<Color>(MAX_TAGS)
    private val tagLeft = FloatArray(MAX_TAGS)
    private val tagTop = FloatArray(MAX_TAGS)
    private val tagW = FloatArray(MAX_TAGS)
    private val tagH = FloatArray(MAX_TAGS)
    private val tagFog = FloatArray(MAX_TAGS)
    private val tagPad = FloatArray(MAX_TAGS)
    private val tagDrawn = BooleanArray(MAX_TAGS)
    private var tagCount = 0
    // C3 grid nodes in the scene frame of [gridGeometry] (the grid may come from an earlier window of the same profile).
    private var gridFor: TerrainGrid? = null
    private var gridGeometry: CorridorGeometry? = null
    private var gx = FloatArray(0)
    private var gy = FloatArray(0)
    private var gz = FloatArray(0)
    /** Cells sorted far → near: (inverted depth bits << 32) | cell. */
    private var order = LongArray(0)

    private companion object {
        /** Clip plane offset in front of the near plane (float rounding margin). */
        const val CLIP_MARGIN_M = 0.01f
        const val MAX_TAGS = 24
    }

    private fun label(text: String, sizeSp: Int, bold: Boolean = false, color: Color = Color.White): TextLayoutResult =
        labels.getOrPut("$sizeSp|$bold|${color.value}|$text") {
            if (labels.size > 256) labels.clear()
            textMeasurer.measure(text, TextStyle(color = color, fontSize = sizeSp.sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
        }

    private fun prepare(g: CorridorGeometry) {
        if (normalsFor === g) return
        normalsFor = g
        val n = g.size
        nx = FloatArray(n); ny = FloatArray(n); screenY = FloatArray(n); visible = BooleanArray(n)
        for (i in 0 until n) {
            // Averaged direction of the adjacent segments within the same part (a GPX break ends a part).
            val prev = if (i > 0 && !g.isBreakBefore(i)) i - 1 else i
            val next = if (i + 1 < n && !g.isBreakBefore(i + 1)) i + 1 else i
            var dx = g.eastAt(next) - g.eastAt(prev)
            var dy = g.northAt(next) - g.northAt(prev)
            var len = sqrt(dx * dx + dy * dy)
            if (len < 1e-4f && i > 0) { dx = -ny[i - 1]; dy = nx[i - 1]; len = 1f } // reuse the previous direction
            if (len < 1e-4f) { dx = 0f; dy = 1f; len = 1f }
            // Left normal of the travel direction.
            nx[i] = -dy / len
            ny[i] = dx / len
        }
    }

    /** Places [grid]'s nodes in [g]'s scene frame ([TerrainGrid.placeIn]). Once per (grid, window) pair. */
    private fun prepareGrid(grid: TerrainGrid, g: CorridorGeometry) {
        if (gridFor === grid && gridGeometry === g) return
        val n = grid.nodeCount
        if (gx.size < n) { gx = FloatArray(n); gy = FloatArray(n); gz = FloatArray(n) }
        if (order.size < grid.cellCount) order = LongArray(grid.cellCount)
        grid.placeIn(g, gx, gy, gz)
        gridFor = grid
        gridGeometry = g
    }

    /** C3 terrain grid, far → near by the camera depth of each cell centre (painter's algorithm, no depth test). */
    private fun DrawScope.grid(camera: CorridorCamera, grid: TerrainGrid, greyed: Boolean) {
        val cols = grid.cols
        var n = 0
        for (k in 0 until grid.cellCount) {
            val a = grid.cellNode(k)
            val d = a + cols + 1
            val depth = camera.depth((gx[a] + gx[d]) * 0.5f, (gy[a] + gy[d]) * 0.5f, (gz[a] + gz[d]) * 0.5f)
            if (depth < CorridorTargets.NEAR_M - CorridorTargets.GRID_BEHIND_M) continue
            // Non-negative float bits order like ints; far first = ascending on the inverted key.
            val key = java.lang.Float.floatToRawIntBits(max(depth, 0f)).toLong()
            order[n++] = ((Int.MAX_VALUE.toLong() - key) shl 32) or k.toLong()
        }
        java.util.Arrays.sort(order, 0, n)
        val range = (CorridorTargets.FOG_END_M - CorridorTargets.FOG_START_M).toFloat()
        val terrain = CorridorColors.terrain
        for (m in 0 until n) {
            val k = (order[m] and 0xFFFFFFFFL).toInt()
            val a = grid.cellNode(k)
            val b = a + 1
            val c = a + cols
            val d = c + 1
            // Quad a → b → d → c.
            p[0] = gx[a]; p[1] = gy[a]; p[2] = gz[a]
            p[3] = gx[b]; p[4] = gy[b]; p[5] = gz[b]
            p[6] = gx[d]; p[7] = gy[d]; p[8] = gz[d]
            p[9] = gx[c]; p[10] = gy[c]; p[11] = gz[c]
            val ex = (gx[a] + gx[d]) * 0.5f - camera.eyeX
            val ey = (gy[a] + gy[d]) * 0.5f - camera.eyeY
            val fog = ((sqrt(ex * ex + ey * ey) - CorridorTargets.FOG_START_M.toFloat()) / range).coerceIn(0f, 1f)
            val l = grid.lightAt(k)
            val lit = Color((terrain.red * l).coerceIn(0f, 1f), (terrain.green * l).coerceIn(0f, 1f),
                (terrain.blue * l).coerceIn(0f, 1f))
            polygon(camera, 4, tone(lit, fog, greyed))
        }
    }

    private fun fogT(g: CorridorGeometry, i: Int, riderM: Double): Float =
        (((g.distanceAt(i) - riderM) - CorridorTargets.FOG_START_M) /
            (CorridorTargets.FOG_END_M - CorridorTargets.FOG_START_M)).toFloat().coerceIn(0f, 1f)

    private fun tone(color: Color, fog: Float, greyed: Boolean): Color {
        val base = if (greyed) {
            val l = 0.3f * color.red + 0.59f * color.green + 0.11f * color.blue
            Color(l * 0.8f, l * 0.8f, l * 0.8f, color.alpha)
        } else color
        return mix(base, CorridorColors.fog, fog)
    }

    /** Clips the polygon in [p] (count vertices, xyz) at the near plane, projects it and fills/strokes it. */
    private fun DrawScope.polygon(camera: CorridorCamera, count: Int, fill: Color?, outline: Color? = null,
                                  outlineWidth: Float = 0f) {
        // Sutherland–Hodgman against a plane slightly in front of the near plane, so every kept vertex projects.
        val near = CorridorTargets.NEAR_M + CLIP_MARGIN_M
        var out = 0
        for (k in 0 until count) {
            val a = k * 3
            val b = ((k + 1) % count) * 3
            val da = camera.depth(p[a], p[a + 1], p[a + 2]) - near
            val db = camera.depth(p[b], p[b + 1], p[b + 2]) - near
            if (da >= 0f) { clip[out++] = p[a]; clip[out++] = p[a + 1]; clip[out++] = p[a + 2] }
            if ((da >= 0f) != (db >= 0f)) {
                val t = da / (da - db)
                clip[out++] = p[a] + (p[b] - p[a]) * t
                clip[out++] = p[a + 1] + (p[b + 1] - p[a + 1]) * t
                clip[out++] = p[a + 2] + (p[b + 2] - p[a + 2]) * t
            }
        }
        val vertices = out / 3
        if (vertices < 3) return
        for (k in 0 until vertices) {
            if (!camera.project(clip[k * 3], clip[k * 3 + 1], clip[k * 3 + 2], screen, k * 2)) return
        }
        if (batching) {
            if (fill != null) {
                val argb = fill.toArgb()
                for (k in 1 until vertices - 1) {
                    batch.triangle(screen[0], screen[1], screen[k * 2], screen[k * 2 + 1],
                        screen[k * 2 + 2], screen[k * 2 + 3], argb)
                }
            }
            if (outline != null) {
                val argb = outline.toArgb()
                for (k in 0 until vertices) {
                    val m = (k + 1) % vertices
                    batch.line(screen[k * 2], screen[k * 2 + 1], screen[m * 2], screen[m * 2 + 1], outlineWidth, argb)
                }
            }
            return
        }
        path.rewind()
        for (k in 0 until vertices) {
            if (k == 0) path.moveTo(screen[0], screen[1]) else path.lineTo(screen[k * 2], screen[k * 2 + 1])
        }
        path.close()
        if (fill != null) drawPath(path, fill)
        if (outline != null) drawPath(path, outline, style = Stroke(outlineWidth))
    }

    /** Line between two scene points, clipped at the near plane. */
    private fun DrawScope.line(camera: CorridorCamera, ax: Float, ay: Float, az: Float,
                               bx: Float, by: Float, bz: Float, color: Color, width: Float) {
        val da = camera.depth(ax, ay, az) - CorridorTargets.NEAR_M - CLIP_MARGIN_M
        val db = camera.depth(bx, by, bz) - CorridorTargets.NEAR_M - CLIP_MARGIN_M
        if (da < 0f && db < 0f) return
        var x0 = ax; var y0 = ay; var z0 = az; var x1 = bx; var y1 = by; var z1 = bz
        if (da < 0f) { val t = da / (da - db); x0 = ax + (bx - ax) * t; y0 = ay + (by - ay) * t; z0 = az + (bz - az) * t }
        if (db < 0f) { val t = db / (db - da); x1 = bx + (ax - bx) * t; y1 = by + (ay - by) * t; z1 = bz + (az - bz) * t }
        if (!camera.project(x0, y0, z0, q, 0) || !camera.project(x1, y1, z1, q, 2)) return
        if (batching) batch.line(q[0], q[1], q[2], q[3], width, color.toArgb())
        else drawLine(color, Offset(q[0], q[1]), Offset(q[2], q[3]), width, StrokeCap.Round)
    }

    /** Draws and empties the triangle batch. */
    private fun DrawScope.flush() {
        drawIntoCanvas { batch.flush(it.nativeCanvas) }
    }

    private fun setQuad(g: CorridorGeometry, i: Int, j: Int, half: Float, lift: Float) {
        val zi = g.sceneHeightAt(i) + lift
        val zj = g.sceneHeightAt(j) + lift
        p[0] = g.eastAt(i) + nx[i] * half; p[1] = g.northAt(i) + ny[i] * half; p[2] = zi
        p[3] = g.eastAt(j) + nx[j] * half; p[4] = g.northAt(j) + ny[j] * half; p[5] = zj
        p[6] = g.eastAt(j) - nx[j] * half; p[7] = g.northAt(j) - ny[j] * half; p[8] = zj
        p[9] = g.eastAt(i) - nx[i] * half; p[10] = g.northAt(i) - ny[i] * half; p[11] = zi
    }

    fun DrawScope.draw(g: CorridorGeometry, riderM: Double, greyed: Boolean, grid: TerrainGrid? = null) {
        val camera = CorridorCamera.chase(g, riderM, size.width, size.height) ?: return
        prepare(g)
        val terrainGrid = grid?.takeIf { it.fits(g) }?.also { prepareGrid(it, g) }
        tagCount = 0
        val n = g.size
        val first = max(0, g.indexAtOrBefore(riderM - CorridorGeometry.BEHIND_M))
        val last = min(n - 1, g.indexAtOrBefore(riderM + CorridorGeometry.AHEAD_M) + 1)

        // Sky and far ground.
        val horizon = camera.horizonY.coerceIn(-size.height, 2 * size.height)
        drawRect(Brush.verticalGradient(listOf(tone(CorridorColors.skyTop, 0f, greyed), CorridorColors.fog),
            startY = min(0f, horizon - size.height), endY = horizon))
        if (horizon < size.height) {
            // Far ground beyond the shoulder bands (inside bends, past the window): ground colour deep in fog.
            drawRect(tone(CorridorColors.ground, 0.75f, greyed), topLeft = Offset(0f, max(0f, horizon)),
                size = Size(size.width, size.height - max(0f, horizon)))
        }

        if (terrainGrid != null) {
            batching = useBatch
            grid(camera, terrainGrid, greyed)
            flush()
            batching = false
        }

        // Crest visibility from the centre line: walking away from the rider, a point that projects below the highest
        // point seen so far is behind a crest.
        var highest = Float.MAX_VALUE
        val riderIndex = g.indexAtOrBefore(riderM)
        for (i in first..last) {
            visible[i] = true
            screenY[i] = if (camera.project(g.eastAt(i), g.northAt(i), g.sceneHeightAt(i), q, 0)) q[1] else Float.NaN
        }
        for (i in riderIndex..last) {
            val y = screenY[i]
            if (y.isNaN()) continue
            visible[i] = y <= highest + 2f
            highest = min(highest, y)
        }

        val half = CorridorTargets.ROAD_WIDTH_M / 2f
        val edgeWidth = 2.dp.toPx()
        val routeWidth = 3.dp.toPx()
        batching = useBatch
        if (terrainGrid != null) {
            // Pass 1 with the grid (the grid is the ground): the route behind a crest, which the terrain hides, as a
            // translucent line over the grid, so a turn beyond a crest stays visible (DESIGN §0.3).
            val lift = CorridorTargets.RIBBON_LIFT_M
            for (i in last - 1 downTo first) {
                val j = i + 1
                if (g.isBreakBefore(j) || (visible[i] && visible[j])) continue
                line(camera, g.eastAt(i), g.northAt(i), g.sceneHeightAt(i) + lift, g.eastAt(j), g.northAt(j),
                    g.sceneHeightAt(j) + lift, tone(CorridorColors.route, fogT(g, i, riderM), greyed)
                        .copy(alpha = CorridorTargets.CREST_ROUTE_ALPHA), routeWidth)
            }
        }
        // Pass 1 without the grid: ground and ribbon, far → near.
        if (terrainGrid == null) for (i in last - 1 downTo first) {
            val j = i + 1
            if (g.isBreakBefore(j)) continue
            val fog = fogT(g, i, riderM)
            val slope = g.slopeAt(i)
            val stripe = if (floor(g.distanceAt(i) / CorridorTargets.POST_SPACING_M).toLong() % 2L == 0L) 0.04f else -0.02f
            val shade = (if (slope.isNaN()) 0f else (slope * 3f).coerceIn(-0.25f, 0.25f)) + stripe
            val ground = CorridorColors.ground.let {
                Color((it.red * (1 + shade)).coerceIn(0f, 1f), (it.green * (1 + shade)).coerceIn(0f, 1f),
                    (it.blue * (1 + shade)).coerceIn(0f, 1f))
            }
            setQuad(g, i, j, CorridorTargets.SHOULDER_M, 0f)
            polygon(camera, 4, tone(ground, fog, greyed))
            ribbon(camera, g, i, j, half, fog, greyed)
        }
        flush()
        // Pass 2: ribbon and lines over bends, far → near, skipping segments behind a crest.
        for (i in last - 1 downTo first) {
            val j = i + 1
            if (g.isBreakBefore(j) || !visible[i] || !visible[j]) continue
            val fog = fogT(g, i, riderM)
            ribbon(camera, g, i, j, half, fog, greyed)
            val available = g.isAvailable(i) && g.isAvailable(j)
            val edge = tone(if (available) CorridorColors.grade(g.gradeBandAt(i)) else CorridorColors.gapOutline,
                fog, greyed)
            val lift = CorridorTargets.RIBBON_LIFT_M
            for (side in intArrayOf(1, -1)) {
                line(camera, g.eastAt(i) + side * nx[i] * half, g.northAt(i) + side * ny[i] * half,
                    g.sceneHeightAt(i) + lift, g.eastAt(j) + side * nx[j] * half, g.northAt(j) + side * ny[j] * half,
                    g.sceneHeightAt(j) + lift, edge, if (g.gradeBandAt(i) == GradeBand.NONE) edgeWidth else edgeWidth * 2f)
            }
            line(camera, g.eastAt(i), g.northAt(i), g.sceneHeightAt(i) + lift, g.eastAt(j), g.northAt(j),
                g.sceneHeightAt(j) + lift, tone(CorridorColors.routeOutline, fog, false), routeWidth * 2f)
            line(camera, g.eastAt(i), g.northAt(i), g.sceneHeightAt(i) + lift, g.eastAt(j), g.northAt(j),
                g.sceneHeightAt(j) + lift, tone(CorridorColors.route, fog, greyed), routeWidth)
        }
        flush()
        batching = false
        // Objects (posts, gates, signs) of the same visible segments, far → near, above the road.
        for (i in last - 1 downTo first) {
            val j = i + 1
            if (g.isBreakBefore(j) || !visible[i] || !visible[j]) continue
            objects(camera, g, i, j, riderM, fogT(g, i, riderM), greyed)
        }
        rider(camera, g, riderM, greyed)
        tags()
    }

    private fun DrawScope.ribbon(camera: CorridorCamera, g: CorridorGeometry, i: Int, j: Int, half: Float,
                                 fog: Float, greyed: Boolean) {
        setQuad(g, i, j, half, CorridorTargets.RIBBON_LIFT_M)
        if (g.isAvailable(i) && g.isAvailable(j)) {
            polygon(camera, 4, tone(CorridorColors.surface(g.surfaceAt(i)), fog, greyed))
        } else {
            // Coverage gap: outlined grey "brak danych" span, held at the last available height (never 0 m).
            polygon(camera, 4, tone(CorridorColors.gapFill, fog, greyed), tone(CorridorColors.gapOutline, fog, greyed),
                1.dp.toPx())
        }
    }

    /** Scene point on the ribbon at [d] with lateral offset [side] metres (left positive) and height [up]. */
    private fun at(g: CorridorGeometry, i: Int, d: Double, side: Float, up: Float, out: FloatArray) {
        g.pointAt(d, out)
        out[0] += nx[i] * side
        out[1] += ny[i] * side
        out[2] += up
    }

    private fun DrawScope.objects(camera: CorridorCamera, g: CorridorGeometry, i: Int, j: Int, riderM: Double,
                                  fog: Float, greyed: Boolean) {
        val d0 = g.distanceAt(i)
        val d1 = g.distanceAt(j)
        fun inSegment(d: Double) = d >= d0 && d < d1
        val half = CorridorTargets.ROAD_WIDTH_M / 2f
        val postColor = tone(CorridorColors.post, fog, greyed)
        // Delineator posts every 25 m on fixed route positions (motion cue).
        val post = floor(d1 / CorridorTargets.POST_SPACING_M) * CorridorTargets.POST_SPACING_M
        if (inSegment(post) && post > riderM - 5.0) {
            for (side in floatArrayOf(half + 0.9f, -(half + 0.9f))) {
                at(g, i, post, side, 0f, tmp)
                line(camera, tmp[0], tmp[1], tmp[2], tmp[0], tmp[1], tmp[2] + 1.1f, postColor, 2.dp.toPx())
            }
        }
        // Distance gates every 100 m ahead of the rider, labelled up to 400 m.
        var k = 1
        while (k * CorridorTargets.GATE_SPACING_M <= CorridorGeometry.AHEAD_M) {
            val gate = riderM + k * CorridorTargets.GATE_SPACING_M
            if (inSegment(gate)) gate(camera, g, i, gate, k * CorridorTargets.GATE_SPACING_M, fog, greyed)
            k++
        }
        if (greyed) return
        for (m in g.maneuvers) if (inSegment(m.distanceM) && m.distanceM >= riderM) {
            at(g, i, m.distanceM, -(half + 1.2f), 0f, tmp)
            line(camera, tmp[0], tmp[1], tmp[2], tmp[0], tmp[1], tmp[2] + 5f, postColor, 2.dp.toPx())
            if (camera.project(tmp[0], tmp[1], tmp[2] + 5f, q, 0)) {
                val ahead = ((m.distanceM - riderM) / 10.0).toInt() * 10
                sign(q[0], q[1], "${maneuverGlyph(m.type)} $ahead m", fog, 20, CorridorColors.sign, Color.White)
            }
        }
        for (e in g.eventStarts) if (inSegment(e.startDistanceM) && e.startDistanceM >= riderM) {
            at(g, i, e.startDistanceM, half + 1.2f, 0f, tmp)
            // Same steepness rule as the corridor edges (CorridorGeometry.gradeBandOf).
            val color = when (CorridorGeometry.gradeBandOf(e)) {
                GradeBand.STEEP -> CorridorColors.steep
                GradeBand.CLIMB -> CorridorColors.climb
                else -> CorridorColors.descent
            }
            line(camera, tmp[0], tmp[1], tmp[2], tmp[0], tmp[1], tmp[2] + 3f, postColor, 2.dp.toPx())
            if (camera.project(tmp[0], tmp[1], tmp[2] + 3f, q, 0)) {
                val arrow = if (e.type == GradeEventType.CLIMB) "▲" else "▼"
                sign(q[0], q[1], "$arrow ${(abs(e.averageGrade) * 100).roundToInt()} %", fog, 16, color, Color.Black)
            }
        }
        for (c in g.surfaceChanges) if (inSegment(c.distanceM) && c.distanceM >= riderM) {
            at(g, i, c.distanceM, half + 1.2f, 0f, tmp)
            line(camera, tmp[0], tmp[1], tmp[2], tmp[0], tmp[1], tmp[2] + 2f, postColor, 2.dp.toPx())
            if (camera.project(tmp[0], tmp[1], tmp[2] + 2f, q, 0)) {
                sign(q[0], q[1], surfaceLabel(c.band), fog, 14, CorridorColors.surface(c.band), Color.White)
            }
        }
        // Start of a coverage gap ahead: a "brak danych" sign, so a distant held span is not read as terrain.
        if (d0 >= riderM && g.isAvailable(i) && !g.isAvailable(j)) {
            at(g, i, d0, half + 1.2f, 0f, tmp)
            line(camera, tmp[0], tmp[1], tmp[2], tmp[0], tmp[1], tmp[2] + 2f, postColor, 2.dp.toPx())
            if (camera.project(tmp[0], tmp[1], tmp[2] + 2f, q, 0)) {
                sign(q[0], q[1], "brak danych", fog, 14, CorridorColors.gapFill, CorridorColors.gapOutline)
            }
        }
    }

    private fun DrawScope.gate(camera: CorridorCamera, g: CorridorGeometry, i: Int, d: Double, ahead: Double,
                               fog: Float, greyed: Boolean) {
        val halfSpan = CorridorTargets.ROAD_WIDTH_M / 2f + 1.5f
        val color = tone(CorridorColors.post, fog, greyed)
        at(g, i, d, halfSpan, 0f, tmp)
        val lx = tmp[0]; val ly = tmp[1]; val lz = tmp[2]
        at(g, i, d, -halfSpan, 0f, tmp)
        val rx = tmp[0]; val ry = tmp[1]; val rz = tmp[2]
        val w = 2.dp.toPx()
        line(camera, lx, ly, lz, lx, ly, lz + 3f, color, w)
        line(camera, rx, ry, rz, rx, ry, rz + 3f, color, w)
        line(camera, lx, ly, lz + 3f, rx, ry, rz + 3f, color, w)
        if (ahead <= CorridorTargets.GATE_LABEL_MAX_M &&
            camera.project((lx + rx) / 2f, (ly + ry) / 2f, (lz + rz) / 2f + 3f, q, 0)) {
            val text = label("${ahead.toInt()} m", 14, bold = true)
            queueTag(text, q[0] - text.size.width / 2f, q[1] - text.size.height - 2.dp.toPx(),
                text.size.width.toFloat(), text.size.height.toFloat(), null, fog, 0f, color)
        }
    }

    private fun DrawScope.sign(x: Float, y: Float, text: String, fog: Float, sizeSp: Int, background: Color,
                               foreground: Color) {
        val layout = label(text, sizeSp, bold = true, color = foreground)
        val pad = 6.dp.toPx()
        val w = layout.size.width + 2 * pad
        val h = layout.size.height + pad
        queueTag(layout, x - w / 2f, y - h, w, h, background, fog, pad, Color.Unspecified)
    }

    /** Queues a label (plain when [background] is null) for [tags]; drops it when the queue is full. */
    private fun queueTag(layout: TextLayoutResult, left: Float, top: Float, w: Float, h: Float, background: Color?,
                         fog: Float, pad: Float, color: Color) {
        val k = tagCount
        if (k == tagText.size) return
        tagText[k] = layout; tagLeft[k] = left; tagTop[k] = top; tagW[k] = w; tagH[k] = h
        tagBackground[k] = background; tagFog[k] = fog; tagPad[k] = pad; tagColor[k] = color
        tagCount++
    }

    /** Labels and signs queued far → near. Signs (maneuver, grade, surface, gap) take precedence over plain distance
     *  labels; within each group the nearest is drawn first. Anything overlapping an already drawn tag is skipped, so
     *  tags never pile up at a crest or on each other. */
    private fun DrawScope.tags() {
        for (k in 0 until tagCount) tagDrawn[k] = false
        for (signs in booleanArrayOf(true, false)) for (k in tagCount - 1 downTo 0) {
            if ((tagBackground[k] != null) != signs) continue
            val l = tagLeft[k]
            val t = tagTop[k]
            var overlaps = false
            for (m in 0 until tagCount) {
                if (m != k && tagDrawn[m] && l < tagLeft[m] + tagW[m] && tagLeft[m] < l + tagW[k] &&
                    t < tagTop[m] + tagH[m] && tagTop[m] < t + tagH[k]) { overlaps = true; break }
            }
            if (overlaps) continue
            tagDrawn[k] = true
            val background = tagBackground[k]
            val pad = tagPad[k]
            if (background != null) {
                val fog = tagFog[k]
                drawRoundRect(mix(background, CorridorColors.fog, fog * 0.6f), Offset(l, t), Size(tagW[k], tagH[k]),
                    CornerRadius(6.dp.toPx()))
                drawRoundRect(Color.White.copy(alpha = 0.8f * (1f - fog)), Offset(l, t), Size(tagW[k], tagH[k]),
                    CornerRadius(6.dp.toPx()), style = Stroke(1.5f.dp.toPx()))
            }
            drawText(tagText[k]!!, color = tagColor[k]!!, topLeft = Offset(l + pad, t + pad / 2f))
        }
    }

    private fun DrawScope.rider(camera: CorridorCamera, g: CorridorGeometry, riderM: Double, greyed: Boolean) {
        val i = g.indexAtOrBefore(riderM).coerceIn(0, g.size - 1)
        g.pointAt(riderM, tmp)
        val x = tmp[0]; val y = tmp[1]; val z = tmp[2] + 0.4f
        val fx = ny[i]; val fy = -nx[i] // forward = left normal rotated clockwise
        val lx = nx[i]; val ly = ny[i]
        p[0] = x + fx * 1.8f; p[1] = y + fy * 1.8f; p[2] = z
        p[3] = x + lx * 1.1f - fx * 1.0f; p[4] = y + ly * 1.1f - fy * 1.0f; p[5] = z
        p[6] = x - fx * 0.4f; p[7] = y - fy * 0.4f; p[8] = z
        p[9] = x - lx * 1.1f - fx * 1.0f; p[10] = y - ly * 1.1f - fy * 1.0f; p[11] = z
        polygon(camera, 4, if (greyed) Color(0xFFB0B8C0) else Color.White, CorridorColors.routeOutline, 2.dp.toPx())
    }
}

/**
 * TEREN tab, Option D road-ahead corridor (DESIGN §3.7, §16.1 D note). States follow the C column: live corridor,
 * frozen greyed corridor off route, outlined "brak danych" spans, stale indicator, labelled end states. A drawing
 * failure is reported once through [onFailure] (MAP fallback) and never propagates.
 */
@Composable
fun RoadAheadCorridor(
    model: TerrainInstrumentModel,
    modifier: Modifier = Modifier,
    onFailure: (String) -> Unit = {},
    /** Receives each projection + draw duration in nanoseconds (DESIGN §21 D column measurement). */
    onDrawNanos: ((Long) -> Unit)? = null,
    /** C3 terrain grid; drawn only when it fits the shown corridor, otherwise the TA-007B corridor alone. */
    grid: TerrainGrid? = null
) {
    val status = when (model) {
        TerrainInstrumentModel.NoRoute -> "Teren: brak trasy"
        TerrainInstrumentModel.Arrived -> "Teren: cel"
        is TerrainInstrumentModel.Detached -> "Teren: poza trasą"
        is TerrainInstrumentModel.NoData -> "Teren: brak danych"
        is TerrainInstrumentModel.Valid -> "Teren: dane"
        is TerrainInstrumentModel.Reloading -> "Teren: ładowanie"
    }
    val corridor = model.shownCorridor
    val riderM = model.shownRiderM
    // §16.1 route changed: the frozen view gets its "ładowanie" label only after RELOAD_LABEL_DELAY_MS.
    val reloading = model is TerrainInstrumentModel.Reloading
    var reloadLabel by remember { mutableStateOf(false) }
    LaunchedEffect(reloading) {
        reloadLabel = false
        if (reloading) {
            delay(CorridorTargets.RELOAD_LABEL_DELAY_MS)
            reloadLabel = true
        }
    }
    val textMeasurer = rememberTextMeasurer()
    val debuggable = LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val painter = remember(textMeasurer, debuggable) { CorridorPainter(textMeasurer, debuggable) }
    var failed by remember { mutableStateOf<String?>(null) }
    val currentOnFailure by rememberUpdatedState(onFailure)
    LaunchedEffect(failed) { failed?.let { currentOnFailure(it) } }

    Box(modifier.background(CorridorColors.fog).testTag("terrain_instrument")
        .semantics { contentDescription = status }) {
        if (corridor != null && riderM != null && failed == null) {
            val greyed = model is TerrainInstrumentModel.Detached || reloading
            Canvas(Modifier.fillMaxSize().testTag("terrain_distance")) {
                val started = SystemClock.elapsedRealtimeNanos()
                try {
                    with(painter) { draw(corridor, riderM, greyed, grid) }
                } catch (e: Exception) {
                    Log.w("TerrainCorridor", "corridor draw failed", e)
                    failed = "Teren niedostępny"
                }
                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                painter.timer.record(elapsed)
                onDrawNanos?.invoke(elapsed)
            }
        }
        val hudStyle = Modifier.background(Color(0xB3071A21), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp,
            vertical = 4.dp)
        when (model) {
            is TerrainInstrumentModel.Valid -> {
                Text(model.gradeLabel, Modifier.align(Alignment.TopStart).padding(12.dp).then(hudStyle)
                    .testTag("terrain_slope"), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                model.nextEventLabel?.let {
                    Text(it, Modifier.align(Alignment.TopEnd).padding(12.dp).then(hudStyle),
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                if (corridor != null) {
                    Text("wysokość ×%.1f".format(corridor.exaggeration).replace('.', ','),
                        Modifier.align(Alignment.BottomStart).padding(12.dp).then(hudStyle),
                        color = Color.White, fontSize = 14.sp)
                }
                if (model.showStaleIndicator) Text("POZYCJA NIEAKTUALNA",
                    Modifier.align(Alignment.Center).then(hudStyle), color = Color.Yellow, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold)
            }
            else -> {
                val label = when (model) {
                    TerrainInstrumentModel.NoRoute -> "BRAK TRASY"
                    TerrainInstrumentModel.Arrived -> "CEL"
                    is TerrainInstrumentModel.Detached -> "POZA TRASĄ" +
                        (model.distanceToRouteM?.let { " · ${it.toInt()} m" } ?: "")
                    is TerrainInstrumentModel.NoData -> "BRAK DANYCH WYSOKOŚCI"
                    is TerrainInstrumentModel.Reloading -> if (reloadLabel) "ŁADOWANIE" else ""
                    else -> ""
                }
                // Over a corridor the state label sits at the top so the road stays visible.
                val placement = if (corridor != null) Alignment.TopCenter else Alignment.Center
                if (label.isNotEmpty()) {
                    Text(label, Modifier.align(placement).padding(12.dp).then(hudStyle), color = Color.White,
                        fontSize = if (corridor != null) 20.sp else 26.sp, fontWeight = FontWeight.Bold)
                }
                if (model is TerrainInstrumentModel.NoData && model.showStaleIndicator) {
                    Text("POZYCJA NIEAKTUALNA", Modifier.align(Alignment.BottomCenter).padding(12.dp).then(hudStyle),
                        color = Color.Yellow)
                }
            }
        }
    }
}

/** Straight sRGB blend (Compose's `lerp` interpolates in Oklab with colour-space conversions: too slow per segment). */
private fun mix(a: Color, b: Color, t: Float): Color = if (t <= 0f) a else Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t)
