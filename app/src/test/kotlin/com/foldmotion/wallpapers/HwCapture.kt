package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import com.foldmotion.wallpapers.render.GpuSnapshot

/**
 * Renders through Android's real hardware pipeline (HWUI) into a bitmap, as the wallpaper and the
 * previews draw on the phone. Robolectric runs it on the CPU, runtime shaders included, which a
 * software canvas cannot do.
 */
object HwCapture {
    fun render(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap =
        GpuSnapshot.render(width, height, draw) ?: error("hardware rendering unavailable")
}
