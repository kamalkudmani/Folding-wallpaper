package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.motion.MotionSpec
import com.foldmotion.core.pack.LayerEntry
import com.foldmotion.core.pack.VideoCrops
import com.foldmotion.core.pack.VideoSpec
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.VideoFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A video scene draws the live frame in place of its poster, with or without a fold animation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class VideoSceneTest {

    @Before
    fun warmUp() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bmp.byteCount))
    }

    private class FakeFrames(var frame: Bitmap?) : VideoFrames {
        var lastPlay = false
        override fun latest(kind: ScreenKind) = frame
        override fun addListener(listener: () -> Unit) = Unit
        override fun removeListener(listener: () -> Unit) = Unit
        override fun setPlaying(play: Boolean) {
            lastPlay = play
        }
        override fun release() = Unit
    }

    private fun solid(color: Int) = Bitmap.createBitmap(120, 132, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun scene(motion: MotionSpec?): LoadedScene {
        val poster = LayerEntry("far", "layers/main_far.webp", 120, 132, listOf(-0.04f, -0.04f, 1.04f, 1.04f), 0f, opaque = true)
        val spec = VideoSpec(VideoSpec.FILE, 1920, 1080, 8_000, 30f, crop = VideoCrops(listOf(0.3f, 0f, 0.55f, 1f), listOf(0.25f, 0f, 0.75f, 1f)), sizeBytes = 1)
        return LoadedScene(
            "video-test", ScreenKind.MAIN, 1968, 2184, 0.5f, 0.4f,
            listOf(poster to solid(Color.RED)), motion, Color.BLACK, Color.WHITE,
            video = spec,
        )
    }

    private fun render(scene: LoadedScene, progress: Float?): Bitmap {
        val out = Bitmap.createBitmap(180, 200, Bitmap.Config.ARGB_8888)
        SceneDrawer().draw(Canvas(out), out.width, out.height, scene, progress)
        return out
    }

    @Test
    fun `the poster shows until the first frame, then the live frame replaces it`() {
        val s = scene(null)
        assertEquals(Color.RED, render(s, null).getPixel(90, 100))
        s.videoFrames = FakeFrames(solid(Color.GREEN))
        assertEquals(Color.GREEN, render(s, null).getPixel(90, 100))
    }

    @Test
    fun `fold animations run on the live frame for every style`() {
        val green = solid(Color.GREEN)
        for (style in FoldStyle.CONCRETE) {
            val inp = com.foldmotion.core.motion.ChoreoInput(
                com.foldmotion.core.scene.SceneFamily.ABSTRACT, com.foldmotion.core.scene.TimeOfDay.NIGHT,
                com.foldmotion.core.scene.Celestial.NONE, com.foldmotion.core.motion.MotionIntensity.LOW,
                com.foldmotion.core.scene.SceneGeometry.frames(com.foldmotion.core.device.DeviceProfileRegistry.fold7, 0f, 0.4f),
                listOf(com.foldmotion.core.motion.MotionLayer("far", 0f, -0.04f, -0.04f, 1.04f, 1.04f, true)),
                listOf(com.foldmotion.core.motion.MotionLayer("far", 0f, -0.04f, -0.04f, 1.04f, 1.04f, true)),
                0xFFFFFFFF.toInt(), 0xFF19B5B0.toInt(), 3L,
            )
            val s = scene(com.foldmotion.core.motion.ChoreographyComposer.compose(inp, style).open)
            s.videoFrames = FakeFrames(green)
            // At the end of every style the frame is fully the video, never the red poster.
            val end = render(s, 1f)
            assertEquals("$style end", Color.GREEN, end.getPixel(90, 100))
            // Midway some of the picture is already video, none of it is the poster.
            val mid = render(s, 0.7f)
            var green = 0
            var red = 0
            for (y in 0 until mid.height step 10) for (x in 0 until mid.width step 10) {
                val c = mid.getPixel(x, y)
                if (Color.green(c) > 120 && Color.red(c) < 90) green++
                if (Color.red(c) > 150 && Color.green(c) < 60) red++
            }
            assertTrue("$style mid: green=$green", green > 0)
            assertEquals("$style mid shows the poster", 0, red)
        }
    }
}
