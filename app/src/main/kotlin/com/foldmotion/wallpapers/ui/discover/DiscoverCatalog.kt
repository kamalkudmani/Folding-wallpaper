package com.foldmotion.wallpapers.ui.discover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.ChoreoInput
import com.foldmotion.core.motion.ChoreographyComposer
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.motion.MotionLayer
import com.foldmotion.core.pack.LayerEntry
import com.foldmotion.core.pack.VideoGeometry
import com.foldmotion.core.pack.VideoSpec
import com.foldmotion.core.scene.Celestial
import com.foldmotion.core.scene.IdeaBank
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.core.scene.SceneFrames
import com.foldmotion.core.scene.SceneIdea
import com.foldmotion.core.scene.TimeOfDay
import com.foldmotion.core.scene.WorldRect
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.video.VideoFrameSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One entry of assets/discover/catalog.json, written by the catalogue render step. */
@Serializable
data class CatalogEntry(
    val id: String,
    val video: Boolean = false,
    val glow: String,
    val accent: String,
    val background: String,
    val mainWorld: List<Float>,
    val coverWorld: List<Float>,
    val focus: List<Float>,
    val seed: Long,
    val style: String? = null,
    /** Video items: the clip inside the app's assets. */
    val videoAsset: String? = null,
    /** Video made to loop perfectly (no cross-fade). */
    val seamless: Boolean = false,
    /** Titles for items that are not curated ideas (the liquid set). */
    val titleEn: String? = null,
    val titleAr: String? = null,
    /** Filters this item belongs to beyond its scene type, e.g. "liquid". */
    val tags: List<String> = emptyList(),
    /** Video items: the clip's size and length, so the detail page can play it straight away. */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoMs: Int = 0,
    /** Live liquid items (v6): computed on the GPU, no clip. */
    val liquid: com.foldmotion.core.pack.LiquidSpec? = null,
)

/** A wallpaper in the Discover wall: a curated idea, or the sample video. */
data class DiscoverItem(val entry: CatalogEntry, val idea: SceneIdea?) {
    val id: String get() = entry.id
    val isVideo: Boolean get() = entry.video
    val coverAsset: String get() = "discover/${id}_cover.webp"
    val mainAsset: String get() = "discover/${id}_main.webp"
    val glow: Int get() = parse(entry.glow)
    val background: Int get() = parse(entry.background)
    val accent: Int get() = parse(entry.accent)
    val family: SceneFamily get() = idea?.family ?: SceneFamily.ABSTRACT
    val autoStyle: FoldStyle get() = FoldStyle.parse(entry.style) ?: FoldStyle.AUTO
    val isLiquid: Boolean get() = DiscoverCatalog.LIQUID_TAG in entry.tags
    /** Live liquid, drawn on the GPU in real time. */
    val isLive: Boolean get() = entry.liquid != null
    /** Moves by itself: a clip or live liquid. */
    val isMoving: Boolean get() = isVideo || isLive

    fun title(language: String, sampleTitle: String): String =
        idea?.let { if (language == "ar") it.titleAr else it.titleEn }
            ?: (if (language == "ar") entry.titleAr else entry.titleEn)
            ?: sampleTitle

    fun description(language: String): String? = idea?.let { if (language == "ar") it.promptAr else it.promptEn }

    private fun parse(hex: String) = try {
        Color.parseColor(hex)
    } catch (e: IllegalArgumentException) {
        Color.BLACK
    }
}

/** Filters of the wall, Pinterest style. */
enum class DiscoverCategory(val label: Int) {
    ALL(R.string.discover_all),
    LIQUID(R.string.discover_liquid),
    VIDEO(R.string.discover_video),
    NIGHT(R.string.discover_night),
    MOUNTAINS(R.string.family_mountains),
    OCEAN(R.string.family_ocean),
    DESERT(R.string.family_desert),
    SPACE(R.string.family_space),
    CITY(R.string.family_city),
    ABSTRACT(R.string.family_abstract),
    MINIMAL(R.string.family_minimal);

    fun accepts(item: DiscoverItem): Boolean = when (this) {
        ALL -> true
        LIQUID -> item.isLiquid
        VIDEO -> item.isVideo
        NIGHT -> item.idea?.timeOfDay == TimeOfDay.NIGHT || item.family == SceneFamily.SPACE
        else -> !item.isMoving && item.family.name == name
    }
}

object DiscoverCatalog {

    @Volatile
    private var cached: List<DiscoverItem>? = null

    fun load(context: Context): List<DiscoverItem> {
        cached?.let { return it }
        val entries = try {
            context.assets.open("discover/catalog.json").bufferedReader().use { CoreInfo.json.decodeFromString(ListSerializer(CatalogEntry.serializer()), it.readText()) }
        } catch (e: Exception) {
            emptyList()
        }
        val items = interleave(entries.map { DiscoverItem(it, IdeaBank.byId(it.id)) })
        cached = items
        return items
    }

