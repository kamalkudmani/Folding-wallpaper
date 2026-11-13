package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader

/**
 * Renders through Android's real hardware pipeline (HWUI) into a bitmap, as the wallpaper and the
 * previews draw on the phone. Robolectric runs it on the CPU, runtime shaders included, which a
 * software canvas cannot do.
 */
object HwCapture {
    fun render(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            val root = RenderNode("capture").apply { setPosition(0, 0, width, height) }
            val canvas = root.beginRecording(width, height)
            try {
                draw(canvas)
            } finally {
                root.endRecording()
            }
            renderer.setContentRoot(root)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            reader.acquireNextImage().use { image ->
                val plane = image.planes[0]
                val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(plane.buffer)
                return if (full.width == width) full else Bitmap.createBitmap(full, 0, 0, width, height).also { full.recycle() }
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }
}
