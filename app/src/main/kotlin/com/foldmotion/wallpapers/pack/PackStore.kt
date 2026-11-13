package com.foldmotion.wallpapers.pack

import android.content.Context
import com.foldmotion.core.CoreInfo
import com.foldmotion.core.motion.AmbientSpec
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.motion.MotionSpec
import com.foldmotion.core.pack.Checksums
import com.foldmotion.core.pack.FoldPackArchive
import com.foldmotion.core.pack.FoldPackManifest
import com.foldmotion.core.pack.FoldPackValidator
import com.foldmotion.core.pack.PackArchiveException
import com.foldmotion.core.pack.PackChoreography
import com.foldmotion.core.pack.Transitions
import com.foldmotion.core.pack.ValidationCode
import com.foldmotion.core.pack.ValidationReport
import com.foldmotion.core.pack.VersionRange
import com.foldmotion.wallpapers.data.AppSettings
import com.foldmotion.wallpapers.render.SceneLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class StoredPack(
    val id: String,
    val dir: File,
    val manifest: FoldPackManifest,
    val imported: Boolean,
    val sizeBytes: Long,
)

sealed interface RestyleResult {
    data class Restyled(val pack: StoredPack, val style: FoldStyle) : RestyleResult
    data class Failed(val codes: List<ValidationCode>, val detail: String) : RestyleResult
}

sealed interface ImportResult {
    data class Imported(val pack: StoredPack) : ImportResult
    data class AlreadyPresent(val pack: StoredPack) : ImportResult
    data class Rejected(val codes: List<ValidationCode>, val detail: String) : ImportResult
}

/**
 * Local FoldPack storage: filesDir/packs/<id>/.
 *
 * Packs only ever enter through [commit] (from the generator's staging folder) or [import]
 * (unpacked into a quarantine folder and validated there). Both end with an atomic rename, so the
 * engine never sees a half-written pack.
 */
class PackStore(context: Context, private val settings: AppSettings) {

    private val root = File(context.filesDir, "packs").apply { mkdirs() }
    private val staging = File(context.cacheDir, "staging")
    private val quarantine = File(context.cacheDir, "quarantine")
    val exportDir = File(context.cacheDir, "exports")

    private val _revision = MutableStateFlow(0)

    /** Increments whenever packs are added or removed; UI lists recollect on change. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun dir(id: String): File = File(root, id)

    fun newStagingDir(): File {
        staging.mkdirs()
        return File(staging, UUID.randomUUID().toString()).apply { mkdirs() }
    }

    fun get(id: String?): StoredPack? {
        if (id.isNullOrBlank()) return null
        val d = dir(id)
        if (!d.isDirectory) return null
        val manifest = SceneLoader.readManifest(d) ?: return null
        return StoredPack(id, d, manifest, id in settings.importedIds, d.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }

    fun list(): List<StoredPack> =
        (root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList())
            .mapNotNull { get(it.name) }
            .sortedByDescending { it.manifest.createdAt }

    /** Moves a validated staging folder into the store. */
    fun commit(stagingDir: File, manifest: FoldPackManifest): StoredPack {
        val target = dir(manifest.id)
        if (target.exists()) target.deleteRecursively()
        if (!stagingDir.renameTo(target)) {
            stagingDir.copyRecursively(target, overwrite = true)
            stagingDir.deleteRecursively()
        }
        _revision.value++
        return get(manifest.id) ?: throw IllegalStateException("pack vanished after commit")
    }

