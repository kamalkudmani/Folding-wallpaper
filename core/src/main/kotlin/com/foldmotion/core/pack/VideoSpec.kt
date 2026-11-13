package com.foldmotion.core.pack

import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.ScreenKind
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.min

/**
 * v4: a pack whose scene is a real video. One looping clip feeds both screens: the main screen
 * shows a window of it, the cover a narrower slice of that window at the same physical scale, so
 * folding keeps the picture continuous exactly like the drawn scenes. The scene layers of a video
 * pack hold one poster frame per screen, shown while the video starts and in lists.
 */
@Serializable
data class VideoSpec(
    /** Path of the clip inside the pack, an MP4 (H.264 or HEVC), no audio needed. */
    val file: String,
    /** Display size after rotation, in pixels. */
    val width: Int,
    val height: Int,
    val durationMs: Int,
    val frameRate: Float = 30f,
    /**
     * Cross-fade between the end and the start when the clip loops, so any clip loops without a
     * visible jump. 0 for clips made to loop seamlessly.
     */
    val loopCrossfadeMs: Int = DEFAULT_CROSSFADE_MS,
    /** Window of the video each screen shows, normalised [l, t, r, b] in video coordinates. */
    val crop: VideoCrops,
    /** Exact size of [file], checked by the validator. */
    val sizeBytes: Long,
) {
    fun crop(kind: ScreenKind): List<Float> = if (kind == ScreenKind.MAIN) crop.main else crop.cover

    companion object {
        const val DEFAULT_CROSSFADE_MS = 700
        const val MIN_DURATION_MS = 1_000
        const val MAX_DURATION_MS = 60_000
        const val MAX_CROSSFADE_MS = 2_000
        const val FILE = "video/scene.mp4"
    }
}

@Serializable
data class VideoCrops(val cover: List<Float>, val main: List<Float>)

object VideoGeometry {

    /**
     * Crops for a [videoWidth] x [videoHeight] clip on [profile]: the largest main-screen window
     * that fits, centred on the focus (clamped inside the clip), and the cover window as the slice
     * of it the cover would show at the same physical scale.
     */
    fun crops(profile: DeviceProfile, videoWidth: Int, videoHeight: Int, focusX: Float = 0.5f, focusY: Float = 0.5f): VideoCrops {
        val vw = videoWidth.toFloat().coerceAtLeast(1f)
        val vh = videoHeight.toFloat().coerceAtLeast(1f)
        val mainScreen = profile.main ?: profile.cover
        val mainAspect = mainScreen.widthPx.toFloat() / mainScreen.heightPx
        val coverAspect = profile.cover.widthPx.toFloat() / profile.cover.heightPx
        // Main window in pixels: as large as the clip allows at the main screen's aspect.
        var mh = vh
        var mw = mh * mainAspect
        if (mw > vw) {
            mw = vw
            mh = mw / mainAspect
        }
        val ml = (focusX * vw - mw / 2f).coerceIn(0f, vw - mw)
        val mt = (focusY * vh - mh / 2f).coerceIn(0f, vh - mh)
        // Cover: same height (both panels are about the same physical height), its own width.
        val physical = profile.cover.widthMm?.let { c -> profile.main?.widthMm?.let { m -> c / m } }
        val cw = min(mw, physical?.let { mw * it } ?: (mh * coverAspect))
        val ch = min(mh, cw / coverAspect)
        val cl = (focusX * vw - cw / 2f).coerceIn(ml, ml + mw - cw)
        val ct = (mt + (mh - ch) / 2f)
        fun n(v: Float, total: Float) = (v / total).coerceIn(0f, 1f)
        return VideoCrops(
            cover = listOf(n(cl, vw), n(ct, vh), n(cl + cw, vw), n(ct + ch, vh)),
            main = listOf(n(ml, vw), n(mt, vh), n(ml + mw, vw), n(mt + mh, vh)),
        )
    }
}

/** Cheap container sniffing: an MP4 starts with an `ftyp` box. */
object Mp4Probe {
    fun looksLikeMp4(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(12)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            read == 12 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        }
    } catch (e: Exception) {
        false
    }
}
