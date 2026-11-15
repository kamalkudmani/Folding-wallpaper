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
import com.foldmotion.core.scene.Celestial
import com.foldmotion.core.scene.IdeaBank
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.core.scene.SceneFrames
import com.foldmotion.core.scene.SceneIdea
import com.foldmotion.core.scene.TimeOfDay
import com.foldmotion.core.scene.WorldRect
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.render.LoadedScene
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A depth layer of a catalogue picture, shipped so the preview moves exactly like the wallpaper. */
@Serializable
data class CatalogLayer(val id: String, val rect: List<Float>, val depth: Float, val opaque: Boolean)

/** One entry of assets/discover/catalog.json, written by the catalogue render step. */
@Serializable
data class CatalogEntry(
    val id: String,
    val glow: String,
    val accent: String,
    val background: String,
    val mainWorld: List<Float>,
    val coverWorld: List<Float>,
    val focus: List<Float>,
    val seed: Long,
    /** Always NATURAL from 0.9: the picture itself moves. */
    val style: String? = null,
    /** Titles for items that are not curated ideas (the glass set). */
    val titleEn: String? = null,
    val titleAr: String? = null,
    /** Filters this item belongs to beyond its scene type, e.g. "glass". */
    val tags: List<String> = emptyList(),
    /** Live liquid glass items: computed on the GPU, no pictures but the thumbnails. */
    val liquid: com.foldmotion.core.pack.LiquidSpec? = null,
    /** Drawn items: the depth layers of each screen (assets discover/layers/<id>_<screen>_<layer>.webp). */
    val coverLayers: List<CatalogLayer> = emptyList(),
    val mainLayers: List<CatalogLayer> = emptyList(),
)

/** A wallpaper in the Discover wall: a curated drawn scene or a liquid glass wallpaper. */
data class DiscoverItem(val entry: CatalogEntry, val idea: SceneIdea?) {
    val id: String get() = entry.id
    val coverAsset: String get() = "discover/${id}_cover.webp"
    val mainAsset: String get() = "discover/${id}_main.webp"
    val glow: Int get() = parse(entry.glow)
    val background: Int get() = parse(entry.background)
    val accent: Int get() = parse(entry.accent)
    val family: SceneFamily get() = idea?.family ?: SceneFamily.ABSTRACT
    /** The motion built into this wallpaper. */
    val style: FoldStyle get() = FoldStyle.parse(entry.style)?.takeIf { it != FoldStyle.AUTO } ?: FoldStyle.NATURAL
    /** Live liquid glass, drawn on the GPU in real time. */
    val isGlass: Boolean get() = entry.liquid?.isGlass == true

    fun title(language: String): String =
        idea?.let { if (language == "ar") it.titleAr else it.titleEn }
            ?: (if (language == "ar") entry.titleAr else entry.titleEn)
            ?: id

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
    GLASS(R.string.discover_glass),
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
        GLASS -> item.isGlass
        NIGHT -> item.idea?.timeOfDay == TimeOfDay.NIGHT || item.family == SceneFamily.SPACE
        else -> !item.isGlass && item.family.name == name
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
     * Order of the wall: a glass wallpaper, then two drawn ones, and so on, so every screenful
     * mixes them instead of grouping them.
     */
    internal fun interleave(items: List<DiscoverItem>): List<DiscoverItem> {
        val glass = ArrayDeque(items.filter { it.isGlass })
        val drawn = ArrayDeque(items.filterNot { it.isGlass })
        val out = ArrayList<DiscoverItem>(items.size)
        while (glass.isNotEmpty() || drawn.isNotEmpty()) {
            glass.removeFirstOrNull()?.let(out::add)
            repeat(2) { drawn.removeFirstOrNull()?.let(out::add) }
        }
        return out
    }

    const val GLASS_TAG = "glass"

    /** Overscan of a flat picture used as a single layer: a hair, so springs never show an edge. */
    private const val OVERSCAN = 0.015f

