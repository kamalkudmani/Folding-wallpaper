package com.foldmotion.core.pack

import kotlinx.serialization.Serializable

/**
 * v6: a live liquid scene. Instead of a picture or a clip, the scene is computed on the GPU every
 * frame, defined in world space so the cover shows exactly the slice the main screen shows
 * around it. It never repeats within ten minutes, and it reacts to the phone.
 *
 * Two looks:
 *  - [LOOK_METAL] (renderer 6): domain-warped noise lit as a glossy molten height field; tilt
 *    makes it drift downhill, taps send out ripples, a half-open hinge makes it pool in the fold.
 *  - [LOOK_GLASS] (renderer 7): drops of clear liquid glass over a soft colour field, like the
 *    phone's own glass design: they bend the colours behind them, catch light on their rims and
 *    cast soft shadows. Their motion is the picture itself ([form]): gathered over the cover when
 *    the phone is closed, they flow apart across the main screen as it opens (right to left) and
 *    merge back together as it closes. Tilt turns their light and parallax, taps wobble them.
 *  - Live scenes (renderer 8): realistic places computed on the GPU, whose own content is the
 *    fold motion: [LOOK_OCEAN] (the sun or moon rises over rolling waves), [LOOK_SKY] (clouds
 *    part around the sun), [LOOK_AURORA] (the lights unfurl across the sky over a lake),
 *    [LOOK_RAIN] (fog clears from a rainy window over city lights) and [LOOK_SPACE] (sunrise
 *    sweeps over a planet). [form] picks the variant, [palette] its colours.
 *
 * The pack still carries one poster per screen (the liquid at time 0) for phones without runtime
 * shaders (Android 11 and 12), for thumbnails and for when motion is off.
 */
