package com.foldmotion.core.motion

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Declarative description of one transition (motion/open.json, motion/close.json).
 *
 * All geometry is expressed in the scene's normalised frame: the visible screen spans [0,1] on
 * both axes. Each track moves from [MotionTrack.from] to [MotionTrack.to] along the eased
 * progress; the resting pose is always `to`. No code and no per-wallpaper shaders: the renderer
 * interprets these numbers, so a pack can never execute anything.
 */
@Serializable
data class MotionSpec(
    val schemaVersion: Int = 1,
    val kind: String,
    val durationMs: Int,
    val easing: EasingSpec = EasingSpec(),
    /** Zoom centre in the normalised frame, normally the scene focus point. */
    val pivot: List<Float> = listOf(0.5f, 0.5f),
    val tracks: List<MotionTrack>,
    /** v2: physical spring for layer tracks. When set it replaces [easing] and may overshoot. */
    val spring: SpringSpec? = null,
    /** v2: how the scene is uncovered: from the cover strip outwards (open) or from the focus (close). */
    val reveal: RevealSpec? = null,
    /** v2: short-lived light particles that belong to this transition. */
    val particles: List<ParticleBurst> = emptyList(),
    /** v3: the [FoldStyle] this transition was designed as (never AUTO); null for older packs. */
    val style: String? = null,
) {
    val pivotX: Float get() = pivot.getOrElse(0) { 0.5f }
    val pivotY: Float get() = pivot.getOrElse(1) { 0.5f }

    /** Eased progress for layer tracks. Exactly 1 at the end so the last frame is the rest pose. */
    fun ease(p: Float): Float {
        if (p >= 1f) return 1f
        if (p <= 0f) return 0f
        return spring?.value(p * durationMs / 1000f) ?: easing.ease(p)
    }

    /** Lowest and highest eased value over the whole transition (springs overshoot). */
    fun easeRange(samples: Int = 96): Pair<Float, Float> {
        var lo = 0f
        var hi = 1f
        for (i in 0..samples) {
            val v = ease(i / samples.toFloat())
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return lo to hi
    }

    companion object {
        const val KIND_OPEN = "OPEN"
        const val KIND_CLOSE = "CLOSE"
        const val TARGET_LAYER_PREFIX = "layer:"
        const val TARGET_DIM = "overlay:dim"
        const val TARGET_GLOW = "overlay:glow"

        /**
         * Blueprint section 11 started at 250-650 ms. The product owner asked for a visible fold
         * animation, so v2 allows up to 1.8 s; the device test decides the final numbers.
         */
        const val MIN_DURATION_MS = 250
        const val MAX_DURATION_MS = 1800

        const val MAX_BURSTS = 4
        const val MAX_PARTICLES_PER_BURST = 300
    }
}

/**
 * Damped spring from 0 to 1 (unit mass). dampingRatio below 1 overshoots slightly, which reads as
 * a physical, settling motion rather than a mechanical ease.
 */
@Serializable
data class SpringSpec(
    val dampingRatio: Float = 0.8f,
    val stiffness: Float = 60f,
) {
    fun value(tSec: Float): Float {
        val zeta = dampingRatio.coerceIn(0.3f, 1f)
        val w0 = sqrt(stiffness.coerceAtLeast(0.01f))
        val t = tSec.coerceAtLeast(0f)
        return if (zeta >= 0.999f) {
            1f - exp(-w0 * t) * (1f + w0 * t)
        } else {
            val wd = w0 * sqrt(1f - zeta * zeta)
            1f - exp(-zeta * w0 * t) * (cos(wd * t) + (zeta * w0 / wd) * sin(wd * t))
        }
    }

    companion object {
        /** Stiffness that settles to within 0.3% at [settleFraction] of [durationMs]. */
        fun settlingIn(durationMs: Int, dampingRatio: Float, settleFraction: Float = 0.9f): SpringSpec {
            val seconds = durationMs / 1000f * settleFraction
            // Envelope decay e^(-c) must leave < 0.3% after the polynomial or oscillation factor,
            // which grows as damping approaches critical (1 + w0 t instead of a bounded sine).
            val c = 6.2f + (dampingRatio - 0.7f).coerceAtLeast(0f) * 8f
            val w0 = c / (dampingRatio * seconds)
            return SpringSpec(dampingRatio, w0 * w0)
        }
    }
}

/**
 * How the scene is uncovered. [from] depends on [type]:
 *  - UNFOLD: rect [l,t,r,b] the scene starts from (the slice the cover was showing); it widens to
 *    the whole frame.
 *  - IRIS: [cx, cy, radius] (radius as a fraction of the frame diagonal) growing until the frame
 *    is uncovered.
 *  - SWEEP (v3): [angleDeg], the direction a band of light travels (0 = left to right, 90 = top
 *    to bottom); the scene is lit behind the band.
 *  - TILES (v3): [cols, rows, originX, originY, axis]; the scene is cut into tiles that flip into
 *    place in a wave spreading from the origin, around the vertical (axis 0) or horizontal (1) axis.
 *  - BOOK (v3): [hingeX, startAngleDeg]; everything right of the hinge is a page that swings
 *    flat from startAngle, in perspective, like the phone's own panel.
 */
@Serializable
data class RevealSpec(
    val type: String,
    val from: List<Float>,
    /** Progress at which the scene is fully uncovered. */
    val end: Float = 0.7f,
    /** Progress at which uncovering begins; lets light gather first on close. */
    val start: Float = 0f,
    /** Width of the soft edge, fraction of the frame. */
    val softness: Float = 0.08f,
    /** Darkness of the still-covered area, 0..1. */
    val shade: Float = 0.85f,
    /** Strength of the light running along the moving edge, 0..1. */
    val edgeGlow: Float = 0.7f,
) {
    /** Reveal progress uses Material's emphasised deceleration: fast start, gentle landing. */
    fun progress(p: Float): Float {
        val span = (end - start).coerceAtLeast(0.05f)
        // The unfold snaps open like a hinge; the iris and the tile wave breathe open evenly;
        // the sweep keeps a steady pace like a scanning light.
        val curve = when (type) {
            IRIS, TILES -> STANDARD
            SWEEP -> SWEEP_CURVE
            BOOK -> PAGE_CURVE
            RIPPLE -> RIPPLE_CURVE
            FLOW -> SWEEP_CURVE
            GLASS -> STANDARD
            // A thin seam opens evenly, or it would be gone in a frame or two. The opening slice
            // against the right edge (0.8.1) travels with the panel's steady swing so its right
            // to left movement reads; other slices (older or imported packs) keep their curve.
            UNFOLD -> when {
                from.size == 4 && from[2] - from[0] < 0.2f -> STANDARD
                from.size == 4 && from[2] >= 0.999f && from[0] > 0.2f -> SWEEP_CURVE
                else -> EMPHASIZED
            }
            else -> EMPHASIZED
        }
        return curve.ease(((p - start) / span).coerceIn(0f, 1f))
    }

    /** Checks the shape of [from] for [type]; unknown types are invalid. */
    fun isWellFormed(): Boolean = when (type) {
        UNFOLD -> from.size == 4 && from.all { it in 0f..1f } && from[2] > from[0] && from[3] > from[1]
        IRIS -> from.size == 3 && from.all { it in 0f..1.5f }
        SWEEP -> from.size == 1 && from[0] in -360f..360f
        TILES -> from.size == 5 && from[0] in 1f..MAX_TILES_PER_SIDE.toFloat() && from[1] in 1f..MAX_TILES_PER_SIDE.toFloat() &&
            from[0] == kotlin.math.floor(from[0]) && from[1] == kotlin.math.floor(from[1]) &&
            from[2] in 0f..1f && from[3] in 0f..1f && (from[4] == 0f || from[4] == 1f)
        BOOK -> from.size == 2 && from[0] in 0f..0.9f && from[1] in 10f..170f
        RIPPLE -> from.size == 3 && from[0] in 0f..1f && from[1] in 0f..1f && from[2] in 0f..1f
        FLOW -> from.size == 2 && from[0] in -360f..360f && from[1] in 0f..1f
        GLASS -> from.size == 1 && from[0] in 0f..1f
        else -> false
    }

    /** Drawn with a liquid shader where the phone has one (renderer 5). */
    val isLiquid: Boolean get() = type in LIQUID_TYPES

    companion object {
        const val UNFOLD = "UNFOLD"
        const val IRIS = "IRIS"
        const val SWEEP = "SWEEP"
        const val TILES = "TILES"
        const val BOOK = "BOOK"
        /** v5, liquid: [originX, originY, strength]; rings spread from the origin and uncover the scene. */
        const val RIPPLE = "RIPPLE"
        /** v5, liquid: [angle, strength]; the scene pours in along angle with a viscous, glossy front. */
        const val FLOW = "FLOW"
        /** v5, liquid: [strength]; the scene clears from behind refracting, frosted glass. */
        const val GLASS = "GLASS"
        val LIQUID_TYPES = setOf(RIPPLE, FLOW, GLASS)
        const val MAX_TILES_PER_SIDE = 12
        val EMPHASIZED = EasingSpec(0.05f, 0.7f, 0.1f, 1f)
        val STANDARD = EasingSpec(0.4f, 0f, 0.2f, 1f)
        val SWEEP_CURVE = EasingSpec(0.3f, 0.12f, 0.3f, 1f)
        /** A page falls slowly at first, then gathers speed and lands softly. */
        val PAGE_CURVE = EasingSpec(0.32f, 0f, 0.12f, 1f)
        /** Water spreads briskly from the drop and slows as the rings widen. */
        val RIPPLE_CURVE = EasingSpec(0.3f, 0.12f, 0.3f, 1f)
    }
}

/**
 * BURST: particles fly out from [origin]. CONVERGE: they spiral into it. STREAK (v3): lines of
 * light rush outwards from it, longer the faster they go, like stars at light speed. RISE (v3):
 * motes float up from a band centred on [origin], [spread] wide, like embers or bubbles.
 * Positions in the normalised frame; [spread] and sizes are fractions of the frame diagonal (of
 * the frame width for RISE) and of its short side.
 */
@Serializable
data class ParticleBurst(
    val mode: String,
    val count: Int,
    val colors: List<String>,
    val origin: List<Float>,
    val spread: Float,
    val sizeMin: Float,
    val sizeMax: Float,
    val start: Float = 0f,
    val end: Float = 1f,
    val swirl: Float = 0f,
    val seed: Int = 1,
) {
    companion object {
        const val BURST = "BURST"
        const val CONVERGE = "CONVERGE"
        const val STREAK = "STREAK"
        const val RISE = "RISE"
        val MODES = setOf(BURST, CONVERGE, STREAK, RISE)
    }
}

/** Cubic bezier easing. y control points are limited to [0,1] so progress never overshoots. */
@Serializable
data class EasingSpec(
    val x1: Float = 0.33f,
    val y1: Float = 0f,
    val x2: Float = 0.2f,
    val y2: Float = 1f,
) {
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // Solve bezierX(u) = x by bisection: monotonic for x1,x2 in [0,1], and allocation free.
        var lo = 0f
        var hi = 1f
        var u = x
        repeat(24) {
            val bx = bezier(u, x1, x2)
            if (abs(bx - x) < 1e-5f) return bezier(u, y1, y2)
            if (bx < x) lo = u else hi = u
            u = (lo + hi) * 0.5f
        }
        return bezier(u, y1, y2)
    }

    private fun bezier(u: Float, p1: Float, p2: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * u * p1 + 3f * inv * u * u * p2 + u * u * u
    }
}

