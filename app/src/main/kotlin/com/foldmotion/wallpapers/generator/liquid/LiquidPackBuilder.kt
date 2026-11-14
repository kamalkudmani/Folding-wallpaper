package com.foldmotion.wallpapers.generator.liquid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
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
import com.foldmotion.core.pack.LiquidSpec
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
import com.foldmotion.wallpapers.render.GpuSnapshot
import com.foldmotion.wallpapers.render.LiquidField
import com.foldmotion.wallpapers.render.LiquidFrame
import com.foldmotion.wallpapers.telemetry.PocTrace
import java.io.File
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt

data class LiquidRequest(
    val spec: LiquidSpec,
    val style: FoldStyle = FoldStyle.AUTO,
    val intensity: MotionIntensity = MotionIntensity.LOW,
    val seed: Long,
    val title: LocalizedText = LocalizedText("سائل حيّ", "Live liquid"),
)

class LiquidPackException(message: String) : Exception(message)

/**
 * Makes a live liquid pack (v6). The liquid's world is the main screen (long side 1); the cover
 * window is the slice of it the cover shows at the same physical scale, so folding is seamless.
 * Each screen gets a poster (the liquid at time 0, rendered on the GPU through the same shader)
 * for Android 11-12 and still moments. Fast: no files to transcode, only two posters.
 */
class LiquidPackBuilder(private val store: PackStore, private val validator: FoldPackValidator) {

    fun build(request: LiquidRequest, profile: DeviceProfile): StoredPack {
        if (!request.spec.isWellFormed()) throw LiquidPackException("liquid settings out of bounds")
        val staging = store.newStagingDir()
        try {
            val geometry = geometry(profile)
            val scenes = HashMap<ScreenKind, SceneEntry>()
            for (kind in listOf(ScreenKind.COVER, ScreenKind.MAIN)) {
                val window = if (kind == ScreenKind.MAIN) geometry.main else geometry.cover
                scenes[kind] = writePoster(staging, kind, profile, request.spec, window)
            }
            writePreview(staging, "previews/main.webp", request.spec, geometry.main, PREVIEW_MAIN_WIDTH, profile.screen(ScreenKind.MAIN).aspect)
            writePreview(staging, "previews/cover.webp", request.spec, geometry.cover, PREVIEW_COVER_WIDTH, profile.screen(ScreenKind.COVER).aspect)

            val colors = request.spec.palette.map { Color.parseColor(it) }
            val glow = colors[3]
            val accent = colors[2]
            val dark = luminance(colors[1]) < 0.45f
            val frames = SceneFrames(geometry.cover, geometry.main, geometry.main.left + geometry.main.width / 2f, geometry.main.top + geometry.main.height / 2f)
            val layer = listOf(MotionLayer("far", 0f, -OVERSCAN, -OVERSCAN, 1f + OVERSCAN, 1f + OVERSCAN, opaque = true))
            val choreo = ChoreographyComposer.compose(
                ChoreoInput(
                    family = SceneFamily.ABSTRACT,
                    time = if (dark) TimeOfDay.NIGHT else TimeOfDay.DAY,
                    celestial = Celestial.NONE,
                    intensity = request.intensity,
                    frames = frames,
                    mainLayers = layer,
                    coverLayers = layer,
                    glow = glow,
                    accent = accent,
                    seed = request.seed,
                ),
                request.style,
            )
            File(staging, "motion").mkdirs()
            File(staging, "motion/open.json").writeText(CoreInfo.json.encodeToString(MotionSpec.serializer(), choreo.open))
            File(staging, "motion/close.json").writeText(CoreInfo.json.encodeToString(MotionSpec.serializer(), choreo.close))

            fun rect(r: WorldRect) = listOf(r.left, r.top, r.right, r.bottom).map(::round4)
            val now = Instant.now()
            val manifest = FoldPackManifest(
                id = "fl-" + now.toEpochMilli().toString(36) + "-" + Sha256.of(request.spec.toString() + request.seed).take(6),
                title = request.title,
                createdAt = now.toString(),
                creationSource = FoldPackManifest.SOURCE_LIQUID,
                profileIds = listOf(profile.id),
                rendererVersion = VersionRange(CoreInfo.minRendererFor(video = false, style = choreo.style.name, liquid = true), CoreInfo.RENDERER_VERSION),
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
                liquid = request.spec,
                colors = PackColors(hex(glow), hex(accent), hex(colors[1]), hex(colors[0])),
                generation = GenerationRecipe(provider = PROVIDER, providerVersion = "1.0.0", seed = request.seed, mode = "CUSTOM"),
                tags = listOf("liquid"),
            )
            File(staging, FoldPackManifest.FILE_NAME).writeText(CoreInfo.json.encodeToString(FoldPackManifest.serializer(), manifest))
            Checksums.write(staging)
            val report = validator.validate(staging, profile.id)
            if (!report.passed) {
                PocTrace.log("liquid", "QA_FAILED", report.issues.joinToString { "${it.code}:${it.detail}" })
                throw LiquidPackException(report.codes.joinToString())
            }
            val stored = store.commit(staging, manifest)
            PocTrace.log("liquid", "READY", "${manifest.id} style=${choreo.style}")
            return stored
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    class Geometry(val main: WorldRect, val cover: WorldRect)

    companion object {
        const val PROVIDER = "local-liquid"
        const val OVERSCAN = 0.04f
        const val POSTER_LONG_SIDE = 1280f
        const val PREVIEW_MAIN_WIDTH = 540
        const val PREVIEW_COVER_WIDTH = 300

        /**
         * The liquid's world: the main screen with its long side as 1, and the cover window as
         * the slice of it the cover shows at the same physical scale (as for a video).
         */
        fun geometry(profile: DeviceProfile): Geometry {
            val main = profile.main ?: profile.cover
            val w = main.widthPx
            val h = main.heightPx
            val unit = max(w, h).toFloat()
            // The cover window over the right half: the closed phone lies there.
            val crops = VideoGeometry.crops(profile, w, h, focusX = 0.75f)
            fun world(r: List<Float>) = WorldRect(r[0] * w / unit, r[1] * h / unit, r[2] * w / unit, r[3] * h / unit)
            return Geometry(world(crops.main), world(crops.cover))
        }

        /** The liquid at time 0 over [window], [w] x [h] pixels; a palette gradient if the GPU path is unavailable. */
        fun still(spec: LiquidSpec, window: WorldRect, w: Int, h: Int): Bitmap {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                GpuSnapshot.render(w, h) { c ->
                    val shader = RuntimeShader(LiquidField.AGSL)
                    LiquidField.configure(shader, spec, window, w, h, LiquidFrame(0))
                    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
                }?.let { return it }
            }
            val colors = spec.palette.map { Color.parseColor(it) }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).drawRect(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    Paint().apply { shader = LinearGradient(0f, 0f, w * 0.6f, h.toFloat(), intArrayOf(colors[2], colors[1], colors[0]), null, Shader.TileMode.CLAMP) },
                )
            }
        }

