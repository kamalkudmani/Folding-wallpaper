package com.foldmotion.wallpapers.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.AmbientSpec
import com.foldmotion.core.motion.AmbientSystem
import com.foldmotion.core.motion.MotionSampler
import com.foldmotion.core.motion.MotionSpec
import com.foldmotion.core.motion.ParticleBurst
import com.foldmotion.core.motion.RevealSpec
import com.foldmotion.core.motion.TrackPose
import com.foldmotion.core.pack.FoldPackManifest
import com.foldmotion.core.pack.LayerEntry
import com.foldmotion.core.scene.WorldRect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Decoded, ready-to-draw scene for one screen of a pack. Immutable once built. */
class LoadedScene(
    val packId: String,
    val kind: ScreenKind,
    val baseWidth: Int,
    val baseHeight: Int,
    val focusX: Float,
    val focusY: Float,
    val layers: List<Pair<LayerEntry, Bitmap>>,
    /** Motion that plays when this screen appears: OPEN for main, CLOSE for cover. */
    val entryMotion: MotionSpec?,
    val background: Int,
    val glow: Int,
    /** What keeps moving between folds; null or still means a still wallpaper. */
    val ambient: AmbientSpec? = null,
    /** World rect this frame shows, so ambient elements line up across screens. */
    val world: WorldRect? = null,
) {
    val byteCount: Int get() = layers.sumOf { it.second.allocationByteCount }
    val isAlive: Boolean get() = ambient != null && !ambient.isStill

    fun recycle() {
        layers.forEach { it.second.recycle() }
    }

    companion object {
        fun parseColor(hex: String, fallback: Int): Int = try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            fallback
        }

        fun backgroundOf(manifest: FoldPackManifest) = parseColor(manifest.colors.background, Color.BLACK)
        fun glowOf(manifest: FoldPackManifest) = parseColor(manifest.colors.primary, Color.WHITE)
    }
}

/** Per-frame inputs that are not part of the scene. */
data class FrameState(
    /** Milliseconds on a clock shared by every engine in the process (elapsedRealtime). */
    val timeMs: Long = 0L,
    /** Living motion on or off; off draws the still frame. */
    val ambient: Boolean = false,
    /** Smoothed device tilt in [-1, 1]. */
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    /** Fraction of each ambient system to draw (1 = all, lower for the subtle setting). */
    val density: Float = 1f,
) {
    companion object {
        val STILL = FrameState()
    }
}

/**
 * Composites a [LoadedScene] onto any canvas. The live wallpaper and the in-app preview both call
 * this, so what the user previews is drawn by the same code that runs on the home screen.
 *
 * Draw order: background, then each depth layer with its transition pose plus sway and tilt; ambient
 * systems slot in after the layer they belong to; then transition effects (reveal, particles,
 * glow, dim). Everything is a pure function of (scene, progress, time), so a frame can be
 * re-drawn at any moment without state.
 */
class SceneDrawer {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val dst = RectF()
    private val frame = RectF()
    private val filters = HashMap<Int, PorterDuffColorFilter>()
    private val colorCache = HashMap<String, Int>()

    /** Current pose of each layer id, reused by ambient systems drawn on top of that layer. */
    private val poseScale = HashMap<String, Float>()
    private val poseTx = HashMap<String, Float>()
    private val poseTy = HashMap<String, Float>()
    private var pivotX = 0.5f
    private var pivotY = 0.5f
    /** Size of the area being drawn; the canvas may be translated (fold demo halves). */
    private var drawWidth = 0
    private var drawHeight = 0

