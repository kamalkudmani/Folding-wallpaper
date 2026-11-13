package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Store artwork, not a check: the launcher icon composited at 512 px and a 1024x500 feature
 * graphic built from real wallpapers. Runs only with -Dfoldmotion.storeOut=<folder>.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class StoreAssetsRender {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun warmUp() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bmp.byteCount))
    }

    private fun icon(size: Int, rounded: Boolean): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (rounded) {
            val r = size * 0.23f
            c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, Path.Direction.CW) })
        }
        for (id in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground)) {
            val d = ResourcesCompat.getDrawable(context.resources, id, null)!!
            // Adaptive layers are 108 dp with the visible icon in the middle 72 dp.
            val bleed = (size * 18f / 72f).toInt()
            d.setBounds(-bleed, -bleed, size + bleed, size + bleed)
            d.draw(c)
        }
        return out
    }

    @Test
    fun `render store assets`() {
        val out = System.getProperty("foldmotion.storeOut")?.let(::File)
        assumeTrue(out != null)
        out!!.mkdirs()
        icon(512, rounded = false).let { b -> File(out, "play-icon-512.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        icon(432, rounded = true).let { b -> File(out, "launcher-preview.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        // Themed (monochrome) preview.
        Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, 432f, 432f), 100f, 100f, Path.Direction.CW) })
            c.drawColor(Color.rgb(222, 230, 242))
            val d = ResourcesCompat.getDrawable(context.resources, R.drawable.ic_launcher_monochrome, null)!!
            d.setTint(Color.rgb(34, 52, 82))
            d.setBounds(-108, -108, 540, 540)
            d.draw(c)
            File(out, "launcher-themed.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // Feature graphic: dark backdrop, wordmark on the left, three wallpapers on open phones.
        val w = 1024
        val h = 500
        val fg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(fg)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(14, 18, 28), Color.rgb(6, 7, 10), Shader.TileMode.CLAMP) })
        c.drawPaint(Paint().apply { shader = RadialGradient(760f, 260f, 520f, intArrayOf(Color.argb(110, 45, 225, 194), Color.argb(60, 139, 124, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP) })
        val ic = icon(132, rounded = true)
        c.drawBitmap(ic, 64f, 104f, null)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 64f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        c.drawText("Fold Motion", 64f, 312f, title)
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 178, 190); textSize = 25f }
        c.drawText("Live wallpapers for the fold", 66f, 356f, sub)
        fun asset(name: String) = context.assets.open("discover/$name").use { BitmapFactory.decodeStream(it) }
        val shots = listOf("liquid_light_main.webp", "aurora_peaks_main.webp", "neon_skyline_main.webp")
        val pw = 214f
        val ph = pw / 0.9f
        for ((i, name) in shots.withIndex()) {
            val x = 520f + i * 160f
            val y = 70f + (if (i == 1) 0f else 40f)
            val rect = RectF(x, y, x + pw, y + ph)
            val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 32, 38) }
            c.drawRoundRect(RectF(rect.left - 6, rect.top - 6, rect.right + 6, rect.bottom + 6), 22f, 22f, frame)
            c.save()
            c.clipPath(Path().apply { addRoundRect(rect, 16f, 16f, Path.Direction.CW) })
            c.drawBitmap(asset(name), null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            val crease = Paint().apply { shader = LinearGradient(rect.centerX() - 6, 0f, rect.centerX() + 6, 0f, intArrayOf(Color.TRANSPARENT, Color.argb(60, 0, 0, 0), Color.argb(40, 255, 255, 255), Color.argb(60, 0, 0, 0), Color.TRANSPARENT), null, Shader.TileMode.CLAMP) }
            c.drawRect(rect.centerX() - 6, rect.top, rect.centerX() + 6, rect.bottom, crease)
            c.restore()
        }
        File(out, "feature-graphic-1024x500.png").outputStream().use { fg.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
