package com.foldmotion.wallpapers.generator.depth

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.AmbientSpec
import com.foldmotion.core.motion.ChoreoInput
import com.foldmotion.core.motion.ChoreographyComposer
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.motion.MotionLayer
import com.foldmotion.core.motion.MotionSpec
import com.foldmotion.core.pack.Checksums
import com.foldmotion.core.pack.FoldPackManifest
import com.foldmotion.core.pack.FoldPackValidator
import com.foldmotion.core.pack.GenerationRecipe
import com.foldmotion.core.pack.LayerEntry
import com.foldmotion.core.pack.LocalizedText
import com.foldmotion.core.pack.PackColors
import com.foldmotion.core.pack.Previews
import com.foldmotion.core.pack.SceneEntry
import com.foldmotion.core.pack.Scenes
import com.foldmotion.core.pack.Sha256
import com.foldmotion.core.pack.TransitionRef
import com.foldmotion.core.pack.Transitions
import com.foldmotion.core.pack.VersionRange
import com.foldmotion.core.pack.VideoGeometry
import com.foldmotion.core.scene.Celestial
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.core.scene.SceneFrames
import com.foldmotion.core.scene.TimeOfDay
import com.foldmotion.core.scene.WorldRect
import com.foldmotion.wallpapers.pack.PackStore
import com.foldmotion.wallpapers.pack.StoredPack
import com.foldmotion.wallpapers.telemetry.PocTrace
import java.io.File
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PhotoRequest(
    val style: FoldStyle = FoldStyle.AUTO,
    val intensity: MotionIntensity = MotionIntensity.LOW,
    val seed: Long,
    val title: LocalizedText = LocalizedText("صورتي ثلاثية الأبعاد", "My 3D photo"),
)

class PhotoPackException(message: String) : Exception(message)

/**
 * A 3D photo wallpaper from the user's own photo. The subject (found on the phone by a
 * [SubjectMasker]) becomes a front layer and the rest a back layer, with the hole behind the
 * subject filled from its surroundings. Tilt the phone and the subject moves against the
 * background; the fold animations move the two layers apart (Rise lifts the subject out of the
 * photo). Without a clear subject it is a single-layer photo wallpaper.
 *
 * It is an ordinary drawn pack (renderer 3): no new format, plays wherever packs play.
 */
class DepthPackBuilder(private val store: PackStore, private val validator: FoldPackValidator) {

    class Result(val pack: StoredPack, val hasSubject: Boolean)

