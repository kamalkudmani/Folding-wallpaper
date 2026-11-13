package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.pack.FoldPackValidator
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.GenerationRequest
import com.foldmotion.wallpapers.generator.LocalPackGenerator
import com.foldmotion.wallpapers.pack.RestyleResult
import com.foldmotion.wallpapers.render.FrameState
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Restyles one pack into every fold style through the real store (staging, validation, swap)
 * and renders each style's unfold and fold frame by frame for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class StyleRenderTest {

    private val container = AppContainer.get(ApplicationProvider.getApplicationContext())
    private val fold7 = DeviceProfileRegistry.fold7
    private val outRoot = File(System.getProperty("foldmotion.screenshotDir") ?: "build/screenshots", "styles").apply { mkdirs() }

    @Before
    fun warmUp() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bmp.byteCount))
    }

    @Test
    fun `every style restyles a stored pack and renders both screens`() {
        val idea = System.getProperty("foldmotion.styleIdea") ?: "aurora_peaks"
        val id = runBlocking {
            LocalPackGenerator(container.packStore, FoldPackValidator())
                .generate(GenerationRequest("styles-$idea", fold7.id, GenerationMode.RANDOM, ideaId = idea, seed = 7L), fold7) {}
                .pack.id
        }
        var version = container.packStore.get(id)!!.manifest.packageVersion
        for (style in FoldStyle.CONCRETE) {
            val result = container.packStore.restyle(id, style, container.validator, fold7.id)
            assertTrue("$style: $result", result is RestyleResult.Restyled)
            val pack = (result as RestyleResult.Restyled).pack
            assertEquals(style.name, pack.manifest.motionStyle)
            assertEquals(version + 1, pack.manifest.packageVersion)
            version = pack.manifest.packageVersion
            assertTrue(container.validator.validate(pack.dir, fold7.id).passed)

            val cover = SceneLoader.load(pack.dir, ScreenKind.COVER)!!
            val main = SceneLoader.load(pack.dir, ScreenKind.MAIN)!!
            assertEquals(style.name, main.entryMotion!!.style)
            assertEquals(style.name, cover.entryMotion!!.style)
            val out = File(outRoot, style.name.lowercase()).apply { deleteRecursively(); mkdirs() }
            render(cover, main, out, style)
            cover.recycle()
            main.recycle()
        }
        // The store keeps exactly one copy, with no leftovers of the swaps.
        assertEquals(1, container.packStore.list().count { it.id == id })
    }

    private fun render(cover: LoadedScene, main: LoadedScene, out: File, style: FoldStyle) {
        val fps = 25
        val step = 1000 / fps
        val mainW = 360
        val mainH = (mainW * 2184f / 1968f).toInt()
        val coverW = (mainW * fold7.cover.widthMm!! / fold7.main!!.widthMm!!).toInt()
        val coverH = (coverW * 2520f / 1080f).toInt()
        val sheetW = coverW + mainW + 30
        val sheetH = maxOf(mainH, coverH) + 24
        val drawer = SceneDrawer()
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f }
        var clock = 10_000L
        var index = 0
        val captions = StringBuilder()

        fun emit(coverProgress: Float?, mainProgress: Float?, active: ScreenKind, caption: String) {
            val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            c.drawColor(Color.rgb(12, 16, 22))
            val state = FrameState(timeMs = clock, ambient = true)
            // Liquid styles run a GPU shader: draw them through the hardware pipeline, as the phone does.
            fun screen(w: Int, h: Int, scene: LoadedScene, progress: Float?, on: Boolean): Bitmap = when {
                !on -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(6, 8, 10)) }
                style.isLiquid -> HwCapture.render(w, h) { drawer.draw(it, w, h, scene, progress, state) }
                else -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawer.draw(Canvas(it), w, h, scene, progress, state) }
            }
            val cb = screen(coverW, coverH, cover, coverProgress, active == ScreenKind.COVER)
            val mb = screen(mainW, mainH, main, mainProgress, active == ScreenKind.MAIN)
            c.drawBitmap(cb, 10f, 10f, null)
            c.drawBitmap(mb, coverW + 20f, 10f, null)
            c.drawText(caption, 14f, sheetH - 6f, label)
            captions.append(caption).append('\n')
            File(out, "f%04d.png".format(index++)).outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cb.recycle(); mb.recycle(); sheet.recycle()
            clock += step
        }

        val name = style.name.lowercase().replaceFirstChar { it.uppercase() }
        repeat(fps / 2) { emit(null, null, ScreenKind.COVER, "$name · closed") }
        val open = main.entryMotion!!.durationMs
        for (t in 0..open step step) emit(null, t / open.toFloat(), ScreenKind.MAIN, "$name · unfold")
        repeat(fps) { emit(null, null, ScreenKind.MAIN, "$name · open") }
        val close = cover.entryMotion!!.durationMs
        for (t in 0..close step step) emit(t / close.toFloat(), null, ScreenKind.COVER, "$name · fold")
        repeat(fps / 2) { emit(null, null, ScreenKind.COVER, "$name · closed") }
        File(out, "captions.txt").writeText(captions.toString())
    }
}