    /**
     * Gives a stored pack new fold animations in [style] (AUTO picks one for its scene). The pack
     * is copied to staging, its motion files and manifest rewritten, checksums recomputed and the
     * whole pack validated again before it replaces the old one; on any failure nothing changes.
     * The package version goes up so a shared copy is recognised as newer.
     */
    fun restyle(id: String, style: FoldStyle, validator: FoldPackValidator, profileId: String): RestyleResult {
        val current = get(id) ?: return RestyleResult.Failed(listOf(ValidationCode.INVALID_MANIFEST), "missing")
        val stage = newStagingDir()
        try {
            current.dir.copyRecursively(stage, overwrite = true)
            val manifest = current.manifest
            val choreo = PackChoreography.upgrade(manifest, style).choreography
            val json = CoreInfo.json
            File(stage, manifest.transitions.open.file).writeText(json.encodeToString(MotionSpec.serializer(), choreo.open))
            File(stage, manifest.transitions.close.file).writeText(json.encodeToString(MotionSpec.serializer(), choreo.close))
            val ambientPath = manifest.ambient ?: AmbientSpec.FILE.also {
                File(stage, it).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(AmbientSpec.serializer(), choreo.ambient))
            }
            val next = manifest.copy(
                packageVersion = manifest.packageVersion + 1,
                rendererVersion = VersionRange(CoreInfo.RENDERER_VERSION, maxOf(manifest.rendererVersion.max, CoreInfo.RENDERER_VERSION)),
                transitions = Transitions(
                    open = manifest.transitions.open.copy(durationMs = choreo.open.durationMs),
                    close = manifest.transitions.close.copy(durationMs = choreo.close.durationMs),
                ),
                ambient = ambientPath,
                motionStyle = choreo.style.name,
            )
            File(stage, FoldPackManifest.FILE_NAME).writeText(json.encodeToString(FoldPackManifest.serializer(), next))
            Checksums.write(stage)
            val report = validator.validate(stage, profileId)
            if (!report.passed) {
                return RestyleResult.Failed(report.codes, report.issues.joinToString { "${it.code}: ${it.detail}" })
            }
            swapIn(stage, id)
            _revision.value++
            settings.updatedPackId = id
            val stored = get(id) ?: return RestyleResult.Failed(listOf(ValidationCode.INVALID_MANIFEST), "vanished")
            return RestyleResult.Restyled(stored, choreo.style)
        } catch (e: Exception) {
            return RestyleResult.Failed(listOf(ValidationCode.INVALID_MOTION), e.message ?: e.javaClass.simpleName)
        } finally {
            stage.deleteRecursively()
        }
    }

    /** Replaces dir(id) with [stage]: the old folder is moved aside first and removed last. */
    private fun swapIn(stage: File, id: String) {
        val target = dir(id)
        val old = File(root, ".old-$id-${UUID.randomUUID()}")
        if (target.exists() && !target.renameTo(old)) throw IllegalStateException("cannot move old pack aside")
        try {
            if (!stage.renameTo(target)) {
                stage.copyRecursively(target, overwrite = true)
            }
        } catch (e: Exception) {
            target.deleteRecursively()
            old.renameTo(target)
            throw e
        }
        old.deleteRecursively()
    }

    /** Deletes a pack. The active pack is protected so the wallpaper never loses its files. */
    fun delete(id: String): Boolean {
        if (id == settings.activePackId) return false
        val ok = dir(id).deleteRecursively()
        settings.importedIds = settings.importedIds - id
        _revision.value++
        return ok
    }

    fun import(input: InputStream, validator: FoldPackValidator, profileId: String): ImportResult {
        quarantine.mkdirs()
        val q = File(quarantine, UUID.randomUUID().toString())
        try {
            FoldPackArchive.unpack(input, q)
            val report: ValidationReport = validator.validate(q, profileId)
            val manifest = report.manifest
            if (!report.passed || manifest == null) {
                return ImportResult.Rejected(report.codes.ifEmpty { listOf(ValidationCode.INVALID_MANIFEST) }, report.issues.joinToString { "${it.code}: ${it.detail}" })
            }
            get(manifest.id)?.let { existing ->
                if (existing.manifest.packageVersion >= manifest.packageVersion) return ImportResult.AlreadyPresent(existing)
            }
            val stored = commit(q, manifest)
            settings.importedIds = settings.importedIds + manifest.id
            return ImportResult.Imported(stored)
        } catch (e: PackArchiveException) {
            return ImportResult.Rejected(listOf(e.code), e.message ?: "")
        } catch (e: Exception) {
            return ImportResult.Rejected(listOf(ValidationCode.NOT_A_FOLDPACK), e.message ?: e.javaClass.simpleName)
        } finally {
            q.deleteRecursively()
        }
    }

    fun export(id: String, output: OutputStream) {
        FoldPackArchive.pack(dir(id), output)
    }

    /** Writes the archive to the share cache and returns the file. */
    fun exportToCache(id: String): File {
        exportDir.mkdirs()
        val file = File(exportDir, "$id.${FoldPackArchive.EXTENSION}")
        file.outputStream().use { export(id, it) }
        return file
    }

    /** Removes leftovers of interrupted generations or imports. */
    fun cleanTemporary() {
        staging.deleteRecursively()
        root.listFiles()?.filter { it.name.startsWith(".old-") }?.forEach { it.deleteRecursively() }
        quarantine.deleteRecursively()
        exportDir.listFiles()?.forEach { it.delete() }
    }

    fun freeBytes(): Long = root.usableSpace
}