@Serializable
data class MotionTrack(
    /** "layer:<id>", "overlay:dim" or "overlay:glow". */
    val target: String,
    val from: TrackPose,
    val to: TrackPose = TrackPose(),
    /** v3: progress at which this track starts moving, so layers can arrive one after another. */
    val delay: Float = 0f,
    /** v3: share of the transition this track takes; delay + span never exceeds 1. */
    val span: Float = 1f,
) {
    /** The track's own progress in [0,1] for the transition's linear progress [p]. */
    fun local(p: Float): Float {
        if (delay <= 0f && span >= 1f) return p
        return ((p - delay) / span.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
    }
}

/**
 * For layers: scale about the pivot, then translate by (tx, ty) in frame fractions.
 * For overlays: alpha is the opacity and scale multiplies the glow radius.
 */
@Serializable
data class TrackPose(
    val scale: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
    val alpha: Float = 1f,
) {
    fun lerp(other: TrackPose, t: Float): TrackPose = TrackPose(
        scale = scale + (other.scale - scale) * t,
        tx = tx + (other.tx - tx) * t,
        ty = ty + (other.ty - ty) * t,
        alpha = alpha + (other.alpha - alpha) * t,
    )

    companion object {
        val REST = TrackPose()
        val HIDDEN = TrackPose(alpha = 0f)
    }
}

/** Evaluates a spec at a linear progress in [0,1]. */
object MotionSampler {

    fun poseFor(spec: MotionSpec?, target: String, linearProgress: Float): TrackPose {
        val track = spec?.tracks?.firstOrNull { it.target == target }
            ?: return if (target.startsWith(MotionSpec.TARGET_LAYER_PREFIX)) TrackPose.REST else TrackPose.HIDDEN
        // Overlays (glow, dim) always use the curve: a spring overshoot on opacity looks like flicker.
        // A delayed track runs its spring in its own (shorter) window, so it still settles.
        val local = track.local(linearProgress)
        val eased = if (target.startsWith(MotionSpec.TARGET_LAYER_PREFIX)) spec.ease(local) else spec.easing.ease(local)
        return track.from.lerp(track.to, eased)
    }

    fun layerTarget(layerId: String): String = MotionSpec.TARGET_LAYER_PREFIX + layerId
}

/**
 * Checks that an opaque layer still covers the whole frame under a pose, so no edge or
 * background colour can flash during a transition.
 *
 * The layer occupies [left, right] x [top, bottom] at rest (it may extend past the frame as
 * overscan). Coverage is linear in (scale, tx, ty), so checking both ends of a track is enough
 * for every eased value in between.
 */
object MotionSafety {

    private const val EPS = 1e-3f

    fun covers(
        pose: TrackPose,
        pivotX: Float,
        pivotY: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Boolean {
        if (pose.scale <= 0f) return false
        val l = pivotX + pose.scale * (left - pivotX) + pose.tx
        val r = pivotX + pose.scale * (right - pivotX) + pose.tx
        val t = pivotY + pose.scale * (top - pivotY) + pose.ty
        val b = pivotY + pose.scale * (bottom - pivotY) + pose.ty
        return l <= EPS && r >= 1f - EPS && t <= EPS && b >= 1f - EPS
    }

    /** Smallest scale about the pivot that keeps the given rect covering the frame. */
    fun minSafeScale(
        pivotX: Float,
        pivotY: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Float {
        fun ratio(numerator: Float, denominator: Float) =
            if (denominator <= 1e-6f) 0f else numerator / denominator
        return maxOf(
            ratio(pivotX, pivotX - left),
            ratio(1f - pivotX, right - pivotX),
            ratio(pivotY, pivotY - top),
            ratio(1f - pivotY, bottom - pivotY),
        )
    }
}