    fun build(photo: Bitmap, request: PhotoRequest, profile: DeviceProfile, masker: SubjectMasker): Result {
        val mask = try {
            masker.mask(photo)?.takeIf { it.width == photo.width && it.height == photo.height }
        } catch (e: Throwable) {
            null
        }
        val staging = store.newStagingDir()
        try {
            val (fx, fy) = if (mask != null) centroid(mask) else 0.5f to 0.5f
            val crops = VideoGeometry.crops(profile, photo.width, photo.height, fx, fy)
            val background = if (mask != null) fillBehind(photo, mask) else photo
            val subject = mask?.let { cutout(photo, it) }

            val scenes = HashMap<ScreenKind, SceneEntry>()
            for (kind in listOf(ScreenKind.COVER, ScreenKind.MAIN)) {
                val crop = if (kind == ScreenKind.MAIN) crops.main else crops.cover
                scenes[kind] = writeScene(staging, kind, profile, background, subject, crop, fx, fy)
            }
            writePreview(staging, "previews/main.webp", photo, crops.main, PREVIEW_MAIN_WIDTH, profile.screen(ScreenKind.MAIN).aspect)
            writePreview(staging, "previews/cover.webp", photo, crops.cover, PREVIEW_COVER_WIDTH, profile.screen(ScreenKind.COVER).aspect)
            val palette = palette(photo)

            val unit = max(photo.width, photo.height).toFloat()
            fun world(r: List<Float>) = WorldRect(r[0] * photo.width / unit, r[1] * photo.height / unit, r[2] * photo.width / unit, r[3] * photo.height / unit)
            val frames = SceneFrames(world(crops.cover), world(crops.main), fx * photo.width / unit, fy * photo.height / unit)
            val layers = buildList {
                add(MotionLayer("far", 0f, -OVERSCAN, -OVERSCAN, 1f + OVERSCAN, 1f + OVERSCAN, opaque = true))
                if (subject != null) add(MotionLayer("near", 1f, -OVERSCAN, -OVERSCAN, 1f + OVERSCAN, 1f + OVERSCAN, opaque = false))
            }
            // A subject wants the style that lifts it out of the picture.
            val style = if (request.style == FoldStyle.AUTO) (if (subject != null) FoldStyle.RISE else FoldStyle.UNFOLD) else request.style
            val choreo = ChoreographyComposer.compose(
                ChoreoInput(
                    family = SceneFamily.ABSTRACT,
                    time = if (palette.dark) TimeOfDay.NIGHT else TimeOfDay.DAY,
                    celestial = Celestial.NONE,
                    intensity = request.intensity,
                    frames = frames,
                    mainLayers = layers,
                    coverLayers = layers,
                    glow = palette.glow,
                    accent = palette.accent,
                    seed = request.seed,
                ),
                style,
            )
            File(staging, "motion").mkdirs()
            File(staging, "motion/open.json").writeText(CoreInfo.json.encodeToString(MotionSpec.serializer(), choreo.open))
            File(staging, "motion/close.json").writeText(CoreInfo.json.encodeToString(MotionSpec.serializer(), choreo.close))
            // Between folds: the subject follows the phone's tilt three times as far as a drawn
            // scene's front layer does, and drifts very slowly, so the depth is always felt.
            val ambient = AmbientSpec(fps = 30, sway = if (subject != null) 0.003f else 0.002f, swayPeriodMs = 14_000, tilt = if (subject != null) 0.036f else 0f)
            File(staging, AmbientSpec.FILE).writeText(CoreInfo.json.encodeToString(AmbientSpec.serializer(), ambient))

            fun rect(r: WorldRect) = listOf(r.left, r.top, r.right, r.bottom).map(::round4)
            val now = Instant.now()
            val manifest = FoldPackManifest(
                id = "fp-" + now.toEpochMilli().toString(36) + "-" + Sha256.of("${photo.width}x${photo.height}" + request.seed).take(6),
                title = request.title,
                createdAt = now.toString(),
                creationSource = FoldPackManifest.SOURCE_PHOTO,
                profileIds = listOf(profile.id),
                rendererVersion = VersionRange(CoreInfo.minRendererFor(video = false, style = choreo.style.name), CoreInfo.RENDERER_VERSION),
                motionIntensity = request.intensity,
                scenes = Scenes(
                    cover = scenes.getValue(ScreenKind.COVER).copy(world = rect(frames.cover)),
                    main = scenes.getValue(ScreenKind.MAIN).copy(world = rect(frames.main)),
                ),
                transitions = Transitions(
                    open = TransitionRef("motion/open.json", choreo.open.durationMs),
                    close = TransitionRef("motion/close.json", choreo.close.durationMs),
                ),
                ambient = AmbientSpec.FILE,
                previews = Previews(cover = "previews/cover.webp", main = "previews/main.webp"),
                motionStyle = choreo.style.name,
                colors = PackColors(hex(palette.glow), hex(palette.accent), hex(palette.mid), hex(palette.background)),
                generation = GenerationRecipe(provider = PROVIDER, providerVersion = "1.0.0", seed = request.seed, mode = "CUSTOM"),
                tags = if (subject != null) listOf("photo", "depth") else listOf("photo"),
            )
            File(staging, FoldPackManifest.FILE_NAME).writeText(CoreInfo.json.encodeToString(FoldPackManifest.serializer(), manifest))
            Checksums.write(staging)
            val report = validator.validate(staging, profile.id)
            if (!report.passed) {
                PocTrace.log("photo", "QA_FAILED", report.issues.joinToString { "${it.code}:${it.detail}" })
                throw PhotoPackException(report.codes.joinToString())
            }
            if (background !== photo) background.recycle()
            subject?.recycle()
            mask?.recycle()
            val stored = store.commit(staging, manifest)
            PocTrace.log("photo", "READY", "${manifest.id} subject=${subject != null} style=${choreo.style}")
            return Result(stored, subject != null)
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** One screen: the background crop and, when there is one, the subject crop over it. */
    private fun writeScene(dir: File, kind: ScreenKind, profile: DeviceProfile, background: Bitmap, subject: Bitmap?, crop: List<Float>, fx: Float, fy: Float): SceneEntry {
        val screen = profile.screen(kind)
        val span = 1f + 2f * OVERSCAN
        val cropW = (crop[2] - crop[0]) * background.width
        val cropH = (crop[3] - crop[1]) * background.height
        val scale = min(1f, POSTER_LONG_SIDE / max(cropW * span, cropH * span))
        val w = (cropW * span * scale).roundToInt().coerceIn(16, 4096)
        val h = (cropH * span * scale).roundToInt().coerceIn(16, 4096)
        val name = if (kind == ScreenKind.MAIN) "main" else "cover"
        val rect = listOf(-OVERSCAN, -OVERSCAN, 1f + OVERSCAN, 1f + OVERSCAN)
        val layers = ArrayList<LayerEntry>()
        val far = window(background, expand(crop), w, h, opaque = true)
        val farEntry = LayerEntry("far", "layers/${name}_far.webp", w, h, rect, 0f, opaque = true)
        File(dir, farEntry.path).apply { parentFile?.mkdirs() }.outputStream().use { far.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, it) }
        far.recycle()
        layers += farEntry
        if (subject != null) {
            val near = window(subject, expand(crop), w, h, opaque = false)
            val nearEntry = LayerEntry("near", "layers/${name}_near.webp", w, h, rect, 1f, opaque = false)
            File(dir, nearEntry.path).outputStream().use { near.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 80, it) }
            near.recycle()
            layers += nearEntry
        }
        val sx = ((fx - crop[0]) / (crop[2] - crop[0])).coerceIn(0f, 1f)
        val sy = ((fy - crop[1]) / (crop[3] - crop[1])).coerceIn(0f, 1f)
        return SceneEntry(baseWidth = screen.widthPx, baseHeight = screen.heightPx, focus = listOf(round4(sx), round4(sy)), layers = layers)
    }

