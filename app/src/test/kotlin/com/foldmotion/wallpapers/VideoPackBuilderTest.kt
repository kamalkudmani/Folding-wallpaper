package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.pack.FoldPackManifest
import com.foldmotion.wallpapers.generator.video.ClipInfo
import com.foldmotion.wallpapers.generator.video.VideoPackBuilder
import com.foldmotion.wallpapers.generator.video.VideoPackException
import com.foldmotion.wallpapers.generator.video.VideoRequest
import com.foldmotion.wallpapers.generator.video.VideoStage
import com.foldmotion.wallpapers.generator.video.VideoTools
import com.foldmotion.wallpapers.pack.RestyleResult
import com.foldmotion.wallpapers.render.SceneLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Builds wallpapers from a clip with stand-in video tools (no real decoder in unit tests). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class VideoPackBuilderTest {

    private val container = AppContainer.get(ApplicationProvider.getApplicationContext())
    private val fold7 = DeviceProfileRegistry.fold7

    @Before
    fun warmUp() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bmp.byteCount))
        java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
    }

    private class FakeTools(val info: ClipInfo) : VideoTools {
        val stages = ArrayList<Float>()
        override fun probe(uri: Uri) = info
        override suspend fun transcode(uri: Uri, startMs: Long, endMs: Long, maxLongSide: Int, maxFrameRate: Float, out: File, onProgress: (Float) -> Unit) {
            out.parentFile?.mkdirs()
            out.writeBytes(ByteArray(4096).also { b -> b[3] = 24; "ftypisom".toByteArray().copyInto(b, 4) })
            onProgress(0.5f)
            onProgress(1f)
        }
        override fun frameAt(file: File, timeMs: Long, maxLongSide: Int): Bitmap {
            val scale = minOf(1f, maxLongSide / maxOf(info.width, info.height).toFloat())
            val w = (info.width * scale).toInt()
            val h = (info.height * scale).toInt()
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                    shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF0B1E3A.toInt(), 0xFF2DE1C2.toInt(), Shader.TileMode.CLAMP)
                })
            }
        }
    }

    private fun build(info: ClipInfo, style: FoldStyle = FoldStyle.AUTO, start: Long = 0, end: Long = 6_000): Pair<com.foldmotion.wallpapers.pack.StoredPack, List<VideoStage>> {
        val tools = FakeTools(info)
        val seen = ArrayList<VideoStage>()
        val pack = runBlocking {
            VideoPackBuilder(container.packStore, container.validator, tools)
                .build(VideoRequest(Uri.parse("content://test/clip.mp4"), start, end, style = style, seed = 11L), fold7) { s, _ -> if (seen.lastOrNull() != s) seen += s }
        }
        return pack to seen
    }

    @Test
    fun `a landscape clip becomes a valid video wallpaper for both screens`() {
        val (pack, stages) = build(ClipInfo(1920, 1080, 6_000, 30f), FoldStyle.SWEEP)
        assertEquals(VideoStage.entries, stages)
        val m = pack.manifest
        assertEquals(FoldPackManifest.SOURCE_VIDEO, m.creationSource)
        assertEquals("SWEEP", m.motionStyle)
        assertEquals(CoreInfo.VIDEO_RENDERER_VERSION, m.rendererVersion.min)
        val v = assertNotNull(m.video).let { m.video!! }
        assertEquals(6_000, v.durationMs)
        assertTrue(v.loopCrossfadeMs in 1..VideoPackBuilderLimits.MAX_XF)
        assertTrue(container.validator.validate(pack.dir, fold7.id).issues.toString(), container.validator.validate(pack.dir, fold7.id).passed)
        for (kind in ScreenKind.entries) {
            val scene = SceneLoader.load(pack.dir, kind)!!
            assertEquals(v, scene.video)
            assertTrue(scene.videoFile!!.isFile)
            assertEquals(null, scene.ambient)
            assertEquals("SWEEP", scene.entryMotion!!.style)
            // The poster is a single opaque back layer with overscan, decoded small.
            assertEquals(1, scene.layers.size)
            assertTrue(scene.layers[0].first.opaque)
            assertTrue(maxOf(scene.layers[0].second.width, scene.layers[0].second.height) <= 1400)
            scene.recycle()
        }
    }

    @Test
    fun `portrait and square clips work, and the style can be changed later`() {
        for (info in listOf(ClipInfo(1080, 1920, 9_000, 60f), ClipInfo(1440, 1440, 4_000, 24f))) {
            val (pack, _) = build(info, end = info.durationMs)
            val result = container.packStore.restyle(pack.id, FoldStyle.TILES, container.validator, fold7.id)
            assertTrue("$result", result is RestyleResult.Restyled)
            val after = (result as RestyleResult.Restyled).pack.manifest
            assertEquals("TILES", after.motionStyle)
            assertEquals(CoreInfo.VIDEO_RENDERER_VERSION, after.rendererVersion.min)
            assertEquals(pack.manifest.video, after.video)
        }
    }

    @Test
    fun `a clip shorter than a second is refused clearly`() {
        try {
            build(ClipInfo(1920, 1080, 6_000, 30f), start = 0, end = 400)
            fail("expected TOO_SHORT")
        } catch (e: VideoPackException) {
            assertEquals(VideoPackException.Code.TOO_SHORT, e.code)
        }
    }

    private object VideoPackBuilderLimits {
        const val MAX_XF = 2_000
    }
}
