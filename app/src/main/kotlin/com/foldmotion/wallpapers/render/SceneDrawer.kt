package com.foldmotion.wallpapers.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
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
import com.foldmotion.core.motion.ChoreographyComposer
import com.foldmotion.core.motion.MotionSpec
import com.foldmotion.core.motion.ParticleBurst
import com.foldmotion.core.motion.RevealSpec
import com.foldmotion.core.motion.TrackPose
import com.foldmotion.core.pack.FoldPackManifest
import com.foldmotion.core.pack.LayerEntry
import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.core.pack.VideoSpec
import com.foldmotion.core.scene.WorldRect
import kotlin.math.PI
import kotlin.math.abs
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
    /** Video scene (pack v4): the back layer is replaced by live video frames when available. */
    val video: VideoSpec? = null,
    val videoFile: java.io.File? = null,
    /** Live liquid scene (pack v6): the back layer is computed on the GPU every frame. */
    val liquid: LiquidSpec? = null,
    /** The main screen's world (liquid glass places its drops there on both screens). */
    val mainWorld: WorldRect? = null,
) {
    /** Taps, tilt drift and hinge for live liquid, owned by whoever shows this scene. */
    val liquidMotion: LiquidMotion by lazy { LiquidMotion() }

    /** World point at ([fx], [fy]) of this scene's frame, as fractions; null without a world. */
    fun worldAt(fx: Float, fy: Float): Pair<Float, Float>? = world?.let { it.left + fx * it.width to it.top + fy * it.height }

    /** Live frames for a video scene; set by whoever plays the video (engine or preview). */
    @Volatile
    var videoFrames: VideoFrames? = null

    val byteCount: Int get() = layers.sumOf { it.second.allocationByteCount }
    /**
     * Something moves between folds. Live liquid only counts where this phone can draw it; on
     * Android 11-12 (or after a shader failure) its still poster must not be redrawn every frame.
     */
    val isAlive: Boolean get() = (ambient != null && !ambient.isStill) || (liquid != null && LiquidField.deviceSupported)

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

/**
 * Decoded video frames for the screens of a video pack. All calls on the main thread; listeners
 * fire there when new frames are ready.
 */
interface VideoFrames {
    fun latest(kind: ScreenKind): Bitmap?
    fun addListener(listener: () -> Unit)
    fun removeListener(listener: () -> Unit)
    fun setPlaying(play: Boolean)
    fun release()
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
    /** Solid fills only; overlayPaint keeps full alpha because its alpha would scale its shaders. */
    private val fillPaint = Paint()
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
    private val poseAlpha = HashMap<String, Float>()
    private val matrix = Matrix()
    private val cell = RectF()
    private val liquid = LiquidReveal()
    private val liquidField = LiquidField()
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
        prepareLiquid(scene, state, if (motion != null) progress else null)
        val reveal = motion?.reveal
        val rp = reveal?.progress(p) ?: 1f
        val revealing = reveal != null && rp < 0.999f

        var liquidDrawn = false
        if (revealing && reveal!!.type == RevealSpec.BOOK) {
            drawBook(canvas, scene, motion!!, p, ambient, state, reveal, rp)
        } else if (revealing && reveal!!.isLiquid &&
            liquid.draw(canvas, width, height, reveal, rp, frame.left + revealOriginX(reveal) * frame.width(), frame.top + revealOriginY(reveal) * frame.height(), scene.glow) { c ->
                c.drawColor(scene.background)
                drawLayers(c, scene, motion, p, ambient, state)
            }
        ) {
            liquidDrawn = true
        } else {
            drawLayers(canvas, scene, motion, p, ambient, state)
        }

        if (motion != null) {
            if (revealing && reveal!!.type == RevealSpec.TILES) drawTiles(canvas, scene, reveal, rp)
            // Order matters: darken, cover what is not uncovered yet, then add light on top, so
            // gathering particles and the bloom glow in the dark instead of being hidden by it.
            val dim = MotionSampler.poseFor(motion, MotionSpec.TARGET_DIM, p)
            if (dim.alpha > 0.003f) canvas.drawColor(SceneArtist.withAlpha(Color.BLACK, dim.alpha))
            if (revealing && !liquidDrawn && reveal!!.type != RevealSpec.BOOK && reveal.type != RevealSpec.TILES) drawReveal(canvas, reveal, rp, scene.glow)
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

    /**
     * Every depth layer with its transition pose plus sway and tilt; ambient systems slot in after
     * the layer they belong to. Remembers each layer's pose for the ambient systems and the tiles.
     */
    private fun drawLayers(canvas: Canvas, scene: LoadedScene, motion: MotionSpec?, p: Float, ambient: AmbientSpec?, state: FrameState) {
        val video = scene.videoFrames?.latest(scene.kind)
        for ((index, entry) in scene.layers.withIndex()) {
            val (layer, poster) = entry
            // A video scene's back layer is the live frame (same window and overscan as its poster).
            val bitmap = if (index == 0 && video != null) video else poster
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
            poseAlpha[layer.id] = pose.alpha
            drawLayerContent(canvas, layer, bitmap, index)

            if (ambient != null) {
                if (layer.id == "far") ambient.halo?.let { drawHalo(canvas, scene, it, state.timeMs) }
                for (system in ambient.systems) if (system.after == layer.id) drawSystem(canvas, scene, system, state, layer.id)
            }
        }
    }

    /**
     * One layer at the pose [drawLayers] last computed for it. A live liquid scene's back layer is
     * the liquid recorded for this frame, placed exactly where its poster would be.
     */
    private fun drawLayerContent(canvas: Canvas, layer: LayerEntry, bitmap: Bitmap, index: Int) {
        val scale = poseScale[layer.id] ?: 1f
        val tx = poseTx[layer.id] ?: 0f
        val ty = poseTy[layer.id] ?: 0f
        val l = pivotX + scale * (layer.left - pivotX) + tx
        val r = pivotX + scale * (layer.right - pivotX) + tx
        val t = pivotY + scale * (layer.top - pivotY) + ty
        val b = pivotY + scale * (layer.bottom - pivotY) + ty
        dst.set(frame.left + l * frame.width(), frame.top + t * frame.height(), frame.left + r * frame.width(), frame.top + b * frame.height())
        val alpha = (poseAlpha[layer.id] ?: 1f).coerceIn(0f, 1f)
        if (index == 0 && liquidField.ready && liquidField.draw(canvas, dst, alpha)) return
        if (bitmap.isRecycled) return
        bitmapPaint.alpha = (255 * alpha).toInt()
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
    }

    /**
     * Records this frame's live liquid for a v6 scene while it is alive (living motion on); the
     * poster stands in when it is still, on Android 11 and 12, and on software canvases. Liquid
     * glass also draws live during a fold motion with living motion off, since its drops are the
     * fold animation ([progress] is the motion's linear progress, null at rest).
     */
    private fun prepareLiquid(scene: LoadedScene, state: FrameState, progress: Float?) {
        val spec = scene.liquid
        val world = scene.world
        val back = scene.layers.firstOrNull()?.first
        val live = state.ambient || (spec?.isGlass == true && progress != null)
        if (spec == null || world == null || back == null || !live || !liquidField.supported) {
            liquidField.clear()
            return
        }
        val layerWorld = WorldRect(
            world.left + back.left * world.width,
            world.top + back.top * world.height,
            world.left + back.right * world.width,
            world.top + back.bottom * world.height,
        )
        // The fold line runs down the middle of the main screen (only the main screen uses it).
        val crease = world.left + world.width / 2f
        val motion = scene.liquidMotion.frame(state.timeMs, state.tiltX, state.tiltY, scene.kind, crease)
            .copy(tiltX = state.tiltX, tiltY = state.tiltY, kind = scene.kind, progress = progress, mainWorld = scene.mainWorld)
        liquidField.prepare(
            spec, layerWorld, frame.width() * (back.right - back.left), frame.height() * (back.bottom - back.top), motion,
            LiquidField.downscaleFor(spec),
        )
    }

    /** Flat gradient shown while a scene loads or when a pack is unavailable. Never black. */
    fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, top: Int, bottom: Int) {
        overlayPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        overlayPaint.shader = null
    }

    // ------------------------------------------------------------------ transition effects

    /** Where a ripple starts, in the normalised frame; other reveals use the frame's centre. */
    private fun revealOriginX(reveal: RevealSpec) = if (reveal.type == RevealSpec.RIPPLE) reveal.from[0] else 0.5f

    private fun revealOriginY(reveal: RevealSpec) = if (reveal.type == RevealSpec.RIPPLE) reveal.from[1] else 0.5f

    private fun drawReveal(canvas: Canvas, reveal: RevealSpec, rp: Float, glowColor: Int) {
        if (rp >= 0.999f) return
        val fw = frame.width()
        val fh = frame.height()
        val shade = SceneArtist.withAlpha(Color.BLACK, reveal.shade)
        val clear = SceneArtist.withAlpha(Color.BLACK, 0f)
        val edgeAlpha = reveal.edgeGlow * (1f - rp).pow(0.6f)
        when (reveal.type) {
            // Liquid reveals without a runtime shader (Android 11 and 12): their closest drawn cousin.
            RevealSpec.RIPPLE -> drawReveal(canvas, reveal.copy(type = RevealSpec.IRIS, from = listOf(reveal.from[0], reveal.from[1], 0f)), rp, glowColor)
            RevealSpec.FLOW -> drawReveal(canvas, reveal.copy(type = RevealSpec.SWEEP, from = listOf(reveal.from[0])), rp, glowColor)
            RevealSpec.GLASS -> {
                val a = reveal.shade * (1f - rp) * (1f - rp)
                if (a > 0.003f) canvas.drawColor(SceneArtist.withAlpha(Color.BLACK, a))
            }
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
            RevealSpec.SWEEP -> {
                // Work in a frame rotated so the band always travels along +x.
                val angle = reveal.from[0]
                val rad = Math.toRadians(angle.toDouble())
                val extent = abs(fw * cos(rad).toFloat()) + abs(fh * sin(rad).toFloat())
                val diag = sqrt(fw * fw + fh * fh)
                val soft = max(8f, reveal.softness * diag)
                val cx = frame.centerX()
                val cy = frame.centerY()
                // Starts at the frame edge so light arrives at once, and ends with the soft edge gone.
                val front = cx - extent / 2f + rp * (extent + soft)
                canvas.save()
                canvas.rotate(angle, cx, cy)
                overlayPaint.shader = LinearGradient(front, 0f, front + soft, 0f, clear, shade, Shader.TileMode.CLAMP)
                canvas.drawRect(front, cy - diag, cx + diag, cy + diag, overlayPaint)
                overlayPaint.shader = null
                val bandAlpha = reveal.edgeGlow * min(1f, (1f - rp) * 6f)
                if (bandAlpha > 0.01f) {
                    val bx = front + soft * 0.25f
                    val wide = max(12f, max(fw, fh) * 0.11f)
                    overlayPaint.blendMode = BlendMode.PLUS
                    overlayPaint.shader = LinearGradient(
                        bx - wide, 0f, bx + wide, 0f,
                        intArrayOf(SceneArtist.withAlpha(glowColor, 0f), SceneArtist.withAlpha(glowColor, bandAlpha * 0.42f), SceneArtist.withAlpha(glowColor, 0f)),
                        null, Shader.TileMode.CLAMP,
                    )
                    canvas.drawRect(bx - wide, cy - diag, bx + wide, cy + diag, overlayPaint)
                    val core = wide * 0.07f
                    val hot = SceneArtist.blend(glowColor, Color.WHITE, 0.55f)
                    overlayPaint.shader = LinearGradient(
                        bx - core, 0f, bx + core, 0f,
                        intArrayOf(SceneArtist.withAlpha(hot, 0f), SceneArtist.withAlpha(hot, bandAlpha * 0.65f), SceneArtist.withAlpha(hot, 0f)),
                        null, Shader.TileMode.CLAMP,
                    )
                    canvas.drawRect(bx - core, cy - diag, bx + core, cy + diag, overlayPaint)
                    overlayPaint.shader = null
                    overlayPaint.blendMode = null
                }
                canvas.restore()
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

    /**
     * BOOK: what lies left of the hinge is flat; the rest is a page swinging down from
     * startAngle in perspective, darker while it stands up and lit along its free edge. Behind
     * the page is darkness, and the flat side carries the page's shadow near the crease.
     */
    /**
     * BOOK: a page turns on [RevealSpec.from][0] (the hinge, as a fraction of the width) and lands
     * flat. With the hinge inside the screen (the main screen's crease) the LEFT part swings: on
     * the Fold the left panel carries the cover screen and swings from right to left as the phone
     * opens. With the hinge on the left edge (the cover) the whole screen swings shut around it
     * like a door, as the closed phone's hinge is on its left.
     */
    private fun drawBook(canvas: Canvas, scene: LoadedScene, motion: MotionSpec, p: Float, ambient: AmbientSpec?, state: FrameState, reveal: RevealSpec, rp: Float) {
        val fw = frame.width()
        val fh = frame.height()
        val hinge = frame.left + reveal.from[0] * fw
        // Motion written since 0.8.1 (and this app's older packs, recomposed on load) swing the
        // left part; motion from elsewhere keeps the right part swinging, as it was written for.
        val leftSwings = hinge > frame.left + 1f && motion.schemaVersion >= ChoreographyComposer.MOTION_SCHEMA
        val angle = reveal.from[1] * (1f - rp)
        val tilt = sin(Math.toRadians(angle.toDouble()).toFloat()).coerceIn(0f, 1f)
        val top = frame.top - 2f
        val bottom = frame.bottom + 2f
        // The still side and the turning side.
        val stillL = if (leftSwings) hinge else frame.left - 2f
        val stillR = if (leftSwings) frame.right + 2f else hinge
        val turnL = if (leftSwings) frame.left - 2f else hinge
        val turnR = if (leftSwings) hinge else frame.right + 2f

        // The still side, which also computes this frame's layer poses.
        canvas.save()
        canvas.clipRect(stillL, top, stillR, bottom)
        drawLayers(canvas, scene, motion, p, ambient, state)
        canvas.restore()
        fillPaint.color = SceneArtist.withAlpha(Color.BLACK, 0.55f)
        canvas.drawRect(turnL, top, turnR, bottom, fillPaint)
        // The turning page shades the still side next to the hinge.
        if (stillR - stillL > 2f && tilt > 0.01f) {
            val reach = fw * 0.22f
            val shadow = SceneArtist.withAlpha(Color.BLACK, reveal.shade * 0.65f * tilt)
            val clear = SceneArtist.withAlpha(Color.BLACK, 0f)
            overlayPaint.shader = if (leftSwings) LinearGradient(hinge, 0f, hinge + reach, 0f, shadow, clear, Shader.TileMode.CLAMP)
            else LinearGradient(hinge - reach, 0f, hinge, 0f, clear, shadow, Shader.TileMode.CLAMP)
            if (leftSwings) canvas.drawRect(hinge, top, hinge + reach, bottom, overlayPaint) else canvas.drawRect(hinge - reach, top, hinge, bottom, overlayPaint)
            overlayPaint.shader = null
        }
        if (angle >= 89.5f) return

        // The eye sits in front of the screen centre, far enough that the free edge, lifted
        // towards the viewer, never reaches it.
        val eye = max(turnR - turnL, fh) * 1.6f
        // The free edge lifts towards the viewer on either side of the hinge.
        if (!turn(matrix, turnL, top, turnR, bottom, hinge, vertical = true, degrees = if (leftSwings) -angle else angle, eyeX = drawWidth / 2f, eyeY = drawHeight / 2f, eye = eye)) return

        canvas.save()
        canvas.concat(matrix)
        canvas.clipRect(turnL, top, turnR, bottom)
        drawLayers(canvas, scene, motion, p, ambient, state)
        if (tilt > 0.01f) canvas.drawColor(SceneArtist.withAlpha(Color.BLACK, reveal.shade * tilt.pow(0.8f)))
        val edgeAlpha = reveal.edgeGlow * sin(PI.toFloat() * tilt.coerceAtMost(1f)).coerceAtLeast(0f) + reveal.edgeGlow * 0.25f * tilt
        if (edgeAlpha > 0.01f) {
            // Light along the free edge of the page.
            val band = max(6f, (turnR - turnL) * 0.06f)
            val edge = if (leftSwings) frame.left else frame.right
            val inner = if (leftSwings) edge + band else edge - band
            overlayPaint.blendMode = BlendMode.PLUS
            overlayPaint.shader = LinearGradient(inner, 0f, edge, 0f, SceneArtist.withAlpha(scene.glow, 0f), SceneArtist.withAlpha(scene.glow, edgeAlpha * 0.6f), Shader.TileMode.CLAMP)
            canvas.drawRect(min(inner, edge) - 2f, top, max(inner, edge) + 2f, bottom, overlayPaint)
            overlayPaint.shader = null
            overlayPaint.blendMode = null
        }
        canvas.restore()
    }

    /**
     * TILES: the finished scene is already drawn; every tile that has not landed yet is covered,
     * and the ones in flight are drawn turning about their own axis, catching the light.
     */
    private fun drawTiles(canvas: Canvas, scene: LoadedScene, reveal: RevealSpec, rp: Float) {
        val cols = reveal.from[0].toInt().coerceAtLeast(1)
        val rows = reveal.from[1].toInt().coerceAtLeast(1)
        val ox = reveal.from[2]
        val oy = reveal.from[3]
        val aroundX = reveal.from[4] >= 0.5f
        val fw = frame.width()
        val fh = frame.height()
        val cw = fw / cols
        val ch = fh / rows
        val aspect = fw / fh
        fun distance(i: Int, j: Int) = sqrt(sq(((i + 0.5f) / cols - ox) * aspect) + sq((j + 0.5f) / rows - oy))
        var farthest = 1e-3f
        for (i in 0 until cols) for (j in 0 until rows) farthest = max(farthest, distance(i, j))
        val wave = 0.6f
        val turn = 1f - wave
        val gap = max(1f, min(cw, ch) * 0.012f)
        val cover = SceneArtist.withAlpha(Color.BLACK, reveal.shade)
        for (j in 0 until rows) for (i in 0 until cols) {
            val left = frame.left + i * cw
            val top = frame.top + j * ch
            if (left > drawWidth || top > drawHeight || left + cw < 0f || top + ch < 0f) continue
            val startAt = wave * (distance(i, j) / farthest) * (0.88f + 0.12f * hash01(cols * 31 + rows, i, j))
            val tp = ((rp - startAt) / turn).coerceIn(0f, 1f)
            if (tp >= 1f) continue
            // Whole-pixel edges so neighbouring covers meet without a hairline of scene between them.
            cell.set(
                kotlin.math.round(left), kotlin.math.round(top),
                kotlin.math.round(frame.left + (i + 1) * cw), kotlin.math.round(frame.top + (j + 1) * ch),
            )
            fillPaint.color = cover
            canvas.drawRect(cell, fillPaint)
            if (tp <= 0f) continue
            val e = RevealSpec.STANDARD.ease(tp)
            val angle = 90f * (1f - e)
            if (angle >= 89.5f) continue
            val axis = if (aroundX) cell.centerY() else cell.centerX()
            val eye = max(fw, fh) * 1.6f
            if (!turn(matrix, cell.left, cell.top, cell.right, cell.bottom, axis, vertical = !aroundX, degrees = angle, eyeX = cell.centerX(), eyeY = cell.centerY(), eye = eye)) continue
            canvas.save()
            canvas.concat(matrix)
            cell.inset(gap * (1f - e), gap * (1f - e))
            canvas.clipRect(cell)
            val video = scene.videoFrames?.latest(scene.kind)
            for ((index, entry) in scene.layers.withIndex()) {
                val bitmap = if (index == 0 && video != null) video else entry.second
                drawLayerContent(canvas, entry.first, bitmap, index)
            }
            val dark = reveal.shade * 0.75f * (1f - e)
            if (dark > 0.01f) canvas.drawColor(SceneArtist.withAlpha(Color.BLACK, dark))
            val shine = reveal.edgeGlow * 0.16f * sin(PI.toFloat() * e)
            if (shine > 0.01f) canvas.drawColor(SceneArtist.withAlpha(scene.glow, shine), BlendMode.PLUS)
            canvas.restore()
        }
    }

    private val turnSrc = FloatArray(8)
    private val turnDst = FloatArray(8)

    /**
     * Perspective of the flat rect [l,t,r,b] turned by [degrees] about the line x = axis
     * ([vertical]) or y = axis, the part beyond the axis lifting towards an eye [eye] pixels in
     * front of (eyeX, eyeY). Computed as an exact four-corner homography, so it does not depend on
     * android.graphics.Camera. Returns false when the rect is seen edge-on.
     */
    private fun turn(out: Matrix, l: Float, t: Float, r: Float, b: Float, axis: Float, vertical: Boolean, degrees: Float, eyeX: Float, eyeY: Float, eye: Float): Boolean {
        val rad = Math.toRadians(degrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        if (cosA < 0.01f) return false
        turnSrc[0] = l; turnSrc[1] = t
        turnSrc[2] = r; turnSrc[3] = t
        turnSrc[4] = r; turnSrc[5] = b
        turnSrc[6] = l; turnSrc[7] = b
        for (k in 0 until 4) {
            val x = turnSrc[2 * k]
            val y = turnSrc[2 * k + 1]
            val u = (if (vertical) x else y) - axis
            val z = u * sinA
            val s = eye / max(1f, eye - z)
            val px = if (vertical) axis + u * cosA else x
            val py = if (vertical) y else axis + u * cosA
            turnDst[2 * k] = eyeX + (px - eyeX) * s
            turnDst[2 * k + 1] = eyeY + (py - eyeY) * s
        }
        return out.setPolyToPoly(turnSrc, 0, turnDst, 0, 4)
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
            when (burst.mode) {
                ParticleBurst.STREAK -> drawStreaks(canvas, burst, bp, ox, oy, diag, short, colors)
                ParticleBurst.RISE -> drawRising(canvas, burst, bp, ox, oy, fw, fh, short, colors)
                else -> drawBurst(canvas, burst, bp, ox, oy, diag, short, colors)
            }
        }
    }

    private fun drawBurst(canvas: Canvas, burst: ParticleBurst, bp: Float, ox: Float, oy: Float, diag: Float, short: Float, colors: List<Int>) {
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

    /** Lines of light rushing outwards, accelerating and stretching like stars at light speed. */
    private fun drawStreaks(canvas: Canvas, burst: ParticleBurst, bp: Float, ox: Float, oy: Float, diag: Float, short: Float, colors: List<Int>) {
        linePaint.blendMode = BlendMode.PLUS
        linePaint.shader = null
        for (i in 0 until burst.count) {
            val delay = 0.4f * hash01(burst.seed, i, 4)
            val lt = ((bp - delay) / (1f - delay)).coerceIn(0f, 1f)
            if (lt <= 0f || lt >= 1f) continue
            val theta = hash01(burst.seed, i, 1) * 2f * PI.toFloat()
            val h2 = hash01(burst.seed, i, 2)
            // Stars start all over the field, not only near the centre, so it reads as depth.
            val r0 = (0.02f + 0.42f * h2 * h2) * diag
            val travel = burst.spread * diag * (0.55f + 0.45f * hash01(burst.seed, i, 3))
            val head = r0 + travel * lt * lt
            val length = travel * (0.04f + 0.5f * lt) * (0.4f + 0.6f * h2)
            val tail = max(r0 * 0.6f, head - length)
            val mid = (head + tail) / 2f
            val alpha = 0.8f * sin(PI.toFloat() * lt).pow(0.5f) * (0.5f + 0.5f * hash01(burst.seed, i, 5))
            val dx = cos(theta)
            val dy = sin(theta)
            val c = colors[i % colors.size]
            val width = max(1.2f, (burst.sizeMin + (burst.sizeMax - burst.sizeMin) * hash01(burst.seed, i, 6)) * short)
            linePaint.strokeWidth = width
            linePaint.color = SceneArtist.withAlpha(c, alpha * 0.35f)
            canvas.drawLine(ox + dx * tail, oy + dy * tail, ox + dx * mid, oy + dy * mid, linePaint)
            linePaint.color = SceneArtist.withAlpha(c, alpha)
            canvas.drawLine(ox + dx * mid, oy + dy * mid, ox + dx * head, oy + dy * head, linePaint)
        }
        linePaint.blendMode = null
    }

    /** Motes that float up from a band, swaying a little, like embers or bubbles. */
    private fun drawRising(canvas: Canvas, burst: ParticleBurst, bp: Float, ox: Float, oy: Float, fw: Float, fh: Float, short: Float, colors: List<Int>) {
        for (i in 0 until burst.count) {
            val delay = 0.5f * hash01(burst.seed, i, 4)
            val lt = ((bp - delay) / (1f - delay)).coerceIn(0f, 1f)
            if (lt <= 0f || lt >= 1f) continue
            val h3 = hash01(burst.seed, i, 3)
            val x = ox + (hash01(burst.seed, i, 1) * 2f - 1f) * burst.spread * fw + sin(lt * 5f + i) * 0.012f * fw
            val startY = oy - hash01(burst.seed, i, 2) * 0.05f * fh
            val y = startY - (0.2f + 0.5f * h3) * fh * (1f - (1f - lt) * (1f - lt))
            val alpha = sin(PI.toFloat() * lt).pow(0.8f) * (0.5f + 0.5f * hash01(burst.seed, i, 5))
            val size = (burst.sizeMin + (burst.sizeMax - burst.sizeMin) * h3 * h3) * short
            sprite(canvas, x, y, size * 3f, colors[i % colors.size], alpha)
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

        private fun sq(v: Float) = v * v
    }
}
