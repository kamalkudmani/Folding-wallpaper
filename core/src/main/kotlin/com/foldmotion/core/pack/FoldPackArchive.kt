package com.foldmotion.core.pack

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PackArchiveException(val code: ValidationCode, detail: String) : Exception("$code: $detail")

/**
 * Reads and writes the portable `.foldpack` archive (a zip of the pack folder).
 *
 * Unpacking is hostile-input safe: entry names are checked before any byte is written, sizes are
 * counted while streaming instead of trusting the zip header (zip bombs), and only whitelisted
 * file types are accepted. The caller unpacks into a quarantine folder and validates there.
 */
object FoldPackArchive {

    const val EXTENSION = "foldpack"
    const val MIME_TYPE = "application/zip"

    fun pack(packDir: File, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            for (path in Checksums.listFiles(packDir).sorted()) {
                zip.putNextEntry(ZipEntry(path))
                File(packDir, path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * @return relative paths written under [destDir].
     * @throws PackArchiveException with the reason the archive was refused.
     */
    fun unpack(input: InputStream, destDir: File, limits: PackLimits = PackLimits()): List<String> {
        val buffered = BufferedInputStream(input)
        rejectForeignFormats(buffered)

        destDir.mkdirs()
        val destRoot = destDir.canonicalFile
        val written = ArrayList<String>()
        var total = 0L
        val buffer = ByteArray(64 * 1024)

        ZipInputStream(buffered).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (!PackPaths.isSafe(name, limits)) throw PackArchiveException(ValidationCode.UNSAFE_PATH, name)
                if (PackPaths.extension(name) !in PackPaths.ALLOWED_EXTENSIONS) {
                    throw PackArchiveException(ValidationCode.UNSUPPORTED_MEDIA_TYPE, name)
                }
                if (written.size >= limits.maxEntries) throw PackArchiveException(ValidationCode.TOO_MANY_ENTRIES, name)

                val target = File(destRoot, name).canonicalFile
                if (!target.path.startsWith(destRoot.path + File.separator)) {
                    throw PackArchiveException(ValidationCode.UNSAFE_PATH, name)
                }
                target.parentFile?.mkdirs()

                var fileBytes = 0L
                val maxBytes = PackPaths.maxBytesFor(name, limits)
                target.outputStream().use { out ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        fileBytes += read
                        total += read
                        if (fileBytes > maxBytes) throw PackArchiveException(ValidationCode.FILE_TOO_LARGE, name)
                        if (total > limits.maxTotalBytes) throw PackArchiveException(ValidationCode.PACK_TOO_LARGE, name)
                        out.write(buffer, 0, read)
                    }
                }
                written += name
            }
        }
        if (written.isEmpty()) throw PackArchiveException(ValidationCode.NOT_A_FOLDPACK, "empty or not a zip archive")
        return written
    }

    /**
     * A lone picture or video is the most common wrong upload. Name it precisely instead of
     * failing later with a generic zip error.
     */
    private fun rejectForeignFormats(input: BufferedInputStream) {
        input.mark(16)
        val head = ByteArray(12)
        val read = Streams.readFully(input, head)
        input.reset()
        fun at(i: Int) = if (i < read) head[i].toInt() and 0xFF else -1
        val kind = when {
            at(0) == 0xFF && at(1) == 0xD8 -> "JPEG image"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "PNG image"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "GIF image"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 -> "WebP image"
            at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> "MP4/HEIF media"
            at(0) == 0x50 && at(1) == 0x4B -> null
            else -> "unknown file"
        }
        if (kind != null) {
            throw PackArchiveException(ValidationCode.NOT_A_FOLDPACK, "$kind is not a FoldPack")
        }
    }
}
