package com.foldmotion.wallpapers.generator.video

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.ScreenKind
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
import com.foldmotion.core.pack.VideoSpec
import com.foldmotion.core.scene.Celestial
import com.foldmotion.core.scene.ReferencePalette
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.core.scene.SceneFrames
import com.foldmotion.core.scene.TimeOfDay
import com.foldmotion.core.scene.WorldRect
import com.foldmotion.wallpapers.pack.PackStore
import com.foldmotion.wallpapers.pack.StoredPack
import com.foldmotion.wallpapers.telemetry.PocTrace
import kotlinx.coroutines.CancellationException
import java.io.File
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A wallpaper from the user's own clip. */
data class VideoRequest(
    val uri: Uri,
    val startMs: Long,
    val endMs: Long,
    val style: FoldStyle = FoldStyle.AUTO,
    val intensity: MotionIntensity = MotionIntensity.LOW,
    /** Cross-fade the loop seam; off for clips that already loop perfectly. */
    val crossfadeLoop: Boolean = true,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val seed: Long,
    val title: LocalizedText = LocalizedText("فيديو خاص بي", "My video"),
    /** The file is already a prepared H.264 clip (the built-in sample): copy instead of re-encoding. */
    val prepared: Boolean = false,
)

enum class VideoStage { TRIM, POSTERS, MOTION, QA }

class VideoPackException(val code: Code, message: String = code.name) : Exception(message) {
    enum class Code { UNREADABLE, TOO_SHORT, TRANSCODE_FAILED, STORAGE_FULL, VALIDATION_FAILED }
}

/**
 * Turns a clip into a FoldPack: trimmed and scaled on the phone, one poster frame per screen,
 * colours taken from the clip, fold animations designed in the chosen style, and the same
 * validation every pack passes. Nothing leaves the phone.
 */
