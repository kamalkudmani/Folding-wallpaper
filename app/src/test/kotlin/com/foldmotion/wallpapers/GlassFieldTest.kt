package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.core.scene.WorldRect
import com.foldmotion.wallpapers.render.LiquidField
import com.foldmotion.wallpapers.render.LiquidFrame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** The liquid glass shader on the real hardware pipeline: it has form, and its drops are the fold motion. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GlassFieldTest {

    private val out = File(System.getProperty("foldmotion.screenshotDir") ?: "build/screenshots", "glass").apply { mkdirs() }
    private val main = WorldRect(0f, 0f, 0.9011f, 1f)
    private val cover = WorldRect(0.4606f, 0f, 0.8913f, 1f)

    private fun render(spec: LiquidSpec, world: WorldRect, frame: LiquidFrame, width: Int, height: Int): Bitmap =
        HwCapture.render(width, height) { c ->
            val s = RuntimeShader(LiquidField.GLASS_AGSL)
            LiquidField.configure(s, spec, world, width, height, frame.copy(mainWorld = main))
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { shader = s })
        }

    private fun save(b: Bitmap, name: String) = File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun diff(a: Bitmap, b: Bitmap): Double {
        var sum = 0L
        var n = 0
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            sum += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
            n += 3
        }
        return sum.toDouble() / n
    }

    /** Spread of luminance: the glass has rims, shadows and colour, not a flat fill. */
    private fun contrast(b: Bitmap): Int {
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
    fun `every preset has glass on both screens, and its drops move with the fold`() {
        for ((name, spec) in LiquidSpec.PRESETS) {
            val open = render(spec, main, LiquidFrame(0, kind = ScreenKind.MAIN), 540, 600)
            val closed = render(spec, cover, LiquidFrame(0, kind = ScreenKind.COVER), 258, 600)
            save(open, "${name}_main")
            save(closed, "${name}_cover")
            assertTrue("$name open contrast ${contrast(open)}", contrast(open) > 40)
            assertTrue("$name closed contrast ${contrast(closed)}", contrast(closed) > 40)
            val small = render(spec, main, LiquidFrame(0, kind = ScreenKind.MAIN), 270, 300)
            for (p in listOf(0f, 0.25f, 0.5f)) {
                val f = render(spec, main, LiquidFrame(0, kind = ScreenKind.MAIN, progress = p), 270, 300)
                save(f, "${name}_open_${(p * 100).toInt()}")
                if (p == 0f) assertTrue("$name gathered vs open ${diff(f, small)}", diff(f, small) > 4.0)
            }
            // Closing ends exactly where the cover rests.
            val endClose = render(spec, cover, LiquidFrame(0, kind = ScreenKind.COVER, progress = 1f), 258, 600)
            assertTrue(diff(endClose, closed) < 0.5)
        }
    }

    @Test
    fun `a tap makes the glass ripple near the finger only`() {
        val spec = LiquidSpec.PRESETS.getValue("lagoon")
        val still = render(spec, main, LiquidFrame(0, kind = ScreenKind.MAIN), 270, 300)
        val tap = render(spec, main, LiquidFrame(0, kind = ScreenKind.MAIN, ripples = listOf(com.foldmotion.wallpapers.render.LiquidMotion.Ripple(0.45f, 0.5f, 0.4f, 1f))), 270, 300)
        save(tap, "ripple")
        val near = diff(Bitmap.createBitmap(still, 100, 110, 70, 80), Bitmap.createBitmap(tap, 100, 110, 70, 80))
        val far = diff(Bitmap.createBitmap(still, 0, 0, 30, 30), Bitmap.createBitmap(tap, 0, 0, 30, 30))
        assertTrue("near $near far $far", near > 1.0 && far < 0.5)
    }
}
