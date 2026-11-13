package com.foldmotion.wallpapers.ui

import android.app.Application
import android.app.LocaleManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.util.LruCache
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.pack.ValidationCode
import com.foldmotion.core.pack.ValidationReport
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.GenerationRequest
import com.foldmotion.core.scene.IdeaBank
import com.foldmotion.core.scene.PromptAnalyzer
import com.foldmotion.core.scene.PromptCheck
import com.foldmotion.core.scene.PromptInterpretation
import com.foldmotion.core.scene.PromptRules
import com.foldmotion.core.scene.ReferenceColors
import com.foldmotion.core.scene.ReferencePalette
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.wallpapers.AppContainer
import com.foldmotion.wallpapers.apply.ApplyOutcome
import com.foldmotion.wallpapers.apply.ApplyStart
import com.foldmotion.wallpapers.data.AppSettings
import com.foldmotion.wallpapers.data.LivingMotion
import com.foldmotion.wallpapers.data.ApplyRecord
import com.foldmotion.wallpapers.device.DeviceSnapshot
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.pack.ImportResult
import com.foldmotion.wallpapers.pack.RestyleResult
import com.foldmotion.wallpapers.pack.StoredPack
import com.foldmotion.wallpapers.render.SceneLoader
import com.foldmotion.wallpapers.telemetry.PocTrace
import com.foldmotion.wallpapers.ui.components.foldStyleLabelRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID

/** Editable state of the Create screen. Lives in the ViewModel so folding never loses it. */
data class CreateForm(
    val mode: GenerationMode = GenerationMode.CUSTOM,
    val prompt: String = "",
    val family: SceneFamily? = null,
    val intensity: MotionIntensity = MotionIntensity.LOW,
    val ideaId: String = IdeaBank.ideas.first().id,
    /** Colours taken from a reference photo; the photo itself is not kept. */
    val reference: ReferenceColors? = null,
    /** Fold animation; AUTO lets the scene choose. */
    val style: FoldStyle = FoldStyle.AUTO,
    /** Third creation mode: a wallpaper from the user's own video. */
    val videoMode: Boolean = false,
    val video: VideoPick? = null,
    /** Fourth creation mode: live liquid in one of the looks, at a chosen pace and wave size. */
    val liquidMode: Boolean = false,
    val liquidLook: String = "gold",
    val liquidPace: Int = 1,
    val liquidScale: Int = 1,
    val error: Int? = null,
) {
    val liquidSpec: com.foldmotion.core.pack.LiquidSpec get() = com.foldmotion.wallpapers.ui.discover.LiquidLooks.spec(liquidLook, liquidPace, liquidScale)

    val interpretation: PromptInterpretation get() = PromptAnalyzer.analyze(prompt)
    val promptLength: Int get() = prompt.codePointCount(0, prompt.length)
}

/** A clip picked for a video wallpaper and the part of it to use. */
data class VideoPick(
    val uri: android.net.Uri,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val thumbnail: Bitmap?,
    val startMs: Long,
    val endMs: Long,
    /** Cross-fade the loop seam (off for clips made to loop). */
    val crossfade: Boolean = true,
    /** The built-in sample clip. */
    val sample: Boolean = false,
)

/** Progress of building a video wallpaper. */
sealed interface VideoJob {
    data class Running(val stage: com.foldmotion.wallpapers.generator.video.VideoStage, val progress: Float) : VideoJob
    data class Done(val packId: String) : VideoJob
    data class Failed(val message: Int) : VideoJob
}

sealed interface UiMessage {
    data class Text(val resId: Int, val arg: String? = null) : UiMessage
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val container = AppContainer.get(app)
    private val settings: AppSettings = container.settings
    private val random = SecureRandom()

    private val _device = MutableStateFlow(container.deviceInfo.snapshot())
    val device: StateFlow<DeviceSnapshot> = _device.asStateFlow()

    private val settingsChanges = settings.changes().onStart { emit("") }

