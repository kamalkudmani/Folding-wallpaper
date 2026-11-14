package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RuntimeShader
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.core.scene.WorldRect
import com.foldmotion.wallpapers.render.LiquidField
import com.foldmotion.wallpapers.render.LiquidFrame
import com.foldmotion.wallpapers.render.LiquidMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The live liquid shader on the real hardware pipeline: it has form, it flows, it is exactly
 * periodic, the cover shows the same liquid as the main screen around it, and tilt, taps and the
 * hinge change it where they should.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LiquidFieldTest {

    private val out = File(System.getProperty("foldmotion.screenshotDir") ?: "build/screenshots", "liquid").apply { mkdirs() }
    private val main = WorldRect(0f, 0f, 0.9f, 1f)
    private val w = 270
    private val h = 300


    private fun render(spec: LiquidSpec, world: WorldRect, frame: LiquidFrame, width: Int = w, height: Int = h): Bitmap =
        HwCapture.render(width, height) { c ->
            val s = RuntimeShader(LiquidField.AGSL)
            LiquidField.configure(s, spec, world, width, height, frame)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), android.graphics.Paint().apply { shader = s })
        }

    private fun save(b: Bitmap, name: String) = File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun diff(a: Bitmap, b: Bitmap, x0: Int = 0, y0: Int = 0, x1: Int = a.width, y1: Int = a.height): Double {
        var sum = 0L
        var n = 0
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            sum += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
            n += 3
        }
        return sum.toDouble() / n
    }

    private fun spread(b: Bitmap): Int {
        var lo = 255
        var hi = 0
        for (y in 0 until b.height step 3) for (x in 0 until b.width step 3) {
            val p = b.getPixel(x, y)
            val l = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            lo = minOf(lo, l)
            hi = maxOf(hi, l)
        }
        return hi - lo
    }

    @Test
    fun `every preset has form and light, and flows`() {
        for ((name, spec) in LiquidSpec.PRESETS) {
            assertTrue(name, spec.isWellFormed())
            val a = render(spec, main, LiquidFrame(0))
            save(a, "preset_$name")
            assertTrue("$name: contrast ${spread(a)}", spread(a) > 90)
            val b = render(spec, main, LiquidFrame(2_000))
            assertTrue("$name flows", diff(a, b) > 4.0)
        }
    }

    @Test
    fun `the flow repeats exactly after ten minutes and never jumps`() {
        val spec = LiquidSpec.PRESETS.getValue("gold")
        val a = render(spec, main, LiquidFrame(1_000))
        val b = render(spec, main, LiquidFrame(1_000 + LiquidSpec.PERIOD_SECONDS * 1000L))
        assertTrue("period ${diff(a, b)}", diff(a, b) < 1.0)
        // One frame apart at 60 fps changes very little.
        val c = render(spec, main, LiquidFrame(1_016))
        assertTrue("step ${diff(a, c)}", diff(a, c) < 3.0)
        // Hours in, still sharp (time is folded into the period).
        val late = render(spec, main, LiquidFrame(9L * 3_600_000L + 1_000))
        assertTrue(spread(late) > 90)
    }

    @Test
    fun `the cover shows the same liquid as the main screen around it`() {
        val spec = LiquidSpec.PRESETS.getValue("aurora")
        val full = render(spec, main, LiquidFrame(500), 270, 300)
        // The middle third of the main window, rendered on its own.
        val slice = WorldRect(0.3f, 0f, 0.6f, 1f)
        val cover = render(spec, slice, LiquidFrame(500), 90, 300)
        val fromFull = Bitmap.createBitmap(full, 90, 0, 90, 300)
        // Vignettes differ (each screen has its own); the liquid itself matches.
        assertTrue("continuity ${diff(cover, fromFull, 20, 60, 70, 240)}", diff(cover, fromFull, 20, 60, 70, 240) < 12.0)
    }

    @Test
    fun `tilt, a tap and the hinge move the liquid where they should`() {
        val spec = LiquidSpec.PRESETS.getValue("pearl")
        val still = render(spec, main, LiquidFrame(0))
        val tilted = render(spec, main, LiquidFrame(0, flowX = 0.4f))
        assertTrue("tilt ${diff(still, tilted)}", diff(still, tilted) > 6.0)
        assertEquals(still.getPixel(10, 10), render(spec.copy(tilt = false), main, LiquidFrame(0, flowX = 0.4f)).getPixel(10, 10))

        // A ripple 0.4 s after a tap in the middle: changed around the tap, not in the far corner.
        val tap = LiquidFrame(0, ripples = listOf(LiquidMotion.Ripple(0.45f, 0.5f, 0.4f, 1f)))
        val rippled = render(spec, main, tap)
        save(rippled, "ripple")
        assertTrue("near ${diff(still, rippled, 100, 110, 170, 190)}", diff(still, rippled, 100, 110, 170, 190) > 4.0)
        assertTrue("far ${diff(still, rippled, 0, 0, 30, 30)}", diff(still, rippled, 0, 0, 30, 30) < 0.5)

        // Half open: the liquid pools along the fold; the edges stay as they were.
        val folded = render(spec, main, LiquidFrame(0, hinge = 1f, creaseX = 0.45f))
        save(folded, "hinge")
        assertTrue("fold ${diff(still, folded, 120, 0, 150, h)}", diff(still, folded, 120, 0, 150, h) > 6.0)
        assertTrue("edge ${diff(still, folded, 0, 0, 20, h)}", diff(still, folded, 0, 0, 20, h) < 3.0)
        assertEquals(0f, LiquidMotion.hingeAmount(180f), 1e-3f)
        assertEquals(1f, LiquidMotion.hingeAmount(90f), 1e-3f)
        assertEquals(0f, LiquidMotion.hingeAmount(5f), 1e-3f)
        assertEquals(0f, LiquidMotion().frame(0, 0f, 0f, ScreenKind.COVER, 0.45f).hinge, 0f)
    }

    @Test
    fun `tilt drifts the liquid and the drift stays bounded`() {
        val m = LiquidMotion()
        var t = 0L
        repeat(600) { m.frame(t, 1f, 0f, ScreenKind.MAIN, 0.45f); t += 100 }
        val f = m.frame(t, 1f, 0f, ScreenKind.MAIN, 0.45f)
        assertTrue("drift ${f.flowX}", f.flowX > 2f && f.flowX < 0.32f / 0.04f)
        m.tap(0.2f, 0.3f, t)
        assertEquals(1, m.frame(t + 100, 0f, 0f, ScreenKind.MAIN, 0.45f).ripples.size)
        assertEquals(0, m.frame(t + 10_000, 0f, 0f, ScreenKind.MAIN, 0.45f).ripples.size)
        // Another owner (a preview) never sees this one's taps or drift.
        m.tap(0.2f, 0.3f, t + 10_000)
        val other = LiquidMotion().frame(t + 10_100, 0f, 0f, ScreenKind.MAIN, 0.45f)
        assertEquals(0, other.ripples.size)
        assertEquals(0f, other.flowX, 0f)
    }

}
