package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.pack.FoldPackManifest
import com.foldmotion.wallpapers.generator.depth.DeepLabMasker
import com.foldmotion.wallpapers.generator.depth.DepthPackBuilder
import com.foldmotion.wallpapers.generator.depth.PhotoRequest
import com.foldmotion.wallpapers.generator.depth.SubjectMasker
import com.foldmotion.wallpapers.render.FrameState
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * 3D photos: the subject becomes a front layer over a background whose hole is filled, the pack
 * validates and plays as an ordinary drawn pack, the subject moves against the background with
 * tilt, and a photo without a subject still makes a (flat) wallpaper. The masking model itself
 * needs the phone's native runtime; its post-processing is tested here on synthetic logits.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DepthPackTest {

    private val container = AppContainer.get(ApplicationProvider.getApplicationContext())
    private val fold7 = DeviceProfileRegistry.fold7
    private val out = File(System.getProperty("foldmotion.screenshotDir") ?: "build/screenshots", "depth").apply { mkdirs() }

    /** A blue-to-teal "sky" with a red disc (the subject) right of centre. */
    private fun photo(): Bitmap = Bitmap.createBitmap(1200, 1500, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        c.drawRect(0f, 0f, 1200f, 1500f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, 1500f, Color.rgb(30, 60, 160), Color.rgb(20, 170, 150), Shader.TileMode.CLAMP) })
        c.drawCircle(700f, 760f, 260f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 40, 40) })
    }

    private val discMasker = SubjectMasker { p ->
        Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ALPHA_8).also { m ->
            Canvas(m).drawCircle(700f, 760f, 260f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        }
    }

    private fun redness(b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in y0 until y1 step 4) for (x in x0 until x1 step 4) {
            val p = b.getPixel(x, y)
            sum += Color.red(p) - (Color.green(p) + Color.blue(p)) / 2.0
            n++
        }
        return sum / n
    }

    @Test
    fun `a photo becomes a subject layer over a filled background`() {
        val p = photo()
        val result = container.depthBuilder.build(p, PhotoRequest(seed = 3L), fold7, discMasker)
        assertTrue(result.hasSubject)
        val m = result.pack.manifest
        assertEquals(FoldPackManifest.SOURCE_PHOTO, m.creationSource)
        assertEquals("RISE", m.motionStyle)
        assertEquals(CoreInfo.STYLES_RENDERER_VERSION, m.rendererVersion.min)
        assertTrue(container.validator.validate(result.pack.dir, fold7.id).passed)
        assertEquals(listOf("far", "near"), m.scenes.main.layers.map { it.id })
        assertTrue(m.scenes.main.layers[1].depth == 1f && !m.scenes.main.layers[1].opaque)
        // The subject sits at the focus of the main screen.
        assertEquals(0.5f, m.scenes.main.focus[1], 0.12f)

        // The background has no red disc left behind the subject; the subject layer is the disc only.
        val far = android.graphics.BitmapFactory.decodeFile(File(result.pack.dir, m.scenes.main.layers[0].path).path)
        val near = android.graphics.BitmapFactory.decodeFile(File(result.pack.dir, m.scenes.main.layers[1].path).path)
        File(out, "far.png").outputStream().use { far.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(out, "near.png").outputStream().use { near.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val cx = (far.width * 0.5f).toInt()
        val cy = (far.height * 0.5f).toInt()
        assertTrue("hole filled ${redness(far, cx - 40, cy - 40, cx + 40, cy + 40)}", redness(far, cx - 40, cy - 40, cx + 40, cy + 40) < 40.0)
        assertTrue(Color.alpha(near.getPixel(cx, cy)) > 240)
        assertTrue(Color.alpha(near.getPixel(4, 4)) < 10)

        // Tilt moves the subject against the background.
        val main = SceneLoader.load(result.pack.dir, ScreenKind.MAIN)!!
        assertTrue(main.isAlive)
        val w = 300
        val h = (w / fold7.screen(ScreenKind.MAIN).aspect).toInt()
        val drawer = SceneDrawer()
        fun frame(tilt: Float) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            drawer.draw(Canvas(it), w, h, main, null, FrameState(timeMs = 0, ambient = true, tiltX = tilt))
        }
        val left = frame(-1f)
        val right = frame(1f)
        File(out, "tilt_left.png").outputStream().use { left.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var moved = 0
        for (x in 0 until w step 2) {
            val a = left.getPixel(x, h / 2)
            val b = right.getPixel(x, h / 2)
            if (abs(Color.red(a) - Color.red(b)) > 60) moved++
        }
        assertTrue("subject edge moved ($moved columns)", moved > 4)
        // ...while the far corners (background) stay put.
        assertEquals(left.getPixel(2, 2), right.getPixel(2, 2))
        main.recycle()
        container.packStore.dir(result.pack.id).deleteRecursively()
    }

    @Test
    fun `without a subject it is a flat photo wallpaper`() {
        val result = container.depthBuilder.build(photo(), PhotoRequest(style = FoldStyle.SWEEP, seed = 4L), fold7) { null }
        assertTrue(!result.hasSubject)
        assertEquals(listOf("far"), result.pack.manifest.scenes.main.layers.map { it.id })
        assertEquals("SWEEP", result.pack.manifest.motionStyle)
        assertTrue(container.validator.validate(result.pack.dir, fold7.id).passed)
        container.packStore.dir(result.pack.id).deleteRecursively()
    }

    @Test
    fun `the model's output becomes a soft mask of subject classes only`() {
        val w = 10
        val h = 10
        val c = DeepLabMasker.CLASSES
        fun logits(cls: (Int, Int) -> Int) = FloatArray(w * h * c).also { l ->
            for (y in 0 until h) for (x in 0 until w) l[(y * w + x) * c + cls(x, y)] = 8f
        }
        // A person (15) in the middle, a sofa (18) at the bottom: only the person is the subject.
        val prob = DeepLabMasker.subjectProbability(logits { x, y -> if (y >= 8) 18 else if (x in 3..6 && y in 2..7) 15 else 0 }, w, h)
        assertNotNull(prob)
        assertTrue(prob!![4 * w + 4] > 0.9f)
        assertTrue(prob[9 * w + 4] < 0.1f)
        assertTrue(prob[0] < 0.1f)
        // Too small a subject: none.
        assertNull(DeepLabMasker.subjectProbability(logits { x, y -> if (x == 5 && y == 5) 15 else 0 }, w, h))
        val mask = DeepLabMasker.toMask(prob, w, h, 100, 100)
        assertEquals(Bitmap.Config.ALPHA_8, mask.config)
        assertTrue(Color.alpha(mask.getPixel(45, 45)) > 200)
        assertTrue(Color.alpha(mask.getPixel(5, 5)) < 30)
    }
}
