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

data class Choreography(val open: MotionSpec, val close: MotionSpec, val ambient: AmbientSpec, val style: FoldStyle)

/**
 * Designs the fold animations and the living motion of a scene.
 *
 * Each [FoldStyle] is a different idea, not a different speed: a hinge unfolding from the cover
 * slice, a page swinging in perspective, layers rising out of the dark, a jump out of light
 * speed, a wave of flipping tiles, a band of scanning light, an iris opening from the focal
 * point. OPEN plays on the main screen when the phone opens, CLOSE on the cover when it closes;
 * the two variants of a style share the idea but are shaped for their screen.
 *
 * Opaque layers only ever zoom in (scale >= 1 about a point inside the frame), so they keep
 * covering the frame; only transparent layers slide.
 *
 * AMBIENT: per family, what keeps living afterwards: stars that twinkle and occasionally fall,
 * glints on water, sand in the wind, fireflies, flowing light, a breathing halo.
 */
object ChoreographyComposer {

    fun compose(input: ChoreoInput, style: FoldStyle = FoldStyle.AUTO): Choreography {
        val resolved = FoldStyle.resolve(style, input.family, input.seed)
        return Choreography(open(input, resolved), close(input, resolved), ambient(input), resolved)
    }

    /** Per-screen context shared by the style builders. */
    private class Ctx(val input: ChoreoInput, val kind: String) {
        val main = kind == MotionSpec.KIND_OPEN
        val lively = input.intensity == MotionIntensity.MEDIUM
        val gain = if (lively) 1.35f else 1f
        val seed = (input.seed and 0xFFFF).toInt()
        val night = input.time == TimeOfDay.NIGHT || input.family == SceneFamily.SPACE
        val focus = input.frames.focusIn(if (main) ScreenKind.MAIN else ScreenKind.COVER)
        val fx = focus.first
        val fy = focus.second
        val layers = if (main) input.mainLayers else input.coverLayers
        val frame = if (main) input.frames.main else input.frames.cover
        /** Width over height of this screen's frame. */
        val aspect = frame.width / frame.height
        fun ms(main: Int, cover: Int, livelyExtra: Int) = (if (this.main) main else cover) + if (lively) livelyExtra else 0
    }

    fun open(input: ChoreoInput, style: FoldStyle = FoldStyle.UNFOLD): MotionSpec = build(Ctx(input, MotionSpec.KIND_OPEN), style)

    fun close(input: ChoreoInput, style: FoldStyle = FoldStyle.IRIS): MotionSpec = build(Ctx(input, MotionSpec.KIND_CLOSE), style)

    private fun build(c: Ctx, style: FoldStyle): MotionSpec {
        val concrete = FoldStyle.resolve(style, c.input.family, c.input.seed)
        if (c.input.intensity == MotionIntensity.STATIC_END) return fadeOnly(c.kind, c.fx, c.fy).copy(style = concrete.name)
        val spec = when (concrete) {
            FoldStyle.UNFOLD -> if (c.main) unfoldOpen(c) else unfoldClose(c)
            FoldStyle.IRIS -> if (c.main) irisOpen(c) else irisClose(c)
            FoldStyle.BOOK -> book(c)
            FoldStyle.RISE -> rise(c)
            FoldStyle.WARP -> warp(c)
            FoldStyle.TILES -> tiles(c)
            FoldStyle.SWEEP -> sweep(c)
            FoldStyle.AUTO -> error("resolved above")
        }
        return spec.copy(schemaVersion = 3, style = concrete.name)
    }

    // ------------------------------------------------------------------ track helpers

    /** Opaque layers may only zoom in; anything else would uncover the frame edge. */
    private fun layer(l: MotionLayer, from: TrackPose, delay: Float = 0f, span: Float = 1f): MotionTrack {
        val safe = if (l.opaque) TrackPose(scale = maxOf(1f, from.scale)) else from
        return MotionTrack(MotionSampler.layerTarget(l.id), from = safe, delay = delay, span = span)
    }

    private fun glow(from: TrackPose, to: TrackPose, delay: Float = 0f, span: Float = 1f) =
        MotionTrack(MotionSpec.TARGET_GLOW, from = from, to = to, delay = delay, span = span)

