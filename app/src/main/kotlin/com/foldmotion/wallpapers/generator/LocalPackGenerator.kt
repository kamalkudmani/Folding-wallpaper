package com.foldmotion.wallpapers.generator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.AmbientSpec
import com.foldmotion.core.motion.ChoreoInput
import com.foldmotion.core.motion.ChoreographyComposer
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
import com.foldmotion.core.pack.ValidationCode
import com.foldmotion.core.pack.VersionRange
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.GenerationRequest
import com.foldmotion.core.scene.LayerRole
import com.foldmotion.core.scene.PipelineStage
import com.foldmotion.core.scene.PromptCheck
import com.foldmotion.core.scene.PromptInterpretation
import com.foldmotion.core.scene.PromptRules
import com.foldmotion.core.scene.SceneComposer
import com.foldmotion.core.scene.SceneFrames
import com.foldmotion.core.scene.SceneGeometry
import com.foldmotion.core.scene.SceneSpec
import com.foldmotion.core.scene.WorldRect
import com.foldmotion.wallpapers.pack.PackStore
import com.foldmotion.wallpapers.pack.StoredPack
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.SceneArtist
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.telemetry.PocTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt

enum class GenerationErrorCode { PROMPT_EMPTY, PROMPT_TOO_LONG, STORAGE_FULL, OUT_OF_MEMORY, RENDER_FAILED, VALIDATION_FAILED }

class GenerationException(val code: GenerationErrorCode, val validationCodes: List<ValidationCode> = emptyList(), message: String = code.name) :
    Exception(message)

data class GenerationOutcome(
    val pack: StoredPack,
    val interpretation: PromptInterpretation,
    val durationMs: Long,
)

/**
 * Provider contract shared by the on-device generator and a future cloud provider
 * (blueprint section 14, "CloudProviderInterface"). Callers never depend on which one runs.
 */
interface GenerationProvider {
    val providerId: String
    val providerVersion: String

    suspend fun generate(
        request: GenerationRequest,
        profile: DeviceProfile,
        onStage: (PipelineStage) -> Unit,
    ): GenerationOutcome
}

/**
 * Builds a complete FoldPack on the device, following the mandatory pipeline of blueprint
 * section 09: prompt check, storyboard, anchor scene, derived cover, depth layers, motion pack, QA.
 *
 * It is a procedural illustrator, not an AI model, and the app says so. It exists so the full
 * product loop (generate, preview, apply, library, import/export) works offline today, and so the
 * pack format, validator and engine are exercised by real packs before a cloud provider exists.
 */