    /**
     * @param progress linear transition progress in [0,1], or null for the resting pose.
     */
    fun draw(canvas: Canvas, width: Int, height: Int, scene: LoadedScene, progress: Float?, state: FrameState = FrameState.STILL) {
        computeFrame(width.toFloat(), height.toFloat(), scene, frame)
        drawWidth = width
        drawHeight = height
        canvas.drawColor(scene.background)

        val motion = if (progress != null) scene.entryMotion else null
        pivotX = motion?.pivotX ?: scene.focusX
        pivotY = motion?.pivotY ?: scene.focusY
        val p = progress ?: 1f
        val ambient = if (state.ambient) scene.ambient else null

        for ((layer, bitmap) in scene.layers) {
            if (bitmap.isRecycled) continue
            val pose = if (motion == null) TrackPose.REST else MotionSampler.poseFor(motion, MotionSampler.layerTarget(layer.id), p)
            var tx = pose.tx
            var ty = pose.ty
            if (ambient != null && layer.depth > 0f) {
                val t = state.timeMs / ambient.swayPeriodMs.toFloat() * 2f * PI.toFloat()
                tx += ambient.sway * layer.depth * sin(t + layer.depth * 1.7f) + ambient.tilt * layer.depth * state.tiltX
                ty += ambient.sway * 0.5f * layer.depth * cos(t * 0.8f + layer.depth) + ambient.tilt * 0.6f * layer.depth * state.tiltY
            }
            poseScale[layer.id] = pose.scale
            poseTx[layer.id] = tx
            poseTy[layer.id] = ty
            val l = pivotX + pose.scale * (layer.left - pivotX) + tx
            val r = pivotX + pose.scale * (layer.right - pivotX) + tx
            val t = pivotY + pose.scale * (layer.top - pivotY) + ty
            val b = pivotY + pose.scale * (layer.bottom - pivotY) + ty
            dst.set(frame.left + l * frame.width(), frame.top + t * frame.height(), frame.left + r * frame.width(), frame.top + b * frame.height())
            bitmapPaint.alpha = (255 * pose.alpha.coerceIn(0f, 1f)).toInt()
            canvas.drawBitmap(bitmap, null, dst, bitmapPaint)

            if (ambient != null) {
                if (layer.id == "far") ambient.halo?.let { drawHalo(canvas, scene, it, state.timeMs) }
                for (system in ambient.systems) if (system.after == layer.id) drawSystem(canvas, scene, system, state, layer.id)
            }
        }

        if (motion != null) {
            // Order matters: darken, cover what is not uncovered yet, then add light on top, so
            // gathering particles and the bloom glow in the dark instead of being hidden by it.
            val dim = MotionSampler.poseFor(motion, MotionSpec.TARGET_DIM, p)
            if (dim.alpha > 0.003f) canvas.drawColor(SceneArtist.withAlpha(Color.BLACK, dim.alpha))
            motion.reveal?.let { drawReveal(canvas, it, p, scene.glow) }
            val glow = MotionSampler.poseFor(motion, MotionSpec.TARGET_GLOW, p)
            if (glow.alpha > 0.003f) {
                val cx = frame.left + pivotX * frame.width()
                val cy = frame.top + pivotY * frame.height()
                val radius = max(1f, glow.scale * 0.45f * min(width, height).toFloat())
                overlayPaint.shader = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(SceneArtist.withAlpha(scene.glow, glow.alpha), SceneArtist.withAlpha(scene.glow, glow.alpha * 0.35f), SceneArtist.withAlpha(scene.glow, 0f)),
                    floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
                )
                overlayPaint.blendMode = BlendMode.PLUS
                canvas.drawCircle(cx, cy, radius, overlayPaint)
                overlayPaint.blendMode = null
                overlayPaint.shader = null
            }
            drawParticles(canvas, motion, p)
        }
    }

    /** Flat gradient shown while a scene loads or when a pack is unavailable. Never black. */
    fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, top: Int, bottom: Int) {
        overlayPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        overlayPaint.shader = null
    }

    // ------------------------------------------------------------------ transition effects

    private fun drawReveal(canvas: Canvas, reveal: RevealSpec, p: Float, glowColor: Int) {
        val rp = reveal.progress(p)
        if (rp >= 0.999f) return
        val fw = frame.width()
        val fh = frame.height()
        val shade = SceneArtist.withAlpha(Color.BLACK, reveal.shade)
        val clear = SceneArtist.withAlpha(Color.BLACK, 0f)
        val edgeAlpha = reveal.edgeGlow * (1f - rp).pow(0.6f)
        when (reveal.type) {
            RevealSpec.UNFOLD -> {
                val from = reveal.from
                // Open past the frame edges so the soft edge leaves the screen completely.
                val l = lerp(from[0], -0.15f, rp)
                val r = lerp(from[2], 1.15f, rp)
                val soft = reveal.softness * fw
                val lx = frame.left + l * fw
                val rx = frame.left + r * fw
                overlayPaint.shader = LinearGradient(lx - soft, 0f, lx, 0f, shade, clear, Shader.TileMode.CLAMP)
                canvas.drawRect(frame.left - 2f, frame.top - 2f, lx, frame.bottom + 2f, overlayPaint)
                overlayPaint.shader = LinearGradient(rx, 0f, rx + soft, 0f, clear, shade, Shader.TileMode.CLAMP)
                canvas.drawRect(rx, frame.top - 2f, frame.right + 2f, frame.bottom + 2f, overlayPaint)
                overlayPaint.shader = null
                if (edgeAlpha > 0.01f) {
                    edgeLight(canvas, lx - soft * 0.25f, fh, glowColor, edgeAlpha)
                    edgeLight(canvas, rx + soft * 0.25f, fh, glowColor, edgeAlpha)
                }
            }
            RevealSpec.IRIS -> {
                val diag = sqrt(fw * fw + fh * fh)
                val cx = frame.left + reveal.from[0] * fw
                val cy = frame.top + reveal.from[1] * fh
                val radius = max(2f, lerp(reveal.from[2], 1.1f, rp) * diag)
                val soft = reveal.softness * diag
                val inner = ((radius - soft) / radius).coerceIn(0f, 0.999f)
                overlayPaint.shader = RadialGradient(cx, cy, radius, intArrayOf(clear, clear, shade), floatArrayOf(0f, inner, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(frame.left - 2f, frame.top - 2f, frame.right + 2f, frame.bottom + 2f, overlayPaint)
                overlayPaint.shader = null
                if (edgeAlpha > 0.01f) {
                    val ring = radius - soft * 0.5f
                    linePaint.blendMode = BlendMode.PLUS
                    linePaint.strokeWidth = soft * 0.9f
                    linePaint.color = SceneArtist.withAlpha(glowColor, edgeAlpha * 0.16f)
                    canvas.drawCircle(cx, cy, ring, linePaint)
                    linePaint.strokeWidth = max(2f, soft * 0.08f)
                    linePaint.color = SceneArtist.withAlpha(glowColor, edgeAlpha * 0.5f)
                    canvas.drawCircle(cx, cy, ring, linePaint)
                    linePaint.blendMode = null
                }
            }
        }
    }

    /** A vertical blade of light along a moving fold edge. */
    private fun edgeLight(canvas: Canvas, x: Float, height: Float, color: Int, alpha: Float) {
        val half = max(6f, frame.width() * 0.035f)
        overlayPaint.shader = LinearGradient(
            x - half, 0f, x + half, 0f,
            intArrayOf(SceneArtist.withAlpha(color, 0f), SceneArtist.withAlpha(color, alpha * 0.75f), SceneArtist.withAlpha(color, 0f)),
            null, Shader.TileMode.CLAMP,
        )
        overlayPaint.blendMode = BlendMode.PLUS
        canvas.drawRect(x - half, frame.top, x + half, frame.top + height, overlayPaint)
        overlayPaint.blendMode = null
        overlayPaint.shader = null
    }

    private fun drawParticles(canvas: Canvas, motion: MotionSpec, p: Float) {
        if (motion.particles.isEmpty()) return
        val fw = frame.width()
        val fh = frame.height()
        val diag = sqrt(fw * fw + fh * fh)
        val short = min(fw, fh)
        for (burst in motion.particles) {
            val span = (burst.end - burst.start).coerceAtLeast(0.001f)
            val bp = (p - burst.start) / span
            if (bp <= 0f || bp >= 1f) continue
            val ox = frame.left + burst.origin.getOrElse(0) { 0.5f } * fw
            val oy = frame.top + burst.origin.getOrElse(1) { 0.5f } * fh
            val colors = burst.colors.map(::color)
            for (i in 0 until burst.count) {
                val h1 = hash01(burst.seed, i, 1)
                val h2 = hash01(burst.seed, i, 2)
                val h3 = hash01(burst.seed, i, 3)
                val delay = 0.35f * hash01(burst.seed, i, 4)
                val lt = ((bp - delay) / (1f - delay)).coerceIn(0f, 1f)
                if (lt <= 0f || lt >= 1f) continue
                val dist = burst.spread * diag * (0.12f + 0.88f * h2.pow(0.7f))
                val theta = h1 * 2f * PI.toFloat()
                val (r, alpha) = if (burst.mode == ParticleBurst.CONVERGE) {
                    dist * (1f - lt).pow(1.6f) to sin(PI.toFloat() * min(1f, lt * 1.1f)).coerceAtLeast(0f)
                } else {
                    dist * (1f - (1f - lt).pow(3f)) to sin(PI.toFloat() * lt).pow(0.7f)
                }
                val a = theta + burst.swirl * lt
                val x = ox + cos(a) * r
                val y = oy + sin(a) * r * 0.9f
                val size = (burst.sizeMin + (burst.sizeMax - burst.sizeMin) * h3 * h3) * short
                sprite(canvas, x, y, size * 3f, colors[i % colors.size], alpha * (0.55f + 0.45f * hash01(burst.seed, i, 5)))
            }
        }
    }

    // ------------------------------------------------------------------ ambient systems

    private fun drawHalo(canvas: Canvas, scene: LoadedScene, halo: com.foldmotion.core.motion.HaloSpec, timeMs: Long) {
        val breath = 0.5f + 0.5f * sin(timeMs / halo.periodMs.toFloat() * 2f * PI.toFloat())
        val alpha = halo.alphaMin + (halo.alphaMax - halo.alphaMin) * breath
        val x = worldX(scene, halo.x, "far")
        val y = worldY(scene, halo.y, "far")
        val radius = worldLen(scene, halo.radius) * (1f + 0.06f * breath)
        sprite(canvas, x, y, radius, color(halo.color), alpha)
    }

    private fun drawSystem(canvas: Canvas, scene: LoadedScene, sys: AmbientSystem, state: FrameState, layerId: String) {
        val count = max(1, (sys.count * state.density.coerceIn(0f, 1f)).toInt())
        val t = state.timeMs / 1000f
        val colors = sys.colors.map(::color)
        val w = sys.right - sys.left
        val h = sys.bottom - sys.top
        when (sys.type) {
            AmbientSystem.TWINKLE -> for (i in 0 until count) {
                val wx = sys.left + hash01(sys.seed, i, 1) * w
                val wy = sys.top + hash01(sys.seed, i, 2) * h
                val period = sys.periodMs / 1000f * (0.6f + hash01(sys.seed, i, 3))
                val phase = hash01(sys.seed, i, 4) * 6.283f
                val tw = 0.5f + 0.5f * sin(t / period * 6.283f + phase)
                val alpha = sys.alpha * (0.15f + 0.85f * tw * tw)
                val size = worldLen(scene, sys.sizeMin + (sys.sizeMax - sys.sizeMin) * hash01(sys.seed, i, 5).pow(2f))
                sprite(canvas, worldX(scene, wx, layerId), worldY(scene, wy, layerId), size * 3.2f, colors[i % colors.size], alpha)
            }
            AmbientSystem.SHOOTING_STAR -> {
                val periodS = sys.periodMs / 1000f
                val slot = floor(t / periodS).toInt()
                // Not every slot has a star, so they feel occasional rather than scheduled.
                if (hash01(sys.seed, slot, 1) > 0.65f) return
                val life = 0.9f
                val startAt = slot * periodS + hash01(sys.seed, slot, 2) * (periodS - life).coerceAtLeast(0f)
                val lt = (t - startAt) / life
                if (lt <= 0f || lt >= 1f) return
                val dx0 = sys.direction.getOrElse(0) { 1f }
                val dy0 = sys.direction.getOrElse(1) { 0f }
                val norm = sqrt(dx0 * dx0 + dy0 * dy0).coerceAtLeast(1e-3f)
                val dx = dx0 / norm
                val dy = dy0 / norm
                val sx = sys.left + hash01(sys.seed, slot, 3) * w
                val sy = sys.top + hash01(sys.seed, slot, 4) * h * 0.6f
                val travelled = sys.speed * life * lt
                val hx = sx + dx * travelled
                val hy = sy + dy * travelled
                val len = (sys.sizeMin + (sys.sizeMax - sys.sizeMin) * hash01(sys.seed, slot, 5)) * min(1f, lt * 3f)
                val alpha = sys.alpha * sin(PI.toFloat() * lt)
                val c = colors[0]
                val x1 = worldX(scene, hx, layerId)
                val y1 = worldY(scene, hy, layerId)
                val x0 = worldX(scene, hx - dx * len, layerId)
                val y0 = worldY(scene, hy - dy * len, layerId)
                linePaint.shader = LinearGradient(x0, y0, x1, y1, SceneArtist.withAlpha(c, 0f), SceneArtist.withAlpha(c, alpha), Shader.TileMode.CLAMP)
                linePaint.strokeWidth = max(1.5f, worldLen(scene, 0.0025f))
                linePaint.blendMode = BlendMode.PLUS
                canvas.drawLine(x0, y0, x1, y1, linePaint)
                linePaint.shader = null
                linePaint.blendMode = null
                sprite(canvas, x1, y1, worldLen(scene, 0.012f), c, alpha)
            }
            AmbientSystem.FIREFLY -> for (i in 0 until count) {
                val bx = sys.left + hash01(sys.seed, i, 1) * w
                val by = sys.top + hash01(sys.seed, i, 2) * h
                val amp = sys.speed * 3f
                val w1 = 0.35f + hash01(sys.seed, i, 3) * 0.5f
                val w2 = 0.25f + hash01(sys.seed, i, 4) * 0.45f
                val x = bx + amp * sin(t * w1 + i * 1.3f)
                val y = by + amp * 0.6f * sin(t * w2 + i * 0.7f)
                val blink = 0.5f + 0.5f * sin(t / (sys.periodMs / 1000f) * 6.283f * (0.7f + 0.6f * hash01(sys.seed, i, 5)) + i)
                val alpha = sys.alpha * (0.1f + 0.9f * blink.pow(3f))
                val size = worldLen(scene, sys.sizeMin + (sys.sizeMax - sys.sizeMin) * hash01(sys.seed, i, 6))
                sprite(canvas, worldX(scene, x, layerId), worldY(scene, y, layerId), size * 5f, colors[i % colors.size], alpha)
            }
            AmbientSystem.DRIFT -> {
                val dx = sys.direction.getOrElse(0) { 1f }
                val dy = sys.direction.getOrElse(1) { 0f }
                for (i in 0 until count) {
                    val k = 0.6f + 0.8f * hash01(sys.seed, i, 3)
                    val x = sys.left + wrap(hash01(sys.seed, i, 1) * w + dx * sys.speed * k * t, w)
                    val y = sys.top + wrap(hash01(sys.seed, i, 2) * h + dy * sys.speed * k * t + 0.004f * sin(t * 0.9f + i), h)
                    val edge = min(min(x - sys.left, sys.right - x), min(y - sys.top, sys.bottom - y)) / (0.05f * max(w, h))
                    val alpha = sys.alpha * edge.coerceIn(0f, 1f) * (0.6f + 0.4f * sin(t * 1.7f + i * 2.1f))
                    val size = worldLen(scene, sys.sizeMin + (sys.sizeMax - sys.sizeMin) * hash01(sys.seed, i, 4))
                    sprite(canvas, worldX(scene, x, layerId), worldY(scene, y, layerId), size * 2.6f, colors[i % colors.size], alpha)
                }
            }
            AmbientSystem.GLINT -> {
                linePaint.blendMode = BlendMode.PLUS
                val cx = (sys.left + sys.right) / 2f
                for (i in 0 until count) {
                    val yt = hash01(sys.seed, i, 1)
                    val y = sys.top + yt.pow(1.5f) * h
                    val spread = w / 2f * (0.15f + 0.85f * yt)
                    val x = cx + (hash01(sys.seed, i, 2) + hash01(sys.seed, i, 3) - 1f) * spread + 0.003f * sin(t * 2.3f + i)
                    val period = sys.periodMs / 1000f * (0.5f + hash01(sys.seed, i, 4))
                    val flash = max(0f, sin(t / period * 6.283f + i * 0.9f)).pow(3f)
                    if (flash < 0.02f) continue
                    val len = (sys.sizeMin + (sys.sizeMax - sys.sizeMin) * yt) * (0.5f + 0.5f * hash01(sys.seed, i, 5))
                    val x0 = worldX(scene, x - len / 2f, layerId)
                    val x1 = worldX(scene, x + len / 2f, layerId)
                    val py = worldY(scene, y, layerId)
                    linePaint.color = SceneArtist.withAlpha(colors[i % colors.size], sys.alpha * flash * (1f - yt * 0.4f))
                    linePaint.strokeWidth = max(1.2f, worldLen(scene, 0.0012f + 0.002f * yt))
                    canvas.drawLine(x0, py, x1, py, linePaint)
                }
                linePaint.blendMode = null
            }
            AmbientSystem.BLINKER -> for (i in 0 until count) {
                val x = sys.left + wrap(hash01(sys.seed, i, 1) * w + sys.speed * (0.7f + 0.6f * hash01(sys.seed, i, 2)) * t, w)
                val y = sys.top + hash01(sys.seed, i, 3) * h + sys.direction.getOrElse(1) { 0f } * x
                val cycle = ((t * 1000f + i * 400f) % sys.periodMs) / sys.periodMs
                val on = if (cycle < 0.09f) 1f else 0.18f
                val size = worldLen(scene, sys.sizeMax)
                sprite(canvas, worldX(scene, x, layerId), worldY(scene, y, layerId), size * 4f, colors[(i + (if (cycle < 0.09f) 0 else 1)) % colors.size], sys.alpha * on)
            }
            AmbientSystem.FLOW -> for (i in 0 until count) {
                val lane = i % 5
                val cy = sys.top + h * (0.18f + lane * 0.16f + 0.05f * (hash01(sys.seed, lane, 9) - 0.5f))
                val amp = h * (0.04f + 0.06f * hash01(sys.seed, lane, 10))
                val f = 1.4f + 2.2f * hash01(sys.seed, lane, 11)
                val k = 0.6f + 0.8f * hash01(sys.seed, i, 2)
                for (trail in 0 until 3) {
                    val x = sys.left + wrap(hash01(sys.seed, i, 1) * w + sys.speed * k * (t - trail * 0.06f), w)
                    val y = cy + amp * sin(x * f + t * 0.25f + lane)
                    val edge = min(x - sys.left, sys.right - x) / (0.08f * w)
                    val alpha = sys.alpha * edge.coerceIn(0f, 1f) * (1f - trail * 0.35f) * (0.6f + 0.4f * sin(t * 2f + i))
                    val size = worldLen(scene, sys.sizeMin + (sys.sizeMax - sys.sizeMin) * hash01(sys.seed, i, 3)) * (1f - trail * 0.2f)
                    sprite(canvas, worldX(scene, x, layerId), worldY(scene, y, layerId), size * 3f, colors[i % colors.size], alpha)
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** World x to screen pixels, following the current pose of the layer the system sits on. */
    private fun worldX(scene: LoadedScene, wx: Float, layerId: String): Float {
        val w = scene.world
        val nx = if (w == null) wx else (wx - w.left) / w.width
        val s = poseScale[layerId] ?: 1f
        val px = pivotX + s * (nx - pivotX) + (poseTx[layerId] ?: 0f)
        return frame.left + px * frame.width()
    }

    private fun worldY(scene: LoadedScene, wy: Float, layerId: String): Float {
        val w = scene.world
        val ny = if (w == null) wy else (wy - w.top) / w.height
        val s = poseScale[layerId] ?: 1f
        val py = pivotY + s * (ny - pivotY) + (poseTy[layerId] ?: 0f)
        return frame.top + py * frame.height()
    }

    private fun worldLen(scene: LoadedScene, len: Float): Float {
        val w = scene.world ?: return len * frame.width()
        return len / w.width * frame.width()
    }

    /** Soft round light from a shared sprite, added onto the scene. */
    private fun sprite(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int, alpha: Float) {
        if (alpha <= 0.01f || radius <= 0.2f) return
        if (x < -radius || y < -radius || x > drawWidth + radius || y > drawHeight + radius) return
        spritePaint.colorFilter = filters.getOrPut(color or (0xFF shl 24)) { PorterDuffColorFilter(color or (0xFF shl 24), PorterDuff.Mode.SRC_IN) }
        spritePaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        dst.set(x - radius, y - radius, x + radius, y + radius)
        canvas.drawBitmap(glowSprite, null, dst, spritePaint)
    }

    private fun color(hex: String): Int = colorCache.getOrPut(hex) { LoadedScene.parseColor(hex, Color.WHITE) }

    companion object {
        /** 64 px white dot: bright core, long soft falloff. Tinted per particle with a colour filter. */
        val glowSprite: Bitmap by lazy {
            val size = 64
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(
                size / 2f, size / 2f, size / 2f,
                intArrayOf(Color.WHITE, Color.argb(150, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.12f, 0.4f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawCircle(size / 2f, size / 2f, size / 2f, p)
            bmp
        }

        fun computeFrame(width: Float, height: Float, scene: LoadedScene, out: RectF) {
            val aspect = scene.baseWidth.toFloat() / scene.baseHeight.toFloat()
            val fw: Float
            val fh: Float
            if (width / height > aspect) {
                fw = width
                fh = width / aspect
            } else {
                fh = height
                fw = height * aspect
            }
            // Halfway between a centred crop and one centred on the focus, clamped to the frame.
            val ox = run {
                val centred = (width - fw) / 2f
                val towardFocus = width / 2f - scene.focusX * fw
                ((centred + towardFocus) / 2f).coerceIn(width - fw, 0f)
            }
            val oy = run {
                val centred = (height - fh) / 2f
                val towardFocus = height / 2f - scene.focusY * fh
                ((centred + towardFocus) / 2f).coerceIn(height - fh, 0f)
            }
            out.set(ox, oy, ox + fw, oy + fh)
        }

        /** Stateless pseudo-random in [0,1) from (seed, index, salt). */
        fun hash01(seed: Int, index: Int, salt: Int): Float {
            var h = seed * 374761393 + index * 668265263 + salt * 1274126177
            h = (h xor (h ushr 13)) * 1103515245
            h = h xor (h ushr 16)
            return (h and 0x00FFFFFF) / 16777216f
        }

        private fun wrap(v: Float, span: Float): Float {
            if (span <= 0f) return 0f
            val m = v % span
            return if (m < 0f) m + span else m
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
