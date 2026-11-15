package com.foldmotion.core.motion

import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.core.scene.WorldRect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the drops of a liquid glass wallpaper are, frame by frame. The fold animation of a glass
 * wallpaper is not an effect laid over it: it is the drops themselves moving.
 *
 *  - Closed, the drops sit gathered over the right part of the main screen, which is what the
 *    cover screen shows (the closed phone lies there), merged into one liquid cluster.
 *  - Opening (main screen, OPEN), they split apart and flow out across the screen, right to
 *    left like the Fold's front panel, one after another on a soft spring, and settle.
 *  - Closing (cover screen, CLOSE), they flow back together from wherever they were and merge.
 *  - Between folds they drift and breathe slowly (whole turns per ten minutes, so the motion
 *    never jumps), shift with tilt by depth (parallax), and the light on their rims turns as the
 *    phone leans. A half-open hinge draws them a little towards the fold.
 *
 * Positions are given in the main screen's frame (u, v in 0..1 of its width and height) and
 * radii in world units (the main screen's height is 1); gathered positions lie inside the cover
 * window of every supported phone (u from about 0.52 to 0.98). Pure: the same inputs give the
 * same frame, so any moment can be redrawn.
 */
object GlassLayout {

    const val MAX_SHAPES = 6

    /** Length of the opening (main) and closing (cover) motion. */
    const val OPEN_MS = 1_500
    const val CLOSE_MS = 1_300

    /** Progress delay between one drop and the next, so they leave one after another. */
    private const val STAGGER = 0.055f

    /** A capsule: centre ([u], [v]), radius [r], half length [half] along [angle] degrees. */
    data class Drop(val u: Float, val v: Float, val r: Float, val half: Float = 0f, val angle: Float = 0f)

    /** One drop of a form: where it sits closed and open, and how near it is (0 far .. 1 near). */
    data class Element(val gathered: Drop, val spread: Drop, val depth: Float)

    class Form(
        val elements: List<Element>,
        /** Colour anchors c0..c3 of the field behind the glass, (u, v) pairs, closed and open. */
        val anchorsGathered: FloatArray,
        val anchorsSpread: FloatArray,
        /** How far apart drops still melt into each other (smooth-union radius, world units). */
        val melt: Float = 0.035f,
    ) {
        /** Departure order of each drop: the one with the longest way to go leaves first. */
        val order: IntArray = run {
            val byTravel = elements.indices.sortedByDescending { i ->
                val e = elements[i]
                kotlin.math.hypot(e.spread.u - e.gathered.u, e.spread.v - e.gathered.v)
            }
            IntArray(elements.size).also { o -> byTravel.forEachIndexed { rank, i -> o[i] = rank } }
        }
    }

    private val ANCHORS_GATHERED = floatArrayOf(0.15f, 0.10f, 0.62f, 0.18f, 0.90f, 0.58f, 0.58f, 0.92f)
    private val ANCHORS_SPREAD = floatArrayOf(0.95f, 0.06f, 0.10f, 0.20f, 0.88f, 0.52f, 0.28f, 0.95f)

    val FORMS: Map<String, Form> = linkedMapOf(
        // Six round drops of different sizes, a diagonal cascade when open.
        "drops" to Form(
            listOf(
                Element(Drop(0.700f, 0.62f, 0.115f), Drop(0.230f, 0.70f, 0.165f), 0.8f),
                Element(Drop(0.640f, 0.40f, 0.055f), Drop(0.380f, 0.30f, 0.080f), 0.4f),
                Element(Drop(0.800f, 0.33f, 0.095f), Drop(0.680f, 0.25f, 0.135f), 0.7f),
                Element(Drop(0.860f, 0.50f, 0.055f), Drop(0.610f, 0.62f, 0.085f), 0.5f),
                Element(Drop(0.830f, 0.80f, 0.070f), Drop(0.870f, 0.80f, 0.100f), 0.6f),
                Element(Drop(0.620f, 0.17f, 0.038f), Drop(0.100f, 0.13f, 0.050f), 0.2f),
            ),
            ANCHORS_GATHERED, ANCHORS_SPREAD, melt = 0.045f,
        ),
        // Rounded pills at angles, stacked when closed.
        "pills" to Form(
            listOf(
                Element(Drop(0.700f, 0.18f, 0.050f, 0.075f, -12f), Drop(0.270f, 0.29f, 0.075f, 0.120f, -32f), 0.6f),
                Element(Drop(0.800f, 0.345f, 0.045f, 0.065f, 10f), Drop(0.690f, 0.19f, 0.060f, 0.100f, 22f), 0.4f),
                Element(Drop(0.710f, 0.51f, 0.060f, 0.090f, -10f), Drop(0.540f, 0.55f, 0.090f, 0.160f, -26f), 0.9f),
                Element(Drop(0.660f, 0.675f, 0.046f, 0.060f, 14f), Drop(0.200f, 0.78f, 0.065f, 0.090f, 36f), 0.5f),
                Element(Drop(0.780f, 0.84f, 0.052f, 0.078f, -16f), Drop(0.810f, 0.81f, 0.070f, 0.110f, -40f), 0.7f),
                Element(Drop(0.900f, 0.60f, 0.030f), Drop(0.920f, 0.45f, 0.045f), 0.3f),
            ),
            ANCHORS_GATHERED, ANCHORS_SPREAD, melt = 0.035f,
        ),
        // One large lens with small moons around it.
        "orbit" to Form(
            listOf(
                Element(Drop(0.750f, 0.50f, 0.150f), Drop(0.500f, 0.50f, 0.250f), 0.9f),
                Element(Drop(0.640f, 0.24f, 0.045f), Drop(0.140f, 0.20f, 0.060f), 0.3f),
                Element(Drop(0.880f, 0.29f, 0.055f), Drop(0.880f, 0.17f, 0.080f), 0.5f),
                Element(Drop(0.870f, 0.73f, 0.038f), Drop(0.860f, 0.83f, 0.050f), 0.4f),
                Element(Drop(0.640f, 0.75f, 0.058f), Drop(0.160f, 0.85f, 0.090f), 0.6f),
                Element(Drop(0.950f, 0.42f, 0.026f), Drop(0.960f, 0.53f, 0.036f), 0.2f),
            ),
            ANCHORS_GATHERED, ANCHORS_SPREAD, melt = 0.05f,
        ),
    )

    /** Base turns per ten minutes of each drop's drift, and of the colour field's. */
    private val DRIFT_TURNS = intArrayOf(13, 17, 11, 19, 15, 23)
    private val FIELD_TURNS = intArrayOf(7, 9, 5, 11)

    /** Everything the glass shader needs for one frame. Reused between frames. */
    class Frame {
        /** Per drop: capsule ends (ax, ay, bx, by) in world units; unused drops are far away. */
        val seg = FloatArray(MAX_SHAPES * 4)

        /** Per drop: (radius, depth, 0, 0); radius 0 means unused. */
        val rad = FloatArray(MAX_SHAPES * 4)

        /** Colour anchors c0..c3 as world (x, y) pairs. */
        val anchors = FloatArray(8)

        /** Direction towards the light in the screen plane (y down). */
        var lightX = -0.55f
        var lightY = -0.83f

        /** Smooth-union radius. */
        var melt = 0.035f
    }

    /**
     * How far drop [index] of [count] has spread (0 gathered, 1 open; a spring overshoots
     * slightly) at linear [progress] of this screen's fold motion; null progress is the rest.
     */
    fun spreadOf(index: Int, count: Int, kind: ScreenKind, progress: Float?): Float {
        if (progress == null) return if (kind == ScreenKind.MAIN) 1f else 0f
        val p = progress.coerceIn(0f, 1f)
        val lastDelay = STAGGER * (count - 1).coerceAtLeast(0)
        // [index] is the departure order: the drop that travels furthest leaves first, opening and
        // closing alike, so the cluster gathers from the far side in.
        val local = ((p - STAGGER * index) / (1f - lastDelay)).coerceIn(0f, 1f)
        val duration = if (kind == ScreenKind.MAIN) OPEN_MS else CLOSE_MS
        val eased = if (local >= 1f) 1f else SPRING.value(local * duration / 1000f * 0.9f)
        return if (kind == ScreenKind.MAIN) eased else 1f - eased
    }

    private val SPRING = SpringSpec.settlingIn(OPEN_MS, 0.72f)

    /**
     * Fills [out] for one frame of [spec] on [kind]. [main] is the main screen's world rect;
     * tilt is in [-1, 1]; [hinge] 0 (flat) .. 1 (half open), main screen only.
     */
    fun frame(
        spec: LiquidSpec,
        main: WorldRect,
        kind: ScreenKind,
        progress: Float?,
        timeMs: Long,
        tiltX: Float = 0f,
        tiltY: Float = 0f,
        hinge: Float = 0f,
        creaseX: Float = main.left + main.width / 2f,
        out: Frame = Frame(),
    ): Frame {
        val form = FORMS[spec.form] ?: FORMS.getValue("drops")
        val n = form.elements.size.coerceAtMost(MAX_SHAPES)
        val t = (timeMs / 1000.0) % LiquidSpec.PERIOD_SECONDS
        val k = 2.0 * PI / LiquidSpec.PERIOD_SECONDS
        val seedPhase = spec.seed * 0.7548777
        val tx = if (spec.tilt) tiltX.coerceIn(-1f, 1f) else 0f
        val ty = if (spec.tilt) tiltY.coerceIn(-1f, 1f) else 0f
        val h = if (spec.hinge) hinge.coerceIn(0f, 1f) else 0f
        var spreadSum = 0f
        for (i in 0 until MAX_SHAPES) {
            val o = i * 4
            if (i >= n) {
                out.seg[o] = -10f; out.seg[o + 1] = -10f; out.seg[o + 2] = -10f; out.seg[o + 3] = -10f
                out.rad[o] = 0f; out.rad[o + 1] = 0f; out.rad[o + 2] = 0f; out.rad[o + 3] = 0f
                continue
            }
            val e = form.elements[i]
            val s = spreadOf(form.order[i], n, kind, progress)
            spreadSum += s
            val a = e.gathered
            val b = e.spread
            var u = lerp(a.u, b.u, s)
            var v = lerp(a.v, b.v, s)
            // Mid-flight the drops stretch a little along their path, like liquid pulled apart.
            val flight = 4f * s * (1f - s)
            val r0 = lerp(a.r, b.r, s)
            val way = kotlin.math.hypot((b.u - a.u) * main.width, (b.v - a.v) * main.height)
            val half = lerp(a.half, b.half, s) + flight * kotlin.math.min(0.15f * way, 0.35f * r0)
            val travel = kotlin.math.atan2((b.v - a.v).toDouble(), ((b.u - a.u) * main.width).toDouble())
            val shapeAngle = Math.toRadians(lerp(a.angle, b.angle, s).toDouble())
            // Blend the drop's own angle towards its direction of travel while it flies.
            val angle = if (a.half + b.half > 0f) shapeAngle else travel
            val turns = (DRIFT_TURNS[i % DRIFT_TURNS.size] * spec.speed).let { kotlin.math.max(1, Math.round(it)) }
            val ph = t * k * turns + seedPhase * (i + 1)
            val drift = 0.008f + 0.008f * e.depth
            u += (drift * cos(ph)).toFloat() / main.width
            v += (drift * 0.8f * sin(ph * 1.0 + 1.3)).toFloat()
            val r = r0 * (1f + 0.025f * sin(ph * 2 + 0.7).toFloat())
            // Parallax: nearer drops shift further with tilt.
            u += tx * 0.022f * (0.4f + e.depth) / main.width
            v += ty * 0.018f * (0.4f + e.depth)
            var x = main.left + u * main.width
            val y = main.top + v * main.height
            if (kind == ScreenKind.MAIN) x += (creaseX - x) * 0.16f * h
            val cx = cos(angle).toFloat() * half
            val cy = sin(angle).toFloat() * half
            out.seg[o] = x - cx; out.seg[o + 1] = y - cy; out.seg[o + 2] = x + cx; out.seg[o + 3] = y + cy
            out.rad[o] = r * (1f - 0.08f * h)
            out.rad[o + 1] = e.depth
            out.rad[o + 2] = 0f
            out.rad[o + 3] = 0f
        }
        val spread = if (n > 0) spreadSum / n else 1f
        for (j in 0 until 4) {
            val turns = (FIELD_TURNS[j] * spec.speed).let { kotlin.math.max(1, Math.round(it)) }
            val ph = t * k * turns + seedPhase * (j + 3)
            val u = lerp(form.anchorsGathered[2 * j], form.anchorsSpread[2 * j], spread) + 0.05f * cos(ph).toFloat()
            val v = lerp(form.anchorsGathered[2 * j + 1], form.anchorsSpread[2 * j + 1], spread) + 0.05f * sin(ph * 1.0 + 0.9).toFloat()
            out.anchors[2 * j] = main.left + u * main.width
            out.anchors[2 * j + 1] = main.top + v * main.height
        }
        // The light sits up and to the left, and swings as the phone leans.
        val la = Math.toRadians(-123.0) + tx * 0.55 - ty * 0.25
        out.lightX = cos(la).toFloat()
        out.lightY = sin(la).toFloat()
        out.melt = form.melt
        return out
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
