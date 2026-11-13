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
import com.foldmotion.wallpapers.pack.ImportResult
import com.foldmotion.wallpapers.pack.StoredPack
import com.foldmotion.wallpapers.render.SceneLoader
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
    val error: Int? = null,
) {
    val interpretation: PromptInterpretation get() = PromptAnalyzer.analyze(prompt)
    val promptLength: Int get() = prompt.codePointCount(0, prompt.length)
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
