package com.foldmotion.wallpapers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.wallpapers.telemetry.PocTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PocTraceTest {

    @Test
    fun `reading the trace keeps time monotonic across a reboot`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PocTrace.init(app)
        val dir = File(app.filesDir, "poc").apply { mkdirs() }
        File(dir, "trace.1.log").writeText(
            "5000\t2026-11-13T00:00:00.000Z\tengine#1\tSURFACE_CHANGED\tkind=COVER\n" +
                "9000\t2026-11-13T00:00:04.000Z\tengine#1\tSURFACE_CHANGED\tkind=MAIN\n",
        )
        // After a reboot elapsedRealtime starts again near zero.
        File(dir, "trace.log").writeText(
            "300\t2026-11-13T01:00:00.000Z\tengine#2\tSURFACE_CHANGED\tkind=COVER\n" +
                "broken line\n" +
                "900\t2026-11-13T01:00:00.600Z\thinge\tPOSTURE_CHANGE\tfrom=CLOSED to=OPEN\n",
        )
        val events = PocTrace.readAll()
        assertEquals(4, events.size)
        assertTrue(events.zipWithNext().all { (a, b) -> b.atMs >= a.atMs })
        assertTrue(events[2].atMs - events[1].atMs >= 60_000)
        assertEquals("OPEN", events[3].fields["to"])
    }
}
