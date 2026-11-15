package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.GenerationRequest
import com.foldmotion.wallpapers.generator.LocalPackGenerator
import com.foldmotion.wallpapers.generator.liquid.LiquidRequest
import com.foldmotion.wallpapers.render.FrameState
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The built-in (NATURAL) motion: nothing is laid over the picture (no darkness, no light, no
 * particles), the picture itself moves into place, right to left when the phone opens; and a
 * liquid glass wallpaper's drops carry the whole motion, from the closed pose to the open one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class NaturalMotionTest {

    private val container = AppContainer.get(ApplicationProvider.getApplicationContext())
    private val fold7 = DeviceProfileRegistry.fold7
    private val out = File(System.getProperty("foldmotion.screenshotDir") ?: "build/screenshots", "natural").apply { mkdirs() }

    private fun save(b: Bitmap, name: String) = File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun luma(b: Bitmap): Double {
        var sum = 0L
        var n = 0
        for (y in 0 until b.height step 3) for (x in 0 until b.width step 3) {
            val p = b.getPixel(x, y)
            sum += (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            n++
        }
        return sum.toDouble() / n
    }

    private fun diff(a: Bitmap, b: Bitmap): Double {
        var sum = 0L
        var n = 0
        for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            sum += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
            n += 3
        }
        return sum.toDouble() / n
    }

    /** Horizontal shift (pixels) that best maps [moved] back onto [rest], over the lower half. */
    private fun shift(rest: Bitmap, moved: Bitmap): Int {
        var best = 0
        var bestErr = Double.MAX_VALUE
        for (dx in -40..40) {
            var err = 0.0
            var n = 0
            for (y in rest.height / 2 until rest.height step 4) for (x in 50 until rest.width - 50 step 4) {
                val p = rest.getPixel(x, y)
                val q = moved.getPixel(x + dx, y)
                err += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
                n++
            }
            if (err / n < bestErr) {
                bestErr = err / n
                best = dx
            }
        }
        return best
    }

    @Test
    fun `a drawn scene moves itself into place, right to left, with nothing laid over it`() {
        val pack = runBlocking {
            LocalPackGenerator(container.packStore, container.validator)
                .generate(GenerationRequest("natural", fold7.id, GenerationMode.RANDOM, ideaId = "aurora_peaks", seed = 11L, motionStyle = FoldStyle.NATURAL), fold7) {}
                .pack
        }
        assertEquals("NATURAL", pack.manifest.motionStyle)
        assertEquals(CoreInfo.NATURAL_RENDERER_VERSION, pack.manifest.rendererVersion.min)
        assertTrue(container.validator.validate(pack.dir, fold7.id).passed)
        val drawer = SceneDrawer()
        for (kind in listOf(ScreenKind.MAIN, ScreenKind.COVER)) {
            val scene = SceneLoader.load(pack.dir, kind, 900)!!
            val motion = scene.entryMotion!!
            assertNull(motion.reveal)
            assertTrue(motion.particles.isEmpty())
            val w = if (kind == ScreenKind.MAIN) 400 else 200
            val h = (w / fold7.screen(kind).aspect).toInt()
            fun frame(p: Float?) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawer.draw(Canvas(it), w, h, scene, p, FrameState.STILL) }
            val rest = frame(null)
            val name = if (kind == ScreenKind.MAIN) "main" else "cover"
            save(rest, "${name}_rest")
            for (p in listOf(0f, 0.15f, 0.3f, 0.5f, 0.8f)) {
                val f = frame(p)
                save(f, "${name}_${(p * 100).toInt()}")
                // No darkness or flash laid over it: as bright as the picture at rest.
                assertTrue("$name $p luma ${luma(f)} vs ${luma(rest)}", abs(luma(f) - luma(rest)) < 6.0)
            }
            // It does move, and settles exactly on the rest pose.
            assertTrue("$name moves ${diff(frame(0f), rest)}", diff(frame(0f), rest) > 2.0)
            assertTrue(diff(frame(1f), rest) < 0.5)
            // Opening, the front glides in from the right (it starts shifted right); closing,
            // from the left.
            val dx = shift(rest, frame(0.05f))
            if (kind == ScreenKind.MAIN) assertTrue("main shift $dx", dx > 0) else assertTrue("cover shift $dx", dx < 0)
            scene.recycle()
        }
        container.packStore.dir(pack.id).deleteRecursively()
    }

    @Test
    fun `a glass wallpaper's drops are its fold motion`() {
        val spec = LiquidSpec.PRESETS.getValue("daybreak")
        val pack = container.liquidBuilder.build(LiquidRequest(spec, FoldStyle.FLOW, seed = 3L), fold7)
        // Glass always plays its own motion, whatever was asked.
        assertEquals("NATURAL", pack.manifest.motionStyle)
        assertEquals(CoreInfo.NATURAL_RENDERER_VERSION, pack.manifest.rendererVersion.min)
        assertTrue(container.validator.validate(pack.dir, fold7.id).passed)
        val cover = SceneLoader.load(pack.dir, ScreenKind.COVER)!!
        val main = SceneLoader.load(pack.dir, ScreenKind.MAIN)!!
        assertEquals(com.foldmotion.wallpapers.render.LiquidField.GLASS_DOWNSCALE, com.foldmotion.wallpapers.render.LiquidField.downscaleFor(spec))
        val drawer = SceneDrawer()
        val w = 300
        val h = (w / fold7.screen(ScreenKind.MAIN).aspect).toInt()
        fun live(p: Float?, t: Long = 5_000) = HwCapture.render(w, h) { drawer.draw(it, w, h, main, p, FrameState(timeMs = t, ambient = true)) }
        val rest = live(null)
        val start = live(0f)
        val mid = live(0.35f)
        save(start, "glass_open_0")
        save(mid, "glass_open_35")
        save(rest, "glass_open_rest")
        assertTrue("drops move ${diff(start, rest)}", diff(start, rest) > 4.0)
        assertTrue(diff(live(1f), rest) < 1.0)
        // Closed, the left part of the main screen is clear: the drops sit on the right.
        val leftStart = Bitmap.createBitmap(start, 0, 0, w / 3, h)
        val leftRest = Bitmap.createBitmap(rest, 0, 0, w / 3, h)
        assertTrue(diff(leftStart, leftRest) > 4.0)
        // Even with living motion off, the drops still play the fold (they are the animation).
        val still = HwCapture.render(w, h) { drawer.draw(it, w, h, main, 0.35f, FrameState(timeMs = 5_000)) }
        assertTrue(diff(still, rest) > 3.0)
        cover.recycle()
        main.recycle()
        container.packStore.dir(pack.id).deleteRecursively()
    }
}