    /**
     * Order of the wall: a moving (video) wallpaper, then two drawn ones, and so on, so every
     * screenful mixes liquid motion with scenes instead of grouping them.
     */
    internal fun interleave(items: List<DiscoverItem>): List<DiscoverItem> {
        // The liquid set leads; the older sample clip follows it.
        val videos = ArrayDeque(items.filter { it.isMoving }.sortedByDescending { it.isLive })
        val drawn = ArrayDeque(items.filterNot { it.isMoving })
        val out = ArrayList<DiscoverItem>(items.size)
        while (videos.isNotEmpty() || drawn.isNotEmpty()) {
            videos.removeFirstOrNull()?.let(out::add)
            repeat(2) { drawn.removeFirstOrNull()?.let(out::add) }
        }
        return out
    }

    /** The clip a video item plays, inside the app's assets. */
    fun videoAsset(item: DiscoverItem): String = item.entry.videoAsset ?: SAMPLE_VIDEO

    /** A bundled clip copied out of the app once, so it can be read like a picked one. */
    fun assetFile(context: Context, asset: String): java.io.File {
        val out = java.io.File(context.cacheDir, asset)
        if (!out.isFile || out.length() == 0L) {
            out.parentFile?.mkdirs()
            val tmp = java.io.File(out.path + ".part")
            context.assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(out)
        }
        return out
    }

    /**
     * Live frames of a video item for its detail page: the clip cropped for each screen of the
     * Fold exactly as the wallpaper will show it, looping as the wallpaper will (seamless clips
     * without a cross-fade). Null when the item is not a video or the clip cannot be read.
     */
    fun videoSource(context: Context, item: DiscoverItem): VideoFrameSource? {
        if (!item.isVideo) return null
        val e = item.entry
        val w = if (e.videoWidth > 0) e.videoWidth else SAMPLE_WIDTH
        val h = if (e.videoHeight > 0) e.videoHeight else SAMPLE_HEIGHT
        val ms = if (e.videoMs > 0) e.videoMs else SAMPLE_MS
        return try {
            val file = assetFile(context, videoAsset(item))
            val spec = VideoSpec(
                file = VideoSpec.FILE,
                width = w,
                height = h,
                durationMs = ms,
                loopCrossfadeMs = if (e.seamless) 0 else VideoSpec.DEFAULT_CROSSFADE_MS,
                crop = VideoGeometry.crops(DeviceProfileRegistry.fold7, w, h),
                sizeBytes = file.length(),
            )
            VideoFrameSource(context, file, spec, OVERSCAN, maxLongSide = 1440)
        } catch (e: Exception) {
            null
        }
    }

    const val LIQUID_TAG = "liquid"
    const val SAMPLE_VIDEO = "samples/liquid-light.mp4"
    private const val SAMPLE_WIDTH = 1296
    private const val SAMPLE_HEIGHT = 1440
    private const val SAMPLE_MS = 10_000

    /** Overscan of the pictures in [scenes]: a hair, so springs never show an edge. */
    private const val OVERSCAN = 0.015f

    fun byId(context: Context, id: String?): DiscoverItem? = load(context).firstOrNull { it.id == id }

    /**
     * The item as live scenes for the 3D fold demo, from its two pictures: the fold animation of
     * [style] and, for drawn scenes, the living motion the real wallpaper will have.
     */
    fun scenes(context: Context, item: DiscoverItem, style: FoldStyle): Pair<LoadedScene, LoadedScene>? {
        fun decode(path: String): Bitmap? = try {
            context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        val coverBmp = decode(item.coverAsset) ?: return null
        val mainBmp = decode(item.mainAsset) ?: return null
        val e = item.entry
        fun rect(v: List<Float>) = WorldRect(v[0], v[1], v[2], v[3])
        val frames = SceneFrames(rect(e.coverWorld), rect(e.mainWorld), e.focus[0], e.focus[1])
        // The picture is the whole frame; a hair of overscan keeps springs from showing an edge.
        val o = OVERSCAN
        val layer = listOf(MotionLayer("far", 0f, -o, -o, 1 + o, 1 + o, opaque = true))
        val idea = item.idea
        val input = ChoreoInput(
            family = item.family,
            time = idea?.timeOfDay ?: TimeOfDay.NIGHT,
            celestial = idea?.celestial ?: Celestial.NONE,
            intensity = MotionIntensity.LOW,
            frames = frames,
            mainLayers = layer,
            coverLayers = layer,
            glow = item.glow,
            accent = item.accent,
            seed = e.seed,
        )
        val choreo = ChoreographyComposer.compose(input, if (style == FoldStyle.AUTO) item.autoStyle else style)
        val fold7 = DeviceProfileRegistry.fold7
        fun scene(kind: ScreenKind, bmp: Bitmap): LoadedScene {
            val screen = fold7.screen(kind)
            val (fx, fy) = frames.focusIn(kind)
            return LoadedScene(
                packId = "discover:${item.id}",
                kind = kind,
                baseWidth = screen.widthPx,
                baseHeight = screen.heightPx,
                focusX = fx,
                focusY = fy,
                layers = listOf(LayerEntry("far", "discover", bmp.width, bmp.height, listOf(-o, -o, 1 + o, 1 + o), 0f, opaque = true) to bmp),
                entryMotion = if (kind == ScreenKind.MAIN) choreo.open else choreo.close,
                background = item.background,
                glow = item.glow,
                ambient = if (item.isMoving) null else choreo.ambient,
                world = frames.frame(kind),
                liquid = e.liquid,
            )
        }
        return scene(ScreenKind.COVER, coverBmp) to scene(ScreenKind.MAIN, mainBmp)
    }
}
