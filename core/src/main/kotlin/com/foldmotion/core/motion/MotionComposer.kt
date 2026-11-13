package com.foldmotion.core.motion

import kotlinx.serialization.Serializable
import kotlin.math.max

/** How much movement a pack carries. Blueprint section 20 uses the same three levels. */
@Serializable
enum class MotionIntensity { STATIC_END, LOW, MEDIUM }

/** Layer facts the composer needs: where the layer sits at rest and how deep it is. */
data class MotionLayer(
    val id: String,
    /** 0 = farthest (moves least), 1 = nearest (moves most). */
    val depth: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val opaque: Boolean,
)

/**
 * Builds the open/close motion for a scene.
 *
 * OPEN widens: the main scene starts slightly zoomed in on the focal element and relaxes to rest,
 * nearer layers travelling further, so the extra field of view appears to unfold.
 * CLOSE gathers: the cover scene starts slightly wide and settles inward on the focal element
 * while a glow converges on it.
 * STATIC_END has no layer movement, only a short dim fade, for motion-sensitive users.
 */
object MotionComposer {

    fun open(intensity: MotionIntensity, focusX: Float, focusY: Float, layers: List<MotionLayer>): MotionSpec {
        val tracks = ArrayList<MotionTrack>()
        if (intensity != MotionIntensity.STATIC_END) {
            val gain = if (intensity == MotionIntensity.MEDIUM) 1.6f else 1f
            for (layer in layers) {
                val scale = 1f + (0.045f + 0.085f * layer.depth) * gain
                tracks += MotionTrack(MotionSampler.layerTarget(layer.id), from = TrackPose(scale = scale))
            }
            tracks += MotionTrack(
                MotionSpec.TARGET_GLOW,
                from = TrackPose(scale = 0.7f, alpha = 0.22f * gain.coerceAtMost(1.3f)),
                to = TrackPose(scale = 1.5f, alpha = 0f),
            )
        }
        tracks += MotionTrack(
            MotionSpec.TARGET_DIM,
            from = TrackPose(alpha = if (intensity == MotionIntensity.STATIC_END) 0.35f else 0.26f),
            to = TrackPose.HIDDEN,
        )
        return MotionSpec(
            kind = MotionSpec.KIND_OPEN,
            durationMs = when (intensity) {
                MotionIntensity.STATIC_END -> 260
                MotionIntensity.LOW -> 480
                MotionIntensity.MEDIUM -> 580
            },
            pivot = listOf(focusX, focusY),
            tracks = tracks,
        )
    }

    fun close(intensity: MotionIntensity, focusX: Float, focusY: Float, layers: List<MotionLayer>): MotionSpec {
        val tracks = ArrayList<MotionTrack>()
        if (intensity != MotionIntensity.STATIC_END) {
            val gain = if (intensity == MotionIntensity.MEDIUM) 1.6f else 1f
            for (layer in layers) {
                val wanted = 1f - (0.02f + 0.05f * layer.depth) * gain
                // Zooming out an opaque layer is only safe as far as its overscan allows.
                val floor = if (layer.opaque) {
                    MotionSafety.minSafeScale(focusX, focusY, layer.left, layer.top, layer.right, layer.bottom)
                } else {
                    0f
                }
                val scale = max(wanted, floor + 0.004f).coerceAtMost(1f)
                tracks += MotionTrack(MotionSampler.layerTarget(layer.id), from = TrackPose(scale = scale))
            }
            tracks += MotionTrack(
                MotionSpec.TARGET_GLOW,
                from = TrackPose(scale = 1.7f, alpha = 0.3f * gain.coerceAtMost(1.3f)),
                to = TrackPose(scale = 0.6f, alpha = 0f),
            )
        }
        tracks += MotionTrack(
            MotionSpec.TARGET_DIM,
            from = TrackPose(alpha = if (intensity == MotionIntensity.STATIC_END) 0.35f else 0.22f),
            to = TrackPose.HIDDEN,
        )
        return MotionSpec(
            kind = MotionSpec.KIND_CLOSE,
            durationMs = when (intensity) {
                MotionIntensity.STATIC_END -> 250
                MotionIntensity.LOW -> 380
                MotionIntensity.MEDIUM -> 460
            },
            pivot = listOf(focusX, focusY),
            tracks = tracks,
        )
    }
}
