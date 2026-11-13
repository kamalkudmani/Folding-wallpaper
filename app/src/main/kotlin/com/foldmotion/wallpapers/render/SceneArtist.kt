package com.foldmotion.wallpapers.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.foldmotion.core.scene.Celestial
import com.foldmotion.core.scene.LayerRole
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.core.scene.SceneSpec
import com.foldmotion.core.scene.SeededRandom
import com.foldmotion.core.scene.ValueNoise
import com.foldmotion.core.scene.Vegetation
import com.foldmotion.core.scene.WorldRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Paints one depth layer of a [SceneSpec] through a window onto the scene world.
 *
 * Everything is placed in world coordinates and every random choice is keyed to the seed and to
 * world position, never to the window or to the pixel size. Rendering the cover window and the
 * main window therefore shows the same mountains, stars and moon in the same places: the cover is
 * a crop of the main picture at the same physical scale. That is the property the whole product
 * depends on.
 *
 * Layer roles: FAR is opaque (sky, sky objects, distant terrain); MID and NEAR are transparent and
 * move more during a transition.
 */
class SceneArtist(private val spec: SceneSpec) {

    private val pal = spec.palette
    private val seedInt = (spec.seed xor (spec.seed ushr 32)).toInt()

    /** World extent every procedural element is generated over, wider than any window. */
    private val worldLeft = -1.7f
    private val worldRight = 1.7f

    private val fx = spec.focusX
    private val fy = spec.focusY

    /** World units per pixel of the layer currently being painted. */
    private var px = 0.001f

    /** Colour behind every layer; matches the top of the sky so a loading frame is not jarring. */
    fun backgroundColor(): Int = pal.skyTop

    fun render(role: LayerRole, window: WorldRect, widthPx: Int): Bitmap {
        val heightPx = max(1, (widthPx * window.height / window.width).roundToInt())
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = widthPx / window.width
        px = 1f / scale
        canvas.scale(scale, scale)
        canvas.translate(-window.left, -window.top)
        when (role) {
            LayerRole.FAR -> far(canvas, window)
            LayerRole.MID -> mid(canvas, window)
            LayerRole.NEAR -> near(canvas, window)
        }
        return bitmap
    }

    // ---------------------------------------------------------------- layers per family