    val packs: StateFlow<List<StoredPack>> = combine(container.packStore.revision, settingsChanges) { _, _ -> Unit }
        .map { withContext(Dispatchers.IO) { container.packStore.list() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activePackId: StateFlow<String?> = settingsChanges
        .filter { it.isEmpty() || it == AppSettings.KEY_ACTIVE }
        .map { settings.activePackId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.activePackId)

    val applyHistory: StateFlow<List<ApplyRecord>> = settingsChanges
        .filter { it.isEmpty() || it == AppSettings.KEY_HISTORY }
        .map { settings.applyHistory }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.applyHistory)

    val reducedMotion: StateFlow<Boolean> = settingsChanges
        .filter { it.isEmpty() || it == AppSettings.KEY_REDUCED_MOTION }
        .map { settings.reducedMotion }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.reducedMotion)

    val livingMotion: StateFlow<LivingMotion> = settingsChanges
        .filter { it.isEmpty() || it == AppSettings.KEY_LIVING }
        .map { settings.livingMotion }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.livingMotion)

    val tiltParallax: StateFlow<Boolean> = settingsChanges
        .filter { it.isEmpty() || it == AppSettings.KEY_TILT }
        .map { settings.tiltParallax }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.tiltParallax)

    val job = container.generation.job

    private val _form = MutableStateFlow(CreateForm(ideaId = IdeaBank.pick(random.nextLong()).id))
    val form: StateFlow<CreateForm> = _form.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiMessage> = _messages

    private val previews = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    var onboardingDone: Boolean
        get() = settings.onboardingDone
        set(v) {
            settings.onboardingDone = v
        }

    fun refreshDevice() {
        _device.value = container.deviceInfo.snapshot()
    }

    val profile: DeviceProfile get() = _device.value.active

    // ------------------------------------------------------------------ create

    fun updateForm(transform: (CreateForm) -> CreateForm) = _form.update { transform(it).copy(error = null) }

    fun startRandom(ideaId: String? = null) {
        _form.update { it.copy(mode = GenerationMode.RANDOM, ideaId = ideaId ?: IdeaBank.pick(random.nextLong(), it.ideaId).id) }
    }

    fun changeIdea() = _form.update { it.copy(ideaId = IdeaBank.pick(random.nextLong(), it.ideaId).id) }

    /** @return true when a job was started. */
    fun generate(): Boolean {
        val f = _form.value
        if (f.mode == GenerationMode.CUSTOM) {
            when (PromptRules.check(f.prompt)) {
                PromptCheck.Empty -> {
                    _form.update { it.copy(error = com.foldmotion.wallpapers.R.string.create_error_empty) }
                    return false
                }
                is PromptCheck.TooLong -> {
                    _form.update { it.copy(error = com.foldmotion.wallpapers.R.string.create_error_long) }
                    return false
                }
                is PromptCheck.Ok -> Unit
            }
        }
        container.generation.dismiss()
        container.generation.start(
            GenerationRequest(
                idempotencyKey = UUID.randomUUID().toString(),
                deviceProfileId = profile.id,
                mode = f.mode,
                prompt = if (f.mode == GenerationMode.CUSTOM) f.prompt else "",
                ideaId = if (f.mode == GenerationMode.RANDOM) f.ideaId else null,
                family = if (f.mode == GenerationMode.CUSTOM) f.family else null,
                motionIntensity = f.intensity,
                seed = random.nextLong(),
                reference = if (f.mode == GenerationMode.CUSTOM) f.reference else null,
                motionStyle = f.style,
            ),
            profile,
        )
        return true
    }

    fun generateIdea(ideaId: String): Boolean {
        _form.update { it.copy(mode = GenerationMode.RANDOM, ideaId = ideaId) }
        return generate()
    }

    /**
     * Reads the picked photo once at about 96 px and keeps only its dominant colours. The photo
     * is never copied, stored, or sent anywhere.
     */
    fun setReferencePhoto(uri: Uri) {
        viewModelScope.launch {
            val colors = withContext(Dispatchers.Default) {
                try {
                    val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val w = info.size.width.coerceAtLeast(1)
                        val h = info.size.height.coerceAtLeast(1)
                        val scale = REFERENCE_SAMPLE_PX.toFloat() / maxOf(w, h)
                        if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    bitmap.recycle()
                    ReferencePalette.extract(pixels)
                } catch (e: Exception) {
                    null
                }
            }
            if (colors == null) emit(com.foldmotion.wallpapers.R.string.reference_failed)
            _form.update { it.copy(reference = colors ?: it.reference) }
        }
    }

    fun clearReferencePhoto() = _form.update { it.copy(reference = null) }

    // ------------------------------------------------------------------ video wallpapers

    private val _videoJob = MutableStateFlow<VideoJob?>(null)
    val videoJob: StateFlow<VideoJob?> = _videoJob.asStateFlow()
    private var videoWork: kotlinx.coroutines.Job? = null

    /** Reads the picked clip's size and length and a first frame for the screen. */
    fun pickVideo(uri: android.net.Uri, sample: Boolean = false) {
        viewModelScope.launch {
            val probe = withContext(Dispatchers.IO) {
                val info = container.videoTools.probe(uri) ?: return@withContext null
                val thumb = try {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(getApplication<Application>(), uri)
                        r.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
                    } finally {
                        r.release()
                    }
                } catch (e: Exception) {
                    null
                }
                info to thumb
            }
            if (probe == null) {
                emit(R.string.video_error_unreadable)
                return@launch
            }
            val (info, thumb) = probe
            val end = minOf(info.durationMs, com.foldmotion.wallpapers.generator.video.VideoPackBuilder.MAX_SEGMENT_MS)
            _form.update {
                it.copy(videoMode = true, video = VideoPick(uri, info.durationMs, info.width, info.height, thumb, 0, end, crossfade = true, sample = sample), error = null)
            }
        }
    }

    /** The built-in sample clip, copied out of the app so it can be read like a picked one. */
    fun pickSampleVideo() {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { sampleVideoFile() }
            pickVideo(android.net.Uri.fromFile(file), sample = true)
        }
    }

    fun setVideoRange(startMs: Long, endMs: Long) = _form.update { f ->
        val v = f.video ?: return@update f
        val max = com.foldmotion.wallpapers.generator.video.VideoPackBuilder.MAX_SEGMENT_MS
        val start = startMs.coerceIn(0, maxOf(0, v.durationMs - 1_000))
        val end = endMs.coerceIn(start + 1_000, minOf(v.durationMs, start + max))
        f.copy(video = v.copy(startMs = start, endMs = end))
    }

    fun setVideoCrossfade(on: Boolean) = _form.update { f -> f.video?.let { f.copy(video = it.copy(crossfade = on)) } ?: f }

    fun clearVideo() = _form.update { it.copy(video = null) }

    /** @return true when a job was started. */
    fun createFromVideo(): Boolean {
        val f = _form.value
        val v = f.video ?: return false
        if (_videoJob.value is VideoJob.Running) return false
        _videoJob.value = VideoJob.Running(com.foldmotion.wallpapers.generator.video.VideoStage.TRIM, 0f)
        videoWork = viewModelScope.launch {
            runVideoJob(
                com.foldmotion.wallpapers.generator.video.VideoRequest(
                    uri = v.uri,
                    startMs = v.startMs,
                    endMs = v.endMs,
                    style = f.style,
                    intensity = f.intensity,
                    crossfadeLoop = v.crossfade,
                    seed = random.nextLong(),
                    title = if (v.sample) SAMPLE_TITLE else com.foldmotion.core.pack.LocalizedText("فيديو خاص بي", "My video"),
                    prepared = v.sample,
                ),
            )
        }
        return true
    }

    /** Discover: the sample video as a wallpaper of your own, in the chosen fold style. */
    fun createSampleVideoWallpaper(style: FoldStyle, item: com.foldmotion.wallpapers.ui.discover.DiscoverItem? = null) {
        if (_videoJob.value is VideoJob.Running) return
        _videoJob.value = VideoJob.Running(com.foldmotion.wallpapers.generator.video.VideoStage.TRIM, 0f)
        val asset = item?.let(com.foldmotion.wallpapers.ui.discover.DiscoverCatalog::videoAsset) ?: com.foldmotion.wallpapers.ui.discover.DiscoverCatalog.SAMPLE_VIDEO
        val title = item?.idea?.let { com.foldmotion.core.pack.LocalizedText(it.titleAr, it.titleEn) }
            ?: item?.entry?.let { e -> if (e.titleEn != null && e.titleAr != null) com.foldmotion.core.pack.LocalizedText(e.titleAr, e.titleEn) else null }
            ?: SAMPLE_TITLE
        val seamless = item?.entry?.seamless == true
        videoWork = viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { assetVideoFile(asset) }
            val uri = android.net.Uri.fromFile(file)
            val length = withContext(Dispatchers.IO) { container.videoTools.probe(uri)?.durationMs } ?: 10_000L
            runVideoJob(
                com.foldmotion.wallpapers.generator.video.VideoRequest(
                    uri = uri, startMs = 0, endMs = minOf(length, com.foldmotion.wallpapers.generator.video.VideoPackBuilder.MAX_SEGMENT_MS),
                    style = style, seed = random.nextLong(), title = title, prepared = true,
                    // Liquid loops are made to loop perfectly: no cross-fade needed.
                    crossfadeLoop = !seamless,
                ),
            )
        }
    }

    /**
     * Discover: live liquid made into a pack in [style] (or any [spec] from Create). Two posters
     * rendered on the GPU, so it takes well under a second; progress goes through [videoJob].
     */
    fun createLiquidWallpaper(spec: com.foldmotion.core.pack.LiquidSpec, style: FoldStyle, title: com.foldmotion.core.pack.LocalizedText) {
        if (_videoJob.value is VideoJob.Running) return
        _videoJob.value = VideoJob.Running(com.foldmotion.wallpapers.generator.video.VideoStage.POSTERS, 0f)
        videoWork = viewModelScope.launch {
            try {
                val pack = withContext(Dispatchers.Default) {
                    container.liquidBuilder.build(
                        com.foldmotion.wallpapers.generator.liquid.LiquidRequest(spec = spec, style = style, seed = random.nextLong(), title = title),
                        profile,
                    )
                }
                _videoJob.value = VideoJob.Done(pack.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _videoJob.value = null
                throw e
            } catch (e: Exception) {
                PocTrace.log("liquid", "BUILD_FAILED", e.javaClass.simpleName + ": " + e.message)
                _videoJob.value = VideoJob.Failed(R.string.liquid_error)
            }
        }
    }

    /**
     * Create > Photo: the user's photo as a 3D wallpaper (subject in front, background behind,
     * moving apart with tilt and the fold). Found and cut on the phone; nothing is uploaded.
     */
    fun createFromPhoto(uri: android.net.Uri) {
        if (_videoJob.value is VideoJob.Running) return
        val f = _form.value
        _videoJob.value = VideoJob.Running(com.foldmotion.wallpapers.generator.video.VideoStage.POSTERS, 0f)
        videoWork = viewModelScope.launch {
            try {
                val photo = withContext(Dispatchers.IO) { decodePhoto(uri, PHOTO_LONG_SIDE) }
                if (photo == null) {
                    _videoJob.value = VideoJob.Failed(R.string.photo_error)
                    return@launch
                }
                val result = withContext(Dispatchers.Default) {
                    container.depthBuilder.build(
                        photo,
                        com.foldmotion.wallpapers.generator.depth.PhotoRequest(style = f.style, intensity = f.intensity, seed = random.nextLong()),
                        profile,
                        container.subjectMasker,
                    )
                }
                photo.recycle()
                if (!result.hasSubject) _messages.tryEmit(UiMessage.Text(R.string.photo_no_subject))
                _videoJob.value = VideoJob.Done(result.pack.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _videoJob.value = null
                throw e
            } catch (e: Exception) {
                PocTrace.log("photo", "BUILD_FAILED", e.javaClass.simpleName + ": " + e.message)
                _videoJob.value = VideoJob.Failed(R.string.photo_error)
            }
        }
    }

    /** A picked photo, upright, at most [longSide] pixels on its long side, in software memory. */
    private fun decodePhoto(uri: android.net.Uri, longSide: Int): android.graphics.Bitmap? = try {
        val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, longSide / maxOf(w, h).toFloat())
            if (scale < 1f) decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.let { if (it.config == android.graphics.Bitmap.Config.ARGB_8888) it else it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }
    } catch (e: Exception) {
        null
    }

    /** Create > Liquid: the chosen look as a live liquid wallpaper. */
    fun createLiquidFromForm() {
        val f = _form.value
        createLiquidWallpaper(f.liquidSpec, f.style, com.foldmotion.wallpapers.ui.discover.LiquidLooks.byName(f.liquidLook).title)
    }

    /** Discover: a curated idea made into a pack exactly as pictured (same seed), in [style]. */
    fun getDiscoverIdea(item: com.foldmotion.wallpapers.ui.discover.DiscoverItem, style: FoldStyle): Boolean {
        val ideaId = item.idea?.id ?: return false
        container.generation.dismiss()
        container.generation.start(
            GenerationRequest(
                idempotencyKey = UUID.randomUUID().toString(),
                deviceProfileId = profile.id,
                mode = GenerationMode.RANDOM,
                ideaId = ideaId,
                motionIntensity = MotionIntensity.LOW,
                seed = item.entry.seed,
                motionStyle = style,
            ),
            profile,
        )
        return true
    }

    private fun sampleVideoFile(): java.io.File = assetVideoFile(com.foldmotion.wallpapers.ui.discover.DiscoverCatalog.SAMPLE_VIDEO)

    /** A bundled clip copied out of the app once, so it can be read like a picked one. */
    private fun assetVideoFile(asset: String): java.io.File =
        com.foldmotion.wallpapers.ui.discover.DiscoverCatalog.assetFile(getApplication(), asset)

    private suspend fun runVideoJob(request: com.foldmotion.wallpapers.generator.video.VideoRequest) {
        run {
            try {
                val pack = container.videoBuilder.build(request, profile) { stage, progress -> _videoJob.value = VideoJob.Running(stage, progress) }
                _videoJob.value = VideoJob.Done(pack.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _videoJob.value = null
                throw e
            } catch (e: com.foldmotion.wallpapers.generator.video.VideoPackException) {
                _videoJob.value = VideoJob.Failed(
                    when (e.code) {
                        com.foldmotion.wallpapers.generator.video.VideoPackException.Code.TOO_SHORT -> R.string.video_error_short
                        com.foldmotion.wallpapers.generator.video.VideoPackException.Code.STORAGE_FULL -> R.string.video_error_storage
                        com.foldmotion.wallpapers.generator.video.VideoPackException.Code.TRANSCODE_FAILED -> R.string.video_error_transcode
                        else -> R.string.video_error_unreadable
                    },
                )
            } catch (e: Exception) {
                PocTrace.log("video", "BUILD_FAILED", e.javaClass.simpleName + ": " + e.message)
                _videoJob.value = VideoJob.Failed(R.string.video_error_unreadable)
            }
        }
    }

    fun cancelVideo() {
        videoWork?.cancel()
        _videoJob.value = null
    }

    fun dismissVideoJob() {
        _videoJob.value = null
    }

    private val _restyling = MutableStateFlow<String?>(null)

    /** Id of the pack whose fold animation is being rewritten, if any. */
    val restyling: StateFlow<String?> = _restyling.asStateFlow()

    /** Gives a stored pack new fold animations; the live wallpaper reloads it if it is on screen. */
    fun restyle(id: String, style: FoldStyle) {
        if (_restyling.value != null) return
        val pack = pack(id) ?: return
        _restyling.value = id
        viewModelScope.launch {
            val profileId = pack.manifest.profileIds.firstOrNull() ?: profile.id
            val result = withContext(Dispatchers.IO) { container.packStore.restyle(id, style, container.validator, profileId) }
            _restyling.value = null
            when (result) {
                is RestyleResult.Restyled -> emit(R.string.style_changed, getApplication<Application>().getString(foldStyleLabelRes(result.style)))
                is RestyleResult.Failed -> {
                    PocTrace.log("library", "RESTYLE_FAILED", result.codes.joinToString())
                    emit(R.string.style_failed)
                }
            }
        }
    }

    fun cancelGeneration() = container.generation.cancel()
    fun dismissJob() = container.generation.dismiss()

    // ------------------------------------------------------------------ packs

    fun pack(id: String?): StoredPack? = packs.value.firstOrNull { it.id == id } ?: container.packStore.get(id)

    fun validate(id: String): ValidationReport = container.validator.validate(container.packStore.dir(id))

    suspend fun preview(pack: StoredPack, kind: ScreenKind, width: Int): Bitmap? {
        val key = "${pack.id}:${pack.manifest.packageVersion}:$kind:$width"
        previews.get(key)?.let { return it }
        return withContext(Dispatchers.IO) { SceneLoader.loadPreview(pack.dir, kind, width) }?.also { previews.put(key, it) }
    }

    fun beginApply(id: String): ApplyStart {
        val pack = pack(id)
        if (pack != null && profile.id !in pack.manifest.profileIds && _device.value.supported) {
            val name = DeviceProfileRegistry.byId(pack.manifest.profileIds.firstOrNull())?.marketingName ?: pack.manifest.profileIds.joinToString()
            emit(com.foldmotion.wallpapers.R.string.apply_profile_mismatch, name)
        }
        return container.apply.begin(id)
    }

    fun onPickerReturned(): ApplyOutcome = container.apply.onPickerReturned()

    fun delete(id: String): Boolean {
        if (id == settings.activePackId) {
            emit(com.foldmotion.wallpapers.R.string.delete_active)
            return false
        }
        return container.packStore.delete(id)
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                    container.packStore.import(it, container.validator, profile.id)
                } ?: ImportResult.Rejected(listOf(ValidationCode.NOT_A_FOLDPACK), "unreadable")
            }
            when (result) {
                is ImportResult.Imported -> emit(com.foldmotion.wallpapers.R.string.import_done, result.pack.manifest.title.forLanguage(language()))
                is ImportResult.AlreadyPresent -> emit(com.foldmotion.wallpapers.R.string.import_exists)
                is ImportResult.Rejected -> emit(com.foldmotion.wallpapers.R.string.import_rejected, getApplication<Application>().getString(rejectReason(result.codes)))
            }
        }
    }

    fun shareIntent(id: String): Intent? {
        val app = getApplication<Application>()
        val file = try {
            container.packStore.exportToCache(id)
        } catch (e: Exception) {
            return null
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        return Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // ------------------------------------------------------------------ settings

    fun setLivingMotion(mode: LivingMotion) {
        settings.livingMotion = mode
    }

    fun setTiltParallax(on: Boolean) {
        settings.tiltParallax = on
    }

    val liveSwitchHoldMs: StateFlow<Long> = settingsChanges
        .filter { it.isEmpty() || it == AppSettings.KEY_SWITCH_HOLD }
        .map { settings.liveSwitchHoldMs }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.liveSwitchHoldMs)

    fun setLiveSwitchHold(ms: Long) {
        settings.liveSwitchHoldMs = ms
    }

    /** Pack shown in the home hero: the one on the home screen, else the newest. */
    fun heroPack(list: List<StoredPack>, active: String?): StoredPack? = list.firstOrNull { it.id == active } ?: list.firstOrNull()

    fun setReducedMotion(on: Boolean) {
        settings.reducedMotion = on
    }

    fun setProfileOverride(id: String?) {
        settings.profileOverride = id
        refreshDevice()
    }

    val languageSupported: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun setLanguage(tag: String?) {
        if (Build.VERSION.SDK_INT < 33) return
        val lm = getApplication<Application>().getSystemService(LocaleManager::class.java) ?: return
        lm.applicationLocales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    fun currentLanguageTag(): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val list = getApplication<Application>().getSystemService(LocaleManager::class.java)?.applicationLocales
        return if (list == null || list.isEmpty) null else list[0].language
    }

    fun language(): String = getApplication<Application>().resources.configuration.locales[0].language

    fun emit(resId: Int, arg: String? = null) {
        _messages.tryEmit(UiMessage.Text(resId, arg))
    }

    companion object {
        val SAMPLE_TITLE = com.foldmotion.core.pack.LocalizedText("ضوء سائل", "Liquid light")
        const val PHOTO_LONG_SIDE = 2560
        const val REFERENCE_SAMPLE_PX = 96

        fun rejectReason(codes: List<ValidationCode>): Int {
            val c = codes.firstOrNull()
            return when (c) {
                ValidationCode.NOT_A_FOLDPACK, ValidationCode.WRONG_ASSET_TYPE -> com.foldmotion.wallpapers.R.string.reject_not_a_foldpack
                ValidationCode.UNSUPPORTED_PROFILE -> com.foldmotion.wallpapers.R.string.reject_unsupported_profile
                ValidationCode.SCHEMA_TOO_NEW, ValidationCode.RENDERER_TOO_OLD -> com.foldmotion.wallpapers.R.string.reject_schema_too_new
                ValidationCode.UNSAFE_PATH, ValidationCode.UNSUPPORTED_MEDIA_TYPE, ValidationCode.TOO_MANY_ENTRIES -> com.foldmotion.wallpapers.R.string.reject_unsafe
                ValidationCode.STATIC_ONLY -> com.foldmotion.wallpapers.R.string.reject_static
                ValidationCode.FILE_TOO_LARGE, ValidationCode.PACK_TOO_LARGE -> com.foldmotion.wallpapers.R.string.reject_too_large
                else -> com.foldmotion.wallpapers.R.string.reject_damaged
            }
        }
    }
}