    private fun writePreview(dir: File, path: String, photo: Bitmap, crop: List<Float>, width: Int, aspect: Float) {
        val height = (width / aspect).roundToInt()
        val bitmap = window(photo, crop, width, height, opaque = true)
        File(dir, path).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
        bitmap.recycle()
    }

    private class Palette(val glow: Int, val accent: Int, val mid: Int, val background: Int, val dark: Boolean)

    companion object {
        const val PROVIDER = "local-photo"
        const val OVERSCAN = 0.06f
        const val POSTER_LONG_SIDE = 1600f
        const val PREVIEW_MAIN_WIDTH = 540
        const val PREVIEW_COVER_WIDTH = 300

        /** Mask-weighted centre of the subject, as fractions of the photo. */
        fun centroid(mask: Bitmap): Pair<Float, Float> {
            val step = max(1, max(mask.width, mask.height) / 256)
            var sx = 0.0
            var sy = 0.0
            var total = 0.0
            val row = IntArray(mask.width)
            for (y in 0 until mask.height step step) {
                mask.getPixels(row, 0, mask.width, 0, y, mask.width, 1)
                for (x in 0 until mask.width step step) {
                    val a = Color.alpha(row[x]) / 255.0
                    sx += a * x
                    sy += a * y
                    total += a
                }
            }
            if (total < 1e-3) return 0.5f to 0.5f
            return (sx / total / mask.width).toFloat() to (sy / total / mask.height).toFloat()
        }

        /**
         * The photo with the subject cut out: its mask as alpha. Done per pixel, because a mask
         * bitmap drawn on a canvas acts as coverage, which "keep only inside" modes ignore.
         */
        fun cutout(photo: Bitmap, mask: Bitmap): Bitmap {
            val w = photo.width
            val h = photo.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val rgb = IntArray(w)
            val alpha = IntArray(w)
            for (y in 0 until h) {
                photo.getPixels(rgb, 0, w, 0, y, w, 1)
                mask.getPixels(alpha, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val a = Color.alpha(alpha[x]) * Color.alpha(rgb[x]) / 255
                    rgb[x] = (a shl 24) or (rgb[x] and 0xFFFFFF)
                }
                out.setPixels(rgb, 0, w, 0, y, w, 1)
            }
            return out
        }

        /**
         * The background with the subject's place filled from what surrounds it (push-pull: the
         * known pixels are averaged down a pyramid and the hole takes the coarser averages), so
         * moving the subject never shows its own ghost behind it.
         */
        fun fillBehind(photo: Bitmap, mask: Bitmap): Bitmap {
            val w = photo.width
            val h = photo.height
            // Grow the hole a little so the subject's fringe does not stay behind.
            val grow = max(2f, min(w, h) * 0.02f)
            val hole = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            Canvas(hole).drawBitmap(mask, 0f, 0f, Paint().apply { maskFilter = BlurMaskFilter(grow, BlurMaskFilter.Blur.OUTER) })
            Canvas(hole).drawBitmap(mask, 0f, 0f, null)
            // Known pixels: the photo with alpha = 1 - hole.
            val known = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(known).apply {
                drawBitmap(photo, 0f, 0f, null)
                drawBitmap(hole, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            }
            hole.recycle()
            val pyramid = ArrayList<Bitmap>()
            var level = known
            while (max(level.width, level.height) > 8) {
                val next = Bitmap.createScaledBitmap(level, max(1, level.width / 2), max(1, level.height / 2), true)
                pyramid += next
                level = next
            }
            // Pull: from the coarsest level up, fill each level's holes with the level below it.
            var filled: Bitmap = pyramid.lastOrNull() ?: known
            for (i in pyramid.size - 2 downTo -1) {
                val target = if (i >= 0) pyramid[i] else known
                val up = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
                Canvas(up).apply {
                    // The coarser level, made opaque (each pixel's own average colour), under the
                    // known pixels of this level.
                    val solid = opaque(filled)
                    drawBitmap(solid, null, android.graphics.Rect(0, 0, target.width, target.height), Paint(Paint.FILTER_BITMAP_FLAG))
                    solid.recycle()
                    drawBitmap(target, 0f, 0f, null)
                }
                if (filled !== known && filled !in pyramid) filled.recycle()
                filled = up
            }
            pyramid.forEach { if (it !== filled) it.recycle() }
            if (filled !== known) known.recycle()
            // Everything opaque in the end.
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(Color.BLACK)
                drawBitmap(filled, 0f, 0f, null)
            }
            if (filled !== known) filled.recycle() else known.recycle()
            return out
        }

        /** [b] with every partly transparent pixel made opaque in its own (un-premultiplied) colour. */
        private fun opaque(b: Bitmap): Bitmap {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            for (i in px.indices) if (px[i] ushr 24 != 0) px[i] = px[i] or (0xFF shl 24)
            return Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, b.width, 0, 0, b.width, b.height) }
        }

        private fun expand(c: List<Float>): List<Float> {
            val w = c[2] - c[0]
            val h = c[3] - c[1]
            return listOf(c[0] - OVERSCAN * w, c[1] - OVERSCAN * h, c[2] + OVERSCAN * w, c[3] + OVERSCAN * h)
        }

        /** [crop] of [source] (fractions, may reach past its edges) as a [w] x [h] bitmap; edges clamp. */
        private fun window(source: Bitmap, crop: List<Float>, w: Int, h: Int, opaque: Boolean): Bitmap {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val sx = w / ((crop[2] - crop[0]) * source.width)
            val sy = h / ((crop[3] - crop[1]) * source.height)
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(-crop[0] * source.width, -crop[1] * source.height); postScale(sx, sy) })
            }
            val c = Canvas(out)
            if (opaque) c.drawColor(Color.BLACK)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
            return out
        }

        private fun palette(photo: Bitmap): Palette {
            val small = Bitmap.createScaledBitmap(photo, 48, max(1, 48 * photo.height / max(1, photo.width)), true)
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            if (small !== photo) small.recycle()
            fun lum(c: Int) = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
            val sorted = px.sortedBy(::lum)
            val dark = sorted[sorted.size / 10]
            val mid = sorted[sorted.size / 2]
            val light = sorted[sorted.size * 9 / 10]
            // The most saturated pixel among the brighter half is the accent.
            val accent = sorted.drop(sorted.size / 2).maxByOrNull { c ->
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                max(r, max(g, b)) - min(r, min(g, b))
            } ?: light
            return Palette(light, accent, mid, dark, lum(mid) < 110f)
        }

        private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

        private fun round4(v: Float) = (v * 10000f).roundToInt() / 10000f
    }
}
