package com.foldmotion.core.motion

import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.scene.Celestial
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.core.scene.SceneFrames
import com.foldmotion.core.scene.SceneGeometry
import com.foldmotion.core.scene.TimeOfDay

/** Everything the choreographer needs to know about one pack. Colours are 0xAARRGGBB. */
data class ChoreoInput(
    val family: SceneFamily,
    val time: TimeOfDay,
    val celestial: Celestial,
    val intensity: MotionIntensity,
    val frames: SceneFrames,
    val mainLayers: List<MotionLayer>,
    val coverLayers: List<MotionLayer>,
    val glow: Int,
    val accent: Int,
    val seed: Long,
)

data class Choreography(val open: MotionSpec, val close: MotionSpec, val ambient: AmbientSpec)

/**
 * Designs the fold animations and the living motion of a scene.
 *
 * OPEN (main screen appears): the scene starts as the exact slice the cover was showing and
 * unfolds outwards from it, like the phone itself; depth layers settle on a spring, nearer ones
 * travelling further, and light bursts from the focal point.
 *
 * CLOSE (cover appears): light spirals into the focal point, then the scene opens from it as an
 * iris while the foreground rises into place.
 *
 * AMBIENT: per family, what keeps living afterwards: stars that twinkle and occasionally fall,
 * glints on water, sand in the wind, fireflies, flowing light, a breathing halo.
 */
object ChoreographyComposer {

    fun compose(input: ChoreoInput): Choreography =
        Choreography(open(input), close(input), ambient(input))

    // ------------------------------------------------------------------ open

