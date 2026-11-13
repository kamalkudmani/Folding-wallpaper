package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.ChoreoInput
import com.foldmotion.core.motion.ChoreographyComposer
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.motion.MotionLayer
import com.foldmotion.core.pack.LayerEntry
import com.foldmotion.core.pack.VideoGeometry
import com.foldmotion.core.pack.VideoSpec
import com.foldmotion.core.scene.Celestial
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.core.scene.SceneFrames
import com.foldmotion.core.scene.TimeOfDay
import com.foldmotion.core.scene.WorldRect
import com.foldmotion.wallpapers.render.FrameState
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.VideoFrames
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Review render, not a check: plays decoded frames of a clip (extracted beforehand, pass the
 * folder as -Dfoldmotion.videoFrames) through the real renderer with several fold styles, the way
 * the wallpaper shows a video pack. Skipped when no frames are given.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class VideoDemoRenderTest {

    private val fold7 = DeviceProfileRegistry.fold7
    private val o = 0.04f

    @Before
    fun warmUp() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bmp.byteCount))
    }

    /** Crops a decoded frame like VideoFrameSource does: the screen's window plus overscan, edges clamped. */
    private fun window(frame: Bitmap, crop: List<Float>, w: Int, h: Int): Bitmap {
        val cw = crop[2] - crop[0]
        val ch = crop[3] - crop[1]
        val l = (crop[0] - o * cw) * frame.width
        val t = (crop[1] - o * ch) * frame.height
        val sx = w / ((cw * (1 + 2 * o)) * frame.width)
        val sy = h / ((ch * (1 + 2 * o)) * frame.height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setTranslate(-l, -t); postScale(sx, sy) })
        }
        Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        return out
    }

    @Test
    fun `render a video pack with fold styles`() {
        val dir = System.getProperty("foldmotion.videoFrames")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }
        val first = BitmapFactory.decodeFile(files[0].path)
        val vw = first.width
        val vh = first.height
        first.recycle()
        val crops = VideoGeometry.crops(fold7, vw, vh)
        val mainW = 360
        val mainH = (mainW * 2184f / 1968f).toInt()
        val coverW = (mainW * fold7.cover.widthMm!! / fold7.main!!.widthMm!!).toInt()
        val coverH = (coverW * 2520f / 1080f).toInt()

        var frameIndex = 0
        val cache = HashMap<Pair<Int, ScreenKind>, Bitmap>()
        val frames = object : VideoFrames {
            override fun latest(kind: ScreenKind): Bitmap = cache.getOrPut(frameIndex to kind) {
                if (cache.size > 6) cache.values.forEach { it.recycle() }.also { cache.clear() }
                val src = BitmapFactory.decodeFile(files[frameIndex % files.size].path)
                val crop = if (kind == ScreenKind.MAIN) crops.main else crops.cover
                val w = if (kind == ScreenKind.MAIN) 400 else 190
                val h = if (kind == ScreenKind.MAIN) 444 else 444
                window(src, crop, w, h).also { src.recycle() }
            }
            override fun addListener(listener: () -> Unit) = Unit
            override fun removeListener(listener: () -> Unit) = Unit
            override fun setPlaying(play: Boolean) = Unit
            override fun release() = Unit
        }
        val unit = maxOf(vw, vh).toFloat()
        fun world(r: List<Float>) = WorldRect(r[0] * vw / unit, r[1] * vh / unit, r[2] * vw / unit, r[3] * vh / unit)
        val sceneFrames = SceneFrames(world(crops.cover), world(crops.main), 0.5f * vw / unit, 0.5f * vh / unit)
        val layer = listOf(MotionLayer("far", 0f, -o, -o, 1 + o, 1 + o, true))
        val spec = VideoSpec(VideoSpec.FILE, vw, vh, 10_000, 30f, crop = crops, sizeBytes = 1)
        fun scene(kind: ScreenKind, style: FoldStyle): LoadedScene {
            val choreo = ChoreographyComposer.compose(
                ChoreoInput(SceneFamily.ABSTRACT, TimeOfDay.NIGHT, Celestial.NONE, MotionIntensity.LOW, sceneFrames, layer, layer, 0xFF9FF5E6.toInt(), 0xFF8B7CFF.toInt(), 9L),
                style,
            )
            val poster = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            val entry = LayerEntry("far", "p.webp", 8, 8, listOf(-o, -o, 1 + o, 1 + o), 0f, true)
            val screen = fold7.screen(kind)
            return LoadedScene("demo", kind, screen.widthPx, screen.heightPx, 0.5f, 0.5f, listOf(entry to poster), if (kind == ScreenKind.MAIN) choreo.open else choreo.close, Color.BLACK, 0xFF9FF5E6.toInt(), video = spec)
                .also { it.videoFrames = frames }
        }

        val out = File(System.getProperty("foldmotion.screenshotDir") ?: "build/screenshots", "video-demo").apply { deleteRecursively(); mkdirs() }
        val drawer = SceneDrawer()
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f }
        val sheetW = coverW + mainW + 30
        val sheetH = maxOf(mainH, coverH) + 24
        var index = 0
        fun emit(cover: LoadedScene?, coverP: Float?, main: LoadedScene?, mainP: Float?, caption: String) {
            val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            c.drawColor(Color.rgb(12, 16, 22))
            val cb = Bitmap.createBitmap(coverW, coverH, Bitmap.Config.ARGB_8888)
            val mb = Bitmap.createBitmap(mainW, mainH, Bitmap.Config.ARGB_8888)
            if (cover != null) drawer.draw(Canvas(cb), coverW, coverH, cover, coverP, FrameState.STILL) else cb.eraseColor(Color.rgb(6, 8, 10))
            if (main != null) drawer.draw(Canvas(mb), mainW, mainH, main, mainP, FrameState.STILL) else mb.eraseColor(Color.rgb(6, 8, 10))
            c.drawBitmap(cb, 10f, 10f, null)
            c.drawBitmap(mb, coverW + 20f, 10f, null)
            c.drawText(caption, 14f, sheetH - 6f, label)
            File(out, "f%04d.png".format(index++)).outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cb.recycle(); mb.recycle(); sheet.recycle()
            frameIndex++
        }
        for (style in listOf(FoldStyle.RISE, FoldStyle.WARP, FoldStyle.BOOK, FoldStyle.TILES)) {
            val name = style.name.lowercase().replaceFirstChar { it.uppercase() }
            val cover = scene(ScreenKind.COVER, style)
            val main = scene(ScreenKind.MAIN, style)
            repeat(20) { emit(cover, null, null, null, "Video · closed") }
            val open = main.entryMotion!!.durationMs
            for (t in 0..open step 33) emit(null, null, main, t / open.toFloat(), "Video + $name · unfold")
            repeat(36) { emit(null, null, main, null, "Video · open") }
            val close = cover.entryMotion!!.durationMs
            for (t in 0..close step 33) emit(cover, t / close.toFloat(), null, null, "Video + $name · fold")
        }
    }
}
