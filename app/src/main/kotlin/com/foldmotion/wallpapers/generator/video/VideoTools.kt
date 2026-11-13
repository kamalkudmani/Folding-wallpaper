package com.foldmotion.wallpapers.generator.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/** What the pack builder needs to know about a clip, as it is displayed (rotation applied). */
data class ClipInfo(val width: Int, val height: Int, val durationMs: Long, val frameRate: Float)

class VideoToolException(message: String) : Exception(message)

/** Platform video operations, behind an interface so the pack builder can be tested off-device. */
interface VideoTools {
    fun probe(uri: Uri): ClipInfo?

    /**
     * Writes [startMs]..[endMs] of [uri] to [out] as a silent H.264 MP4 whose long side is at most
     * [maxLongSide] and frame rate at most [maxFrameRate].
     */
    suspend fun transcode(uri: Uri, startMs: Long, endMs: Long, maxLongSide: Int, maxFrameRate: Float, out: File, onProgress: (Float) -> Unit)

    /** The frame at [timeMs] of [file], upright, long side at most [maxLongSide]. */
    fun frameAt(file: File, timeMs: Long, maxLongSide: Int): Bitmap?
}

/** MediaMetadataRetriever, MediaExtractor and Media3 Transformer, all on the phone. */
class AndroidVideoTools(context: Context) : VideoTools {

    private val app = context.applicationContext

    override fun probe(uri: Uri): ClipInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(app, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val upright = rotation % 180 == 0
            ClipInfo(if (upright) w else h, if (upright) h else w, duration, frameRate(uri))
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun frameRate(uri: Uri): Float {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(app, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    return try {
                        format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                    } catch (e: ClassCastException) {
                        format.getFloat(MediaFormat.KEY_FRAME_RATE)
                    }
                }
            }
            30f
        } catch (e: Exception) {
            30f
        } finally {
            extractor.release()
        }
    }

    override suspend fun transcode(uri: Uri, startMs: Long, endMs: Long, maxLongSide: Int, maxFrameRate: Float, out: File, onProgress: (Float) -> Unit) {
        val info = probe(uri) ?: throw VideoToolException("unreadable video")
        out.parentFile?.mkdirs()
        val effects = ArrayList<Effect>()
        val long = max(info.width, info.height)
        if (long > maxLongSide) {
            val scale = maxLongSide / long.toFloat()
            // Encoders want even sizes.
            val w = ((info.width * scale).roundToInt() / 2) * 2
            val h = ((info.height * scale).roundToInt() / 2) * 2
            effects += Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)
        }
        if (info.frameRate > maxFrameRate + 0.5f) effects += FrameDropEffect.createDefaultFrameDropEffect(maxFrameRate)
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
            .build()
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), effects))
            .build()
        // Transformer must be driven from a thread with a Looper: the main thread.
        withContext(Dispatchers.Main) {
            coroutineScope {
                lateinit var transformer: Transformer
                val poll = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                        delay(200)
                    }
                }
                try {
                    suspendCancellableCoroutine { cont ->
                        transformer = Transformer.Builder(app)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (cont.isActive) cont.resume(Unit)
                                }

                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (cont.isActive) cont.resumeWithException(VideoToolException(exportException.errorCodeName))
                                }
                            })
                            .build()
                        cont.invokeOnCancellation { transformer.cancel() }
                        transformer.start(edited, out.absolutePath)
                    }
                } finally {
                    poll.cancel()
                }
            }
        }
        onProgress(1f)
    }

    override fun frameAt(file: File, timeMs: Long, maxLongSide: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val scale = if (max(w, h) > maxLongSide) maxLongSide / max(w, h).toFloat() else 1f
            val frame = retriever.getScaledFrameAtTime(
                timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                max(16, (w * scale).roundToInt()), max(16, (h * scale).roundToInt()),
            ) ?: retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
