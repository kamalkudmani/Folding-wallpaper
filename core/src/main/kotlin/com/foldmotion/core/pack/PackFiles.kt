package com.foldmotion.core.pack

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Size and count ceilings applied to every pack, whether generated, imported or downloaded. */
data class PackLimits(
    val maxEntries: Int = 64,
    val maxFileBytes: Long = 32L * 1024 * 1024,
    val maxTotalBytes: Long = 96L * 1024 * 1024,
    val maxImageDimension: Int = 4096,
    val maxLayersPerScene: Int = 6,
    val maxPathLength: Int = 120,
    val maxPathDepth: Int = 3,
)

object PackPaths {
    const val CHECKSUMS = "checksums.sha256"
    val ALLOWED_EXTENSIONS = setOf("json", "webp", "sha256")

    /**
     * True for a plain relative path made of safe segments. Rejects absolute paths, backslashes,
     * dot-dot, empty segments and anything a zip entry could use to escape the pack folder.
     */
    fun isSafe(path: String, limits: PackLimits = PackLimits()): Boolean {
        if (path.isEmpty() || path.length > limits.maxPathLength) return false
        if (path.startsWith("/") || path.contains('\\') || path.contains(':')) return false
        val segments = path.split('/')
        if (segments.size > limits.maxPathDepth) return false
        return segments.all { seg ->
            seg.isNotEmpty() && seg != "." && seg != ".." &&
                seg.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' || it == '.' }
        }
    }

    fun extension(path: String): String = path.substringAfterLast('.', "").lowercase()
}

object Sha256 {
    fun of(file: File): String = file.inputStream().use { of(it) }

    fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    fun of(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        val digits = "0123456789abcdef"
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(chars)
    }
}

/** checksums.sha256: one "<hex>  <relative path>" line per file, the file itself excluded. */
object Checksums {

    fun parse(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val hex = trimmed.substringBefore(' ')
            val path = trimmed.substringAfter(' ').trim()
            if (hex.length == 64 && path.isNotEmpty()) result[path] = hex.lowercase()
        }
        return result
    }

    /** Hashes every file under [packDir] and writes the checksum list. Returns path -> hash. */
    fun write(packDir: File): Map<String, String> {
        val entries = listFiles(packDir).filter { it != PackPaths.CHECKSUMS }.sorted()
        val map = LinkedHashMap<String, String>()
        for (path in entries) map[path] = Sha256.of(File(packDir, path))
        File(packDir, PackPaths.CHECKSUMS).writeText(
            map.entries.joinToString(separator = "\n", postfix = "\n") { "${it.value}  ${it.key}" },
        )
        return map
    }

    /** Relative, forward-slash paths of all regular files in the pack. */
    fun listFiles(packDir: File): List<String> {
        val base = packDir.toPath()
        return packDir.walkTopDown()
            .filter { it.isFile }
            .map { base.relativize(it.toPath()).joinToString("/") }
            .toList()
    }
}

object Streams {
    /** Fills [buffer] as far as the stream allows. InputStream.readNBytes needs API 33. */
    fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}

/** Reads just enough of a WebP header to know it really is WebP and how large the canvas is. */
object WebpInfo {

    data class Size(val width: Int, val height: Int)

    fun read(file: File): Size? {
        val header = ByteArray(30)
        val read = file.inputStream().use { Streams.readFully(it, header) }
        if (read < 30) return null
        if (!header.matches(0, "RIFF") || !header.matches(8, "WEBP")) return null
        return when {
            header.matches(12, "VP8X") -> Size(1 + header.u24(24), 1 + header.u24(27))
            header.matches(12, "VP8L") -> {
                if (header[20].toInt() and 0xFF != 0x2F) return null
                val bits = header.u32(21)
                Size(1 + (bits and 0x3FFF).toInt(), 1 + ((bits ushr 14) and 0x3FFF).toInt())
            }
            header.matches(12, "VP8 ") -> {
                val startCodeOk = header[23].toInt() and 0xFF == 0x9D &&
                    header[24].toInt() and 0xFF == 0x01 && header[25].toInt() and 0xFF == 0x2A
                if (!startCodeOk) return null
                Size(header.u16(26) and 0x3FFF, header.u16(28) and 0x3FFF)
            }
            else -> null
        }
    }

    private fun ByteArray.matches(offset: Int, tag: String): Boolean =
        tag.indices.all { this[offset + it] == tag[it].code.toByte() }

    private fun ByteArray.u16(o: Int): Int = (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u24(o: Int): Int = u16(o) or ((this[o + 2].toInt() and 0xFF) shl 16)
    private fun ByteArray.u32(o: Int): Long =
        (u24(o).toLong() and 0xFFFFFF) or ((this[o + 3].toLong() and 0xFF) shl 24)
}
