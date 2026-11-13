package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.pack.FoldPackManifest
import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.wallpapers.generator.liquid.LiquidRequest
import com.foldmotion.wallpapers.pack.RestyleResult
import com.foldmotion.wallpapers.render.FrameState
import com.foldmotion.wallpapers.render.LiquidMotion
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * A live liquid pack end to end: built on the phone, validated, stored, loaded, drawn live on the
 * hardware pipeline (flowing, with the fold animation on top) and as its poster elsewhere, and
 * restyled like any pack.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LiquidPackTest {

    private val container = AppContainer.get(ApplicationProvider.getApplicationContext())
    private val fold7 = DeviceProfileRegistry.fold7

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

    @Test
    fun `a liquid pack is built, validated, drawn live and as a poster, and restyled`() {
        LiquidMotion.reset()
        val spec = LiquidSpec.PRESETS.getValue("emerald")
        val pack = container.liquidBuilder.build(LiquidRequest(spec, FoldStyle.GLASS, seed = 5L), fold7)
        val m = pack.manifest
        assertEquals(FoldPackManifest.SOURCE_LIQUID, m.creationSource)
        assertEquals(spec, m.liquid)
        assertEquals(CoreInfo.LIVE_LIQUID_RENDERER_VERSION, m.rendererVersion.min)
        assertEquals("GLASS", m.motionStyle)
        assertTrue(container.validator.validate(pack.dir, fold7.id).passed)
        assertNotNull(m.scenes.main.world)

        val main = SceneLoader.load(pack.dir, ScreenKind.MAIN)!!
        assertEquals(spec, main.liquid)
        assertTrue(main.isAlive)
        val w = 300
        val h = (w / fold7.screen(ScreenKind.MAIN).aspect).toInt()
        val drawer = SceneDrawer()
        fun live(t: Long, progress: Float? = null) = HwCapture.render(w, h) { drawer.draw(it, w, h, main, progress, FrameState(timeMs = t, ambient = true)) }
        val a = live(10_000)
        val b = live(12_000)
        assertTrue("live liquid flows ${diff(a, b)}", diff(a, b) > 3.0)
        // The fold animation plays over the live liquid: the start of the reveal is darker.
        val opening = live(10_000, 0.05f)
        assertTrue("reveal over liquid ${diff(a, opening)}", diff(a, opening) > 10.0)
        // Still (motion off) on the GPU, and any software canvas: the poster.
        val still = HwCapture.render(w, h) { drawer.draw(it, w, h, main, null, FrameState.STILL) }
        val soft = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawer.draw(Canvas(it), w, h, main, null, FrameState(timeMs = 10_000, ambient = true)) }
        assertTrue("poster matches the GPU still ${diff(still, soft)}", diff(still, soft) < 4.0)
        // The poster is the liquid at time 0.
        val zero = live(0)
        assertTrue("poster is time 0 ${diff(zero, still)}", diff(zero, still) < 10.0)
        main.recycle()

        val restyled = container.packStore.restyle(pack.id, FoldStyle.RIPPLE, container.validator, fold7.id)
        assertTrue("$restyled", restyled is RestyleResult.Restyled)
        val after = (restyled as RestyleResult.Restyled).pack.manifest
        assertEquals("RIPPLE", after.motionStyle)
        assertEquals(spec, after.liquid)
        assertEquals(CoreInfo.LIVE_LIQUID_RENDERER_VERSION, after.rendererVersion.min)
        container.packStore.dir(pack.id).deleteRecursively()
    }
}
