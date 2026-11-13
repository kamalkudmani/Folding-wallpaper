package com.foldmotion.wallpapers.telemetry

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.foldmotion.core.poc.TraceAnalyzer
import com.foldmotion.wallpapers.BuildConfig
import com.foldmotion.wallpapers.device.DeviceSnapshot
import java.io.File

/**
 * A tester's problem report: what they wrote, the facts needed to reproduce it (phone, Android,
 * app version, profile, the wallpaper in use and its fold style, motion settings) and, if they
 * agree, the Fold Lab trace. It goes through Android's share sheet, so the tester picks the app
 * and the person it goes to; Fold Motion itself still sends nothing.
 */
object ProblemReport {

    data class Facts(
        val device: DeviceSnapshot,
        val wallpaperActive: Boolean,
        val activePack: String?,
        val activeStyle: String?,
        val video: Boolean,
        val livingMotion: String,
        val reducedMotion: Boolean,
    )

    /** The report as plain text, readable in any chat or mail app. */
    fun text(note: String, c: Facts): String = buildString {
        appendLine("Fold Motion problem report")
        appendLine()
        appendLine(note.trim().ifEmpty { "(no description)" })
        appendLine()
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Phone: ${c.device.manufacturer} ${c.device.model} · Android ${c.device.androidRelease} (API ${c.device.sdkInt})")
        appendLine("Profile: ${c.device.active.id}${if (c.device.manuallyChosen) " (chosen)" else ""} · detected ${c.device.detected.profile.id} ${c.device.detected.confidence}")
        appendLine("Hinge sensor: ${if (c.device.hasHingeSensor) "yes" else "no"} · window ${c.device.maxWidthPx}x${c.device.maxHeightPx}")
        appendLine("Wallpaper active: ${if (c.wallpaperActive) "yes" else "no"} · pack ${c.activePack ?: "-"} · style ${c.activeStyle ?: "-"}${if (c.video) " · video" else ""}")
        appendLine("Living motion: ${c.livingMotion} · reduce motion: ${if (c.reducedMotion) "on" else "off"}")
        append("Liquid effects on GPU: ${if (Build.VERSION.SDK_INT >= 33) "yes" else "no (Android 12 or older)"}")
    }

    /**
     * The share intent. With [withTrace], the Fold Lab trace and its analysis are attached as a
     * file through the app's FileProvider.
     */
    fun intent(context: Context, note: String, c: Facts, withTrace: Boolean): Intent {
        val body = text(note, c)
        val send = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_SUBJECT, "Fold Motion ${BuildConfig.VERSION_NAME}: problem report")
            .putExtra(Intent.EXTRA_TEXT, body)
        if (withTrace) {
            val file = File(context.cacheDir, "exports/fold-motion-report-${System.currentTimeMillis()}.txt")
            PocTrace.exportTo(file, body + "\n\n" + TraceAnalyzer.analyze(PocTrace.readAll()).toReport())
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            send.setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            send.setType("text/plain")
        }
        return send
    }
}
