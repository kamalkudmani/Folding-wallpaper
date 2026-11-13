package com.foldmotion.core.scene

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Colours taken from a reference photo. The photo itself is never kept. */
data class ReferenceColors(
    /** Cluster centres as 0xAARRGGBB, largest share first. */
    val swatches: List<Int>,
    /** Share of the photo each swatch covers, same order, sums to 1. */
    val weights: List<Float>,
    /** Weighted mean lightness in [0,1]. */
    val lightness: Float,
    /**
     * Weighted mean chroma (max - min channel) in [0,1]. Chroma, not HSL saturation: near-white
     * and near-black pixels have a high HSL saturation but no visible colour.
     */
    val saturation: Float,
) {
    val isDark: Boolean get() = lightness < 0.45f
}

/**
 * Builds a scene palette from a reference photo on the device.
 *
 * Not image generation: it clusters the photo's pixels into a few dominant colours, then lays
 * the main hue and the strongest accent onto the fixed palette roles (sky, light source, three
 * terrain depths, accent) with lightness ramps that keep the scene readable. Dark photos give a
 * night palette, bright photos a daylight one.
 */
object ReferencePalette {

    const val CLUSTERS = 5
    private const val ITERATIONS = 10

    /** @param pixels 0xAARRGGBB values, typically a ~96 px wide downscale of the photo. */
    fun extract(pixels: IntArray): ReferenceColors? {
        val opaque = pixels.filter { (it ushr 24) > 128 }
        if (opaque.size < CLUSTERS) return null
        val rgb = Array(opaque.size) { i -> floatArrayOf(r(opaque[i]), g(opaque[i]), b(opaque[i])) }

        // Deterministic start: centres at evenly spaced lightness quantiles.
        val byLight = rgb.indices.sortedBy { lum(rgb[it]) }
        val centres = Array(CLUSTERS) { k -> rgb[byLight[((k + 0.5f) / CLUSTERS * byLight.size).toInt().coerceAtMost(byLight.size - 1)]].copyOf() }
        val assign = IntArray(rgb.size)
        repeat(ITERATIONS) {
            for (i in rgb.indices) assign[i] = nearest(rgb[i], centres)
            val sums = Array(CLUSTERS) { FloatArray(3) }
            val counts = IntArray(CLUSTERS)
            for (i in rgb.indices) {
                val c = assign[i]
                counts[c]++
                for (ch in 0..2) sums[c][ch] += rgb[i][ch]
            }
            for (k in 0 until CLUSTERS) if (counts[k] > 0) for (ch in 0..2) centres[k][ch] = sums[k][ch] / counts[k]
        }
        val counts = IntArray(CLUSTERS)
        for (i in rgb.indices) counts[assign[i]]++
        val order = (0 until CLUSTERS).filter { counts[it] > 0 }.sortedByDescending { counts[it] }
        val total = rgb.size.toFloat()
        val swatches = order.map { toColor(centres[it]) }
        val weights = order.map { counts[it] / total }
        var light = 0f
        var sat = 0f
        for (i in order.indices) {
            light += Hsl.of(swatches[i]).l * weights[i]
            sat += chroma(swatches[i]) * weights[i]
        }
        return ReferenceColors(swatches, weights, light, sat)
    }

