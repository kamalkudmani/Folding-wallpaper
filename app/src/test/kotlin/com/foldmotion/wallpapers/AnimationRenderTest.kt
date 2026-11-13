package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.pack.FoldPackValidator
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.GenerationRequest
import com.foldmotion.wallpapers.generator.LocalPackGenerator
import com.foldmotion.wallpapers.render.FrameState
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real fold choreography frame by frame (cover alive, unfold, main alive, fold, cover
 * alive) with the same drawer the engine uses, and writes PNG frames for review as an animation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AnimationRenderTest {

    private val container = AppContainer.get(ApplicationProvider.getApplicationContext())
    private val fold7 = DeviceProfileRegistry.fold7
    private val outRoot = File(System.getProperty("foldmotion.screenshotDir") ?: "build/screenshots", "anim").apply { mkdirs() }

    @Before
    fun warmUp() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bmp.byteCount))
    }

    @Test
    fun `fold sequences render without gaps`() {
        for ((idea, intensity) in listOf("aurora_peaks" to MotionIntensity.LOW, "teal_horizon" to MotionIntensity.MEDIUM, "brand_flow" to MotionIntensity.LOW, "desert_crescent" to MotionIntensity.LOW)) {
            val id = runBlocking {
                LocalPackGenerator(container.packStore, FoldPackValidator())
                    .generate(GenerationRequest("anim-$idea", fold7.id, GenerationMode.RANDOM, ideaId = idea, seed = 7L, motionIntensity = intensity), fold7) {}
                    .pack.id
            }
            val dir = container.packStore.dir(id)
            val cover = SceneLoader.load(dir, ScreenKind.COVER)!!
            val main = SceneLoader.load(dir, ScreenKind.MAIN)!!
            assertTrue(cover.isAlive && main.isAlive)
            val out = File(outRoot, idea).apply { deleteRecursively(); mkdirs() }
            renderSequence(cover, main, out)
            cover.recycle()
            main.recycle()
        }
    }

    private fun renderSequence(cover: LoadedScene, main: LoadedScene, out: File) {
        val fps = 25
        val step = 1000L / fps
        val mainW = 360
        val mainH = (mainW * 2184f / 1968f).toInt()
        val coverW = (mainW * fold7.cover.widthMm!! / fold7.main!!.widthMm!!).toInt()
        val coverH = (coverW * 2520f / 1080f).toInt()
        val sheetW = coverW + mainW + 30
        val sheetH = maxOf(mainH, coverH) + 20
        val drawer = SceneDrawer()
        var clock = 10_000L
        var index = 0
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f }

        fun emit(coverProgress: Float?, mainProgress: Float?, active: ScreenKind, caption: String) {
            val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            c.drawColor(Color.rgb(12, 16, 22))
            val state = FrameState(timeMs = clock, ambient = true)
            val cb = Bitmap.createBitmap(coverW, coverH, Bitmap.Config.ARGB_8888)
            val mb = Bitmap.createBitmap(mainW, mainH, Bitmap.Config.ARGB_8888)
            // Only the screen in use is lit, as on the phone.
            if (active == ScreenKind.COVER) drawer.draw(Canvas(cb), coverW, coverH, cover, coverProgress, state) else cb.eraseColor(Color.rgb(6, 8, 10))
            if (active == ScreenKind.MAIN) drawer.draw(Canvas(mb), mainW, mainH, main, mainProgress, state) else mb.eraseColor(Color.rgb(6, 8, 10))
            c.drawBitmap(cb, 10f, 10f, null)
            c.drawBitmap(mb, coverW + 20f, 10f, null)
            c.drawText(caption, 14f, sheetH - 4f, label)
            File(out, "f%04d.png".format(index++)).outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cb.recycle(); mb.recycle(); sheet.recycle()
            clock += step
        }

        repeat(fps) { emit(null, null, ScreenKind.COVER, "cover · alive") }
        val open = main.entryMotion!!.durationMs
        for (t in 0..open step step.toInt()) emit(null, t / open.toFloat(), ScreenKind.MAIN, "unfold")
        repeat(fps * 2) { emit(null, null, ScreenKind.MAIN, "main · alive") }
        val close = cover.entryMotion!!.durationMs
        for (t in 0..close step step.toInt()) emit(t / close.toFloat(), null, ScreenKind.COVER, "fold")
        repeat(fps) { emit(null, null, ScreenKind.COVER, "cover · alive") }
    }
}
