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

/**
 * Editable state of the Add screen (your own photo or video). Lives in the ViewModel so folding
 * never loses it.
 */
data class CreateForm(
    /** The motion for a photo; AUTO makes it 3D when a subject is found. */
    val style: FoldStyle = FoldStyle.AUTO,
    val intensity: MotionIntensity = MotionIntensity.LOW,
    val photo: PhotoPick? = null,
    val video: VideoPick? = null,
    val error: Int? = null,
)

/** A photo picked for a wallpaper, with a small picture of it for the screen. */
data class PhotoPick(val uri: android.net.Uri, val width: Int, val height: Int, val thumbnail: Bitmap?)

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

    val waitForUnlock: StateFlow<Boolean> = settingsChanges
        .filter { it.isEmpty() || it == AppSettings.KEY_WAIT_UNLOCK }
        .map { settings.waitForUnlock }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.waitForUnlock)

    val livingMotion: StateFlow<LivingMotion> = settingsChanges
        .filter { it.isEmpty() || it == AppSettings.KEY_LIVING }
        .map { settings.livingMotion }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.livingMotion)

    val tiltParallax: StateFlow<Boolean> = settingsChanges
        .filter { it.isEmpty() || it == AppSettings.KEY_TILT }
        .map { settings.tiltParallax }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.tiltParallax)

    val job = container.generation.job

    private val _form = MutableStateFlow(CreateForm())
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

    // ------------------------------------------------------------------ add your own

    fun updateForm(transform: (CreateForm) -> CreateForm) = _form.update { transform(it).copy(error = null) }

    /** Reads the picked photo's size and a small picture of it; the photo itself is read when made. */
    fun pickPhoto(uri: Uri) {
        viewModelScope.launch {
            val thumb = withContext(Dispatchers.IO) { decodePhoto(uri, PHOTO_THUMB_SIDE) }
            if (thumb == null) {
                emit(R.string.photo_error)
                return@launch
            }
            val size = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeStream(it, null, o)
                        o.outWidth to o.outHeight
                    }
                } catch (e: Exception) {
                    null
                }
            }?.takeIf { it.first > 0 && it.second > 0 } ?: (thumb.width to thumb.height)
            _form.update { it.copy(photo = PhotoPick(uri, size.first, size.second, thumb), video = null, error = null) }
        }
    }

    fun clearPhoto() = _form.update { it.copy(photo = null) }

    // ------------------------------------------------------------------ video wallpapers

    private val _videoJob = MutableStateFlow<VideoJob?>(null)
    val videoJob: StateFlow<VideoJob?> = _videoJob.asStateFlow()
    private var videoWork: kotlinx.coroutines.Job? = null

    /** Reads the picked clip's size and length and a first frame for the screen. */
    fun pickVideo(uri: android.net.Uri) {
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
                it.copy(video = VideoPick(uri, info.durationMs, info.width, info.height, thumb, 0, end, crossfade = true), photo = null, error = null)
            }
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
                    // The motion of a video is the video itself: nothing laid over it.
                    style = FoldStyle.NATURAL,
                    intensity = f.intensity,
                    crossfadeLoop = v.crossfade,
                    seed = random.nextLong(),
                    title = com.foldmotion.core.pack.LocalizedText("فيديو خاص بي", "My video"),
                ),
            )
        }
        return true
    }

    /**
     * Discover: a liquid glass wallpaper made into a pack (its drops are its motion). Two posters
     * rendered on the GPU, so it takes well under a second; progress goes through [videoJob].
     */
    fun createLiquidWallpaper(
        spec: com.foldmotion.core.pack.LiquidSpec,
        title: com.foldmotion.core.pack.LocalizedText,
        style: FoldStyle = FoldStyle.NATURAL,
        intensity: MotionIntensity = MotionIntensity.LOW,
    ) {
        if (_videoJob.value is VideoJob.Running) return
        _videoJob.value = VideoJob.Running(com.foldmotion.wallpapers.generator.video.VideoStage.POSTERS, 0f)
        videoWork = viewModelScope.launch {
            try {
                val pack = withContext(Dispatchers.Default) {
                    container.liquidBuilder.build(
                        com.foldmotion.wallpapers.generator.liquid.LiquidRequest(spec = spec, style = style, intensity = intensity, seed = random.nextLong(), title = title),
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
     * Add > Photo: the picked photo as a wallpaper in the chosen motion. With AUTO (and the
     * motions that move layers) a subject found on the phone is lifted in front of its
     * background, moving apart with tilt and the fold. Nothing is uploaded.
     */
    fun createFromPhoto(uri: android.net.Uri? = _form.value.photo?.uri) {
        if (uri == null || _videoJob.value is VideoJob.Running) return
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
                        com.foldmotion.wallpapers.generator.depth.PhotoRequest(style = f.style, intensity = f.intensity, seed = random.nextLong(), title = com.foldmotion.core.pack.LocalizedText("صورتي", "My photo")),
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

    /** Discover: a curated idea made into a pack exactly as pictured (same seed), with its built-in motion. */
    fun getDiscoverIdea(item: com.foldmotion.wallpapers.ui.discover.DiscoverItem): Boolean {
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
                motionStyle = item.style,
            ),
            profile,
        )
        return true
    }

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

    fun setWaitForUnlock(on: Boolean) {
        settings.waitForUnlock = on
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
        const val PHOTO_LONG_SIDE = 2560
        const val PHOTO_THUMB_SIDE = 720

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
