package com.foldmotion.wallpapers.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader

/**
 * Renders a drawing through Android's hardware pipeline (HWUI, on the GPU) into an ordinary
 * bitmap, off screen. Runtime shaders only run there, so this is how the live liquid becomes a
 * poster or a thumbnail. Returns null if the phone cannot do it; callers then fall back.
 */
object GpuSnapshot {
    fun render(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap? = try {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            val root = RenderNode("snapshot").apply { setPosition(0, 0, width, height) }
            val canvas = root.beginRecording(width, height)
            try {
                draw(canvas)
            } finally {
                root.endRecording()
            }
            renderer.setContentRoot(root)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            reader.acquireNextImage()?.use { image ->
                val plane = image.planes[0]
                val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(plane.buffer)
                if (full.width == width) full else Bitmap.createBitmap(full, 0, 0, width, height).also { full.recycle() }
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    } catch (e: Throwable) {
        android.util.Log.w("GpuSnapshot", "offscreen GPU render failed", e)
        null
    }
}