class VideoPackBuilder(
    private val store: PackStore,
    private val validator: FoldPackValidator,
    private val tools: VideoTools,
) {

    suspend fun build(request: VideoRequest, profile: DeviceProfile, onStage: (VideoStage, Float) -> Unit = { _, _ -> }): StoredPack {
        if (store.freeBytes() < MIN_FREE_BYTES) throw VideoPackException(VideoPackException.Code.STORAGE_FULL)
        val length = request.endMs - request.startMs
        if (length < VideoSpec.MIN_DURATION_MS) throw VideoPackException(VideoPackException.Code.TOO_SHORT)
        val staging = store.newStagingDir()
        try {
            onStage(VideoStage.TRIM, 0f)
            val clip = File(staging, VideoSpec.FILE)
            clip.parentFile?.mkdirs()
            if (request.prepared) {
                store.copyIn(request.uri, clip)
            } else {
                try {
                    tools.transcode(request.uri, request.startMs, request.endMs, MAX_LONG_SIDE, MAX_FRAME_RATE, clip) { onStage(VideoStage.TRIM, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PocTrace.log("video", "TRANSCODE_FAILED", e.message ?: e.javaClass.simpleName)
                    throw VideoPackException(VideoPackException.Code.TRANSCODE_FAILED, e.message ?: "transcode")
                }
            }
            val info = tools.probe(Uri.fromFile(clip)) ?: throw VideoPackException(VideoPackException.Code.UNREADABLE)
            val duration = info.durationMs.coerceAtMost(VideoSpec.MAX_DURATION_MS.toLong()).toInt()
            if (duration < VideoSpec.MIN_DURATION_MS) throw VideoPackException(VideoPackException.Code.TOO_SHORT)

            onStage(VideoStage.POSTERS, 0f)
            val crops = VideoGeometry.crops(profile, info.width, info.height, request.focusX, request.focusY)
            val frame = tools.frameAt(clip, 0, POSTER_SOURCE_LONG_SIDE) ?: throw VideoPackException(VideoPackException.Code.UNREADABLE)
            val scenes = HashMap<ScreenKind, SceneEntry>()
            for (kind in listOf(ScreenKind.COVER, ScreenKind.MAIN)) {
                scenes[kind] = writePoster(staging, kind, profile, frame, info, if (kind == ScreenKind.MAIN) crops.main else crops.cover, request)
            }
            writePreview(staging, "previews/main.webp", frame, info, crops.main, PREVIEW_MAIN_WIDTH, profile.screen(ScreenKind.MAIN).aspect)
            writePreview(staging, "previews/cover.webp", frame, info, crops.cover, PREVIEW_COVER_WIDTH, profile.screen(ScreenKind.COVER).aspect)
            val palette = paletteOf(frame)
            frame.recycle()

            onStage(VideoStage.MOTION, 0f)
            // The clip is the world: frames are the crop windows, in units of the clip's long side so
            // both axes share one scale (tiles stay square, the iris stays round).
            val unit = max(info.width, info.height).toFloat()
            fun world(r: List<Float>) = WorldRect(r[0] * info.width / unit, r[1] * info.height / unit, r[2] * info.width / unit, r[3] * info.height / unit)
            val frames = SceneFrames(world(crops.cover), world(crops.main), request.focusX * info.width / unit, request.focusY * info.height / unit)
            val layer = listOf(MotionLayer("far", 0f, -OVERSCAN, -OVERSCAN, 1f + OVERSCAN, 1f + OVERSCAN, opaque = true))
            val choreo = ChoreographyComposer.compose(
                ChoreoInput(
                    family = SceneFamily.ABSTRACT,
                    time = if (palette.dark) TimeOfDay.NIGHT else TimeOfDay.DAY,
                    celestial = Celestial.NONE,
                    intensity = request.intensity,
                    frames = frames,
                    mainLayers = layer,
                    coverLayers = layer,
                    glow = palette.glow,
                    accent = palette.accent,
                    seed = request.seed,
                ),
                request.style,
            )
            File(staging, "motion").mkdirs()
            File(staging, "motion/open.json").writeText(CoreInfo.json.encodeToString(MotionSpec.serializer(), choreo.open))
            File(staging, "motion/close.json").writeText(CoreInfo.json.encodeToString(MotionSpec.serializer(), choreo.close))

            onStage(VideoStage.QA, 0f)
            fun rect(r: WorldRect) = listOf(r.left, r.top, r.right, r.bottom).map(::round2)
            val now = Instant.now()
            val manifest = FoldPackManifest(
                id = "fv-" + now.toEpochMilli().toString(36) + "-" + Sha256.of(request.uri.toString() + request.seed).take(6),
                title = request.title,
                createdAt = now.toString(),
                creationSource = FoldPackManifest.SOURCE_VIDEO,
                profileIds = listOf(profile.id),
                rendererVersion = VersionRange(CoreInfo.minRendererFor(video = true, style = choreo.style.name), CoreInfo.RENDERER_VERSION),
                motionIntensity = request.intensity,
                scenes = Scenes(
                    cover = scenes.getValue(ScreenKind.COVER).copy(world = rect(frames.cover)),
                    main = scenes.getValue(ScreenKind.MAIN).copy(world = rect(frames.main)),
                ),
                transitions = Transitions(
                    open = TransitionRef("motion/open.json", choreo.open.durationMs),
                    close = TransitionRef("motion/close.json", choreo.close.durationMs),
                ),
                previews = Previews(cover = "previews/cover.webp", main = "previews/main.webp"),
                motionStyle = choreo.style.name,
                video = VideoSpec(
                    file = VideoSpec.FILE,
                    width = info.width,
                    height = info.height,
                    durationMs = duration,
                    frameRate = info.frameRate.coerceIn(1f, 240f),
                    loopCrossfadeMs = if (request.crossfadeLoop) min(VideoSpec.DEFAULT_CROSSFADE_MS, duration / 3) else 0,
                    crop = crops,
                    sizeBytes = clip.length(),
                ),
                colors = PackColors(hex(palette.glow), hex(palette.accent), hex(palette.mid), hex(palette.background)),
                generation = GenerationRecipe(provider = PROVIDER, providerVersion = "1.0.0", seed = request.seed, mode = "CUSTOM"),
                tags = listOf("video"),
            )
            File(staging, FoldPackManifest.FILE_NAME).writeText(CoreInfo.json.encodeToString(FoldPackManifest.serializer(), manifest))
            Checksums.write(staging)
            val report = validator.validate(staging, profile.id)
            if (!report.passed) {
                PocTrace.log("video", "QA_FAILED", report.issues.joinToString { "${it.code}:${it.detail}" })
                throw VideoPackException(VideoPackException.Code.VALIDATION_FAILED, report.codes.joinToString())
            }
            val stored = store.commit(staging, manifest)
            PocTrace.log("video", "READY", "${manifest.id} ${info.width}x${info.height} ${duration}ms ${clip.length() / 1024}KB style=${choreo.style}")
            return stored
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** One screen's poster: the crop window plus overscan, clamped at the clip's edges like the video. */
    private fun writePoster(dir: File, kind: ScreenKind, profile: DeviceProfile, frame: Bitmap, info: ClipInfo, crop: List<Float>, request: VideoRequest): SceneEntry {
        val screen = profile.screen(kind)
        val span = 1f + 2f * OVERSCAN
        val cropW = (crop[2] - crop[0]) * info.width
        val cropH = (crop[3] - crop[1]) * info.height
        val scale = min(1f, POSTER_LONG_SIDE / max(cropW * span, cropH * span))
        val w = (cropW * span * scale).roundToInt().coerceIn(16, 4096)
        val h = (cropH * span * scale).roundToInt().coerceIn(16, 4096)
        val bitmap = renderWindow(frame, info, expand(crop), w, h)
        val name = if (kind == ScreenKind.MAIN) "main" else "cover"
        val entry = LayerEntry(
            id = "far",
            path = "layers/${name}_far.webp",
            width = w,
            height = h,
            rect = listOf(-OVERSCAN, -OVERSCAN, 1f + OVERSCAN, 1f + OVERSCAN),
            depth = 0f,
            opaque = true,
        )
        File(dir, entry.path).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, it) }
        bitmap.recycle()
        // Focus in this screen's own frame.
        val fx = ((request.focusX - crop[0]) / (crop[2] - crop[0])).coerceIn(0f, 1f)
        val fy = ((request.focusY - crop[1]) / (crop[3] - crop[1])).coerceIn(0f, 1f)
        return SceneEntry(
            baseWidth = screen.widthPx,
            baseHeight = screen.heightPx,
            focus = listOf(round2(fx), round2(fy)),
            layers = listOf(entry),
        )
    }

    private fun writePreview(dir: File, path: String, frame: Bitmap, info: ClipInfo, crop: List<Float>, width: Int, aspect: Float) {
        val height = (width / aspect).roundToInt()
        val bitmap = renderWindow(frame, info, crop, width, height)
        File(dir, path).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
        bitmap.recycle()
    }

    private fun expand(c: List<Float>): List<Float> {
        val w = c[2] - c[0]
        val h = c[3] - c[1]
        return listOf(c[0] - OVERSCAN * w, c[1] - OVERSCAN * h, c[2] + OVERSCAN * w, c[3] + OVERSCAN * h)
    }

    /** Draws the window [r] (video-normalised, may reach past the edges) of [frame] into w x h. */
    private fun renderWindow(frame: Bitmap, info: ClipInfo, r: List<Float>, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fx = frame.width / info.width.toFloat()
        val fy = frame.height / info.height.toFloat()
        val left = r[0] * info.width * fx
        val top = r[1] * info.height * fy
        val sx = w / ((r[2] - r[0]) * info.width * fx)
        val sy = h / ((r[3] - r[1]) * info.height * fy)
        val shader = BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply {
            setTranslate(-left, -top)
            postScale(sx, sy)
        })
        Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        return out
    }

    private class Palette(val glow: Int, val accent: Int, val mid: Int, val background: Int, val dark: Boolean)

    /** Light colour, accent and background from the clip's dominant colours. */
    private fun paletteOf(frame: Bitmap): Palette {
        val small = Bitmap.createScaledBitmap(frame, 96, max(1, 96 * frame.height / max(1, frame.width)), true)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        if (small !== frame) small.recycle()
        val colors = ReferencePalette.extract(pixels)
        val fallback = Palette(0xFF9FF5E6.toInt(), 0xFF8B7CFF.toInt(), 0xFF1D5A63.toInt(), 0xFF050B14.toInt(), true)
        colors ?: return fallback
        fun light(c: Int) = (((c shr 16) and 0xFF) * 0.3f + ((c shr 8) and 0xFF) * 0.59f + (c and 0xFF) * 0.11f) / 255f
        fun chroma(c: Int): Float {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return (maxOf(r, g, b) - minOf(r, g, b)) / 255f
        }
        val byLight = colors.swatches.sortedBy(::light)
        val vivid = colors.swatches.sortedByDescending { chroma(it) * 0.7f + light(it) * 0.3f }
        val glow = brighten(vivid.first())
        val accent = vivid.getOrElse(1) { vivid.first() }
        return Palette(glow, accent, byLight[byLight.size / 2], darken(byLight.first()), colors.isDark)
    }

    private fun brighten(c: Int): Int {
        fun ch(v: Int) = (v + (255 - v) * 0.45f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
    }

    private fun darken(c: Int): Int {
        fun ch(v: Int) = (v * 0.35f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private fun round2(v: Float) = (v * 10000f).roundToInt() / 10000f

    companion object {
        const val PROVIDER = "local-video"
        /** Enough for a sharp 2.2K main screen; larger clips only cost battery when decoded. */
        const val MAX_LONG_SIDE = 1920
        const val MAX_FRAME_RATE = 60f
        const val MAX_SEGMENT_MS = 15_000L
        const val OVERSCAN = 0.04f
        const val POSTER_LONG_SIDE = 1280f
        const val POSTER_SOURCE_LONG_SIDE = 1600
        const val PREVIEW_MAIN_WIDTH = 540
        const val PREVIEW_COVER_WIDTH = 300
        const val MIN_FREE_BYTES = 128L * 1024 * 1024
    }
}
