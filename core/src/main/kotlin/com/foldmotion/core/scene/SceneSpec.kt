package com.foldmotion.core.scene

import kotlinx.serialization.Serializable

/** Scene families the on-device generator can draw. */
@Serializable
enum class SceneFamily { MOUNTAINS, DESERT, OCEAN, SPACE, CITY, ABSTRACT, MINIMAL }

@Serializable
enum class TimeOfDay { NIGHT, DUSK, DAWN, DAY }

@Serializable
enum class Celestial { NONE, FULL_MOON, CRESCENT, SUN }

@Serializable
enum class Vegetation { NONE, PINES, PALMS }

/** Depth role of a generated layer, back to front. */
enum class LayerRole(val id: String, val depth: Float, val overscan: Float) {
    FAR("far", 0f, 0.04f),
    MID("mid", 0.5f, 0.06f),
    NEAR("near", 1f, 0.09f),
}

/**
 * Colours as 0xAARRGGBB ints. Roles are shared by every family so one palette works anywhere:
 * three sky stops, a light source, three ground tones from far to near, and an accent.
 */
@Serializable
data class Palette(
    val id: String,
    val skyTop: Int,
    val skyMid: Int,
    val skyBottom: Int,
    val glow: Int,
    val far: Int,
    val mid: Int,
    val near: Int,
    val accent: Int,
)

/**
 * Complete, deterministic description of one anchor scene.
 *
 * Both the cover and the main image are rendered from this single spec through different
 * windows onto the same world, which is what guarantees they are the same scene rather than two
 * unrelated pictures (blueprint section 09, "anchor scene").
 */
@Serializable
data class SceneSpec(
    val family: SceneFamily,
    val seed: Long,
    val palette: Palette,
    val timeOfDay: TimeOfDay,
    val celestial: Celestial,
    /** 0..1. */
    val starDensity: Float,
    val aurora: Boolean,
    val vegetation: Vegetation,
    /** Focal element position in world units. See [SceneGeometry]. */
    val focusX: Float,
    val focusY: Float,
    val titleAr: String,
    val titleEn: String,
    val tags: List<String> = emptyList(),
)

/** Axis-aligned window onto the scene world. */
data class WorldRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Grows the window by a fraction of its own size on every side. */
    fun expand(fraction: Float): WorldRect {
        val dx = width * fraction
        val dy = height * fraction
        return WorldRect(left - dx, top - dy, right + dx, bottom + dy)
    }
}
