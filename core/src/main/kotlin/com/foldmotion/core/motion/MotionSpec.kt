package com.foldmotion.core.motion

import kotlinx.serialization.Serializable
import kotlin.math.abs

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
) {
    val pivotX: Float get() = pivot.getOrElse(0) { 0.5f }
    val pivotY: Float get() = pivot.getOrElse(1) { 0.5f }

    companion object {
        const val KIND_OPEN = "OPEN"
        const val KIND_CLOSE = "CLOSE"
        const val TARGET_LAYER_PREFIX = "layer:"
        const val TARGET_DIM = "overlay:dim"
        const val TARGET_GLOW = "overlay:glow"

        /** Blueprint section 11: 250-650 ms, to be tuned on the device. */
        const val MIN_DURATION_MS = 250
        const val MAX_DURATION_MS = 650
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
)

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
        val eased = spec.easing.ease(linearProgress)
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
