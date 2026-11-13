package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.foldmotion.core.motion.RevealSpec
import com.foldmotion.wallpapers.render.LiquidReveal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The liquid reveals' shaders compile and run, bend the picture while they play, darken what is
 * not uncovered yet, and land exactly on the plain picture; before Android 13 they hand over to
 * the drawn fallback.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LiquidRevealTest {

    private val w = 240
    private val h = 216

    /** A picture with detail everywhere, so any bending shows. */
    private val content: (Canvas) -> Unit = { c ->
        c.drawColor(Color.rgb(20, 40, 60))
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(40, 200, 220), Color.rgb(230, 120, 40), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val stripe = Paint().apply { color = Color.WHITE }
        for (x in 0 until w step 12) c.drawRect(x.toFloat(), 0f, x + 3f, h.toFloat(), stripe)
        for (y in 0 until h step 12) c.drawRect(0f, y.toFloat(), w.toFloat(), y + 2f, stripe)
    }

    private val reveals = listOf(
        RevealSpec(RevealSpec.RIPPLE, listOf(0.5f, 0.5f, 1f), shade = 0.92f, edgeGlow = 0.85f),
        RevealSpec(RevealSpec.FLOW, listOf(0f, 1f), shade = 0.92f, edgeGlow = 0.9f),
        RevealSpec(RevealSpec.GLASS, listOf(1f), shade = 0.9f, edgeGlow = 0.8f),
    )

    private fun plain(): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { content(Canvas(it)) }

    /** On the hardware pipeline, as on the phone. */
    private fun render(reveal: RevealSpec, rp: Float, liquid: LiquidReveal = LiquidReveal()): Pair<Bitmap, Boolean> {
        var drawn = false
        val out = HwCapture.render(w, h) { c ->
            drawn = liquid.draw(c, w, h, reveal, rp, w * reveal.fromOrHalf(0), h * reveal.fromOrHalf(1), Color.rgb(200, 255, 240), content)
        }
        return out to drawn
    }

    private fun RevealSpec.fromOrHalf(i: Int) = if (type == RevealSpec.RIPPLE) from[i] else 0.5f

    /** Mean absolute difference per channel, 0..255. */
    private fun diff(a: Bitmap, b: Bitmap, x0: Int = 0, y0: Int = 0, x1: Int = w, y1: Int = h): Double {
        var sum = 0L
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            sum += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
            n += 3
        }
        return sum.toDouble() / n
    }

    private fun luma(b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = b.getPixel(x, y)
            sum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
            n++
        }
        return sum / n
    }

    @Test
    fun `every liquid reveal runs its shader and lands on the plain picture`() {
        val plain = plain()
        for (r in reveals) {
            assertTrue(r.type, r.isWellFormed() && r.isLiquid)
            val (mid, drawnMid) = render(r, 0.45f)
            assertTrue("${r.type}: shader ran", drawnMid)
            assertTrue("${r.type}: something happens mid-way (${diff(mid, plain)})", diff(mid, plain) > 6.0)
            val (end, drawnEnd) = render(r, 1f)
            assertTrue(drawnEnd)
            assertTrue("${r.type}: lands on the picture (${diff(end, plain)})", diff(end, plain) < 1.5)
        }
    }

    @Test
    fun `ripple and flow uncover from where they start`() {
        val (ripple, _) = render(reveals[0], 0.3f)
        // Centre (where the drop fell) is uncovered, the corners are still dark.
        assertTrue(luma(ripple, w / 2 - 15, h / 2 - 15, w / 2 + 15, h / 2 + 15) > luma(ripple, 0, 0, 30, 30) * 2.5)
        val (flow, _) = render(reveals[1], 0.35f)
        // Angle 0 pours from the left edge.
        assertTrue(luma(flow, 0, 0, 40, h) > luma(flow, w - 40, 0, w, h) * 2.5)
        // Glass starts dim and frosted, then clears.
        val (glassEarly, _) = render(reveals[2], 0.1f)
        val (glassLate, _) = render(reveals[2], 0.8f)
        val plain = plain()
        assertTrue(diff(glassEarly, plain) > diff(glassLate, plain) * 2)
    }

    @Test
    fun `a software canvas gets the drawn fallback`() {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (r in reveals) assertEquals(r.type, false, LiquidReveal().draw(Canvas(bmp), w, h, r, 0.5f, 0f, 0f, Color.WHITE, content))
    }

    @Test
    @Config(sdk = [32])
    fun `before Android 13 the drawn fallback takes over`() {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (r in reveals) assertEquals(r.type, false, LiquidReveal().draw(Canvas(bmp), w, h, r, 0.5f, 0f, 0f, Color.WHITE, content))
    }
}
