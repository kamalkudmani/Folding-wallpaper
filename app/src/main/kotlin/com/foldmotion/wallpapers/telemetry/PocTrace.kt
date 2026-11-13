package com.foldmotion.wallpapers.telemetry

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Feasibility trace for the Fold7 proof of concept (blueprint section 03).
 *
 * Records engine lifecycle, surface sizes, display ids, visibility, hinge posture and the fold
 * state machine's decisions with monotonic timestamps, to answer: does the same engine survive
 * the display switch, how late does the new surface arrive, does Home/Lock both work.
 *
 * Contains no personal data: no prompts, no account, no file names beyond pack ids.
 * Kept on the device in a rotating file; the user exports it from Fold Lab.
 */
object PocTrace {

    data class Entry(val elapsedMs: Long, val wallMs: Long, val source: String, val event: String, val detail: String)

    private const val MAX_MEMORY = 600
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val memory = ArrayDeque<Entry>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "poc-trace").apply { isDaemon = true } }
    private var file: File? = null
    private var listeners = emptyList<() -> Unit>()

    @Volatile
    var enabled: Boolean = true

    fun init(context: Context) {
        file = File(context.filesDir, "poc/trace.log").apply { parentFile?.mkdirs() }
    }

    fun log(source: String, event: String, detail: String = "") {
        if (!enabled) return
        val entry = Entry(SystemClock.elapsedRealtime(), System.currentTimeMillis(), source, event, detail)
        synchronized(memory) {
            memory.addLast(entry)
            while (memory.size > MAX_MEMORY) memory.removeFirst()
        }
        val f = file
        if (f != null) {
            writer.execute {
                try {
                    if (f.length() > MAX_FILE_BYTES) {
                        val old = File(f.parentFile, "trace.1.log")
                        old.delete()
                        f.renameTo(old)
                    }
                    f.appendText(format(entry) + "\n")
                } catch (_: Exception) {
                    // Diagnostics must never crash the wallpaper.
                }
            }
        }
        listeners.forEach { it() }
    }

    fun recent(): List<Entry> = synchronized(memory) { memory.toList() }

    fun clear() {
        synchronized(memory) { memory.clear() }
        val f = file ?: return
        writer.execute {
            f.delete()
            File(f.parentFile, "trace.1.log").delete()
        }
        listeners.forEach { it() }
    }

    fun addListener(l: () -> Unit) {
        listeners = listeners + l
    }

    fun removeListener(l: () -> Unit) {
        listeners = listeners - l
    }

    /** Full trace with a device header, written to [target] for sharing. */
    fun exportTo(target: File, header: String) {
        val done = CountDownLatch(1)
        writer.execute {
            try {
                target.parentFile?.mkdirs()
                target.bufferedWriter().use { out ->
                    out.write("# Fold Motion POC trace\n")
                    out.write("# device: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | build ${Build.DISPLAY}\n")
                    out.write(header.lineSequence().joinToString("\n") { "# $it" })
                    out.write("\n# columns: elapsed_ms\twall_utc\tsource\tevent\tdetail\n")
                    val dir = file?.parentFile
                    for (name in listOf("trace.1.log", "trace.log")) {
                        val part = dir?.let { File(it, name) }
                        if (part != null && part.isFile) part.forEachLine { out.write(it); out.write("\n") }
                    }
                }
            } finally {
                done.countDown()
            }
        }
        // The writer thread is serial, so every entry logged before this call is in the file.
        done.await(5, TimeUnit.SECONDS)
    }

    private val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun format(e: Entry): String = "${e.elapsedMs}\t${synchronized(utc) { utc.format(Date(e.wallMs)) }}\t${e.source}\t${e.event}\t${e.detail}"
}
