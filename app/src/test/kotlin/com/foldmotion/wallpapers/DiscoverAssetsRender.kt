package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.pack.VideoGeometry
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.GenerationRequest
import com.foldmotion.core.scene.IdeaBank
import com.foldmotion.wallpapers.generator.LocalPackGenerator
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Asset build step, not a check: renders the folded (cover) and unfolded (main) picture of every
 * curated idea, and of the sample video, into the Discover catalogue the app ships. Runs only
 * with -Dfoldmotion.discoverOut=<assets/discover folder>.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DiscoverAssetsRender {

    private val container = AppContainer.get(ApplicationProvider.getApplicationContext())
    private val fold7 = DeviceProfileRegistry.fold7

    @Before
    fun warmUp() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bmp.byteCount))
        java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
        bitmap.recycle()
    }

    @Test
    fun `render discover catalogue`() {
        val out = System.getProperty("foldmotion.discoverOut")?.let(::File)
        assumeTrue(out != null)
        out!!.mkdirs()
        val drawer = SceneDrawer()
        val entries = ArrayList<String>()
        fun arr(v: List<Float>?) = v?.joinToString(",", "[", "]") ?: "null"
        for ((i, idea) in IdeaBank.ideas.withIndex()) {
            val pack = runBlocking {
                LocalPackGenerator(container.packStore, container.validator)
                    .generate(GenerationRequest("discover-${idea.id}", fold7.id, GenerationMode.RANDOM, ideaId = idea.id, seed = DISCOVER_SEED + i), fold7) {}
                    .pack
            }
            for ((kind, width) in listOf(ScreenKind.COVER to COVER_WIDTH, ScreenKind.MAIN to MAIN_WIDTH)) {
                val screen = fold7.screen(kind)
                val height = (width / screen.aspect).toInt()
                val scene = SceneLoader.load(pack.dir, kind, maxOf(width, height) * 2)!!
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                drawer.draw(Canvas(bmp), width, height, scene, null)
                scene.recycle()
                save(bmp, File(out, "${idea.id}_${if (kind == ScreenKind.MAIN) "main" else "cover"}.webp"))
            }
            val m = pack.manifest
            val mw = m.scenes.main.world!!
            entries += """{"id":"${idea.id}","video":false,"glow":"${m.colors.primary}","accent":"${m.colors.secondary}","background":"${m.colors.background}",""" +
                """"mainWorld":${arr(mw)},"coverWorld":${arr(m.scenes.cover.world)},"focus":${arr(listOf(mw[0] + m.scenes.main.focusX * (mw[2] - mw[0]), mw[1] + m.scenes.main.focusY * (mw[3] - mw[1])))},""" +
                """"seed":${m.generation?.seed ?: 0},"style":"${m.motionStyle}"}"""
            container.packStore.dir(pack.id).deleteRecursively()
        }
        // The sample video: its first frame, framed per screen exactly as the player crops it.
        System.getProperty("foldmotion.videoFrames")?.let(::File)?.listFiles { f -> f.name.endsWith(".png") }?.minByOrNull { it.name }?.let { first ->
            val frame = BitmapFactory.decodeFile(first.path)
            val crops = VideoGeometry.crops(fold7, frame.width, frame.height)
            for ((kind, width) in listOf(ScreenKind.COVER to COVER_WIDTH, ScreenKind.MAIN to MAIN_WIDTH)) {
                val c = if (kind == ScreenKind.MAIN) crops.main else crops.cover
                val height = (width / fold7.screen(kind).aspect).toInt()
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val sx = width / ((c[2] - c[0]) * frame.width)
                val sy = height / ((c[3] - c[1]) * frame.height)
                val shader = BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setTranslate(-c[0] * frame.width, -c[1] * frame.height); postScale(sx, sy) })
                }
                Canvas(bmp).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
                save(bmp, File(out, "liquid_light_${if (kind == ScreenKind.MAIN) "main" else "cover"}.webp"))
            }
            val unit = maxOf(frame.width, frame.height).toFloat()
            fun world(r: List<Float>) = listOf(r[0] * frame.width / unit, r[1] * frame.height / unit, r[2] * frame.width / unit, r[3] * frame.height / unit)
            entries += """{"id":"liquid_light","video":true,"glow":"#9FF5E6","accent":"#8B7CFF","background":"#050B14",""" +
                """"mainWorld":${arr(world(crops.main))},"coverWorld":${arr(world(crops.cover))},"focus":${arr(listOf(0.5f * frame.width / unit, 0.5f * frame.height / unit))},"seed":7,"style":"WARP"}"""
        }
        File(out, "catalog.json").writeText(entries.joinToString(",\n", "[\n", "\n]\n"))
    }

    companion object {
        const val DISCOVER_SEED = 2026_11_13L
        const val MAIN_WIDTH = 760
        const val COVER_WIDTH = 360
    }
}
