package com.foldmotion.wallpapers.render.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.pack.VideoSpec
import com.foldmotion.core.video.LoopSeam
import com.foldmotion.wallpapers.render.VideoFrames
import com.foldmotion.wallpapers.telemetry.PocTrace
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Plays a pack's video and hands out ready-to-draw frames for each screen.
 *
 * The clip is decoded by hardware (ExoPlayer) into GPU textures. For every decoded frame a tiny
 * shader crops it to each screen's window, with overscan, into an offscreen buffer, and the buffer
 * is wrapped as a hardware Bitmap. SceneDrawer draws that bitmap as the scene's back layer, so
 * the fold styles, light and particles work on video exactly as on drawn scenes, and the wallpaper
 * surface keeps a single drawing path (no OpenGL on it).
 *
 * Two players take turns at the loop seam and cross-fade ([LoopSeam]), so any clip loops without
 * a jump. Everything runs on one background thread; frames are published on the main thread.
 * If anything fails (no decoder, no EGL), it simply produces no frames and the poster is shown.
 */
class VideoFrameSource(
    context: Context,
    private val file: File,
    private val spec: VideoSpec,
    /** Overscan of the scene's back layer, as a fraction of the crop on each side. */
    private val overscan: Float,
    kinds: Set<ScreenKind> = setOf(ScreenKind.COVER, ScreenKind.MAIN),
    private val maxLongSide: Int = MAX_LONG_SIDE,
) : VideoFrames {

    private val app = context.applicationContext
    private val thread = HandlerThread("fold-video").apply { start() }
    private val gl = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // Main thread: current frame per screen and the previous ones kept alive while HWUI may
    // still be drawing them.
    private val current = HashMap<ScreenKind, Frame>()
    private val history = HashMap<ScreenKind, ArrayDeque<Frame>>()
    private var notifyPending = false

    @Volatile
    private var released = false

    @Volatile
    var failed = false
        private set

    private val wanted = kinds

    // ---- GL thread state
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPos = -1
    private var uTexA = -1
    private var uTexB = -1
    private var uMatA = -1
    private var uMatB = -1
    private var uCrop = -1
    private var uMix = -1
    private lateinit var quad: FloatBuffer
    private val outputs = ArrayList<Output>()
    private val players = ArrayList<Track>()
    private var active = 0
    private var standbyStarted = false
    private var mix = 0f
    private var playing = false
    private var renderPending = false

    private class Frame(val bitmap: Bitmap, val image: Image)

    private class Output(
        val kind: ScreenKind,
        val width: Int,
        val height: Int,
        val crop: FloatArray,
        val reader: ImageReader,
        var surface: EGLSurface,
    )

    private class Track(
        val player: ExoPlayer,
        val texture: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        val matrix: FloatArray = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) },
        var fresh: Boolean = false,
    )

    init {
        gl.post {
            try {
                setUp()
            } catch (t: Throwable) {
                failed = true
                PocTrace.log("video", "VIDEO_FAILED", "${t.javaClass.simpleName}: ${t.message}")
                tearDown()
            }
        }
    }

    // ------------------------------------------------------------------ public API (main thread)

    override fun latest(kind: ScreenKind): Bitmap? = current[kind]?.bitmap

    override fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Plays while true; paused (keeping the last frame) while false. */
    override fun setPlaying(play: Boolean) {
        if (released) return
        gl.post { applyPlaying(play) }
    }

    override fun release() {
        if (released) return
        released = true
        listeners.clear()
        gl.post {
            tearDown()
            thread.quitSafely()
        }
        // Frames stay valid for any view still holding them: the bitmaps keep their buffers.
        current.clear()
        history.clear()
    }

    // ------------------------------------------------------------------ GL thread

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        config = configs[0]
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext)) { "eglMakeCurrent" }

        program = linkProgram(VideoShaders.VERTEX, VideoShaders.FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTexA = GLES20.glGetUniformLocation(program, "uTexA")
        uTexB = GLES20.glGetUniformLocation(program, "uTexB")
        uMatA = GLES20.glGetUniformLocation(program, "uMatA")
        uMatB = GLES20.glGetUniformLocation(program, "uMatB")
        uCrop = GLES20.glGetUniformLocation(program, "uCrop")
        uMix = GLES20.glGetUniformLocation(program, "uMix")
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

        for (kind in wanted) outputs += createOutput(kind)

        val twoPlayers = spec.loopCrossfadeMs > 0
        repeat(if (twoPlayers) 2 else 1) { players += createTrack(repeat = !twoPlayers) }
        PocTrace.log(
            "video", "VIDEO_READY",
            "size=${spec.width}x${spec.height} fps=${spec.frameRate} crossfade=${spec.loopCrossfadeMs} outputs=" +
                outputs.joinToString(",") { "${it.kind}:${it.width}x${it.height}" },
        )
        if (playing) applyPlaying(true)
    }

    private fun createOutput(kind: ScreenKind): Output {
        val c = spec.crop(kind)
        val w0 = (c[2] - c[0]) * spec.width * (1f + 2f * overscan)
        val h0 = (c[3] - c[1]) * spec.height * (1f + 2f * overscan)
        val scale = minOf(1f, maxLongSide / max(w0, h0))
        val w = (w0 * scale).roundToInt().coerceIn(16, 4096)
        val h = (h0 * scale).roundToInt().coerceIn(16, 4096)
        val cw = c[2] - c[0]
        val ch = c[3] - c[1]
        val crop = floatArrayOf(c[0] - overscan * cw, c[1] - overscan * ch, c[2] + overscan * cw, c[3] + overscan * ch)
        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, MAX_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        reader.setOnImageAvailableListener({ r -> onImage(kind, r) }, gl)
        val surface = EGL14.eglCreateWindowSurface(display, config, reader.surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        return Output(kind, w, h, crop, reader, surface)
    }

    private fun createTrack(repeat: Boolean): Track {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(tex[0])
        val surface = Surface(st)
        val player = ExoPlayer.Builder(app).setLooper(thread.looper).build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        player.volume = 0f
        player.setVideoSurface(surface)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.playWhenReady = false
        player.prepare()
        val track = Track(player, tex[0], st, surface)
        st.setOnFrameAvailableListener({
            track.fresh = true
            requestRender()
        }, gl)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) seam()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                failed = true
                PocTrace.log("video", "VIDEO_FAILED", error.errorCodeName)
                // Stop for good: no more loop ticks, players paused; the scene keeps its last
                // frame or poster, and whoever listens learns to stop asking for video frame rates.
                gl.removeCallbacks(seamTick)
                players.forEach { it.player.pause() }
                main.post { listeners.forEach { it() } }
            }
        })
        return track
    }

    private fun applyPlaying(play: Boolean) {
        playing = play
        if (players.isEmpty()) return
        val a = players[active]
        if (play) {
            a.player.play()
            if (standbyStarted) players.getOrNull(1 - active)?.player?.play()
            gl.removeCallbacks(seamTick)
            gl.post(seamTick)
        } else {
            players.forEach { it.player.pause() }
            gl.removeCallbacks(seamTick)
        }
    }

    /** Starts the standby player and updates the cross-fade; runs a few times a second. */
    private val seamTick = object : Runnable {
        override fun run() {
            if (!playing || released || failed) return
            seam()
            gl.postDelayed(this, SEAM_TICK_MS)
        }
    }

    private fun seam() {
        if (players.size < 2) return
        val a = players[active]
        val b = players[1 - active]
        val duration = a.player.duration.takeIf { it > 0 } ?: spec.durationMs.toLong()
        val position = a.player.currentPosition
        if (!standbyStarted && position >= LoopSeam.standbyStartMs(duration, spec.loopCrossfadeMs)) {
            standbyStarted = true
            b.player.seekTo(0)
            if (playing) b.player.play()
        }
        mix = if (standbyStarted) LoopSeam.mix(position, duration, spec.loopCrossfadeMs) else 0f
        if (standbyStarted && LoopSeam.shouldSwap(position, duration, a.player.playbackState == Player.STATE_ENDED)) {
            // The standby clip becomes the picture; the old one rewinds and waits for next time.
            active = 1 - active
            standbyStarted = false
            mix = 0f
            a.player.pause()
            a.player.seekTo(0)
            requestRender()
        }
    }

    private fun requestRender() {
        if (renderPending || released) return
        renderPending = true
        gl.post {
            renderPending = false
            render()
        }
    }

    private fun render() {
        if (released || outputs.isEmpty() || players.isEmpty()) return
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext)) return
        for (t in players) {
            if (t.fresh) {
                t.fresh = false
                try {
                    t.surfaceTexture.updateTexImage()
                    t.surfaceTexture.getTransformMatrix(t.matrix)
                } catch (e: RuntimeException) {
                    return
                }
            }
        }
        seam()
        val a = players[active]
        val b = players.getOrNull(1 - active)
        val m = if (b != null && standbyStarted) mix else 0f
        for (out in outputs) {
            if (!EGL14.eglMakeCurrent(display, out.surface, out.surface, eglContext)) continue
            EGL14.eglSwapInterval(display, 0)
            GLES20.glViewport(0, 0, out.width, out.height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, a.texture)
            GLES20.glUniform1i(uTexA, 0)
            GLES20.glUniformMatrix4fv(uMatA, 1, false, a.matrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, (b ?: a).texture)
            GLES20.glUniform1i(uTexB, 1)
            GLES20.glUniformMatrix4fv(uMatB, 1, false, (b ?: a).matrix, 0)
            GLES20.glUniform4f(uCrop, out.crop[0], out.crop[1], out.crop[2], out.crop[3])
            GLES20.glUniform1f(uMix, m)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(display, out.surface)
        }
    }

    private fun onImage(kind: ScreenKind, reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        val buffer = image.hardwareBuffer
        val bitmap = buffer?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
        buffer?.close()
        if (bitmap == null) {
            image.close()
            return
        }
        main.post { publish(kind, Frame(bitmap, image)) }
    }

    // ------------------------------------------------------------------ main thread

    private fun publish(kind: ScreenKind, frame: Frame) {
        if (released) {
            frame.image.close()
            return
        }
        current[kind] = frame
        val kept = history.getOrPut(kind) { ArrayDeque() }
        kept.addLast(frame)
        // HWUI draws a frame a little after it is recorded; keep the last few buffers out of the
        // producer's hands until they are surely done.
        while (kept.size > KEEP_FRAMES) kept.removeFirst().image.close()
        if (!notifyPending) {
            notifyPending = true
            main.post {
                notifyPending = false
                listeners.forEach { it() }
            }
        }
    }

    // ------------------------------------------------------------------ teardown (GL thread)

    private fun tearDown() {
        gl.removeCallbacks(seamTick)
        for (t in players) {
            try {
                t.player.release()
            } catch (_: Exception) {
            }
            t.surface.release()
            t.surfaceTexture.release()
        }
        players.clear()
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            for (o in outputs) {
                EGL14.eglDestroySurface(display, o.surface)
                o.reader.close()
            }
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
        outputs.clear()
        display = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        pbuffer = EGL14.EGL_NO_SURFACE
    }

    private fun linkProgram(vertex: String, fragment: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        /** Frames for a 2.2K main screen are plenty; larger clips are scaled down here. */
        const val MAX_LONG_SIDE = 2304
        const val MAX_IMAGES = 5
        const val KEEP_FRAMES = 3
        const val SEAM_TICK_MS = 33L
    }
}
