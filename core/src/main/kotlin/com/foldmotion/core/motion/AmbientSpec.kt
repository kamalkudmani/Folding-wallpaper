package com.foldmotion.core.motion

import kotlinx.serialization.Serializable

/**
 * motion/ambient.json: what keeps moving while the wallpaper is on screen, between folds.
 *
 * Positions and sizes are in scene-world units (see SceneGeometry), not in screen fractions, so a
 * shooting star or a firefly is at the same place in the world on the cover and on the main
 * screen. Every system is a pure function of (seed, index, time): no state, nothing to save, and
 * two engines on two displays draw the same sky at the same moment.
 */
@Serializable
data class AmbientSpec(
    val schemaVersion: Int = 1,
    /** Frame rate cap while only ambient motion runs. Transitions always run at the display rate. */
    val fps: Int = 30,
    /** Slow drift of the depth layers, world-independent fraction of the frame at depth 1. */
    val sway: Float = 0.004f,
    val swayPeriodMs: Int = 16_000,
    /** How far depth-1 layers follow the phone's tilt, fraction of the frame. */
    val tilt: Float = 0.012f,
    val halo: HaloSpec? = null,
    val systems: List<AmbientSystem> = emptyList(),
) {
    val isStill: Boolean get() = systems.isEmpty() && halo == null && sway == 0f

    companion object {
        const val FILE = "motion/ambient.json"
        const val MAX_SYSTEMS = 8
        const val MAX_COUNT = 300
        const val MAX_TOTAL = 800
        val FPS_RANGE = 12..60
        const val MAX_SWAY = 0.02f
        const val MAX_TILT = 0.04f
        val TYPES = setOf(
            AmbientSystem.TWINKLE, AmbientSystem.SHOOTING_STAR, AmbientSystem.FIREFLY, AmbientSystem.DRIFT,
            AmbientSystem.GLINT, AmbientSystem.BLINKER, AmbientSystem.FLOW,
        )
        val LAYERS = setOf("far", "mid", "near")
    }
}

/** Soft light around the focal element that slowly breathes. World coordinates. */
@Serializable
data class HaloSpec(
    val x: Float,
    val y: Float,
    val radius: Float,
    val color: String,
    val alphaMin: Float = 0.08f,
    val alphaMax: Float = 0.24f,
    val periodMs: Int = 6_000,
)

@Serializable
data class AmbientSystem(
    val type: String,
    val count: Int,
    val colors: List<String>,
    /** World rect [l, t, r, b] the system lives in. */
    val region: List<Float>,
    /** World units. For GLINT and SHOOTING_STAR this is the streak length. */
    val sizeMin: Float,
    val sizeMax: Float,
    /** World units per second. */
    val speed: Float = 0.02f,
    /** Typical cycle: twinkle period, glint blink, shooting star interval. */
    val periodMs: Int = 4_000,
    val alpha: Float = 0.9f,
    /** Drawn just after this layer, so stars stay behind mountains and fireflies in front. */
    val after: String = "far",
    /** Unit-ish direction for DRIFT, SHOOTING_STAR and BLINKER. */
    val direction: List<Float> = listOf(1f, 0f),
    val seed: Int = 1,
) {
    val left: Float get() = region.getOrElse(0) { 0f }
    val top: Float get() = region.getOrElse(1) { 0f }
    val right: Float get() = region.getOrElse(2) { 1f }
    val bottom: Float get() = region.getOrElse(3) { 1f }

    companion object {
        const val TWINKLE = "TWINKLE"
        const val SHOOTING_STAR = "SHOOTING_STAR"
        const val FIREFLY = "FIREFLY"
        const val DRIFT = "DRIFT"
        const val GLINT = "GLINT"
        const val BLINKER = "BLINKER"
        const val FLOW = "FLOW"
    }
}
