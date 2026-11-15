package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The phone turned sideways: the main screen's surface becomes landscape. Every kind of
 * wallpaper must still fill it edge to edge (cropped, never letterboxed), at rest and while
 * its fold motion plays.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RotationTest {

    private val container = AppContainer.get(ApplicationProvider.getApplicationContext())
    private val fold7 = DeviceProfileRegistry.fold7
    private val out = File(System.getProperty("foldmotion.screenshotDir") ?: "build/screenshots", "rotation").apply { mkdirs() }

    /** Fraction of the outer border that is exactly the scene's flat background colour. */
    private fun bareEdge(b: Bitmap, background: Int): Float {
        var bare = 0
        var n = 0
        val bg = background or 0xFF000000.toInt()
        fun check(x: Int, y: Int) {
            n++
            if (b.getPixel(x, y) == bg) bare++
        }
        for (x in 0 until b.width step 2) { check(x, 1); check(x, b.height - 2) }
        for (y in 0 until b.height step 2) { check(1, y); check(b.width - 2, y) }
        return bare / n.toFloat()
    }

    @Test
    fun `every kind of wallpaper fills the main screen turned sideways`() {
        val drawn = runBlocking {
            LocalPackGenerator(container.packStore, container.validator)
                .generate(GenerationRequest("rot", fold7.id, GenerationMode.RANDOM, ideaId = "aurora_peaks", seed = 3L, motionStyle = FoldStyle.NATURAL), fold7) {}
                .pack
        }
        val glass = container.liquidBuilder.build(LiquidRequest(LiquidSpec.PRESETS.getValue("daybreak"), seed = 2L), fold7)
        val scene = container.liquidBuilder.build(LiquidRequest(LiquidSpec.SCENES.getValue("ocean_sunset"), seed = 2L), fold7)
        val drawer = SceneDrawer()
        val problems = ArrayList<String>()
        for ((name, pack) in listOf("drawn" to drawn, "glass" to glass, "scene" to scene)) {
            val main = SceneLoader.load(pack.dir, ScreenKind.MAIN)!!
            for ((w, h) in listOf(492 to 546, 546 to 492)) {
                for (p in listOf(null, 0f, 0.4f)) {
                    val bmp = HwCapture.render(w, h) { drawer.draw(it, w, h, main, p, FrameState(timeMs = 4_000, ambient = true)) }
                    val tag = "${name}_${if (w > h) "land" else "port"}_${p?.let { (it * 100).toInt() } ?: "rest"}"
                    File(out, "$tag.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val bare = bareEdge(bmp, main.background)
                    if (bare > 0.02f) problems += "$tag bare edge ${"%.2f".format(bare)}"
                }
            }
            main.recycle()
            container.packStore.dir(pack.id).deleteRecursively()
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
