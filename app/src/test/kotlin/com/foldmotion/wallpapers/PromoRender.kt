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
 * Asset build step, not a check: films a vertical promo (1080x1920, 30 fps) of the liquid glass
 * wallpapers on a drawn Fold, through the app's own renderer on the hardware pipeline: drops
 * gathered on the closed phone flow apart as it opens, ripple under taps, gather again as it
 * closes. Captions are added by tools/promo/promo.py. Runs only with -Dfoldmotion.promoOut=<folder>.
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

    private fun shot(look: String): Shot {
        val pack = container.liquidBuilder.build(LiquidRequest(LiquidSpec.PRESETS.getValue(look), FoldStyle.NATURAL, seed = 9L), fold7)
        return Shot(SceneLoader.load(pack.dir, ScreenKind.COVER)!!, SceneLoader.load(pack.dir, ScreenKind.MAIN)!!)
    }

    @Test
    fun `render promo frames`() {
        val out = System.getProperty("foldmotion.promoOut")?.let(::File)
        assumeTrue(out != null)
        out!!.mkdirs()
        out.listFiles()?.forEach { if (it.name.endsWith(".png")) it.delete() }
        val drawer = SceneDrawer()
        val daybreak = shot("daybreak")
        val lagoon = shot("lagoon")
        val orchid = shot("orchid")
        val midnight = shot("midnight")
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

        /** The main screen opening: the drops flow apart, then rest. */
        fun unfold(s: Shot, rest: Float) {
            val ms = s.main.entryMotion!!.durationMs
            for (i in 0 until seconds(ms / 1000f)) emit(s.main.glow) { c, st -> phone(c, s.main, mainW, mainH, H * 0.52f, i / (ms / 1000f * fps), st, true) }
            repeat(seconds(rest)) { emit(s.main.glow) { c, st -> phone(c, s.main, mainW, mainH, H * 0.52f, null, st, true) } }
        }

        // 1. Closed: the drops gathered on the cover screen, drifting.
        repeat(seconds(2.6f)) { emit(daybreak.cover.glow) { c, st -> phone(c, daybreak.cover, coverW, coverH, H * 0.52f, null, st, false) } }
        // 2. It opens: they flow apart across the main screen.
        unfold(daybreak, 1.6f)
        // 3. Lagoon opens, then two taps make the glass ripple.
        unfold(lagoon, 0.4f)
        val world = lagoon.main.world!!
        lagoon.main.liquidMotion.tap(world.left + world.width * 0.3f, world.top + world.height * 0.45f, clock + 200)
        lagoon.main.liquidMotion.tap(world.left + world.width * 0.68f, world.top + world.height * 0.6f, clock + 1_100)
        repeat(seconds(2.6f)) { emit(lagoon.main.glow) { c, st -> phone(c, lagoon.main, mainW, mainH, H * 0.52f, null, st, true) } }
        // 4. Orchid closes: the pills gather onto the cover.
        val closeMs = orchid.cover.entryMotion!!.durationMs
        for (i in 0 until seconds(closeMs / 1000f)) emit(orchid.cover.glow) { c, st -> phone(c, orchid.cover, coverW, coverH, H * 0.52f, i / (closeMs / 1000f * fps), st, false) }
        repeat(seconds(1.2f)) { emit(orchid.cover.glow) { c, st -> phone(c, orchid.cover, coverW, coverH, H * 0.52f, null, st, false) } }
        // 5. Midnight opens.
        unfold(midnight, 1.4f)
        // 6. End card over the open daybreak, dimmed.
        repeat(seconds(3f)) {
            emit(daybreak.main.glow) { c, st ->
                phone(c, daybreak.main, mainW, mainH, H * 0.52f, null, st, true)
                c.drawColor(Color.argb(150, 4, 5, 8))
            }
        }
        for (s in listOf(daybreak, lagoon, orchid, midnight)) {
            s.cover.recycle()
            s.main.recycle()
        }
        File(out, "fps.txt").writeText("$fps\n")
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}