@Serializable
data class LiquidSpec(
    /** Four colours: deep shadow, body, swirl (raised parts) and highlight. */
    val palette: List<String>,
    /** Strength of the specular highlight, 0..1.5. */
    val specular: Float = 0.6f,
    /** Sharpness of the highlight (Blinn-Phong exponent), 4..160. */
    val gloss: Float = 44f,
    /** Overall exposure, 0.4..1.8. */
    val brightness: Float = 1f,
    /** Noise units per world unit (the main screen's long side is 1): bigger means smaller forms. 0.4..4. */
    val span: Float = 1.25f,
    /** Domain-warp strength: how much the liquid swirls into itself, 0..5. */
    val warp: Float = 2.6f,
    /** Flow speed, 0.25..4 (1 = the pace of the 0.7 liquid loops). */
    val speed: Float = 1f,
    /** Picks the liquid's shapes, 0..9999. */
    val seed: Int = 0,
    /** Drifts downhill as the phone tilts. */
    val tilt: Boolean = true,
    /** Ripples where the home screen is tapped. */
    val touch: Boolean = true,
    /** Pools along the fold while the phone is half open. */
    val hinge: Boolean = true,
    /** v7: [LOOK_METAL] or [LOOK_GLASS]. For glass, [palette] is the colour field behind the glass. */
    val look: String = LOOK_METAL,
    /** v7, glass: the arrangement of the drops and how they move (a key of GlassLayout.FORMS). */
    val form: String = "drops",
    /** v7, glass: colour of the glass itself. */
    val tint: String = "#FFFFFF",
    /** v7, glass: how much of [tint] the glass carries, 0 (clear) .. 0.6 (frosted colour). */
    val frost: Float = 0.08f,
    /** v7, glass: how strongly the glass bends what lies behind it, 0..2. */
    val refraction: Float = 1f,
) {
    val isGlass: Boolean get() = look == LOOK_GLASS

    /** A realistic live scene (renderer 8). */
    val isScene: Boolean get() = look in SCENE_LOOKS

    /** The content itself is the fold motion (glass and scenes): no layer motion on top. */
    val isIntrinsic: Boolean get() = look != LOOK_METAL

    fun isWellFormed(): Boolean =
        palette.size == 4 && palette.all(::isHexColor) &&
            specular in 0f..1.5f && gloss in 4f..160f && brightness in 0.4f..1.8f &&
            span in 0.4f..4f && warp in 0f..5f && speed in 0.25f..4f && seed in 0..9999 &&
            look in LOOKS && isHexColor(tint) && frost in 0f..0.6f && refraction in 0f..2f &&
            (!isGlass || form in com.foldmotion.core.motion.GlassLayout.FORMS) &&
            (!isScene || form in SCENE_FORMS.getValue(look))

    companion object {
        /** The flow repeats exactly after this long, so time never loses float precision. */
        const val PERIOD_SECONDS = 600

        /** Base angular frequencies (whole turns per period) of the four flow circles. */
        val BASE_TURNS = intArrayOf(60, 47, 71, 53)

        /** Whole turns per period for [speed], so the flow stays exactly periodic. */
        fun turns(speed: Float): IntArray = IntArray(4) { i -> maxOf(1, Math.round(BASE_TURNS[i] * speed)) }

        const val LOOK_METAL = "metal"
        const val LOOK_GLASS = "glass"
        const val LOOK_OCEAN = "ocean"
        const val LOOK_SKY = "sky"
        const val LOOK_AURORA = "aurora"
        const val LOOK_RAIN = "rain"
        const val LOOK_SPACE = "space"
        val SCENE_LOOKS = setOf(LOOK_OCEAN, LOOK_SKY, LOOK_AURORA, LOOK_RAIN, LOOK_SPACE)
        val LOOKS = setOf(LOOK_METAL, LOOK_GLASS) + SCENE_LOOKS

        /** Variants of each live scene, in the order the shader numbers them. */
        val SCENE_FORMS: Map<String, List<String>> = mapOf(
            LOOK_OCEAN to listOf("sun", "moon"),
            LOOK_SKY to listOf("day", "night"),
            LOOK_AURORA to listOf("lake"),
            LOOK_RAIN to listOf("city"),
            LOOK_SPACE to listOf("earth", "mars", "giant", "ice"),
        )

        /**
         * The realistic set that ships with the app (0.9.1): four of each scene. Palettes:
         * ocean and sky (zenith, horizon, sun, water or land), aurora (sky top, sky low, the
         * lights' two colours), rain (night top, street glow, two light colours), space (space
         * tint, nebula, atmosphere, surface).
         */
        val SCENES: Map<String, LiquidSpec> = linkedMapOf(
            "ocean_sunset" to scene(LOOK_OCEAN, "sun", listOf("#2B3A67", "#F29E6D", "#FFD9A0", "#0E2433"), 11, amount = 2.4f),
            "ocean_moon" to scene(LOOK_OCEAN, "moon", listOf("#03060F", "#1B2B4A", "#E8EEFF", "#050B16"), 23, amount = 1.6f),
            "ocean_tropic" to scene(LOOK_OCEAN, "sun", listOf("#1E6FD9", "#BFE6FF", "#FFF6E0", "#0C6E86"), 37, amount = 0.9f),
            "ocean_storm" to scene(LOOK_OCEAN, "sun", listOf("#2C3138", "#8A8F96", "#FFE6B8", "#1A2328"), 41, brightness = 0.95f, amount = 4.6f),
            "sky_golden" to scene(LOOK_SKY, "day", listOf("#3C5C9C", "#F7B267", "#FFE2B0", "#2A1E2E"), 5, amount = 2.6f),
            "sky_dawn" to scene(LOOK_SKY, "day", listOf("#6A7FC1", "#F6C1C8", "#FFF1E0", "#3B3552"), 17, amount = 2.3f),
            "sky_blue" to scene(LOOK_SKY, "day", listOf("#2F6FD0", "#A9D3F5", "#FFFBF0", "#33503A"), 29, amount = 2.0f),
            "sky_night" to scene(LOOK_SKY, "night", listOf("#04070F", "#1A2440", "#DDE6FF", "#05070C"), 53, amount = 2.6f),
            "aurora_green" to scene(LOOK_AURORA, "lake", listOf("#020611", "#0B2233", "#3DFFB0", "#7A5CFF"), 7),
            "aurora_violet" to scene(LOOK_AURORA, "lake", listOf("#05030F", "#1B1236", "#B46BFF", "#FF5FA8"), 19),
            "aurora_teal" to scene(LOOK_AURORA, "lake", listOf("#010A10", "#08303A", "#3FE0FF", "#3DFF8F"), 31),
            "aurora_crimson" to scene(LOOK_AURORA, "lake", listOf("#07030A", "#22101E", "#FF4F6D", "#5CFFB5"), 43),
            "rain_neon" to scene(LOOK_RAIN, "city", listOf("#05040C", "#2A1240", "#FF3FA4", "#3FD7FF"), 13),
            "rain_amber" to scene(LOOK_RAIN, "city", listOf("#06050A", "#3A2412", "#FFB347", "#FF6A3D"), 27),
            "rain_blue" to scene(LOOK_RAIN, "city", listOf("#030812", "#0E2440", "#6FB8FF", "#FFE08A"), 39),
            "rain_emerald" to scene(LOOK_RAIN, "city", listOf("#030A08", "#0E2A22", "#4DFFC1", "#FFD36E"), 47),
            "space_earth" to scene(LOOK_SPACE, "earth", listOf("#02030A", "#1C2A5A", "#6FB6FF", "#2E6FB0"), 3),
            "space_mars" to scene(LOOK_SPACE, "mars", listOf("#050204", "#3A1A2A", "#FF9A6A", "#B5532E"), 15),
            "space_giant" to scene(LOOK_SPACE, "giant", listOf("#030305", "#2A2040", "#FFD9A8", "#C9A27A"), 25),
            "space_ice" to scene(LOOK_SPACE, "ice", listOf("#02050A", "#10304A", "#A8F0FF", "#BFE3F2"), 35),
        )

        /** [amount] (0..5, stored in [warp]): clouds for ocean and sky, drops for rain, brightness of the lights for aurora. */
        private fun scene(look: String, form: String, palette: List<String>, seed: Int, brightness: Float = 1f, amount: Float = 2.6f) =
            LiquidSpec(palette, brightness = brightness, seed = seed, look = look, form = form, touch = false, hinge = false, warp = amount)

        /**
         * The liquid glass set that ships with the app (0.9). Light and dark colour fields, clear
         * and tinted glass, three arrangements.
         */
        val PRESETS: Map<String, LiquidSpec> = linkedMapOf(
            "daybreak" to glass(listOf("#FFD9C2", "#FF8F87", "#C7A8FF", "#8CC8FF"), "drops", seed = 3),
            "lagoon" to glass(listOf("#06304A", "#14B3C4", "#8BEBD8", "#2C66E8"), "orbit", seed = 17, frost = 0.06f, brightness = 1.05f),
            "orchid" to glass(listOf("#24093A", "#B548D9", "#FF7FC8", "#5A3CFF"), "pills", seed = 29, tint = "#FFE6FA", frost = 0.1f),
            "midnight" to glass(listOf("#04050C", "#1A2A70", "#6B3EEA", "#00BFFF"), "drops", seed = 41, frost = 0.05f, specular = 1.05f),
            "citrus" to glass(listOf("#FFEAA6", "#FFB24A", "#FF6E63", "#B9F09F"), "pills", seed = 53, tint = "#FFF6DA", frost = 0.1f),
            "graphite" to glass(listOf("#15171B", "#3B4048", "#8D949F", "#DADFE6"), "orbit", seed = 61, frost = 0.06f, specular = 1.1f),
            "ocean" to glass(listOf("#DDF3FF", "#78C5FF", "#3A78FF", "#B2F0E6"), "drops", seed = 73, tint = "#EAF6FF", frost = 0.1f),
            "ember" to glass(listOf("#180505", "#8C1D0F", "#FF5A1E", "#FFB13D"), "orbit", seed = 89, tint = "#FFE2C8", frost = 0.08f),
        )

        private fun glass(
            palette: List<String>, form: String, seed: Int, tint: String = "#FFFFFF", frost: Float = 0.08f,
            specular: Float = 0.9f, brightness: Float = 1f, refraction: Float = 1f,
        ) = LiquidSpec(
            palette, specular = specular, brightness = brightness, seed = seed, look = LOOK_GLASS, form = form,
            tint = tint, frost = frost, refraction = refraction, hinge = true,
        )

        /** The molten metal set of 0.8 (renderer 6), kept so packs made with it still play. */
        val METAL: Map<String, LiquidSpec> = linkedMapOf(
            "gold" to LiquidSpec(listOf("#080503", "#8C5414", "#F2B84D", "#FFF2CC"), specular = 0.95f, gloss = 70f, brightness = 1.15f, seed = 23),
            "aurora" to LiquidSpec(listOf("#050D1A", "#0D8C8C", "#7359F2", "#B3FFF2"), specular = 0.55f, gloss = 42f, brightness = 1.1f, seed = 11),
            "neon" to LiquidSpec(listOf("#0A0314", "#CC1ABF", "#1AD9FF", "#FFCCFF"), specular = 0.55f, gloss = 44f, brightness = 1.1f, seed = 71),
            "rose" to LiquidSpec(listOf("#290A14", "#DB5C70", "#FFC2B3", "#FFEBE6"), specular = 0.55f, gloss = 40f, brightness = 1.05f, seed = 37),
            "abyss" to LiquidSpec(listOf("#00030D", "#031F61", "#009ED1", "#99FFFF"), specular = 0.6f, gloss = 50f, brightness = 0.92f, seed = 41),
            "lava" to LiquidSpec(listOf("#080003", "#6B0A05", "#FF660D", "#FFD980"), specular = 0.45f, gloss = 30f, brightness = 0.95f, seed = 53),
            "pearl" to LiquidSpec(listOf("#050508", "#595C6B", "#D9DBEB", "#FFFFFF"), specular = 0.85f, gloss = 60f, brightness = 1.05f, seed = 67),
            "emerald" to LiquidSpec(listOf("#000A08", "#05734D", "#59E6A6", "#D9FFE6"), specular = 0.65f, gloss = 48f, brightness = 1.05f, seed = 89),
        )

        private fun isHexColor(s: String): Boolean =
            s.length == 7 && s[0] == '#' && s.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
