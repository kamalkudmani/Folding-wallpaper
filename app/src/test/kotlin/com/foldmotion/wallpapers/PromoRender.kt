package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.wallpapers.generator.liquid.LiquidRequest
import com.foldmotion.wallpapers.render.FrameState
import com.foldmotion.wallpapers.render.LiquidMotion
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Asset build step, not a check: films a vertical promo (1080x1920, 30 fps) of live liquid
 * wallpapers on a drawn Fold, through the app's own renderer on the hardware pipeline: the closed
 * phone, unfolding with Flow, Glass and Ripple, taps, the half-open hinge. Captions are added by
 * tools/promo/promo.py. Runs only with -Dfoldmotion.promoOut=<folder>.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PromoRender {

    private val container = AppContainer.get(ApplicationProvider.getApplicationContext())
    private val fold7 = DeviceProfileRegistry.fold7
    private val W = 1080
    private val H = 1920
    private val fps = 30

    private class Shot(val cover: LoadedScene, val main: LoadedScene)

    private fun shot(look: String, style: FoldStyle): Shot {
        val pack = container.liquidBuilder.build(LiquidRequest(LiquidSpec.PRESETS.getValue(look), style, seed = 9L), fold7)
        return Shot(SceneLoader.load(pack.dir, ScreenKind.COVER)!!, SceneLoader.load(pack.dir, ScreenKind.MAIN)!!)
    }

    @Test
    fun `render promo frames`() {
        val out = System.getProperty("foldmotion.promoOut")?.let(::File)
        assumeTrue(out != null)
        out!!.mkdirs()
        out.listFiles()?.forEach { if (it.name.endsWith(".png")) it.delete() }
        val drawer = SceneDrawer()
        val gold = shot("gold", FoldStyle.FLOW)
        val aurora = shot("aurora", FoldStyle.GLASS)
        val abyss = shot("abyss", FoldStyle.RIPPLE)
        val neon = shot("neon", FoldStyle.FLOW)
        var index = 0
        var clock = 20_000L

        val coverH = 1240f
        val coverW = coverH * fold7.cover.widthPx / fold7.cover.heightPx
        val mainW = 940f
        val mainH = mainW * fold7.main!!.heightPx / fold7.main!!.widthPx

        fun background(c: Canvas, glow: Int) {
            c.drawColor(Color.rgb(6, 7, 11))
            val p = Paint()
            p.shader = RadialGradient(W / 2f, H * 0.52f, H * 0.55f, intArrayOf(withAlpha(glow, 0.22f), withAlpha(glow, 0f)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        }

        /** A phone body with [scene] on its screen, centred at (cx, cy). */
        fun phone(c: Canvas, scene: LoadedScene, sw: Float, sh: Float, cy: Float, progress: Float?, state: FrameState, crease: Boolean) {
            val left = (W - sw) / 2f
            val top = cy - sh / 2f
            val bezel = 14f
            val body = RectF(left - bezel, top - bezel, left + sw + bezel, top + sh + bezel)
            c.drawRoundRect(body, 64f, 64f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 13, 18) })
            c.drawRoundRect(body, 64f, 64f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                shader = LinearGradient(0f, body.top, 0f, body.bottom, Color.rgb(160, 178, 196), Color.rgb(58, 74, 89), Shader.TileMode.CLAMP)
            })
            c.save()
            val path = android.graphics.Path().apply { addRoundRect(RectF(left, top, left + sw, top + sh), 52f, 52f, android.graphics.Path.Direction.CW) }
            c.clipPath(path)
            c.translate(left, top)
            drawer.draw(c, sw.toInt(), sh.toInt(), scene, progress, state)
            c.restore()
            if (crease) {
                c.drawRect(W / 2f - 1.5f, top, W / 2f + 1.5f, top + sh, Paint().apply { color = Color.argb(46, 255, 255, 255) })
            }
        }

        fun emit(glow: Int, draw: (Canvas, FrameState) -> Unit) {
            val state = FrameState(timeMs = clock, ambient = true)
            val frame = HwCapture.render(W, H) { c ->
                background(c, glow)
                draw(c, state)
            }
            File(out, "f%04d.png".format(index++)).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            frame.recycle()
            clock += 1000L / fps
        }

        fun seconds(s: Float) = (s * fps).toInt()

        // 1. Closed: gold flowing on the cover screen.
        repeat(seconds(3f)) { emit(gold.cover.glow) { c, st -> phone(c, gold.cover, coverW, coverH, H * 0.52f, null, st, false) } }
        // 2. Unfold with Flow.
        val flowMs = gold.main.entryMotion!!.durationMs
        for (i in 0 until seconds(flowMs / 1000f)) emit(gold.main.glow) { c, st -> phone(c, gold.main, mainW, mainH, H * 0.52f, i / (flowMs / 1000f * fps), st, true) }
        repeat(seconds(1.6f)) { emit(gold.main.glow) { c, st -> phone(c, gold.main, mainW, mainH, H * 0.52f, null, st, true) } }
        // 3. Aurora through Glass, then two taps send out ripples.
        val glassMs = aurora.main.entryMotion!!.durationMs
        for (i in 0 until seconds(glassMs / 1000f)) emit(aurora.main.glow) { c, st -> phone(c, aurora.main, mainW, mainH, H * 0.52f, i / (glassMs / 1000f * fps), st, true) }
        val world = aurora.main.world!!
        aurora.main.liquidMotion.tap(world.left + world.width * 0.3f, world.top + world.height * 0.4f, clock + 200)
        aurora.main.liquidMotion.tap(world.left + world.width * 0.7f, world.top + world.height * 0.62f, clock + 1_100)
        repeat(seconds(2.6f)) { emit(aurora.main.glow) { c, st -> phone(c, aurora.main, mainW, mainH, H * 0.52f, null, st, true) } }
        // 4. Deep ocean through Ripple.
        val rippleMs = abyss.main.entryMotion!!.durationMs
        for (i in 0 until seconds(rippleMs / 1000f)) emit(abyss.main.glow) { c, st -> phone(c, abyss.main, mainW, mainH, H * 0.52f, i / (rippleMs / 1000f * fps), st, true) }
        repeat(seconds(1.2f)) { emit(abyss.main.glow) { c, st -> phone(c, abyss.main, mainW, mainH, H * 0.52f, null, st, true) } }
        // 5. Neon: the phone half opens and the liquid pools along the fold.
        val n = seconds(3.4f)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            LiquidMotion.hingeAngle = 180f - 90f * kotlin.math.sin(Math.PI * t).toFloat()
            emit(neon.main.glow) { c, st -> phone(c, neon.main, mainW, mainH, H * 0.52f, null, st, true) }
        }
        LiquidMotion.hingeAngle = Float.NaN
        // 6. End card over flowing gold, dimmed.
        repeat(seconds(3f)) {
            emit(gold.main.glow) { c, st ->
                phone(c, gold.main, mainW, mainH, H * 0.52f, null, st, true)
                c.drawColor(Color.argb(150, 4, 5, 8))
            }
        }
        for (s in listOf(gold, aurora, abyss, neon)) {
            s.cover.recycle()
            s.main.recycle()
        }
        File(out, "fps.txt").writeText("$fps\n")
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}
