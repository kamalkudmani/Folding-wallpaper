package com.foldmotion.wallpapers.generator.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max

/** Finds the main subject of a photo (people, pets, vehicles...). */
fun interface SubjectMasker {
    /**
     * A soft mask of the subject at [photo]'s size, as an ALPHA_8 bitmap (255 = subject), or null
     * when the photo has no clear subject.
     */
    fun mask(photo: Bitmap): Bitmap?
}

/**
 * On-device subject masking with DeepLab v3 (PASCAL VOC, 257x257, Apache 2.0, bundled in the
 * app's assets) on LiteRT. Nothing leaves the phone: the model runs in this process, without
 * Google Play services and without network access.
 */
class DeepLabMasker(private val context: Context) : SubjectMasker {

    override fun mask(photo: Bitmap): Bitmap? {
        val interpreter = try {
            context.assets.openFd(MODEL).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    org.tensorflow.lite.Interpreter(ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength), org.tensorflow.lite.Interpreter.Options().setNumThreads(4))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "subject model unavailable", e)
            return null
        }
        try {
            val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
            val small = Bitmap.createScaledBitmap(photo, SIZE, SIZE, true)
            val pixels = IntArray(SIZE * SIZE)
            small.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            if (small !== photo) small.recycle()
            for (p in pixels) {
                input.putFloat((Color.red(p) - 127.5f) / 127.5f)
                input.putFloat((Color.green(p) - 127.5f) / 127.5f)
                input.putFloat((Color.blue(p) - 127.5f) / 127.5f)
            }
            input.rewind()
            val output = ByteBuffer.allocateDirect(SIZE * SIZE * CLASSES * 4).order(ByteOrder.nativeOrder())
            interpreter.run(input, output)
            output.rewind()
            val logits = FloatArray(SIZE * SIZE * CLASSES)
            output.asFloatBuffer().get(logits)
            val soft = subjectProbability(logits, SIZE, SIZE) ?: return null
            return toMask(soft, SIZE, SIZE, photo.width, photo.height)
        } catch (e: Throwable) {
            Log.w(TAG, "subject masking failed", e)
            return null
        } finally {
            interpreter.close()
        }
    }

    companion object {
        private const val TAG = "DeepLabMasker"
        const val MODEL = "models/deeplab_v3.tflite"
        const val SIZE = 257
        const val CLASSES = 21

        /**
         * PASCAL VOC classes that make a subject: aeroplane, bicycle, bird, boat, bus, car, cat,
         * cow, dog, horse, motorbike, person, sheep, train. Furniture, bottles, plants and screens
         * stay in the background.
         */
        val SUBJECT_CLASSES = intArrayOf(1, 2, 3, 4, 6, 7, 8, 10, 12, 13, 14, 15, 17, 19)

        /** Smallest share of the picture a subject must cover. */
        const val MIN_AREA = 0.03f

        /**
         * Per-pixel probability that the pixel belongs to a subject class (softmax over the 21
         * logits), or null when the subject is too small to separate.
         */
        fun subjectProbability(logits: FloatArray, w: Int, h: Int): FloatArray? {
            val subject = BooleanArray(CLASSES).also { s -> SUBJECT_CLASSES.forEach { s[it] = true } }
            val out = FloatArray(w * h)
            var area = 0f
            for (i in 0 until w * h) {
                val base = i * CLASSES
                var m = Float.NEGATIVE_INFINITY
                for (c in 0 until CLASSES) m = max(m, logits[base + c])
                var all = 0f
                var fg = 0f
                for (c in 0 until CLASSES) {
                    val e = exp(logits[base + c] - m)
                    all += e
                    if (subject[c]) fg += e
                }
                val p = fg / all
                out[i] = p
                if (p > 0.5f) area++
            }
            return if (area / (w * h) < MIN_AREA) null else out
        }

        /** The probability map as a feathered ALPHA_8 mask at the photo's size. */
        fun toMask(prob: FloatArray, w: Int, h: Int, outW: Int, outH: Int): Bitmap {
            val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h) { i ->
                // Sharpen the transition a little around 0.5 so edges are clean but not jagged.
                val a = ((prob[i] - 0.5f) * 2.2f + 0.5f).coerceIn(0f, 1f)
                Color.argb((a * 255).toInt(), 255, 255, 255)
            }
            small.setPixels(px, 0, w, 0, 0, w, h)
            val big = Bitmap.createScaledBitmap(small, outW, outH, true)
            small.recycle()
            val mask = Bitmap.createBitmap(outW, outH, Bitmap.Config.ALPHA_8)
            Canvas(mask).drawBitmap(big, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            big.recycle()
            return mask
        }
    }
}