class LocalPackGenerator(
    private val store: PackStore,
    private val validator: FoldPackValidator = FoldPackValidator(),
) : GenerationProvider {

    override val providerId = "local-procedural"
    override val providerVersion = "1.0.0"

    override suspend fun generate(
        request: GenerationRequest,
        profile: DeviceProfile,
        onStage: (PipelineStage) -> Unit,
    ): GenerationOutcome {
        val started = SystemClock.elapsedRealtime()
        if (store.freeBytes() < MIN_FREE_BYTES) throw GenerationException(GenerationErrorCode.STORAGE_FULL)

        onStage(PipelineStage.PROMPT_CHECK)
        val prompt = if (request.mode == GenerationMode.CUSTOM) {
            when (val check = PromptRules.check(request.prompt)) {
                PromptCheck.Empty -> throw GenerationException(GenerationErrorCode.PROMPT_EMPTY)
                is PromptCheck.TooLong -> throw GenerationException(GenerationErrorCode.PROMPT_TOO_LONG)
                is PromptCheck.Ok -> check.cleaned
            }
        } else {
            ""
        }
        checkActive()

        onStage(PipelineStage.STORYBOARD)
        val composed = SceneComposer.compose(request.copy(prompt = prompt), profile)
        val spec = composed.spec
        val frames = SceneGeometry.frames(profile, spec.focusX, spec.focusY)
        val artist = SceneArtist(spec)
        val staging = store.newStagingDir()

        try {
            onStage(PipelineStage.ANCHOR)
            val main = renderScene(artist, frames, ScreenKind.MAIN, profile, staging, "main")
            checkActive()

            onStage(PipelineStage.DERIVE_COVER)
            val cover = renderScene(artist, frames, ScreenKind.COVER, profile, staging, "cover")
            checkActive()

            onStage(PipelineStage.LAYERS)
            writePreview(staging, "previews/main.webp", main, PREVIEW_MAIN_WIDTH)
            writePreview(staging, "previews/cover.webp", cover, PREVIEW_COVER_WIDTH)
            main.scene.recycle()
            cover.scene.recycle()
            checkActive()

            onStage(PipelineStage.MOTION_PACK)
            val choreo = ChoreographyComposer.compose(
                ChoreoInput(
                    family = spec.family,
                    time = spec.timeOfDay,
                    celestial = spec.celestial,
                    intensity = request.motionIntensity,
                    frames = frames,
                    mainLayers = motionLayers(main.entry),
                    coverLayers = motionLayers(cover.entry),
                    glow = spec.palette.glow,
                    accent = spec.palette.accent,
                    seed = spec.seed,
                ),
                request.motionStyle,
            )
            val open = choreo.open
            val close = choreo.close
            File(staging, "motion").mkdirs()
            File(staging, "motion/open.json").writeText(CoreInfo.json.encodeToString(MotionSpec.serializer(), open))
            File(staging, "motion/close.json").writeText(CoreInfo.json.encodeToString(MotionSpec.serializer(), close))
            File(staging, AmbientSpec.FILE).writeText(CoreInfo.json.encodeToString(AmbientSpec.serializer(), choreo.ambient))
            checkActive()

            onStage(PipelineStage.QA)
            fun world(r: WorldRect) = listOf(r.left, r.top, r.right, r.bottom).map(::round4)
            val manifest = manifestFor(
                request, profile, spec,
                main.entry.copy(world = world(frames.main)), cover.entry.copy(world = world(frames.cover)),
                open, close, prompt, choreo.style.name,
            )
            File(staging, FoldPackManifest.FILE_NAME).writeText(CoreInfo.json.encodeToString(FoldPackManifest.serializer(), manifest))
            Checksums.write(staging)
            val report = validator.validate(staging, profile.id)
            if (!report.passed) {
                PocTrace.log("generator", "QA_FAILED", report.issues.joinToString { "${it.code}:${it.detail}" })
                throw GenerationException(GenerationErrorCode.VALIDATION_FAILED, report.codes)
            }
            checkActive()

            val stored = store.commit(staging, manifest)
            val duration = SystemClock.elapsedRealtime() - started
            PocTrace.log("generator", "READY", "${manifest.id} ${spec.family} ${duration}ms ${stored.sizeBytes / 1024}KB")
            return GenerationOutcome(stored, composed.interpretation, duration)
        } catch (e: CancellationException) {
            staging.deleteRecursively()
            throw e
        } catch (e: OutOfMemoryError) {
            staging.deleteRecursively()
            throw GenerationException(GenerationErrorCode.OUT_OF_MEMORY)
        } catch (e: GenerationException) {
            staging.deleteRecursively()
            throw e
        } catch (e: Exception) {
            staging.deleteRecursively()
            PocTrace.log("generator", "RENDER_FAILED", e.javaClass.simpleName + ": " + e.message)
            throw GenerationException(GenerationErrorCode.RENDER_FAILED, message = e.message ?: "render failed")
        }
    }

    private class RenderedScene(val entry: SceneEntry, val scene: LoadedScene)

    /** Renders, crops and encodes the three layers of one screen; keeps decoded copies for previews. */
    private suspend fun renderScene(
        artist: SceneArtist,
        frames: SceneFrames,
        kind: ScreenKind,
        profile: DeviceProfile,
        dir: File,
        folder: String,
    ): RenderedScene {
        val screen = profile.screen(kind)
        val frame = frames.frame(kind)
        val (fxN, fyN) = frames.focusIn(kind)
        File(dir, folder).mkdirs()
        val entries = ArrayList<LayerEntry>(3)
        val bitmaps = ArrayList<Pair<LayerEntry, Bitmap>>(3)
        for (role in LayerRole.entries) {
            checkActive()
            val window: WorldRect = frame.expand(role.overscan)
            val widthPx = max(64, (screen.widthPx * profile.renderScale * (1 + 2 * role.overscan)).roundToInt())
            val full = artist.render(role, window, widthPx)
            val fullHeight = full.height
            val opaque = role == LayerRole.FAR
            // Transparent layers are cropped to their content rows: less memory and smaller files.
            val (top, bottom) = if (opaque) 0 to full.height else contentRows(full)
            if (bottom <= top) {
                full.recycle()
                continue
            }
            val bitmap = if (top == 0 && bottom == full.height) full else Bitmap.createBitmap(full, 0, top, full.width, bottom - top).also { full.recycle() }
            val o = role.overscan
            val span = 1 + 2 * o
            val entry = LayerEntry(
                id = role.id,
                path = "$folder/${role.id}.webp",
                width = bitmap.width,
                height = bitmap.height,
                rect = listOf(-o, -o + span * top / fullHeight.toFloat(), 1 + o, -o + span * bottom / fullHeight.toFloat()),
                depth = role.depth,
                opaque = opaque,
            )
            File(dir, entry.path).outputStream().use {
                // Lossy WebP keeps the alpha channel of transparent layers at a fraction of PNG size.
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, if (opaque) 90 else 92, it)
            }
            entries += entry
            bitmaps += entry to bitmap
        }
        val sceneEntry = SceneEntry(
            baseWidth = screen.widthPx,
            baseHeight = screen.heightPx,
            focus = listOf(round3(fxN), round3(fyN)),
            safeZone = safeZoneFor(kind, profile),
            layers = entries,
        )
        val loaded = LoadedScene(
            packId = "",
            kind = kind,
            baseWidth = screen.widthPx,
            baseHeight = screen.heightPx,
            focusX = fxN,
            focusY = fyN,
            layers = bitmaps,
            entryMotion = null,
            background = artist.backgroundColor(),
            glow = 0,
        )
        return RenderedScene(sceneEntry, loaded)
    }

    /** First and last+1 pixel rows containing anything visible. */
    private fun contentRows(bitmap: Bitmap): Pair<Int, Int> {
        val row = IntArray(bitmap.width)
        fun visible(y: Int): Boolean {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (p in row) if ((p ushr 24) > 2) return true
            return false
        }
        var top = 0
        while (top < bitmap.height && !visible(top)) top++
        var bottom = bitmap.height
        while (bottom > top && !visible(bottom - 1)) bottom--
        // Keep a couple of pixels of margin for filtering at the edges.
        return max(0, top - 2) to minOf(bitmap.height, bottom + 2)
    }

    private fun writePreview(dir: File, path: String, rendered: RenderedScene, width: Int) {
        val e = rendered.entry
        val height = (width * e.baseHeight / e.baseWidth.toFloat()).roundToInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        SceneDrawer().draw(Canvas(bitmap), width, height, rendered.scene, null)
        File(dir, path).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
        bitmap.recycle()
    }

    private fun motionLayers(scene: SceneEntry) = scene.layers.map { MotionLayer(it.id, it.depth, it.left, it.top, it.right, it.bottom, it.opaque) }

    /** Important content stays clear of the status bar, the dock and, on the main screen, the crease. */
    private fun safeZoneFor(kind: ScreenKind, profile: DeviceProfile): List<Float> =
        if (kind == ScreenKind.MAIN && profile.crease != null) listOf(0.06f, 0.1f, 0.94f, 0.82f) else listOf(0.08f, 0.1f, 0.92f, 0.84f)

    private fun manifestFor(
        request: GenerationRequest,
        profile: DeviceProfile,
        spec: SceneSpec,
        main: SceneEntry,
        cover: SceneEntry,
        open: MotionSpec,
        close: MotionSpec,
        prompt: String,
        motionStyle: String,
    ): FoldPackManifest {
        val now = Instant.now()
        val id = "fp-" + now.toEpochMilli().toString(36) + "-" + Sha256.of(request.idempotencyKey).take(6)
        fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
        return FoldPackManifest(
            id = id,
            title = LocalizedText(spec.titleAr, spec.titleEn),
            createdAt = now.toString(),
            creationSource = FoldPackManifest.SOURCE_LOCAL,
            author = null,
            profileIds = listOf(profile.id),
            isStaticOnly = false,
            motionIntensity = request.motionIntensity,
            // Drawn packs need fold styles (3), not video (4), so 0.4 apps can still import them.
            rendererVersion = VersionRange(CoreInfo.STYLES_RENDERER_VERSION, CoreInfo.RENDERER_VERSION),
            scenes = Scenes(cover = cover, main = main),
            transitions = Transitions(
                open = TransitionRef("motion/open.json", open.durationMs),
                close = TransitionRef("motion/close.json", close.durationMs),
            ),
            previews = Previews(cover = "previews/cover.webp", main = "previews/main.webp"),
            ambient = AmbientSpec.FILE,
            motionStyle = motionStyle,
            colors = PackColors(hex(spec.palette.glow), hex(spec.palette.accent), hex(spec.palette.mid), hex(spec.palette.skyTop)),
            generation = GenerationRecipe(
                provider = providerId,
                providerVersion = providerVersion,
                seed = spec.seed,
                family = spec.family.name,
                // Only a hash is kept: the prompt text itself never leaves the generation call.
                promptSha256 = if (prompt.isEmpty()) null else Sha256.of(prompt),
                mode = request.mode.name,
                paletteSource = if (request.reference != null && request.mode == GenerationMode.CUSTOM) "REFERENCE_IMAGE" else "CURATED",
            ),
            tags = spec.tags,
        )
    }

    private suspend fun checkActive() = currentCoroutineContext().ensureActive()

    private fun round3(v: Float) = (v * 1000f).roundToInt() / 1000f
    private fun round4(v: Float) = (v * 10000f).roundToInt() / 10000f

    companion object {
        const val MIN_FREE_BYTES = 64L * 1024 * 1024
        const val PREVIEW_MAIN_WIDTH = 540
        const val PREVIEW_COVER_WIDTH = 300
    }
}