    fun byId(context: Context, id: String?): DiscoverItem? = load(context).firstOrNull { it.id == id }

    /** Asset of one depth layer of a drawn item. */
    fun layerAsset(id: String, kind: ScreenKind, layer: String) = "discover/layers/${id}_${if (kind == ScreenKind.MAIN) "main" else "cover"}_$layer.webp"

    /**
     * The item as live scenes for the 3D fold demo, moving exactly as the wallpaper will: a drawn
     * scene from its depth layers (its built-in motion and living motion), a glass wallpaper
     * live on the GPU (its drops are the motion; the thumbnails stand in where shaders cannot).
     */
    fun scenes(context: Context, item: DiscoverItem): Pair<LoadedScene, LoadedScene>? {
        fun decode(path: String): Bitmap? = try {
            context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        val e = item.entry
        fun rect(v: List<Float>) = WorldRect(v[0], v[1], v[2], v[3])
        val frames = SceneFrames(rect(e.coverWorld), rect(e.mainWorld), e.focus[0], e.focus[1])
        val o = OVERSCAN
        val flat = listOf(CatalogLayer("far", listOf(-o, -o, 1 + o, 1 + o), 0f, true))

        /** The layers of one screen with their pictures; null if any picture is missing. */
        fun layers(kind: ScreenKind): List<Pair<LayerEntry, Bitmap>>? {
            val shipped = if (kind == ScreenKind.MAIN) e.mainLayers else e.coverLayers
            val spec = shipped.ifEmpty { flat }
            val out = ArrayList<Pair<LayerEntry, Bitmap>>()
            for (l in spec) {
                val path = if (shipped.isEmpty()) (if (kind == ScreenKind.MAIN) item.mainAsset else item.coverAsset) else layerAsset(item.id, kind, l.id)
                val bmp = decode(path) ?: run {
                    out.forEach { it.second.recycle() }
                    return null
                }
                out += LayerEntry(l.id, "discover", bmp.width, bmp.height, l.rect, l.depth, l.opaque) to bmp
            }
            return out
        }
        val coverLayers = layers(ScreenKind.COVER) ?: return null
        val mainLayers = layers(ScreenKind.MAIN) ?: run {
            coverLayers.forEach { it.second.recycle() }
            return null
        }
        fun motionLayers(list: List<Pair<LayerEntry, Bitmap>>) = list.map { (l, _) -> MotionLayer(l.id, l.depth, l.left, l.top, l.right, l.bottom, l.opaque) }
        val idea = item.idea
        val input = ChoreoInput(
            family = item.family,
            time = idea?.timeOfDay ?: TimeOfDay.NIGHT,
            celestial = idea?.celestial ?: Celestial.NONE,
            intensity = MotionIntensity.LOW,
            frames = frames,
            mainLayers = motionLayers(mainLayers),
            coverLayers = motionLayers(coverLayers),
            glow = item.glow,
            accent = item.accent,
            seed = e.seed,
            intrinsic = item.isGlass,
        )
        val choreo = ChoreographyComposer.compose(input, item.style)
        val fold7 = DeviceProfileRegistry.fold7
        fun scene(kind: ScreenKind, layers: List<Pair<LayerEntry, Bitmap>>): LoadedScene {
            val screen = fold7.screen(kind)
            val (fx, fy) = frames.focusIn(kind)
            return LoadedScene(
                packId = "discover:${item.id}",
                kind = kind,
                baseWidth = screen.widthPx,
                baseHeight = screen.heightPx,
                focusX = fx,
                focusY = fy,
                layers = layers,
                entryMotion = if (kind == ScreenKind.MAIN) choreo.open else choreo.close,
                background = item.background,
                glow = item.glow,
                ambient = if (item.isGlass) null else choreo.ambient,
                world = frames.frame(kind),
                liquid = e.liquid,
                mainWorld = frames.main,
            )
        }
        return scene(ScreenKind.COVER, coverLayers) to scene(ScreenKind.MAIN, mainLayers)
    }
}