    private fun dim(alpha: Float, span: Float = 1f) =
        MotionTrack(MotionSpec.TARGET_DIM, from = TrackPose(alpha = alpha), to = TrackPose.HIDDEN, span = span)

    private fun spec(c: Ctx, duration: Int, tracks: List<MotionTrack>, spring: SpringSpec?, reveal: RevealSpec?, particles: List<ParticleBurst>) = MotionSpec(
        schemaVersion = 3,
        kind = c.kind,
        durationMs = duration.coerceIn(MotionSpec.MIN_DURATION_MS, MotionSpec.MAX_DURATION_MS),
        easing = EasingSpec(0.2f, 0f, 0f, 1f),
        pivot = listOf(c.fx, c.fy),
        tracks = tracks,
        spring = spring,
        reveal = reveal,
        particles = particles.take(MotionSpec.MAX_BURSTS),
    )

    // ------------------------------------------------------------------ UNFOLD

    /** The scene widens from exactly the slice the cover was showing, like the phone itself. */
    private fun unfoldOpen(c: Ctx): MotionSpec {
        val duration = c.ms(1_100, 1_000, 300)
        val tracks = ArrayList<MotionTrack>()
        for (l in c.layers) {
            val scale = 1f + (0.05f + 0.13f * l.depth) * c.gain
            val rise = if (l.depth >= 0.99f) 0.035f * c.gain else 0f
            tracks += layer(l, TrackPose(scale = scale, ty = rise))
        }
        // Bloom is added light: strong at night, gentle in bright scenes where it would wash out.
        val bloom = if (c.night) 0.4f else 0.22f
        tracks += glow(TrackPose(scale = 0.35f, alpha = bloom), TrackPose(scale = 2.4f, alpha = 0f))
        tracks += dim(0.22f)
        return spec(
            c, duration, tracks, SpringSpec.settlingIn(duration, if (c.lively) 0.68f else 0.78f),
            RevealSpec(RevealSpec.UNFOLD, coverWindowInMain(c.input.frames), end = 0.62f, softness = 0.07f, shade = 0.9f, edgeGlow = 0.85f),
            listOf(
                ParticleBurst(
                    ParticleBurst.BURST, count = if (c.lively) 160 else 100, colors = burstColors(c.input),
                    origin = listOf(c.fx, c.fy), spread = 0.75f, sizeMin = 0.003f, sizeMax = 0.011f,
                    start = 0.02f, end = 0.95f, swirl = 0.25f, seed = c.seed + 1,
                ),
                ParticleBurst(
                    ParticleBurst.BURST, count = if (c.lively) 14 else 9, colors = listOf(hex(c.input.glow)),
                    origin = listOf(c.fx, c.fy), spread = 0.5f, sizeMin = 0.01f, sizeMax = 0.026f,
                    start = 0.05f, end = 1f, swirl = 0.1f, seed = c.seed + 2,
                ),
            ),
        )
    }

