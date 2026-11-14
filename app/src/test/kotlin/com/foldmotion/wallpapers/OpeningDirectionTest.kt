package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Color
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.ChoreoInput
import com.foldmotion.core.motion.ChoreographyComposer
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.motion.MotionLayer
import com.foldmotion.core.pack.LayerEntry
import com.foldmotion.core.scene.Celestial
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.core.scene.SceneGeometry
import com.foldmotion.core.scene.TimeOfDay
import com.foldmotion.wallpapers.render.FrameState
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.SceneDrawer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On the Fold the front panel swings from right to left as the phone opens, so every fold style
 * that has a direction must uncover the main screen from the right side first, and the cover
 * from the left side first as the panel swings back. Drawn on the hardware pipeline, as on the
 * phone, so the liquid styles run their shaders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OpeningDirectionTest {

    private val fold7 = DeviceProfileRegistry.fold7

    private fun scene(kind: ScreenKind, style: FoldStyle): LoadedScene {
        val frames = SceneGeometry.frames(fold7, SceneGeometry.defaultFocusX(fold7, rightHalf = true), 0.5f)
        val layer = listOf(MotionLayer("far", 0f, -0.04f, -0.04f, 1.04f, 1.04f, opaque = true))
        val input = ChoreoInput(SceneFamily.ABSTRACT, TimeOfDay.DAY, Celestial.NONE, MotionIntensity.LOW, frames, layer, layer, Color.WHITE, Color.CYAN, seed = 1L)
        val choreo = ChoreographyComposer.compose(input, style)
        val screen = fold7.screen(kind)
        val flat = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 200, 200)) }
        return LoadedScene(
            "direction", kind, screen.widthPx, screen.heightPx, 0.5f, 0.5f,
            listOf(LayerEntry("far", "flat", 8, 8, listOf(-0.04f, -0.04f, 1.04f, 1.04f), 0f, opaque = true) to flat),
            if (kind == ScreenKind.MAIN) choreo.open else choreo.close,
            Color.BLACK, Color.WHITE, world = frames.frame(kind),
        )
    }

    private fun luma(b: Bitmap, x0: Int, x1: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in b.height / 5 until b.height * 4 / 5 step 4) for (x in x0 until x1 step 3) {
            val p = b.getPixel(x, y)
            sum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
            n++
        }
        return sum / n
    }

    /** Over the reveal, how many frames show the right side clearly ahead, and the left side. */
    private fun lead(kind: ScreenKind, style: FoldStyle): Pair<Int, Int> {
        val s = scene(kind, style)
        val w = if (kind == ScreenKind.MAIN) 220 else 110
        val h = (w / fold7.screen(kind).aspect).toInt()
        val drawer = SceneDrawer()
        var right = 0
        var left = 0
        for (i in 1..14) {
            val p = i * 0.05f
            val b = HwCapture.render(w, h) { drawer.draw(it, w, h, s, p, FrameState(timeMs = 1_000, ambient = false)) }
            val l = luma(b, 0, w / 3)
            val r = luma(b, w * 2 / 3, w)
            if (r > l * 1.25 + 4) right++
            if (l > r * 1.25 + 4) left++
            b.recycle()
        }
        return right to left
    }

    @Test
    fun `opening the phone uncovers the main screen from the right`() {
        for (style in listOf(FoldStyle.UNFOLD, FoldStyle.BOOK, FoldStyle.TILES, FoldStyle.SWEEP, FoldStyle.FLOW)) {
            val (right, left) = lead(ScreenKind.MAIN, style)
            assertTrue("$style opens right to left (right ahead in $right frames, left in $left)", right >= 2 && left == 0)
        }
    }

    @Test
    fun `folding uncovers the cover from the left`() {
        for (style in listOf(FoldStyle.BOOK, FoldStyle.SWEEP, FoldStyle.FLOW)) {
            val (right, left) = lead(ScreenKind.COVER, style)
            assertTrue("$style folds left to right (left ahead in $left frames, right in $right)", left >= 2 && right == 0)
        }
    }

    @Test
    fun `a wallpaper made before the fix opens right to left once loaded`() {
        val container = AppContainer.get(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val pack = kotlinx.coroutines.runBlocking {
            com.foldmotion.wallpapers.generator.LocalPackGenerator(container.packStore, container.validator)
                .generate(com.foldmotion.core.scene.GenerationRequest("direction-old", fold7.id, com.foldmotion.core.scene.GenerationMode.RANDOM, ideaId = "aurora_peaks", seed = 3L, motionStyle = FoldStyle.SWEEP), fold7) {}
                .pack
        }
        // Rewrite its opening as 0.4-0.8 wrote it: schema 3, band travelling left to right.
        val file = java.io.File(pack.dir, pack.manifest.transitions.open.file)
        val json = com.foldmotion.core.CoreInfo.json
        val old = json.decodeFromString(com.foldmotion.core.motion.MotionSpec.serializer(), file.readText())
        file.writeText(json.encodeToString(com.foldmotion.core.motion.MotionSpec.serializer(), old.copy(schemaVersion = 3, reveal = old.reveal!!.copy(from = listOf(22f)))))
        val loaded = com.foldmotion.wallpapers.render.SceneLoader.load(pack.dir, ScreenKind.MAIN)!!
        val motion = loaded.entryMotion!!
        assertTrue("schema ${motion.schemaVersion}", motion.schemaVersion == ChoreographyComposer.MOTION_SCHEMA)
        assertTrue("angle ${motion.reveal?.from}", motion.reveal?.from == listOf(158f))
        assertTrue(motion.style == "SWEEP")
        loaded.recycle()
        container.packStore.dir(pack.id).deleteRecursively()
    }

    @Test
    fun `scenes put the cover over the right half, where the closed phone lies`() {
        val main = SceneGeometry.frames(fold7, SceneGeometry.defaultFocusX(fold7, rightHalf = true), 0.5f)
        assertTrue(main.cover.left > main.main.left + main.main.width * 0.4f)
        val window = ChoreographyComposer.openingWindow(main)
        assertTrue(window[2] == 1f && window[0] > 0.4f)
    }
}