        private fun expand(r: WorldRect, o: Float) = WorldRect(r.left - o * r.width, r.top - o * r.height, r.right + o * r.width, r.bottom + o * r.height)

        private fun luminance(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

        private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

        private fun round4(v: Float) = (v * 10000f).roundToInt() / 10000f
    }

    private fun writePoster(dir: File, kind: ScreenKind, profile: DeviceProfile, spec: LiquidSpec, window: WorldRect): SceneEntry {
        val screen = profile.screen(kind)
        val layerWorld = expand(window, OVERSCAN)
        val scale = POSTER_LONG_SIDE / max(screen.widthPx, screen.heightPx) / (1f + 2f * OVERSCAN)
        val w = (screen.widthPx * (1f + 2f * OVERSCAN) * scale).roundToInt().coerceIn(16, 4096)
        val h = (screen.heightPx * (1f + 2f * OVERSCAN) * scale).roundToInt().coerceIn(16, 4096)
        val bitmap = still(spec, layerWorld, w, h)
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
        File(dir, entry.path).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, it) }
        bitmap.recycle()
        return SceneEntry(baseWidth = screen.widthPx, baseHeight = screen.heightPx, focus = listOf(0.5f, 0.5f), layers = listOf(entry))
    }

    private fun writePreview(dir: File, path: String, spec: LiquidSpec, window: WorldRect, width: Int, aspect: Float) {
        val height = (width / aspect).roundToInt()
        val bitmap = still(spec, window, width, height)
        File(dir, path).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
        bitmap.recycle()
    }
}
