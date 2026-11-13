package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.pack.VideoGeometry
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
 * Asset build step, not a check: renders the folded (cover) and unfolded (main) picture of every
 * curated idea, and of the sample video, into the Discover catalogue the app ships. Runs only
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
        // The liquid step alone when its frames are given.
        assumeTrue(out != null && System.getProperty("foldmotion.liquid") == null)
        out!!.mkdirs()
        val drawer = SceneDrawer()
        val entries = ArrayList<String>()
        fun arr(v: List<Float>?) = v?.joinToString(",", "[", "]") ?: "null"
        for ((i, idea) in IdeaBank.ideas.withIndex()) {
            val pack = runBlocking {
                LocalPackGenerator(container.packStore, container.validator)
                    .generate(GenerationRequest("discover-${idea.id}", fold7.id, GenerationMode.RANDOM, ideaId = idea.id, seed = DISCOVER_SEED + i), fold7) {}
                    .pack
            }
            for ((kind, width) in listOf(ScreenKind.COVER to COVER_WIDTH, ScreenKind.MAIN to MAIN_WIDTH)) {
                val screen = fold7.screen(kind)
                val height = (width / screen.aspect).toInt()
                val scene = SceneLoader.load(pack.dir, kind, maxOf(width, height) * 2)!!
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                drawer.draw(Canvas(bmp), width, height, scene, null)
                scene.recycle()
                save(bmp, File(out, "${idea.id}_${if (kind == ScreenKind.MAIN) "main" else "cover"}.webp"))
            }
            val m = pack.manifest
            val mw = m.scenes.main.world!!
            entries += """{"id":"${idea.id}","video":false,"glow":"${m.colors.primary}","accent":"${m.colors.secondary}","background":"${m.colors.background}",""" +
                """"mainWorld":${arr(mw)},"coverWorld":${arr(m.scenes.cover.world)},"focus":${arr(listOf(mw[0] + m.scenes.main.focusX * (mw[2] - mw[0]), mw[1] + m.scenes.main.focusY * (mw[3] - mw[1])))},""" +
                """"seed":${m.generation?.seed ?: 0},"style":"${m.motionStyle}"}"""
            container.packStore.dir(pack.id).deleteRecursively()
        }
        // The sample video: its first frame, framed per screen exactly as the player crops it.
        System.getProperty("foldmotion.videoFrames")?.let(::File)?.listFiles { f -> f.name.endsWith(".png") }?.minByOrNull { it.name }?.let { first ->
            val frame = BitmapFactory.decodeFile(first.path)
            val (main, cover) = videoThumbs(frame, out, "liquid_light")
            entries += """{"id":"liquid_light","video":true,"glow":"#9FF5E6","accent":"#8B7CFF","background":"#050B14",""" +
                """"mainWorld":${arr(main)},"coverWorld":${arr(cover)},"focus":${arr(listOf(0.5f * frame.width / maxOf(frame.width, frame.height), 0.5f * frame.height / maxOf(frame.width, frame.height)))},"seed":7,"style":"WARP",""" +
                """"videoAsset":"samples/liquid-light.mp4","videoWidth":${frame.width},"videoHeight":${frame.height},"videoMs":10000}"""
        }
        // Keep the liquid set (its own step, below) when the rest is rebuilt.
        val catalog = File(out, "catalog.json")
        if (catalog.isFile) {
            val oneLine = Json { encodeDefaults = false; explicitNulls = false }
            Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(CatalogEntry.serializer()), catalog.readText())
                .filter { it.isLiquidEntry() }
                .forEach { entries += oneLine.encodeToString(CatalogEntry.serializer(), it) }
        }
        catalog.writeText(entries.joinToString(",\n", "[\n", "\n]\n"))
    }

    /**
     * Folded and unfolded pictures of a clip from its first [frame], framed per screen exactly
     * as the player crops it. @return the main and cover windows in world units (long side = 1).
     */
    private fun videoThumbs(frame: Bitmap, out: File, id: String): Pair<List<Float>, List<Float>> {
        val crops = VideoGeometry.crops(fold7, frame.width, frame.height)
        for ((kind, width) in listOf(ScreenKind.COVER to COVER_WIDTH, ScreenKind.MAIN to MAIN_WIDTH)) {
            val c = if (kind == ScreenKind.MAIN) crops.main else crops.cover
            val height = (width / fold7.screen(kind).aspect).toInt()
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val sx = width / ((c[2] - c[0]) * frame.width)
            val sy = height / ((c[3] - c[1]) * frame.height)
            val shader = BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(-c[0] * frame.width, -c[1] * frame.height); postScale(sx, sy) })
            }
            Canvas(bmp).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
            save(bmp, File(out, "${id}_${if (kind == ScreenKind.MAIN) "main" else "cover"}.webp"))
        }
        val unit = maxOf(frame.width, frame.height).toFloat()
        fun world(r: List<Float>) = listOf(r[0] * frame.width / unit, r[1] * frame.height / unit, r[2] * frame.width / unit, r[3] * frame.height / unit)
        return world(crops.main) to world(crops.cover)
    }

    /** One wallpaper of the live liquid set (its look is [LiquidSpec.PRESETS] under [name]). */
    private data class Liquid(val name: String, val titleEn: String, val titleAr: String, val glow: String, val accent: String, val background: String, val style: String)

    /**
     * Adds the live liquid set to the catalogue: thumbnails rendered by the live shader itself
     * (time 0, on the hardware pipeline) for the cover and main windows, and one entry per preset
     * carrying its [com.foldmotion.core.pack.LiquidSpec], replacing earlier liquid entries. Runs
     * with -Dfoldmotion.discoverOut=<assets/discover> -Dfoldmotion.liquid=1.
     */
    @Test
    fun `render liquid catalogue`() {
        val out = System.getProperty("foldmotion.discoverOut")?.let(::File)
        assumeTrue(out != null && System.getProperty("foldmotion.liquid") != null)
        val catalog = File(out, "catalog.json")
        val serializer = ListSerializer(CatalogEntry.serializer())
        val oneLine = Json { encodeDefaults = false; explicitNulls = false }
        val kept = Json { ignoreUnknownKeys = true }.decodeFromString(serializer, catalog.readText()).filterNot { it.isLiquidEntry() }
        val g = LiquidPackBuilder.geometry(fold7)
        val added = LIQUIDS.mapIndexed { i, l ->
            val spec = LiquidSpec.PRESETS.getValue(l.name)
            for ((kind, width) in listOf(ScreenKind.COVER to COVER_WIDTH, ScreenKind.MAIN to MAIN_WIDTH)) {
                val height = (width / fold7.screen(kind).aspect).toInt()
                val bmp = LiquidPackBuilder.still(spec, if (kind == ScreenKind.MAIN) g.main else g.cover, width, height)
                save(bmp, File(out!!, "liquid_${l.name}_${if (kind == ScreenKind.MAIN) "main" else "cover"}.webp"))
            }
            fun rect(r: WorldRect) = listOf(r.left, r.top, r.right, r.bottom)
            CatalogEntry(
                id = "liquid_${l.name}", video = false, glow = l.glow, accent = l.accent, background = l.background,
                mainWorld = rect(g.main), coverWorld = rect(g.cover), focus = listOf(g.main.left + g.main.width / 2f, g.main.top + g.main.height / 2f),
                seed = 100L + i, style = l.style, titleEn = l.titleEn, titleAr = l.titleAr, tags = listOf("liquid"), liquid = spec,
            )
        }
        catalog.writeText((kept + added).joinToString(",\n", "[\n", "\n]\n") { oneLine.encodeToString(CatalogEntry.serializer(), it) })
    }

    private fun CatalogEntry.isLiquidEntry() = "liquid" in tags

    companion object {
        private val LIQUIDS = listOf(
            Liquid("gold", "Molten gold", "ذهب منصهر", "#FFE7B0", "#E0A030", "#0B0703", "FLOW"),
            Liquid("aurora", "Aurora silk", "حرير الشفق", "#B8FFF4", "#7A6CFF", "#03080E", "GLASS"),
            Liquid("neon", "Neon tide", "مدّ النيون", "#FFB8FA", "#22D8FF", "#0A0312", "WARP"),
            Liquid("rose", "Rose quartz", "كوارتز وردي", "#FFE0DC", "#E06A7E", "#1A070C", "SWEEP"),
            Liquid("abyss", "Deep ocean", "أعماق المحيط", "#A0F4FF", "#0A9ED0", "#00030A", "RIPPLE"),
            Liquid("lava", "Ember flow", "تدفّق الجمر", "#FFD38A", "#FF6A10", "#070101", "RISE"),
            Liquid("pearl", "Liquid chrome", "كروم سائل", "#FFFFFF", "#B9BCC8", "#050507", "RIPPLE"),
            Liquid("emerald", "Emerald glass", "زجاج زمردي", "#C8FFE4", "#2BD18F", "#000805", "GLASS"),
        )
        const val DISCOVER_SEED = 2026_11_13L
        const val MAIN_WIDTH = 760
        const val COVER_WIDTH = 360
    }
}