    fun open(input: ChoreoInput): MotionSpec {
        val (fx, fy) = input.frames.focusIn(ScreenKind.MAIN)
        if (input.intensity == MotionIntensity.STATIC_END) return fadeOnly(MotionSpec.KIND_OPEN, fx, fy)

        val lively = input.intensity == MotionIntensity.MEDIUM
        val duration = if (lively) 1_400 else 1_100
        val gain = if (lively) 1.35f else 1f
        val tracks = ArrayList<MotionTrack>()
        for (layer in input.mainLayers) {
            val scale = 1f + (0.05f + 0.13f * layer.depth) * gain
            val rise = if (layer.depth >= 0.99f) 0.035f * gain else 0f
            tracks += MotionTrack(MotionSampler.layerTarget(layer.id), from = TrackPose(scale = scale, ty = rise))
        }
        // Bloom is added light: strong at night, gentle in bright scenes where it would wash out.
        val bloom = if (input.time == TimeOfDay.NIGHT || input.family == SceneFamily.SPACE) 0.4f else 0.22f
        tracks += MotionTrack(MotionSpec.TARGET_GLOW, from = TrackPose(scale = 0.35f, alpha = bloom), to = TrackPose(scale = 2.4f, alpha = 0f))
        tracks += MotionTrack(MotionSpec.TARGET_DIM, from = TrackPose(alpha = 0.22f), to = TrackPose.HIDDEN)

        val cover = coverWindowInMain(input.frames)
        val colors = burstColors(input)
        return MotionSpec(
            schemaVersion = 2,
            kind = MotionSpec.KIND_OPEN,
            durationMs = duration,
            easing = EasingSpec(0.2f, 0f, 0f, 1f),
            pivot = listOf(fx, fy),
            tracks = tracks,
            spring = SpringSpec.settlingIn(duration, if (lively) 0.68f else 0.78f),
            reveal = RevealSpec(RevealSpec.UNFOLD, cover, end = 0.62f, softness = 0.07f, shade = 0.9f, edgeGlow = 0.85f),
            particles = listOf(
                ParticleBurst(
                    ParticleBurst.BURST, count = if (lively) 160 else 100, colors = colors,
                    origin = listOf(fx, fy), spread = 0.75f, sizeMin = 0.003f, sizeMax = 0.011f,
                    start = 0.02f, end = 0.95f, swirl = 0.25f, seed = (input.seed and 0xFFFF).toInt() + 1,
                ),
                ParticleBurst(
                    ParticleBurst.BURST, count = if (lively) 14 else 9, colors = listOf(hex(input.glow)),
                    origin = listOf(fx, fy), spread = 0.5f, sizeMin = 0.01f, sizeMax = 0.026f,
                    start = 0.05f, end = 1f, swirl = 0.1f, seed = (input.seed and 0xFFFF).toInt() + 2,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ close

    fun close(input: ChoreoInput): MotionSpec {
        val (fx, fy) = input.frames.focusIn(ScreenKind.COVER)
        if (input.intensity == MotionIntensity.STATIC_END) return fadeOnly(MotionSpec.KIND_CLOSE, fx, fy)

        val lively = input.intensity == MotionIntensity.MEDIUM
        val duration = if (lively) 1_250 else 1_000
        val gain = if (lively) 1.3f else 1f
        val tracks = ArrayList<MotionTrack>()
        for (layer in input.coverLayers) {
            val scale = 1f + (0.08f + 0.1f * layer.depth) * gain
            val rise = if (layer.depth >= 0.99f) 0.12f * gain else if (layer.depth >= 0.5f) 0.04f * gain else 0f
            tracks += MotionTrack(MotionSampler.layerTarget(layer.id), from = TrackPose(scale = scale, ty = rise))
        }
        val gather = if (input.time == TimeOfDay.NIGHT || input.family == SceneFamily.SPACE) 0.3f else 0.18f
        tracks += MotionTrack(MotionSpec.TARGET_GLOW, from = TrackPose(scale = 2.2f, alpha = gather), to = TrackPose(scale = 0.45f, alpha = 0f))
        tracks += MotionTrack(MotionSpec.TARGET_DIM, from = TrackPose(alpha = 0.18f), to = TrackPose.HIDDEN)

        val colors = burstColors(input)
        val seed = (input.seed and 0xFFFF).toInt()
        return MotionSpec(
            schemaVersion = 2,
            kind = MotionSpec.KIND_CLOSE,
            durationMs = duration,
            easing = EasingSpec(0.2f, 0f, 0f, 1f),
            pivot = listOf(fx, fy),
            tracks = tracks,
            spring = SpringSpec.settlingIn(duration, if (lively) 0.7f else 0.8f),
            // Light gathers into the focal point in the dark first, then the iris opens from it.
            reveal = RevealSpec(RevealSpec.IRIS, listOf(fx, fy, 0.05f), start = 0.3f, end = 0.88f, softness = 0.12f, shade = 0.92f, edgeGlow = 0.8f),
            particles = listOf(
                ParticleBurst(
                    ParticleBurst.CONVERGE, count = if (lively) 170 else 120, colors = colors,
                    origin = listOf(fx, fy), spread = 0.8f, sizeMin = 0.003f, sizeMax = 0.01f,
                    start = 0f, end = 0.45f, swirl = 1.3f, seed = seed + 3,
                ),
                ParticleBurst(
                    ParticleBurst.BURST, count = if (lively) 60 else 40, colors = colors,
                    origin = listOf(fx, fy), spread = 0.4f, sizeMin = 0.003f, sizeMax = 0.009f,
                    start = 0.36f, end = 1f, swirl = 0.2f, seed = seed + 4,
                ),
            ),
        )
    }

    private fun fadeOnly(kind: String, fx: Float, fy: Float) = MotionSpec(
        schemaVersion = 2,
        kind = kind,
        durationMs = 320,
        pivot = listOf(fx, fy),
        tracks = listOf(MotionTrack(MotionSpec.TARGET_DIM, from = TrackPose(alpha = 0.4f), to = TrackPose.HIDDEN)),
    )

    /** The cover window expressed in the main screen's normalised frame. */
    fun coverWindowInMain(frames: SceneFrames): List<Float> {
        val m = frames.main
        val c = frames.cover
        fun nx(x: Float) = ((x - m.left) / m.width).coerceIn(0f, 1f)
        fun ny(y: Float) = ((y - m.top) / m.height).coerceIn(0f, 1f)
        return listOf(nx(c.left), ny(c.top), nx(c.right), ny(c.bottom))
    }

    private fun burstColors(input: ChoreoInput): List<String> {
        val white = "#FFFFFF"
        return when (input.family) {
            SceneFamily.DESERT -> listOf(hex(input.glow), "#FFE3A0", white)
            SceneFamily.OCEAN -> listOf(white, hex(input.glow), hex(input.accent))
            SceneFamily.CITY -> listOf(hex(input.accent), hex(input.glow), white)
            SceneFamily.ABSTRACT -> listOf(hex(input.accent), hex(input.glow))
            else -> listOf(white, hex(input.glow), hex(input.accent))
        }
    }

    // ------------------------------------------------------------------ ambient

    fun ambient(input: ChoreoInput): AmbientSpec {
        if (input.intensity == MotionIntensity.STATIC_END) return AmbientSpec(sway = 0f, tilt = 0f)
        val k = if (input.intensity == MotionIntensity.MEDIUM) 1.5f else 1f
        fun n(base: Int) = (base * k).toInt().coerceAtMost(AmbientSpec.MAX_COUNT)
        val seed = (input.seed and 0x7FFF).toInt()
        val m = input.frames.main.expand(0.04f)
        val horizon = SceneGeometry.horizonFor(input.family)
        val fx = input.frames.focusX
        val fy = input.frames.focusY
        val glow = hex(input.glow)
        val accent = hex(input.accent)
        val night = input.time == TimeOfDay.NIGHT || input.family == SceneFamily.SPACE
        val sky = listOf(m.left, m.top, m.right, horizon - 0.04f)
        val systems = ArrayList<AmbientSystem>()

        if (night && input.family != SceneFamily.ABSTRACT) {
            systems += AmbientSystem(
                AmbientSystem.TWINKLE, n(if (input.family == SceneFamily.SPACE) 110 else 70), listOf("#FFFFFF", glow, "#FFFFFF"),
                if (input.family == SceneFamily.SPACE) listOf(m.left, m.top, m.right, m.bottom) else sky,
                sizeMin = 0.0012f, sizeMax = 0.0042f, periodMs = 3_600, alpha = 0.95f, after = "far", seed = seed + 11,
            )
            if (input.family != SceneFamily.CITY && input.family != SceneFamily.MINIMAL) {
                systems += AmbientSystem(
                    AmbientSystem.SHOOTING_STAR, 1, listOf("#FFFFFF", glow),
                    listOf(m.left, m.top, m.right, (horizon - 0.2f).coerceAtLeast(m.top + 0.2f)),
                    sizeMin = 0.1f, sizeMax = 0.18f, speed = 0.9f, periodMs = 9_000, alpha = 0.95f, after = "far",
                    direction = listOf(-0.82f, 0.45f), seed = seed + 12,
                )
            }
        }
        when (input.family) {
            SceneFamily.MOUNTAINS -> if (input.time != TimeOfDay.DAY) {
                systems += AmbientSystem(
                    AmbientSystem.FIREFLY, n(18), listOf("#FFE38A", "#FFF4C2", glow),
                    listOf(m.left, 0.74f, m.right, 0.97f), sizeMin = 0.0018f, sizeMax = 0.0036f,
                    speed = 0.012f, periodMs = 3_200, alpha = 0.95f, after = "near", seed = seed + 13,
                )
            }
            SceneFamily.DESERT -> systems += AmbientSystem(
                AmbientSystem.DRIFT, n(70), listOf("#F4D9A0", glow), listOf(m.left, 0.6f, m.right, 1.02f),
                sizeMin = 0.0008f, sizeMax = 0.0022f, speed = 0.06f, periodMs = 2_000, alpha = 0.55f,
                after = "mid", direction = listOf(1f, -0.06f), seed = seed + 14,
            )
            SceneFamily.OCEAN -> systems += AmbientSystem(
                AmbientSystem.GLINT, n(90), listOf(glow, "#FFFFFF"),
                listOf(fx - 0.3f, horizon + 0.004f, fx + 0.3f, 1.05f), sizeMin = 0.008f, sizeMax = 0.04f,
                speed = 0.004f, periodMs = 1_800, alpha = 0.9f, after = "far", seed = seed + 15,
            )
            SceneFamily.CITY -> systems += AmbientSystem(
                AmbientSystem.BLINKER, 2, listOf("#FF5A5A", "#FFFFFF"), listOf(m.left, 0.06f, m.right, 0.38f),
                sizeMin = 0.0025f, sizeMax = 0.0035f, speed = 0.018f, periodMs = 1_300, alpha = 1f,
                after = "far", direction = listOf(1f, -0.02f), seed = seed + 16,
            )
            SceneFamily.SPACE -> systems += AmbientSystem(
                AmbientSystem.DRIFT, n(40), listOf(accent, glow), listOf(m.left, m.top, m.right, m.bottom),
                sizeMin = 0.0008f, sizeMax = 0.002f, speed = 0.008f, periodMs = 5_000, alpha = 0.5f,
                after = "mid", direction = listOf(0.6f, 0.25f), seed = seed + 17,
            )
            SceneFamily.ABSTRACT -> systems += AmbientSystem(
                AmbientSystem.FLOW, n(80), listOf(glow, accent, "#FFFFFF"), listOf(m.left, m.top, m.right, m.bottom),
                sizeMin = 0.0015f, sizeMax = 0.0045f, speed = 0.07f, periodMs = 7_000, alpha = 0.85f,
                after = "mid", seed = seed + 18,
            )
            SceneFamily.MINIMAL -> systems += AmbientSystem(
                AmbientSystem.DRIFT, n(14), listOf(glow), listOf(m.left, 0.1f, m.right, 1.0f),
                sizeMin = 0.004f, sizeMax = 0.01f, speed = 0.008f, periodMs = 9_000, alpha = 0.35f,
                after = "mid", direction = listOf(0.15f, -1f), seed = seed + 19,
            )
        }

        val halo = when {
            input.family == SceneFamily.ABSTRACT -> HaloSpec(fx, fy, 0.3f, glow, 0.14f, 0.36f, 4_500)
            input.family == SceneFamily.SPACE -> HaloSpec(fx, fy, 0.3f, accent, 0.06f, 0.18f, 7_000)
            input.celestial == Celestial.SUN -> HaloSpec(fx, fy, 0.24f, glow, 0.1f, 0.28f, 6_000)
            input.celestial != Celestial.NONE -> HaloSpec(fx, fy, 0.17f, glow, 0.08f, 0.24f, 5_500)
            else -> null
        }
        return AmbientSpec(
            fps = 30,
            sway = if (input.intensity == MotionIntensity.MEDIUM) 0.006f else 0.004f,
            tilt = if (input.intensity == MotionIntensity.MEDIUM) 0.018f else 0.012f,
            halo = halo,
            systems = systems.take(AmbientSpec.MAX_SYSTEMS),
        )
    }

    fun hex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)
}
