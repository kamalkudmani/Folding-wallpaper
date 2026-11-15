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

/**
 * The realistic live scenes on the real hardware pipeline: each has detail and light on both
 * screens, lives between folds, and its fold motion is the picture itself.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LiveSceneTest {

    private val out = File(System.getProperty("foldmotion.screenshotDir") ?: "build/screenshots", "scenes").apply { mkdirs() }
    private val main = WorldRect(0f, 0f, 0.9011f, 1f)
    private val cover = WorldRect(0.4606f, 0f, 0.8913f, 1f)

    private fun render(spec: LiquidSpec, world: WorldRect, frame: LiquidFrame, width: Int, height: Int): Bitmap =
        HwCapture.render(width, height) { c ->
            val s = RuntimeShader(LiquidField.agslFor(spec))
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
    fun `every scene has form on both screens, lives, and opens by itself`() {
        val problems = ArrayList<String>()
        fun check(ok: Boolean, what: String) {
            if (!ok) problems += what
        }
        for ((name, spec) in LiquidSpec.SCENES) {
            assertTrue(name, spec.isWellFormed() && spec.isScene)
            val open = render(spec, main, LiquidFrame(30_000, kind = ScreenKind.MAIN), 540, 600)
            val closed = render(spec, cover, LiquidFrame(30_000, kind = ScreenKind.COVER), 258, 600)
            save(open, "${name}_main")
            save(closed, "${name}_cover")
            check(contrast(open) > 60, "$name open contrast ${contrast(open)}")
            check(contrast(closed) > 60, "$name closed contrast ${contrast(closed)}")
            val small = render(spec, main, LiquidFrame(30_000, kind = ScreenKind.MAIN), 270, 300)
            val start = render(spec, main, LiquidFrame(30_000, kind = ScreenKind.MAIN, progress = 0f), 270, 300)
            save(start, "${name}_open_0")
            save(render(spec, main, LiquidFrame(30_000, kind = ScreenKind.MAIN, progress = 0.35f), 270, 300), "${name}_open_35")
            check(diff(start, small) > 1.5, "$name opening moves ${diff(start, small)}")
            val later = render(spec, main, LiquidFrame(36_000, kind = ScreenKind.MAIN), 270, 300)
            check(diff(later, small) > 0.3, "$name lives ${diff(later, small)}")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