    /** The cover opens outwards from a thin seam in its middle, as if the halves met there. */
    private fun unfoldClose(c: Ctx): MotionSpec {
        val duration = c.ms(1_000, 1_000, 250)
        val tracks = ArrayList<MotionTrack>()
        for (l in c.layers) {
            val scale = 1f + (0.06f + 0.08f * l.depth) * c.gain
            val rise = if (l.depth >= 0.99f) 0.05f * c.gain else 0f
            tracks += layer(l, TrackPose(scale = scale, ty = rise))
        }
        tracks += glow(TrackPose(scale = 0.4f, alpha = if (c.night) 0.32f else 0.18f), TrackPose(scale = 2.2f, alpha = 0f))
        tracks += dim(0.2f)
        return spec(
            c, duration, tracks, SpringSpec.settlingIn(duration, if (c.lively) 0.7f else 0.8f),
            RevealSpec(RevealSpec.UNFOLD, listOf(0.45f, 0f, 0.55f, 1f), end = 0.78f, softness = 0.12f, shade = 0.9f, edgeGlow = 0.85f),
            listOf(
                ParticleBurst(
                    ParticleBurst.BURST, count = if (c.lively) 110 else 70, colors = burstColors(c.input),
                    origin = listOf(0.5f, c.fy), spread = 0.6f, sizeMin = 0.003f, sizeMax = 0.01f,
                    start = 0.03f, end = 0.95f, swirl = 0.15f, seed = c.seed + 5,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ IRIS

    /** Light spirals into the focal point in the dark, then the scene opens from it as a circle. */
    private fun irisOpen(c: Ctx): MotionSpec {
        val duration = c.ms(1_150, 1_000, 250)
        val tracks = ArrayList<MotionTrack>()
        for (l in c.layers) {
            val scale = 1f + (0.08f + 0.12f * l.depth) * c.gain
            val rise = if (l.depth >= 0.99f) 0.08f * c.gain else if (l.depth >= 0.5f) 0.03f * c.gain else 0f
            tracks += layer(l, TrackPose(scale = scale, ty = rise))
        }
        tracks += glow(TrackPose(scale = 2.4f, alpha = if (c.night) 0.32f else 0.2f), TrackPose(scale = 0.5f, alpha = 0f))
        tracks += dim(0.18f)
        val colors = burstColors(c.input)
        return spec(
            c, duration, tracks, SpringSpec.settlingIn(duration, if (c.lively) 0.7f else 0.8f),
            RevealSpec(RevealSpec.IRIS, listOf(c.fx, c.fy, 0.04f), start = 0.19f, end = 0.8f, softness = 0.1f, shade = 0.92f, edgeGlow = 0.85f),
            listOf(
                ParticleBurst(
                    ParticleBurst.CONVERGE, count = if (c.lively) 200 else 150, colors = colors,
                    origin = listOf(c.fx, c.fy), spread = 0.75f, sizeMin = 0.003f, sizeMax = 0.01f,
                    start = 0f, end = 0.32f, swirl = 1.3f, seed = c.seed + 3,
                ),
                ParticleBurst(
                    ParticleBurst.BURST, count = if (c.lively) 70 else 50, colors = colors,
                    origin = listOf(c.fx, c.fy), spread = 0.55f, sizeMin = 0.003f, sizeMax = 0.01f,
                    start = 0.25f, end = 1f, swirl = 0.2f, seed = c.seed + 4,
                ),
            ),
        )
    }

    private fun irisClose(c: Ctx): MotionSpec {
        val duration = c.ms(1_000, 1_000, 250)
        val tracks = ArrayList<MotionTrack>()
        for (l in c.layers) {
            val scale = 1f + (0.08f + 0.1f * l.depth) * c.gain
            val rise = if (l.depth >= 0.99f) 0.12f * c.gain else if (l.depth >= 0.5f) 0.04f * c.gain else 0f
            tracks += layer(l, TrackPose(scale = scale, ty = rise))
        }
        tracks += glow(TrackPose(scale = 2.2f, alpha = if (c.night) 0.3f else 0.18f), TrackPose(scale = 0.45f, alpha = 0f))
        tracks += dim(0.18f)
        val colors = burstColors(c.input)
        return spec(
            c, duration, tracks, SpringSpec.settlingIn(duration, if (c.lively) 0.7f else 0.8f),
            // Light gathers into the focal point in the dark first, then the iris opens from it.
            RevealSpec(RevealSpec.IRIS, listOf(c.fx, c.fy, 0.05f), start = 0.3f, end = 0.88f, softness = 0.12f, shade = 0.92f, edgeGlow = 0.8f),
            listOf(
                ParticleBurst(
                    ParticleBurst.CONVERGE, count = if (c.lively) 170 else 120, colors = colors,
                    origin = listOf(c.fx, c.fy), spread = 0.8f, sizeMin = 0.003f, sizeMax = 0.01f,
                    start = 0f, end = 0.45f, swirl = 1.3f, seed = c.seed + 3,
                ),
                ParticleBurst(
                    ParticleBurst.BURST, count = if (c.lively) 60 else 40, colors = colors,
                    origin = listOf(c.fx, c.fy), spread = 0.4f, sizeMin = 0.003f, sizeMax = 0.009f,
                    start = 0.36f, end = 1f, swirl = 0.2f, seed = c.seed + 4,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ BOOK

    /**
     * Main: the right half swings down flat in perspective around the centre crease, exactly the
     * way the panel just moved, and light escapes from the crease as it lands. Cover: the whole
     * scene swings shut around its left edge like a door.
     */
    private fun book(c: Ctx): MotionSpec {
        val duration = c.ms(1_200, 1_050, 250)
        val tracks = ArrayList<MotionTrack>()
        for (l in c.layers) tracks += layer(l, TrackPose(scale = 1f + (0.025f + 0.05f * l.depth) * c.gain), span = 0.95f)
        tracks += dim(0.22f, span = 0.85f)
        val reveal = if (c.main) {
            RevealSpec(RevealSpec.BOOK, listOf(0.5f, 104f), end = 0.8f, softness = 0.1f, shade = 0.8f, edgeGlow = 0.75f)
        } else {
            RevealSpec(RevealSpec.BOOK, listOf(0f, 88f), end = 0.82f, softness = 0.1f, shade = 0.85f, edgeGlow = 0.7f)
        }
        val particles = if (c.main) {
            listOf(
                ParticleBurst(
                    ParticleBurst.BURST, count = if (c.lively) 70 else 45, colors = burstColors(c.input),
                    origin = listOf(0.5f, 0.5f), spread = 0.42f, sizeMin = 0.0025f, sizeMax = 0.008f,
                    start = 0.5f, end = 1f, swirl = 0.05f, seed = c.seed + 6,
                ),
            )
        } else {
            emptyList()
        }
        return spec(c, duration, tracks, SpringSpec.settlingIn(duration, 0.82f), reveal, particles)
    }

    // ------------------------------------------------------------------ RISE

    /**
     * The scene wakes from the dark and its layers rise into place one after another, farthest
     * first, settling with a small bounce, while embers float up from below.
     */
    private fun rise(c: Ctx): MotionSpec {
        val duration = c.ms(1_350, 1_150, 250)
        val k = if (c.main) 1f else 0.8f
        val tracks = ArrayList<MotionTrack>()
        for (l in c.layers) {
            tracks += when {
                l.opaque || l.depth <= 0.01f -> layer(l, TrackPose(scale = 1f + 0.08f * c.gain), span = 0.9f)
                l.depth < 0.99f -> layer(l, TrackPose(ty = 0.24f * k * c.gain, alpha = 0.15f), delay = 0.06f, span = 0.78f)
                else -> layer(l, TrackPose(ty = 0.42f * k * c.gain, alpha = 0.15f), delay = 0.15f, span = 0.78f)
            }
        }
        tracks += glow(TrackPose(scale = 1.5f, alpha = if (c.night) 0.3f else 0.18f), TrackPose(scale = 0.8f, alpha = 0f), delay = 0.1f, span = 0.9f)
        tracks += dim(0.68f, span = 0.72f)
        val warm = listOf(hex(c.input.glow), "#FFD9A0", "#FFFFFF")
        return spec(
            c, duration, tracks, SpringSpec.settlingIn(duration, if (c.lively) 0.62f else 0.7f), null,
            listOf(
                ParticleBurst(
                    ParticleBurst.RISE, count = (if (c.lively) 120 else 80).let { if (c.main) it else it * 2 / 3 }, colors = warm,
                    origin = listOf(0.5f, 1.02f), spread = 0.55f, sizeMin = 0.0025f, sizeMax = 0.008f,
                    start = 0.1f, end = 1f, swirl = 0f, seed = c.seed + 7,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ WARP

    /**
     * A jump out of light speed: stars streak outwards from the focal point, a flash, and the
     * layers fly in from close up, the nearest from furthest, and settle.
     */
    private fun warp(c: Ctx): MotionSpec {
        val duration = c.ms(1_200, 1_050, 200)
        val tracks = ArrayList<MotionTrack>()
        for (l in c.layers) {
            val scale = 1f + (0.28f + 0.62f * l.depth) * c.gain
            tracks += layer(l, TrackPose(scale = scale, alpha = if (l.opaque) 1f else 0.4f), delay = 0.04f * l.depth, span = 0.92f)
        }
        tracks += glow(TrackPose(scale = 0.3f, alpha = 0.9f), TrackPose(scale = 3.2f, alpha = 0f), span = 0.5f)
        tracks += dim(0.62f, span = 0.55f)
        val colors = listOf("#FFFFFF", hex(c.input.glow), hex(c.input.accent))
        return spec(
            c, duration, tracks, SpringSpec.settlingIn(duration, 0.86f), null,
            listOf(
                ParticleBurst(
                    ParticleBurst.STREAK, count = (if (c.lively) 220 else 150).let { if (c.main) it else it * 2 / 3 }, colors = colors,
                    origin = listOf(c.fx, c.fy), spread = 0.95f, sizeMin = 0.0018f, sizeMax = 0.0045f,
                    start = 0f, end = 0.72f, swirl = 0f, seed = c.seed + 8,
                ),
                ParticleBurst(
                    ParticleBurst.BURST, count = if (c.lively) 45 else 30, colors = colors,
                    origin = listOf(c.fx, c.fy), spread = 0.5f, sizeMin = 0.003f, sizeMax = 0.009f,
                    start = 0.38f, end = 1f, swirl = 0.1f, seed = c.seed + 9,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ TILES

    /**
     * The scene assembles from square tiles flipping over in a wave: from the focal point on the
     * main screen, top to bottom like a split-flap board on the cover.
     */
    private fun tiles(c: Ctx): MotionSpec {
        val duration = c.ms(1_350, 1_200, 200)
        val rows = if (c.main) 7 else 8
        val cols = (rows * c.aspect).let { kotlin.math.round(it).toInt() }.coerceIn(2, RevealSpec.MAX_TILES_PER_SIDE)
        val tracks = ArrayList<MotionTrack>()
        for (l in c.layers) tracks += layer(l, TrackPose(scale = 1f + (0.03f + 0.04f * l.depth) * c.gain))
        tracks += dim(0.15f, span = 0.9f)
        val from = if (c.main) listOf(cols.toFloat(), rows.toFloat(), c.fx.coerceIn(0f, 1f), c.fy.coerceIn(0f, 1f), 0f)
        else listOf(cols.toFloat(), rows.toFloat(), 0.5f, 0f, 1f)
        return spec(
            c, duration, tracks, SpringSpec.settlingIn(duration, 0.85f),
            RevealSpec(RevealSpec.TILES, from, end = 0.9f, softness = 0f, shade = 0.94f, edgeGlow = 0.7f),
            emptyList(),
        )
    }

    // ------------------------------------------------------------------ SWEEP

    /**
     * A band of light crosses the screen and the scene appears behind it, its layers carried a
     * little along: diagonally across the main screen, top to bottom down the cover.
     */
    private fun sweep(c: Ctx): MotionSpec {
        val duration = c.ms(1_300, 1_100, 200)
        val angle = if (c.main) (if ((c.input.seed ushr 4) and 1L == 0L) 22f else 158f) else 90f
        val rad = Math.toRadians(angle.toDouble())
        val dx = kotlin.math.cos(rad).toFloat()
        val dy = kotlin.math.sin(rad).toFloat()
        val tracks = ArrayList<MotionTrack>()
        for (l in c.layers) {
            val shift = when {
                l.opaque || l.depth <= 0.01f -> 0f
                l.depth < 0.99f -> 0.035f
                else -> 0.065f
            }
            tracks += layer(l, TrackPose(scale = if (l.opaque) 1.04f else 1f, tx = -dx * shift, ty = -dy * shift), delay = 0.04f * l.depth, span = 0.96f)
        }
        tracks += dim(0.1f)
        return spec(
            c, duration, tracks, SpringSpec.settlingIn(duration, 0.88f),
            RevealSpec(RevealSpec.SWEEP, listOf(angle), end = 0.8f, softness = 0.16f, shade = 0.9f, edgeGlow = 1f),
            emptyList(),
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
