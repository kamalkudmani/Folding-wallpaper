package com.foldmotion.core.pack

import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.MotionIntensity
import kotlinx.serialization.Serializable

/**
 * manifest.json of a FoldPack, schema version 1.
 *
 * A FoldPack is the only thing the app treats as a wallpaper: two related scenes (cover + main)
 * plus the open and close motion. A lone picture or video is never a FoldPack.
 */
@Serializable
data class FoldPackManifest(
    val schemaVersion: Int = CoreInfo.SCHEMA_VERSION,
    val assetType: String = CoreInfo.ASSET_TYPE,
    val id: String,
    val packageVersion: Int = 1,
    val title: LocalizedText,
    /** ISO-8601 UTC timestamp. */
    val createdAt: String,
    /** LOCAL_PROCEDURAL, CLOUD_AI or UPLOAD. */
    val creationSource: String,
    val author: String? = null,
    val license: String = "PERSONAL_USE",
    val contentRating: String = "EVERYONE",
    val profileIds: List<String>,
    val rendererVersion: VersionRange = VersionRange(CoreInfo.RENDERER_VERSION, CoreInfo.RENDERER_VERSION),
    val isStaticOnly: Boolean = false,
    val motionIntensity: MotionIntensity = MotionIntensity.LOW,
    val scenes: Scenes,
    val transitions: Transitions,
    /** v2: motion/ambient.json, the motion that runs between folds. */
    val ambient: String? = null,
    /** v3: the fold style of motion/open.json and close.json (a [com.foldmotion.core.motion.FoldStyle] name, never AUTO). */
    val motionStyle: String? = null,
    val previews: Previews,
    val colors: PackColors,
    val generation: GenerationRecipe? = null,
    val tags: List<String> = emptyList(),
) {
    fun scene(kind: ScreenKind): SceneEntry = if (kind == ScreenKind.MAIN) scenes.main else scenes.cover
    fun preview(kind: ScreenKind): String = if (kind == ScreenKind.MAIN) previews.main else previews.cover

    companion object {
        const val FILE_NAME = "manifest.json"
        const val SOURCE_LOCAL = "LOCAL_PROCEDURAL"
        const val SOURCE_CLOUD_AI = "CLOUD_AI"
        const val SOURCE_UPLOAD = "UPLOAD"
    }
}

@Serializable
data class LocalizedText(val ar: String, val en: String) {
    fun forLanguage(language: String): String = if (language.startsWith("ar")) ar else en
}

@Serializable
data class VersionRange(val min: Int, val max: Int)

@Serializable
data class Scenes(val cover: SceneEntry, val main: SceneEntry)

@Serializable
data class SceneEntry(
    /** Portrait pixel size of the panel this scene was authored for. */
    val baseWidth: Int,
    val baseHeight: Int,
    /** Focal point [x, y] in the normalised frame. */
    val focus: List<Float>,
    /** Region [left, top, right, bottom] that must keep the important content. */
    val safeZone: List<Float> = listOf(0.08f, 0.12f, 0.92f, 0.88f),
    /** Back to front. The first layer must be opaque and cover the frame. */
    val layers: List<LayerEntry>,
    /** v2: world rect [l, t, r, b] this frame shows, so ambient motion lines up across screens. */
    val world: List<Float>? = null,
) {
    val focusX: Float get() = focus.getOrElse(0) { 0.5f }
    val focusY: Float get() = focus.getOrElse(1) { 0.5f }
}

@Serializable
data class LayerEntry(
    val id: String,
    val path: String,
    /** Pixel size of the stored image. */
    val width: Int,
    val height: Int,
    /**
     * Where the image sits at rest, [left, top, right, bottom] in the normalised frame. Values
     * outside [0,1] are overscan that keeps edges hidden while the layer moves.
     */
    val rect: List<Float>,
    /** 0 = far, 1 = near. Drives how far the layer travels. */
    val depth: Float,
    val opaque: Boolean = false,
) {
    val left: Float get() = rect.getOrElse(0) { 0f }
    val top: Float get() = rect.getOrElse(1) { 0f }
    val right: Float get() = rect.getOrElse(2) { 1f }
    val bottom: Float get() = rect.getOrElse(3) { 1f }
}

@Serializable
data class Transitions(val open: TransitionRef, val close: TransitionRef)

@Serializable
data class TransitionRef(val file: String, val durationMs: Int)

@Serializable
data class Previews(val cover: String, val main: String)

/** "#RRGGBB" strings. Feeds the system colour extraction and the loading placeholder. */
@Serializable
data class PackColors(
    val primary: String,
    val secondary: String,
    val tertiary: String,
    val background: String,
)

/** How the pack was produced. Prompts are never stored in clear, only a hash. */
@Serializable
data class GenerationRecipe(
    val provider: String,
    val providerVersion: String,
    val seed: Long,
    val family: String? = null,
    val promptSha256: String? = null,
    val mode: String = "CUSTOM",
    /** "CURATED" or "REFERENCE_IMAGE" (colours extracted on the device; the photo is not kept). */
    val paletteSource: String = "CURATED",
)
