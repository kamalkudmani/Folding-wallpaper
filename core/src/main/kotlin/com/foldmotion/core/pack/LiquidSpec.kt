package com.foldmotion.core.pack

import kotlinx.serialization.Serializable

/**
 * v6: a live liquid scene. Instead of a picture or a clip, the scene is computed on the GPU every
 * frame: domain-warped noise lit as a glossy height field, defined in world space so the cover
 * shows exactly the slice of liquid the main screen shows around it. It never repeats within ten
 * minutes, and it can react to the phone: tilt makes it drift downhill, taps send out ripples,
 * and a half-open hinge makes it pool along the fold.
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
) {
    fun isWellFormed(): Boolean =
        palette.size == 4 && palette.all(::isHexColor) &&
            specular in 0f..1.5f && gloss in 4f..160f && brightness in 0.4f..1.8f &&
            span in 0.4f..4f && warp in 0f..5f && speed in 0.25f..4f && seed in 0..9999

    companion object {
        /** The flow repeats exactly after this long, so time never loses float precision. */
        const val PERIOD_SECONDS = 600

        /** Base angular frequencies (whole turns per period) of the four flow circles. */
        val BASE_TURNS = intArrayOf(60, 47, 71, 53)

        /** Whole turns per period for [speed], so the flow stays exactly periodic. */
        fun turns(speed: Float): IntArray = IntArray(4) { i -> maxOf(1, Math.round(BASE_TURNS[i] * speed)) }

        /** The liquid set that ships with the app; also the 0.7 video loops' looks. */
        val PRESETS: Map<String, LiquidSpec> = linkedMapOf(
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
