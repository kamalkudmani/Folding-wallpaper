package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.GenerationRequest
import com.foldmotion.core.scene.IdeaBank
import com.foldmotion.wallpapers.generator.LocalPackGenerator
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import com.foldmotion.wallpapers.generator.liquid.LiquidPackBuilder
import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.core.scene.WorldRect
import com.foldmotion.wallpapers.ui.discover.CatalogEntry
import com.foldmotion.wallpapers.ui.discover.CatalogLayer
import com.foldmotion.core.motion.FoldStyle
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Asset build step, not a check: renders the Discover catalogue the app ships. Every curated
 * drawn scene with its built-in (NATURAL) motion: the folded (cover) and unfolded (main) picture
 * for the wall and its depth layers for the moving preview; and the liquid glass set. Runs only
 * with -Dfoldmotion.discoverOut=<assets/discover folder>.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DiscoverAssetsRender {

    private val container = AppContainer.get(ApplicationProvider.getApplicationContext())
    private val fold7 = DeviceProfileRegistry.fold7

    @Before
    fun warmUp() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bmp.byteCount))
        java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
        bitmap.recycle()
    }

    @Test
    fun `render discover catalogue`() {
        val out = System.getProperty("foldmotion.discoverOut")?.let(::File)
        assumeTrue(out != null)
        out!!.mkdirs()
        out.listFiles()?.filter { it.isFile && it.name.endsWith(".webp") }?.forEach { it.delete() }
        File(out, "layers").apply { deleteRecursively(); mkdirs() }
        val drawer = SceneDrawer()
        val entries = ArrayList<CatalogEntry>()
        for ((i, idea) in IdeaBank.ideas.withIndex()) {
            val pack = runBlocking {
                LocalPackGenerator(container.packStore, container.validator)
                    .generate(
                        GenerationRequest("discover-${idea.id}", fold7.id, GenerationMode.RANDOM, ideaId = idea.id, seed = DISCOVER_SEED + i, motionStyle = FoldStyle.NATURAL),
                        fold7,
                    ) {}
                    .pack
            }
            val layers = HashMap<ScreenKind, List<CatalogLayer>>()
            for ((kind, width) in listOf(ScreenKind.COVER to COVER_WIDTH, ScreenKind.MAIN to MAIN_WIDTH)) {
                val screen = fold7.screen(kind)
                val height = (width / screen.aspect).toInt()
                val name = if (kind == ScreenKind.MAIN) "main" else "cover"
                // The wall's thumbnail: the picture at rest.
                val thumb = SceneLoader.load(pack.dir, kind, maxOf(width, height) * 2)!!
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                drawer.draw(Canvas(bmp), width, height, thumb, null)
                thumb.recycle()
                save(bmp, File(out, "${idea.id}_$name.webp"))
                // The depth layers for the preview, so it moves exactly like the wallpaper.
                val scene = SceneLoader.load(pack.dir, kind, 0)!!
                // Each layer at the size it covers on a screen whose long side is LAYER_LONG_SIDE.
                val k = LAYER_LONG_SIDE.toFloat() / maxOf(scene.baseWidth, scene.baseHeight)
                layers[kind] = scene.layers.map { (l, b) ->
                    val w = ((l.right - l.left) * scene.baseWidth * k).toInt().coerceAtLeast(8)
                    val h = ((l.bottom - l.top) * scene.baseHeight * k).toInt().coerceAtLeast(8)
                    val small = Bitmap.createScaledBitmap(b, w, h, true)
                    File(out, "layers/${idea.id}_${name}_${l.id}.webp").outputStream().use { small.compress(Bitmap.CompressFormat.WEBP_LOSSY, if (l.opaque) 80 else 86, it) }
                    if (small !== b) small.recycle()
                    CatalogLayer(l.id, l.rect.map(::round4), l.depth, l.opaque)
                }
                scene.recycle()
            }
            val m = pack.manifest
            val mw = m.scenes.main.world!!
            entries += CatalogEntry(
                id = idea.id, glow = m.colors.primary, accent = m.colors.secondary, background = m.colors.background,
                mainWorld = mw, coverWorld = m.scenes.cover.world!!,
                focus = listOf(mw[0] + m.scenes.main.focusX * (mw[2] - mw[0]), mw[1] + m.scenes.main.focusY * (mw[3] - mw[1])),
                seed = m.generation?.seed ?: 0, style = m.motionStyle,
                coverLayers = layers.getValue(ScreenKind.COVER), mainLayers = layers.getValue(ScreenKind.MAIN),
            )
            container.packStore.dir(pack.id).deleteRecursively()
        }
        // The liquid glass set: thumbnails rendered by the live shader itself, at rest.
        val g = LiquidPackBuilder.geometry(fold7)
        for ((i, l) in GLASS.withIndex()) {
            val spec = LiquidSpec.PRESETS.getValue(l.name)
            for ((kind, width) in listOf(ScreenKind.COVER to COVER_WIDTH, ScreenKind.MAIN to MAIN_WIDTH)) {
                val height = (width / fold7.screen(kind).aspect).toInt()
                val bmp = LiquidPackBuilder.still(spec, if (kind == ScreenKind.MAIN) g.main else g.cover, width, height, kind, g.main)
                save(bmp, File(out, "glass_${l.name}_${if (kind == ScreenKind.MAIN) "main" else "cover"}.webp"))
            }
            fun rect(r: WorldRect) = listOf(r.left, r.top, r.right, r.bottom)
            entries += CatalogEntry(
                id = "glass_${l.name}", glow = spec.palette[3], accent = spec.palette[2], background = spec.palette[0],
                mainWorld = rect(g.main), coverWorld = rect(g.cover), focus = listOf(g.main.left + g.main.width / 2f, g.main.top + g.main.height / 2f),
                seed = 200L + i, style = FoldStyle.NATURAL.name, titleEn = l.titleEn, titleAr = l.titleAr, tags = listOf("glass"), liquid = spec,
            )
        }
        // The realistic live scenes: thumbnails by their own shaders, at rest.
        for ((i, sc) in SCENES.withIndex()) {
            val spec = LiquidSpec.SCENES.getValue(sc.name)
            for ((kind, width) in listOf(ScreenKind.COVER to COVER_WIDTH, ScreenKind.MAIN to MAIN_WIDTH)) {
                val height = (width / fold7.screen(kind).aspect).toInt()
                val bmp = LiquidPackBuilder.still(spec, if (kind == ScreenKind.MAIN) g.main else g.cover, width, height, kind, g.main)
                save(bmp, File(out, "${sc.name}_${if (kind == ScreenKind.MAIN) "main" else "cover"}.webp"))
            }
            fun rect(r: WorldRect) = listOf(r.left, r.top, r.right, r.bottom)
            entries += CatalogEntry(
                id = sc.name, glow = spec.palette[2], accent = spec.palette[3], background = spec.palette[0],
                mainWorld = rect(g.main), coverWorld = rect(g.cover), focus = listOf(g.main.left + g.main.width / 2f, g.main.top + g.main.height / 2f),
                seed = 300L + i, style = FoldStyle.NATURAL.name, titleEn = sc.titleEn, titleAr = sc.titleAr,
                tags = listOfNotNull("live", spec.look, if (sc.night) "night" else null), liquid = spec,
                family = sc.family, descEn = sc.descEn, descAr = sc.descAr,
            )
        }
        val oneLine = Json { encodeDefaults = false; explicitNulls = false }
        File(out, "catalog.json").writeText(entries.joinToString(",\n", "[\n", "\n]\n") { oneLine.encodeToString(CatalogEntry.serializer(), it) })
    }

    private fun round4(v: Float) = Math.round(v * 10000f) / 10000f

    /** One wallpaper of the glass set (its look is [LiquidSpec.PRESETS] under [name]). */
    private data class Glass(val name: String, val titleEn: String, val titleAr: String)

    /** One realistic live scene ([LiquidSpec.SCENES] under [name]). */
    private data class Scene(
        val name: String, val titleEn: String, val titleAr: String, val descEn: String, val descAr: String,
        val family: String? = null, val night: Boolean = false,
    )

    companion object {
        private val GLASS = listOf(
            Glass("daybreak", "Daybreak", "شروق"),
            Glass("lagoon", "Lagoon", "بحيرة"),
            Glass("orchid", "Orchid", "أوركيد"),
            Glass("midnight", "Midnight glass", "زجاج منتصف الليل"),
            Glass("citrus", "Citrus", "حمضيات"),
            Glass("graphite", "Graphite", "جرافيت"),
            Glass("ocean", "Ocean air", "نسيم المحيط"),
            Glass("ember", "Ember", "جمرة"),
        )
        private val SCENES = listOf(
            Scene("ocean_sunset", "Ocean sunset", "غروب على المحيط", "Warm light over a calm sea, the sun low on the horizon.", "ضوء دافئ فوق بحر هادئ والشمس منخفضة على الأفق.", "OCEAN"),
            Scene("ocean_moon", "Moonlit sea", "بحر مقمر", "A full moon over dark water, its path of light on the waves.", "قمر مكتمل فوق ماء داكن، وطريق ضوئه على الأمواج.", "OCEAN", night = true),
            Scene("ocean_tropic", "Tropical noon", "ظهيرة استوائية", "Turquoise water under a bright, open sky.", "ماء فيروزي تحت سماء مشرقة مفتوحة.", "OCEAN"),
            Scene("ocean_storm", "Storm light", "ضوء العاصفة", "Heavy clouds over a grey sea, a pale sun breaking through.", "غيوم ثقيلة فوق بحر رمادي، وشمس شاحبة تشق طريقها.", "OCEAN"),
            Scene("sky_golden", "Golden hour", "الساعة الذهبية", "Clouds lit gold by a low sun over quiet hills.", "غيوم ذهبية بضوء شمس منخفضة فوق تلال هادئة."),
            Scene("sky_dawn", "Pastel dawn", "فجر وردي", "Soft pink morning light through thin clouds.", "ضوء صباحي وردي ناعم عبر غيوم رقيقة."),
            Scene("sky_blue", "Blue afternoon", "عصر أزرق", "A clear blue sky with drifting clouds over green fields.", "سماء زرقاء صافية وغيوم سارحة فوق حقول خضراء."),
            Scene("sky_night", "Moon behind clouds", "قمر خلف الغيوم", "A night sky where the moon slips out from the clouds.", "سماء ليلية يطل فيها القمر من بين الغيوم.", night = true),
            Scene("aurora_green", "Northern lights", "الشفق القطبي", "Green curtains of light over mountains and a still lake.", "ستائر ضوء خضراء فوق الجبال وبحيرة ساكنة.", "MOUNTAINS", night = true),
            Scene("aurora_violet", "Violet aurora", "شفق بنفسجي", "Violet and rose lights over snowy peaks.", "أضواء بنفسجية ووردية فوق قمم مثلجة.", "MOUNTAINS", night = true),
            Scene("aurora_teal", "Arctic teal", "شفق فيروزي", "Cold blue-green lights over an arctic lake.", "أضواء زرقاء خضراء باردة فوق بحيرة قطبية.", "MOUNTAINS", night = true),
            Scene("aurora_crimson", "Crimson aurora", "شفق قرمزي", "A rare red aurora fringed with green.", "شفق أحمر نادر بحواف خضراء.", "MOUNTAINS", night = true),
            Scene("rain_neon", "Neon rain", "مطر النيون", "Pink and blue city lights behind a rainy window.", "أضواء مدينة وردية وزرقاء خلف نافذة ممطرة.", "CITY", night = true),
            Scene("rain_amber", "Amber streets", "شوارع كهرمانية", "Warm street lights blurred by the rain.", "أضواء شوارع دافئة يغبّشها المطر.", "CITY", night = true),
            Scene("rain_blue", "Blue hour rain", "مطر الساعة الزرقاء", "A cool evening city through wet glass.", "مدينة مسائية باردة عبر زجاج مبلل.", "CITY", night = true),
            Scene("rain_emerald", "Emerald night", "ليل زمردي", "Green and gold lights on a rainy night.", "أضواء خضراء وذهبية في ليلة ممطرة.", "CITY", night = true),
            Scene("space_earth", "Blue planet", "الكوكب الأزرق", "Sunrise over a blue world, seen from orbit.", "شروق فوق عالم أزرق كما يُرى من المدار.", "SPACE"),
            Scene("space_mars", "Red planet", "الكوكب الأحمر", "Dawn over a rust-red desert world.", "فجر فوق عالم صحراوي بلون الصدأ.", "SPACE"),
            Scene("space_giant", "Gas giant", "عملاق غازي", "Banded clouds of a giant planet catching the sun.", "غيوم مخططة لكوكب عملاق تلتقط ضوء الشمس.", "SPACE"),
            Scene("space_ice", "Ice world", "عالم جليدي", "A frozen moon glowing at the edge of night.", "قمر متجمد يتوهج عند حافة الليل.", "SPACE"),
        )
        const val LAYER_LONG_SIDE = 900
        const val DISCOVER_SEED = 2026_11_13L
        const val MAIN_WIDTH = 760
        const val COVER_WIDTH = 360
    }
}