    private fun far(c: Canvas, w: WorldRect) {
        when (spec.family) {
            SceneFamily.SPACE -> {
                spaceBackdrop(c, w)
                stars(c, w, horizon = 2f, count = (spec.starDensity * 2600).toInt(), salt = 11)
            }
            SceneFamily.ABSTRACT -> abstractBackdrop(c, w)
            SceneFamily.MINIMAL -> {
                minimalSky(c, w)
                if (spec.starDensity > 0f) stars(c, w, horizon = 0.62f, count = (spec.starDensity * 500).toInt(), salt = 11, minimal = true)
                celestialFlat(c)
            }
            else -> {
                val horizon = horizonFor(spec.family)
                sky(c, w, horizon)
                if (spec.starDensity > 0f) stars(c, w, horizon, (spec.starDensity * 1200).toInt(), salt = 11)
                if (spec.aurora) aurora(c)
                celestial(c)
                when (spec.family) {
                    SceneFamily.MOUNTAINS -> ridge(c, w, base = 0.6f, amp = 0.15f, freq = 1.7f, salt = 21, color = pal.far, haze = 0.45f)
                    SceneFamily.DESERT -> dunes(c, w, base = 0.66f, amp = 0.07f, freq = 1.1f, salt = 21, color = pal.far, haze = 0.4f)
                    SceneFamily.OCEAN -> sea(c, w, horizon)
                    SceneFamily.CITY -> {
                        ridge(c, w, base = 0.74f, amp = 0.04f, freq = 1.2f, salt = 22, color = blend(pal.far, pal.skyBottom, 0.35f), haze = 0.2f)
                        skyline(c, base = 0.74f, minH = 0.04f, maxH = 0.17f, salt = 31, color = blend(pal.far, pal.skyBottom, 0.25f), windows = 0f)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun mid(c: Canvas, w: WorldRect) {
        when (spec.family) {
            SceneFamily.MOUNTAINS -> ridge(c, w, base = 0.72f, amp = 0.15f, freq = 2.3f, salt = 41, color = pal.mid, haze = 0.18f)
            SceneFamily.DESERT -> dunes(c, w, base = 0.77f, amp = 0.09f, freq = 1.6f, salt = 41, color = pal.mid, haze = 0.12f, lit = true)
            SceneFamily.OCEAN -> islands(c)
            SceneFamily.SPACE -> planet(c)
            SceneFamily.CITY -> skyline(c, base = 0.82f, minH = 0.09f, maxH = 0.3f, salt = 42, color = pal.mid, windows = 0.33f)
            SceneFamily.ABSTRACT -> ribbons(c)
            SceneFamily.MINIMAL -> {
                flatHill(c, w, base = 0.66f, amp = 0.07f, freq = 0.9f, phase = 0.3f, color = pal.far)
                flatHill(c, w, base = 0.76f, amp = 0.06f, freq = 1.4f, phase = 2.1f, color = pal.mid)
            }
        }
    }

    private fun near(c: Canvas, w: WorldRect) {
        when (spec.family) {
            SceneFamily.MOUNTAINS -> {
                ridge(c, w, base = 0.86f, amp = 0.07f, freq = 3.1f, salt = 61, color = pal.near, haze = 0f)
                if (spec.vegetation == Vegetation.PINES) pines(c, base = 0.86f, amp = 0.07f, freq = 3.1f, salt = 61)
            }
            SceneFamily.DESERT -> {
                dunes(c, w, base = 0.89f, amp = 0.06f, freq = 2.2f, salt = 61, color = pal.near, haze = 0f, lit = true)
                if (spec.vegetation == Vegetation.PALMS) palms(c, base = 0.89f, amp = 0.06f, freq = 2.2f, salt = 61)
            }
            SceneFamily.OCEAN -> shore(c, w)
            SceneFamily.SPACE -> glints(c)
            SceneFamily.CITY -> skyline(c, base = 1.02f, minH = 0.16f, maxH = 0.36f, salt = 62, color = pal.near, windows = 0.18f, clearFocus = 0.17f)
            SceneFamily.ABSTRACT -> orb(c)
            SceneFamily.MINIMAL -> flatHill(c, w, base = 0.88f, amp = 0.05f, freq = 1.1f, phase = 4.0f, color = pal.near)
        }
    }

    // ---------------------------------------------------------------- sky

    private fun horizonFor(f: SceneFamily) = when (f) {
        SceneFamily.OCEAN -> 0.6f
        SceneFamily.DESERT -> 0.7f
        SceneFamily.CITY -> 0.76f
        else -> 0.72f
    }

    private fun sky(c: Canvas, w: WorldRect, horizon: Float) {
        val p = Paint()
        p.shader = LinearGradient(
            0f, -0.05f, 0f, horizon,
            intArrayOf(pal.skyTop, pal.skyMid, pal.skyBottom),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(w.left - 1f, w.top - 1f, w.right + 1f, w.bottom + 1f, p)
        // Warm light pooling around the sun or moon, part of the sky itself.
        if (spec.celestial != Celestial.NONE) {
            val strength = if (spec.celestial == Celestial.SUN) 0.55f else 0.28f
            radial(c, fx, fy, if (spec.celestial == Celestial.SUN) 0.75f else 0.5f, pal.glow, strength)
        }
    }

    private fun minimalSky(c: Canvas, w: WorldRect) {
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, 0f, 0.8f, pal.skyTop, pal.skyMid, Shader.TileMode.CLAMP)
        c.drawRect(w.left - 1f, w.top - 1f, w.right + 1f, w.bottom + 1f, p)
    }

    private fun stars(c: Canvas, w: WorldRect, horizon: Float, count: Int, salt: Long, minimal: Boolean = false) {
        if (count <= 0) return
        val r = SeededRandom(spec.seed).fork(salt)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val top = -0.15f
        val bottom = min(horizon, 1.15f)
        val margin = 0.02f
        repeat(count) {
            val x = r.range(worldLeft, worldRight)
            val y = r.range(top, bottom)
            val size = r.nextFloat().pow(3f)
            val bright = 0.35f + 0.65f * r.nextFloat()
            val warm = r.chance(0.2f)
            if (x < w.left - margin || x > w.right + margin || y < w.top - margin || y > w.bottom + margin) return@repeat
            // Fade towards the horizon and around the moon or sun.
            var alpha = bright * ((bottom - y) / 0.3f).coerceIn(0f, 1f)
            if (spec.celestial != Celestial.NONE) {
                val d = dist(x, y, fx, fy)
                alpha *= ((d - 0.06f) / 0.22f).coerceIn(0f, 1f)
            }
            if (alpha <= 0.02f) return@repeat
            val radius = if (minimal) 0.0022f else 0.0007f + size * 0.0026f
            p.color = withAlpha(if (warm) blend(0xFFFFFFFF.toInt(), pal.accent, 0.35f) else 0xFFFFFFFF.toInt(), alpha)
            c.drawCircle(x, y, max(radius, px * 0.7f), p)
            if (!minimal && size > 0.55f) radial(c, x, y, radius * 6f, pal.glow, alpha * 0.35f)
        }
    }

    private fun aurora(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = listOf(pal.accent, blend(pal.accent, pal.glow, 0.4f), blend(pal.accent, pal.skyMid, 0.3f))
        for (band in 0 until 3) {
            val phase = band * 1.7f + (seedInt and 0xFF) / 40f
            val baseTop = 0.08f + band * 0.07f
            val path = Path()
            val step = 0.01f
            var x = worldLeft
            path.moveTo(x, curtainTop(x, baseTop, phase, band))
            while (x <= worldRight) {
                path.lineTo(x, curtainTop(x, baseTop, phase, band))
                x += step
            }
            while (x >= worldLeft) {
                path.lineTo(x, curtainTop(x, baseTop, phase, band) + 0.2f + 0.05f * sin(x * 2.1f + phase))
                x -= step
            }
            path.close()
            val color = colors[band]
            p.shader = LinearGradient(
                0f, baseTop - 0.05f, 0f, baseTop + 0.3f,
                intArrayOf(withAlpha(color, 0f), withAlpha(color, 0.42f - band * 0.08f), withAlpha(color, 0.12f), withAlpha(color, 0f)),
                floatArrayOf(0f, 0.3f, 0.65f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawPath(path, p)
        }
    }

    private fun curtainTop(x: Float, base: Float, phase: Float, band: Int): Float =
        base + 0.05f * sin(x * 2.6f + phase) + 0.035f * (ValueNoise.fbm(x * 2f + band * 10f, seedInt + 77, 3) - 0.5f)

    private fun celestial(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (spec.celestial) {
            Celestial.NONE -> Unit
            Celestial.SUN -> {
                val r = 0.052f
                radial(c, fx, fy, r * 3.2f, pal.glow, 0.6f)
                p.shader = RadialGradient(fx, fy, r, blend(0xFFFFFFFF.toInt(), pal.glow, 0.3f), pal.glow, Shader.TileMode.CLAMP)
                c.drawCircle(fx, fy, r, p)
            }
            Celestial.FULL_MOON, Celestial.CRESCENT -> {
                val r = 0.043f
                radial(c, fx, fy, r * 5f, pal.glow, 0.32f)
                val moon = Path().apply { addCircle(fx, fy, r, Path.Direction.CW) }
                if (spec.celestial == Celestial.CRESCENT) {
                    val bite = Path().apply { addCircle(fx + r * 0.42f, fy - r * 0.18f, r * 0.9f, Path.Direction.CW) }
                    moon.op(bite, Path.Op.DIFFERENCE)
                }
                p.shader = RadialGradient(fx - r * 0.3f, fy - r * 0.3f, r * 1.4f, 0xFFFFFFFF.toInt(), blend(pal.glow, pal.skyMid, 0.15f), Shader.TileMode.CLAMP)
                c.drawPath(moon, p)
                if (spec.celestial == Celestial.FULL_MOON) {
                    p.shader = null
                    val r2 = SeededRandom(spec.seed).fork(5)
                    // Faint maria spread over the disc; kept subtle so they never read as a face.
                    repeat(7) { i ->
                        val a = i * 0.9f + r2.range(0f, 0.8f)
                        val d = r2.range(r * 0.25f, r * 0.7f)
                        p.color = withAlpha(pal.skyMid, 0.035f + 0.035f * r2.nextFloat())
                        c.drawCircle(fx + cos(a) * d, fy + sin(a) * d, r * r2.range(0.12f, 0.3f), p)
                    }
                }
            }
        }
    }

    private fun celestialFlat(c: Canvas) {
        if (spec.celestial == Celestial.NONE) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = pal.glow
        val r = if (spec.celestial == Celestial.SUN) 0.07f else 0.05f
        if (spec.celestial == Celestial.CRESCENT) {
            val moon = Path().apply { addCircle(fx, fy, r, Path.Direction.CW) }
            moon.op(Path().apply { addCircle(fx + r * 0.45f, fy - r * 0.2f, r * 0.9f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
            c.drawPath(moon, p)
        } else {
            c.drawCircle(fx, fy, r, p)
        }
    }

    // ---------------------------------------------------------------- terrain

    private fun ridgeY(x: Float, base: Float, amp: Float, freq: Float, salt: Int): Float {
        val n = ValueNoise.fbm(x * freq + 50f, seedInt + salt, octaves = 5, roughness = 0.52f)
        // Sharpen peaks a little so ranges read as mountains, not hills.
        val peaky = n.pow(1.35f)
        return base - amp * (peaky * 1.6f - 0.15f)
    }

    private fun ridge(c: Canvas, w: WorldRect, base: Float, amp: Float, freq: Float, salt: Int, color: Int, haze: Float) {
        val path = Path()
        val step = max(px * 2f, 0.0015f)
        val l = w.left - 0.02f
        val r = w.right + 0.02f
        path.moveTo(l, w.bottom + 0.1f)
        var x = l
        while (x <= r) {
            path.lineTo(x, ridgeY(x, base, amp, freq, salt))
            x += step
        }
        path.lineTo(r, ridgeY(r, base, amp, freq, salt))
        path.lineTo(r, w.bottom + 0.1f)
        path.close()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val top = base - amp * 1.45f
        p.shader = LinearGradient(
            0f, top, 0f, base + 0.25f,
            intArrayOf(blend(color, pal.skyBottom, haze), blend(color, pal.skyBottom, haze * 0.35f), color),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawPath(path, p)
        // Rim light on the crest facing the light source.
        if (spec.celestial != Celestial.NONE && haze < 0.4f) {
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(px * 1.5f, 0.0012f)
                shader = RadialGradient(fx, fy, 0.9f, withAlpha(pal.glow, 0.5f), withAlpha(pal.glow, 0f), Shader.TileMode.CLAMP)
            }
            val crest = Path()
            x = l
            crest.moveTo(x, ridgeY(x, base, amp, freq, salt))
            while (x <= r) {
                crest.lineTo(x, ridgeY(x, base, amp, freq, salt))
                x += step
            }
            c.drawPath(crest, rim)
        }
    }

    private fun duneY(x: Float, base: Float, amp: Float, freq: Float, salt: Int): Float {
        val crest = (1f - abs(sin(x * freq * 2.2f + salt))).pow(1.6f)
        val roll = ValueNoise.fbm(x * freq + 20f, seedInt + salt, octaves = 3, roughness = 0.35f)
        return base - amp * (0.55f * crest + 0.75f * roll)
    }

    private fun dunes(
        c: Canvas, w: WorldRect, base: Float, amp: Float, freq: Float, salt: Int,
        color: Int, haze: Float, lit: Boolean = false,
    ) {
        val path = Path()
        val step = max(px * 2f, 0.0015f)
        val l = w.left - 0.02f
        val r = w.right + 0.02f
        path.moveTo(l, w.bottom + 0.1f)
        var x = l
        while (x <= r) {
            path.lineTo(x, duneY(x, base, amp, freq, salt))
            x += step
        }
        path.lineTo(r, w.bottom + 0.1f)
        path.close()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val top = base - amp * 1.3f
        val crestColor = if (lit) blend(color, pal.glow, 0.28f) else blend(color, pal.skyBottom, haze)
        p.shader = LinearGradient(0f, top, 0f, base + 0.18f, intArrayOf(crestColor, color, blend(color, 0xFF000000.toInt(), 0.25f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
    }

    private fun flatHill(c: Canvas, w: WorldRect, base: Float, amp: Float, freq: Float, phase: Float, color: Int) {
        val path = Path()
        val step = max(px * 3f, 0.003f)
        val l = w.left - 0.02f
        val r = w.right + 0.02f
        path.moveTo(l, w.bottom + 0.1f)
        var x = l
        while (x <= r) {
            path.lineTo(x, base - amp * (sin(x * freq * 3f + phase) * 0.6f + sin(x * freq * 1.3f + phase * 2f) * 0.4f))
            x += step
        }
        path.lineTo(r, w.bottom + 0.1f)
        path.close()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun pines(c: Canvas, base: Float, amp: Float, freq: Float, salt: Int) {
        val r = SeededRandom(spec.seed).fork(71)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blend(pal.near, 0xFF000000.toInt(), 0.2f) }
        var x = worldLeft
        while (x < worldRight) {
            x += r.range(0.012f, 0.05f)
            val h = r.range(0.03f, 0.085f) * (if (r.chance(0.15f)) 1.4f else 1f)
            val lean = r.range(-0.004f, 0.004f)
            if (r.chance(0.18f)) continue
            val ground = ridgeY(x, base, amp, freq, salt) + 0.006f
            val half = h * 0.24f
            val tree = Path()
            for (tier in 0 until 3) {
                val tTop = ground - h + tier * h * 0.28f
                val tBottom = tTop + h * 0.5f
                val tw = half * (0.55f + tier * 0.28f)
                tree.moveTo(x + lean, tTop)
                tree.lineTo(x + tw, tBottom)
                tree.lineTo(x - tw, tBottom)
                tree.close()
            }
            tree.addRect(x - h * 0.025f, ground - h * 0.12f, x + h * 0.025f, ground + 0.01f, Path.Direction.CW)
            c.drawPath(tree, p)
        }
    }

    private fun palms(c: Canvas, base: Float, amp: Float, freq: Float, salt: Int) {
        val r = SeededRandom(spec.seed).fork(72)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blend(pal.near, 0xFF000000.toInt(), 0.3f) }
        // Palms come in groves placed across the world, kept clear of the focal element.
        val groves = (0 until 6).map { r.range(worldLeft, worldRight) }
        for (g in groves) {
            if (abs(g - fx) < 0.1f) continue
            val count = 1 + r.nextInt(3)
            repeat(count) {
                val x = g + r.range(-0.05f, 0.05f)
                val h = r.range(0.12f, 0.2f)
                val bend = r.range(-0.05f, 0.05f)
                palm(c, p, x, duneY(x, base, amp, freq, salt) + 0.008f, h, bend, r)
            }
        }
    }

    private fun palm(c: Canvas, p: Paint, x: Float, ground: Float, h: Float, bend: Float, r: SeededRandom) {
        val topX = x + bend
        val topY = ground - h
        val trunk = Path()
        val wBase = h * 0.045f
        val wTop = h * 0.025f
        trunk.moveTo(x - wBase, ground)
        trunk.quadTo(x + bend * 0.2f - wBase, ground - h * 0.6f, topX - wTop, topY)
        trunk.lineTo(topX + wTop, topY)
        trunk.quadTo(x + bend * 0.2f + wBase, ground - h * 0.6f, x + wBase, ground)
        trunk.close()
        c.drawPath(trunk, p)
        val fronds = 7
        for (i in 0 until fronds) {
            val angle = (-PI.toFloat() * 0.95f) + i * (PI.toFloat() * 0.9f / (fronds - 1)) + r.range(-0.1f, 0.1f)
            val len = h * r.range(0.45f, 0.62f)
            val ex = topX + cos(angle) * len
            val ey = topY + sin(angle) * len * 0.55f + len * 0.35f
            val cx = topX + cos(angle) * len * 0.5f
            val cy = topY + sin(angle) * len * 0.5f - len * 0.12f
            val leaf = Path()
            val thick = len * 0.07f
            leaf.moveTo(topX, topY)
            leaf.quadTo(cx, cy - thick, ex, ey)
            leaf.quadTo(cx, cy + thick, topX, topY + thick * 0.5f)
            leaf.close()
            c.drawPath(leaf, p)
        }
    }

    // ---------------------------------------------------------------- ocean

    private fun sea(c: Canvas, w: WorldRect, horizon: Float) {
        val p = Paint()
        p.shader = LinearGradient(
            0f, horizon, 0f, 1.1f,
            intArrayOf(blend(pal.skyBottom, pal.far, 0.45f), pal.far, blend(pal.far, pal.near, 0.6f)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(w.left - 1f, horizon, w.right + 1f, w.bottom + 1f, p)
        if (spec.celestial == Celestial.NONE) return
        // Path of light under the sun or moon: short glints, wider and sparser towards the viewer.
        val r = SeededRandom(spec.seed).fork(81)
        val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        repeat(900) {
            val t = r.nextFloat()
            val y = horizon + 0.004f + t.pow(1.6f) * (1.1f - horizon)
            val spread = 0.02f + t * 0.22f
            val x = fx + (r.nextFloat() + r.nextFloat() - 1f) * spread
            val len = 0.006f + t * 0.05f * r.nextFloat()
            val a = (1f - abs(x - fx) / (spread + 0.001f)).coerceIn(0f, 1f) * (0.25f + 0.6f * r.nextFloat()) * (1f - t * 0.5f)
            glint.color = withAlpha(pal.glow, a)
            glint.strokeWidth = max(px, 0.0009f + t * 0.002f)
            c.drawLine(x - len / 2, y, x + len / 2, y, glint)
        }
        radial(c, fx, horizon, 0.18f, pal.glow, 0.35f)
    }

    private fun islands(c: Canvas) {
        val horizon = 0.6f
        val r = SeededRandom(spec.seed).fork(82)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blend(pal.mid, pal.skyBottom, 0.3f) }
        repeat(5) {
            val cx = r.range(worldLeft, worldRight)
            val width = r.range(0.12f, 0.35f)
            val height = r.range(0.015f, 0.05f)
            if (abs(cx - fx) < width / 2 + 0.08f) return@repeat
            val path = Path()
            path.moveTo(cx - width / 2, horizon + 0.002f)
            var x = cx - width / 2
            while (x <= cx + width / 2) {
                val t = (x - (cx - width / 2)) / width
                val shape = sin(t * PI.toFloat()).pow(0.7f)
                val n = ValueNoise.fbm(x * 9f, seedInt + 83, 3)
                path.lineTo(x, horizon - height * shape * (0.7f + 0.6f * n))
                x += 0.002f
            }
            path.lineTo(cx + width / 2, horizon + 0.002f)
            path.close()
            c.drawPath(path, p)
        }
        // Soft swell lines across the water.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(px, 0.001f) }
        for (i in 0 until 26) {
            val y = horizon + 0.02f + (i / 26f).pow(1.5f) * 0.5f
            line.color = withAlpha(blend(pal.far, pal.glow, 0.4f), 0.06f + 0.05f * (i % 3))
            val path = Path()
            var x = worldLeft
            path.moveTo(x, y)
            while (x <= worldRight) {
                path.lineTo(x, y + 0.004f * sin(x * (14f + i) + i))
                x += 0.01f
            }
            c.drawPath(path, line)
        }
    }

    private fun shore(c: Canvas, w: WorldRect) {
        // Rocks rise towards the far sides of the world, so opening the phone reveals the coast.
        val path = Path()
        val step = max(px * 2f, 0.0015f)
        val l = w.left - 0.02f
        val r = w.right + 0.02f
        fun shoreY(x: Float): Float {
            val d = abs(x - fx)
            val rise = ((d - 0.22f) / 0.6f).coerceIn(0f, 1f).pow(1.4f)
            val n = ValueNoise.fbm(x * 6f, seedInt + 91, 4)
            return 1.0f - 0.03f - rise * 0.32f - n * 0.03f
        }
        path.moveTo(l, w.bottom + 0.1f)
        var x = l
        while (x <= r) {
            path.lineTo(x, shoreY(x))
            x += step
        }
        path.lineTo(r, w.bottom + 0.1f)
        path.close()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0.65f, 0f, 1.05f, blend(pal.near, pal.mid, 0.3f), pal.near, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        // Foam along the waterline.
        val foam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(px * 1.5f, 0.0015f)
            color = withAlpha(pal.accent, 0.35f)
        }
        val crest = Path()
        x = l
        crest.moveTo(x, shoreY(x) - 0.004f)
        while (x <= r) {
            crest.lineTo(x, shoreY(x) - 0.004f - 0.002f * sin(x * 40f))
            x += step
        }
        c.drawPath(crest, foam)
    }

    // ---------------------------------------------------------------- space

    private fun spaceBackdrop(c: Canvas, w: WorldRect) {
        val p = Paint()
        p.shader = RadialGradient(fx, fy, 1.3f, intArrayOf(pal.skyBottom, pal.skyMid, pal.skyTop), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(w.left - 1f, w.top - 1f, w.right + 1f, w.bottom + 1f, p)
        val r = SeededRandom(spec.seed).fork(101)
        val colors = listOf(pal.far, pal.mid, pal.accent, pal.glow)
        repeat(9) { i ->
            val x = r.range(-1.2f, 1.2f)
            val y = r.range(-0.1f, 1.1f)
            val radius = r.range(0.22f, 0.55f)
            radial(c, x, y, radius, colors[i % colors.size], r.range(0.12f, 0.3f))
        }
        // A brighter wisp crossing behind the focal object ties the scene together.
        radial(c, fx - 0.2f, fy + 0.1f, 0.6f, pal.far, 0.25f)
    }

    private fun planet(c: Canvas) {
        val radius = 0.095f
        val tilt = -14f
        val rx = radius * 2.05f
        val ry = radius * 0.42f
        val oval = RectF(fx - rx, fy - ry, fx + rx, fy + ry)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        fun rings(start: Float) {
            c.save()
            c.rotate(tilt, fx, fy)
            for (i in 0 until 5) {
                val inset = i * radius * 0.07f
                val o = RectF(oval.left + inset, oval.top + inset * 0.2f, oval.right - inset, oval.bottom - inset * 0.2f)
                ring.strokeWidth = radius * (0.05f - i * 0.006f)
                ring.color = withAlpha(blend(pal.accent, pal.glow, i / 5f), 0.55f - i * 0.07f)
                c.drawArc(o, start, 180f, false, ring)
            }
            c.restore()
        }

        radial(c, fx, fy, radius * 2.6f, pal.accent, 0.18f)
        rings(180f)
        val body = Paint(Paint.ANTI_ALIAS_FLAG)
        body.shader = LinearGradient(
            fx - radius, fy - radius, fx + radius, fy + radius,
            intArrayOf(blend(pal.glow, pal.mid, 0.35f), pal.mid, blend(pal.near, 0xFF000000.toInt(), 0.4f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(fx, fy, radius, body)
        // Cloud bands, clipped to the disc.
        c.save()
        c.clipPath(Path().apply { addCircle(fx, fy, radius, Path.Direction.CW) })
        val band = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = SeededRandom(spec.seed).fork(102)
        for (i in 0 until 7) {
            val y = fy - radius + (i + 0.5f) * (2 * radius / 7f) + r.range(-0.005f, 0.005f)
            band.color = withAlpha(if (i % 2 == 0) pal.far else pal.glow, 0.12f + 0.08f * r.nextFloat())
            c.drawRect(fx - radius, y - radius * 0.06f, fx + radius, y + radius * 0.06f, band)
        }
        // Night side.
        val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        shade.shader = RadialGradient(fx + radius * 0.9f, fy + radius * 0.8f, radius * 1.6f, withAlpha(0xFF000000.toInt(), 0.7f), withAlpha(0xFF000000.toInt(), 0f), Shader.TileMode.CLAMP)
        c.drawCircle(fx, fy, radius, shade)
        c.restore()
        rings(0f)
    }

    private fun glints(c: Canvas) {
        val r = SeededRandom(spec.seed).fork(111)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        repeat(34) {
            val x = r.range(worldLeft, worldRight)
            val y = r.range(-0.05f, 1.05f)
            val s = r.range(0.006f, 0.02f)
            if (dist(x, y, fx, fy) < 0.25f) return@repeat
            val a = r.range(0.4f, 0.9f)
            radial(c, x, y, s * 1.6f, pal.glow, a * 0.45f)
            p.strokeWidth = max(px, s * 0.08f)
            p.color = withAlpha(0xFFFFFFFF.toInt(), a)
            c.drawLine(x - s, y, x + s, y, p)
            c.drawLine(x, y - s, x, y + s, p)
        }
        // Two small moons drifting in the foreground.
        repeat(2) { i ->
            val angle = r.range(0f, 2f * PI.toFloat())
            val d = r.range(0.3f, 0.6f)
            val mx = fx + cos(angle) * d
            val my = (fy + sin(angle) * d * 0.7f).coerceIn(0.1f, 0.9f)
            val mr = 0.012f + i * 0.006f
            val body = Paint(Paint.ANTI_ALIAS_FLAG)
            body.shader = RadialGradient(mx - mr * 0.4f, my - mr * 0.4f, mr * 1.5f, blend(pal.glow, pal.mid, 0.4f), pal.near, Shader.TileMode.CLAMP)
            c.drawCircle(mx, my, mr, body)
        }
    }

    // ---------------------------------------------------------------- city

    private fun skyline(
        c: Canvas, base: Float, minH: Float, maxH: Float, salt: Long, color: Int, windows: Float,
        clearFocus: Float = 0f,
    ) {
        val r = SeededRandom(spec.seed).fork(salt)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val lit = Paint().apply { this.color = withAlpha(blend(pal.glow, pal.accent, 0.35f), 0.85f) }
        val litAccent = Paint().apply { this.color = withAlpha(pal.accent, 0.8f) }
        var x = worldLeft
        while (x < worldRight) {
            val bw = r.range(0.035f, 0.085f)
            val gap = r.range(0f, 0.012f)
            var h = r.range(minH, maxH) * (if (r.chance(0.12f)) 1.35f else 1f)
            val windowSeed = r.nextLong()
            val hasSpire = r.chance(0.2f)
            val left = x
            x += bw + gap
            val centre = left + bw / 2
            if (clearFocus > 0f && abs(centre - fx) < clearFocus) continue
            // Never cover the moon: cap the building just below it.
            if (abs(centre - fx) < 0.09f) h = min(h, base - (fy + 0.075f))
            if (h <= 0.01f) continue
            val top = base - h
            c.drawRect(left, top, left + bw, base + 0.2f, body)
            if (hasSpire) c.drawRect(centre - bw * 0.04f, top - h * 0.18f, centre + bw * 0.04f, top, body)
            if (windows > 0f) {
                val wr = SeededRandom(windowSeed)
                val ww = bw * 0.12f
                val wh = 0.006f
                var wy = top + 0.01f
                while (wy < min(base + 0.15f, 1.05f)) {
                    var wx = left + bw * 0.12f
                    while (wx + ww < left + bw - bw * 0.08f) {
                        if (wr.chance(windows)) c.drawRect(wx, wy, wx + ww, wy + wh, if (wr.chance(0.15f)) litAccent else lit)
                        wx += ww * 2f
                    }
                    wy += wh * 2.4f
                }
            }
        }
    }

    // ---------------------------------------------------------------- abstract

    private fun abstractBackdrop(c: Canvas, w: WorldRect) {
        val p = Paint()
        p.shader = LinearGradient(-1f, -0.2f, 1f, 1.2f, intArrayOf(pal.skyTop, pal.skyMid, pal.skyBottom), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(w.left - 1f, w.top - 1f, w.right + 1f, w.bottom + 1f, p)
        val r = SeededRandom(spec.seed).fork(121)
        val colors = listOf(pal.far, pal.accent, pal.mid, pal.glow, pal.skyBottom)
        repeat(8) { i ->
            radial(c, r.range(-1.3f, 1.3f), r.range(-0.1f, 1.1f), r.range(0.3f, 0.7f), colors[i % colors.size], r.range(0.25f, 0.5f))
        }
    }

    private fun ribbons(c: Canvas) {
        val r = SeededRandom(spec.seed).fork(122)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pairs = listOf(pal.far to pal.accent, pal.mid to pal.glow, pal.accent to pal.skyBottom, pal.near to pal.mid)
        for (i in 0 until 4) {
            val centre = 0.22f + i * 0.2f + r.range(-0.04f, 0.04f)
            val amp = r.range(0.06f, 0.14f)
            val f = r.range(1.6f, 3.2f)
            val ph = r.range(0f, 6.28f)
            val thick = r.range(0.04f, 0.09f)
            fun yAt(xx: Float) = centre + amp * sin(xx * f + ph) + 0.04f * (ValueNoise.fbm(xx * 1.5f, seedInt + i * 13, 3) - 0.5f)
            fun tAt(xx: Float) = thick * (0.6f + 0.4f * sin(xx * f * 0.7f + ph * 1.3f))
            fun band(scale: Float): Path {
                val path = Path()
                var x = worldLeft
                path.moveTo(x, yAt(x) - tAt(x) * scale)
                while (x <= worldRight) {
                    path.lineTo(x, yAt(x) - tAt(x) * scale); x += 0.008f
                }
                while (x >= worldLeft) {
                    path.lineTo(x, yAt(x) + tAt(x) * scale); x -= 0.008f
                }
                path.close()
                return path
            }
            val (a, b) = pairs[i]
            // Silk: a wide faint halo, a softer body and a denser core give soft edges without blur.
            for ((scale, strength) in listOf(1.9f to 0.18f, 1.3f to 0.4f, 1f to 0.85f)) {
                p.shader = LinearGradient(
                    -1.2f, 0f, 1.2f, 0f,
                    intArrayOf(withAlpha(a, 0.8f * strength), withAlpha(b, strength), withAlpha(a, 0.75f * strength)),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.MIRROR,
                )
                c.drawPath(band(scale), p)
            }
            // A fine sheen along the middle of the ribbon.
            val sheen = Path()
            var x = worldLeft
            sheen.moveTo(x, yAt(x) - tAt(x) * 0.35f)
            while (x <= worldRight) {
                sheen.lineTo(x, yAt(x) - tAt(x) * 0.35f); x += 0.008f
            }
            c.drawPath(sheen, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(px * 1.2f, 0.0012f)
                color = withAlpha(pal.glow, 0.35f)
            })
        }
    }

    private fun orb(c: Canvas) {
        radial(c, fx, fy, 0.32f, pal.glow, 0.42f)
        radial(c, fx, fy, 0.12f, pal.accent, 0.5f)
        val core = Paint(Paint.ANTI_ALIAS_FLAG)
        core.shader = RadialGradient(fx - 0.01f, fy - 0.012f, 0.05f, intArrayOf(0xFFFFFFFF.toInt(), pal.glow, withAlpha(pal.accent, 0.9f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(fx, fy, 0.045f, core)
        // A thin orbit line that sweeps through both screens.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(px * 1.5f, 0.0018f)
            shader = LinearGradient(fx - 1f, 0f, fx + 1f, 0f, intArrayOf(withAlpha(pal.glow, 0f), withAlpha(pal.glow, 0.8f), withAlpha(pal.glow, 0f)), null, Shader.TileMode.CLAMP)
        }
        c.save()
        c.rotate(-18f, fx, fy)
        c.drawOval(RectF(fx - 0.9f, fy - 0.11f, fx + 0.9f, fy + 0.11f), line)
        c.restore()
    }

    // ---------------------------------------------------------------- helpers

    private fun radial(c: Canvas, x: Float, y: Float, radius: Float, color: Int, alpha: Float) {
        if (radius <= 0f || alpha <= 0f) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            x, y, radius,
            intArrayOf(withAlpha(color, alpha), withAlpha(color, alpha * 0.45f), withAlpha(color, 0f)),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(x, y, radius, p)
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        fun withAlpha(color: Int, alpha: Float): Int {
            val base = (color ushr 24) and 0xFF
            val a = (base * alpha.coerceIn(0f, 1f)).roundToInt()
            return (color and 0x00FFFFFF) or (a shl 24)
        }

        fun blend(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            fun ch(shift: Int) = (((a ushr shift) and 0xFF) * (1 - k) + ((b ushr shift) and 0xFF) * k).roundToInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
