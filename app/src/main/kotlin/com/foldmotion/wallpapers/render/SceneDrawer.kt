package com.foldmotion.wallpapers.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.MotionSampler
import com.foldmotion.core.motion.MotionSpec
import com.foldmotion.core.motion.TrackPose
import com.foldmotion.core.pack.FoldPackManifest
import com.foldmotion.core.pack.LayerEntry
import kotlin.math.max
import kotlin.math.min

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
) {
    val byteCount: Int get() = layers.sumOf { it.second.allocationByteCount }

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
 * Composites a [LoadedScene] onto any canvas: the live wallpaper surface and the in-app preview
 * both call this, so what the user previews is drawn by the same code that runs on the home screen.
 *
 * The scene's normalised frame is fitted to cover the surface. When the surface has a different
 * shape than the scene was authored for (rotation, insets, another device), the frame is cropped,
 * shifting the crop towards the focal point so it stays visible.
 */
class SceneDrawer {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val frame = RectF()

    /**
     * @param progress linear transition progress in [0,1], or null for the resting pose.
     */
    fun draw(canvas: Canvas, width: Int, height: Int, scene: LoadedScene, progress: Float?) {
        computeFrame(width.toFloat(), height.toFloat(), scene, frame)
        canvas.drawColor(scene.background)

        val motion = if (progress != null) scene.entryMotion else null
        val pivotX = motion?.pivotX ?: scene.focusX
        val pivotY = motion?.pivotY ?: scene.focusY
        val p = progress ?: 1f

        for ((layer, bitmap) in scene.layers) {
            if (bitmap.isRecycled) continue
            val pose = if (motion == null) TrackPose.REST else MotionSampler.poseFor(motion, MotionSampler.layerTarget(layer.id), p)
            val l = pivotX + pose.scale * (layer.left - pivotX) + pose.tx
            val r = pivotX + pose.scale * (layer.right - pivotX) + pose.tx
            val t = pivotY + pose.scale * (layer.top - pivotY) + pose.ty
            val b = pivotY + pose.scale * (layer.bottom - pivotY) + pose.ty
            dst.set(
                frame.left + l * frame.width(),
                frame.top + t * frame.height(),
                frame.left + r * frame.width(),
                frame.top + b * frame.height(),
            )
            bitmapPaint.alpha = (255 * pose.alpha.coerceIn(0f, 1f)).toInt()
            canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
        }

        if (motion != null) {
            val glow = MotionSampler.poseFor(motion, MotionSpec.TARGET_GLOW, p)
            if (glow.alpha > 0.003f) {
                val cx = frame.left + pivotX * frame.width()
                val cy = frame.top + pivotY * frame.height()
                val radius = max(1f, glow.scale * 0.45f * min(width, height).toFloat())
                overlayPaint.shader = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(SceneArtist.withAlpha(scene.glow, glow.alpha), SceneArtist.withAlpha(scene.glow, 0f)),
                    null, Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(cx, cy, radius, overlayPaint)
                overlayPaint.shader = null
            }
            val dim = MotionSampler.poseFor(motion, MotionSpec.TARGET_DIM, p)
            if (dim.alpha > 0.003f) canvas.drawColor(SceneArtist.withAlpha(Color.BLACK, dim.alpha))
        }
    }

    /** Flat gradient shown while a scene loads or when a pack is unavailable. Never black. */
    fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, top: Int, bottom: Int) {
        overlayPaint.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        overlayPaint.shader = null
    }

    companion object {
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
    }
}
