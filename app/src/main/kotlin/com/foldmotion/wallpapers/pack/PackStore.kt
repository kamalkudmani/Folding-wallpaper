package com.foldmotion.wallpapers.pack

import android.content.Context
import com.foldmotion.core.pack.FoldPackArchive
import com.foldmotion.core.pack.FoldPackManifest
import com.foldmotion.core.pack.FoldPackValidator
import com.foldmotion.core.pack.PackArchiveException
import com.foldmotion.core.pack.ValidationCode
import com.foldmotion.core.pack.ValidationReport
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
        (root.listFiles()?.filter { it.isDirectory } ?: emptyList())
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
        quarantine.deleteRecursively()
        exportDir.listFiles()?.forEach { it.delete() }
    }

    fun freeBytes(): Long = root.usableSpace
}