    /** Palette and the time of day it implies. */
    fun toPalette(ref: ReferenceColors): Pair<Palette, TimeOfDay> {
        val hsl = ref.swatches.map { Hsl.of(it) }
        val chroma = ref.swatches.map { chroma(it) }
        // Main hue: the largest cluster with visible colour, else the largest cluster.
        val mainIdx = hsl.indices.firstOrNull { chroma[it] > 0.08f } ?: 0
        // Accent: most colourful cluster weighted by presence, preferably a different hue.
        val accentIdx = hsl.indices
            .filter { it != mainIdx }
            .maxByOrNull { i ->
                val hueGap = hueDistance(hsl[i].h, hsl[mainIdx].h) / 180f
                chroma[i] * sqrt(ref.weights[i]) * (0.5f + hueGap)
            } ?: mainIdx
        val h1 = hsl[mainIdx].h
        val h2 = hsl[accentIdx].h
        // Between two hues, blend the colours themselves (as a real sky gradient does) instead of
        // averaging the hue angle, which can land on a colour neither hue contains (teal + sand
        // would give green).
        fun between(l: Float, sat: Float) = blendRgb(Hsl(h1, sat, l).toColor(), Hsl(h2, sat, l).toColor(), 0.5f)
        // Keep a grey photo grey, but never so saturated that it looks synthetic.
        val s = (0.25f + ref.saturation * 1.1f).coerceIn(0.12f, 0.7f)

        return if (ref.isDark) {
            Palette(
                id = "reference_night",
                skyTop = Hsl(h1, s, 0.07f).toColor(),
                skyMid = Hsl(h1, s * 0.95f, 0.17f).toColor(),
                skyBottom = between(0.34f, s),
                glow = Hsl(h2, min(0.65f, s + 0.1f), 0.86f).toColor(),
                far = Hsl(h1, s * 0.7f, 0.22f).toColor(),
                mid = Hsl(h1, s * 0.7f, 0.13f).toColor(),
                near = Hsl(h1, s * 0.75f, 0.05f).toColor(),
                accent = Hsl(h2, min(0.85f, s + 0.25f), 0.62f).toColor(),
            ) to TimeOfDay.NIGHT
        } else {
            Palette(
                id = "reference_day",
                skyTop = Hsl(h1, s * 0.9f, 0.42f).toColor(),
                skyMid = between(0.66f, s),
                skyBottom = Hsl(h2, s, 0.82f).toColor(),
                glow = Hsl(h2, min(0.7f, s + 0.15f), 0.92f).toColor(),
                far = Hsl(h1, s * 0.6f, 0.55f).toColor(),
                mid = Hsl(h1, s * 0.6f, 0.37f).toColor(),
                near = Hsl(h1, s * 0.7f, 0.18f).toColor(),
                accent = Hsl(h2, min(0.85f, s + 0.25f), 0.5f).toColor(),
            ) to TimeOfDay.DUSK
        }
    }

    private fun nearest(p: FloatArray, centres: Array<FloatArray>): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (k in centres.indices) {
            val dr = p[0] - centres[k][0]
            val dg = p[1] - centres[k][1]
            val db = p[2] - centres[k][2]
            // Weighted RGB distance approximates perceived difference at negligible cost.
            val d = 2f * dr * dr + 4f * dg * dg + 3f * db * db
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        return best
    }

    private fun r(c: Int) = ((c ushr 16) and 0xFF) / 255f
    private fun g(c: Int) = ((c ushr 8) and 0xFF) / 255f
    private fun b(c: Int) = (c and 0xFF) / 255f
    private fun lum(p: FloatArray) = 0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2]

    private fun toColor(p: FloatArray): Int {
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ch(p[0]) shl 16) or (ch(p[1]) shl 8) or ch(p[2])
    }

    fun chroma(c: Int): Float {
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        return (max(r, max(g, b)) - min(r, min(g, b))) / 255f
    }

    fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    private fun blendRgb(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((((a ushr shift) and 0xFF) * (1 - t) + ((b ushr shift) and 0xFF) * t) + 0.5f).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Minimal HSL colour, enough for palette ramps. h in [0,360), s and l in [0,1]. */
data class Hsl(val h: Float, val s: Float, val l: Float) {
    fun toColor(): Int {
        val ss = s.coerceIn(0f, 1f)
        val ll = l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * ll - 1f)) * ss
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = ll - c / 2f
        fun ch(v: Float) = ((v + m).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }

    companion object {
        fun of(color: Int): Hsl {
            val r = ((color ushr 16) and 0xFF) / 255f
            val g = ((color ushr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val l = (mx + mn) / 2f
            if (mx == mn) return Hsl(0f, 0f, l)
            val d = mx - mn
            val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            val h = when (mx) {
                r -> ((g - b) / d + (if (g < b) 6f else 0f))
                g -> ((b - r) / d + 2f)
                else -> ((r - g) / d + 4f)
            } * 60f
            return Hsl(h, s, l)
        }
    }
}
