package com.foldmotion.wallpapers

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.wallpapers.telemetry.PocTrace
import com.foldmotion.wallpapers.telemetry.ProblemReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tester's report carries their words and the facts, and attaches the trace only when asked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProblemReportTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val container = AppContainer.get(app)

    private fun facts() = ProblemReport.Facts(
        device = container.deviceInfo.snapshot(),
        wallpaperActive = true,
        activePack = "fv-123",
        activeStyle = "RIPPLE",
        video = true,
        livingMotion = "FULL",
        reducedMotion = false,
    )

    @Test
    fun `the report holds the words, the app, the phone and the wallpaper`() {
        val text = ProblemReport.text("Folded with the screen on, it froze.", facts())
        assertTrue(text, text.contains("Folded with the screen on, it froze."))
        assertTrue(text, text.contains("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
        assertTrue(text, text.contains("Android"))
        assertTrue(text, text.contains("style RIPPLE · video"))
        assertTrue(text, text.contains("Living motion: FULL"))
        assertTrue(ProblemReport.text("  ", facts()).contains("(no description)"))
    }

    @Test
    fun `the trace is attached only when the tester agrees`() {
        PocTrace.clear()
        val without = ProblemReport.intent(app, "x", facts(), withTrace = false)
        assertEquals(Intent.ACTION_SEND, without.action)
        assertNull(without.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        val with = ProblemReport.intent(app, "x", facts(), withTrace = true)
        val uri = with.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertNotNull(uri)
        assertTrue(with.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(with.getStringExtra(Intent.EXTRA_TEXT)!!.contains("Fold Motion problem report"))
    }
}
