package com.foldmotion.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.pack.FoldPackValidator
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.GenerationRequest
import com.foldmotion.core.scene.IdeaBank
import com.foldmotion.core.scene.PipelineStage
import com.foldmotion.wallpapers.generator.LocalPackGenerator
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * End-to-end check of the on-device generator with real Skia rendering:
 * every pack must pass the validator, and the cover must be the same scene as the main screen.
 * Writes contact sheets to build/screenshots for human review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GeneratorRenderTest {

    private val container = AppContainer.get(ApplicationProvider.getApplicationContext())
    private val fold7 = DeviceProfileRegistry.fold7
    private val outDir = File(System.getProperty("foldmotion.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private fun generate(request: GenerationRequest): String = runBlocking {
        val stages = ArrayList<PipelineStage>()
        val outcome = LocalPackGenerator(container.packStore, FoldPackValidator()).generate(request, fold7) { stages += it }
        assertEquals(PipelineStage.entries.toList(), stages)
        outcome.pack.id
    }

    @Test
    fun `every family produces a valid pack whose cover is a slice of the main scene`() {
        val ideas = listOf("aurora_peaks", "golden_dunes", "teal_horizon", "ringed_world", "neon_skyline", "brand_flow", "sand_minimal", "desert_crescent")
        val sheets = ArrayList<Bitmap>()
        for ((i, ideaId) in ideas.withIndex()) {
            val id = generate(GenerationRequest("k$i", fold7.id, GenerationMode.RANDOM, ideaId = ideaId, seed = 1000L + i, motionIntensity = MotionIntensity.LOW))
            val dir = container.packStore.dir(id)
            val report = FoldPackValidator().validate(dir, fold7.id)
            assertTrue("$ideaId ${report.issues}", report.passed)
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            assertTrue("$ideaId pack is ${size / 1024} KB", size < 20L * 1024 * 1024)
            sheets += contactSheet(dir, IdeaBank.byId(ideaId)!!.titleEn)
        }
        writeGrid(sheets, File(outDir, "packs_overview.png"))
    }

    @Test
    fun `custom arabic prompt generates the requested scene`() {
        val id = generate(GenerationRequest("ar1", fold7.id, GenerationMode.CUSTOM, prompt = "جبال ليلية تحت هلال ونجوم مع شفق أخضر", seed = 77L))
        val pack = container.packStore.get(id)!!
        assertEquals("MOUNTAINS", pack.manifest.generation?.family)
        // Keep a real pack for schema checks and manual import tests.
        File(pack.dir, "manifest.json").copyTo(File(outDir, "sample-manifest.json"), overwrite = true)
        File(outDir, "sample.foldpack").outputStream().use { container.packStore.export(id, it) }
        writeGrid(listOf(contactSheet(pack.dir, "prompt: mountains night crescent aurora")), File(outDir, "pack_custom_ar.png"))
    }

    @Test
    fun `transition frames go from wide to rest without exposing the background`() {
        val id = generate(GenerationRequest("t1", fold7.id, GenerationMode.RANDOM, ideaId = "indigo_valley", seed = 5L, motionIntensity = MotionIntensity.MEDIUM))
        val dir = container.packStore.dir(id)
        val frames = ArrayList<Bitmap>()
        for (kind in listOf(ScreenKind.MAIN, ScreenKind.COVER)) {
            val scene = SceneLoader.load(dir, kind)!!
            val w = if (kind == ScreenKind.MAIN) 492 else 270
            val h = if (kind == ScreenKind.MAIN) 546 else 630
            for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.MAGENTA)
                SceneDrawer().draw(Canvas(bmp), w, h, scene, p)
                // The background colour is drawn first; magenta would only survive outside it.
                assertTrue(bmp.getPixel(0, 0) != Color.MAGENTA && bmp.getPixel(w - 1, h - 1) != Color.MAGENTA)
                frames += bmp
            }
            scene.recycle()
        }
        writeGrid(frames, File(outDir, "transition_frames.png"), columns = 5)
    }

    /** Main screen next to the cover, both at the same physical scale, as on the phone. */
    private fun contactSheet(dir: File, label: String): Bitmap {
        val main = SceneLoader.load(dir, ScreenKind.MAIN)!!
        val cover = SceneLoader.load(dir, ScreenKind.COVER)!!
        val mainW = 393
        val mainH = (mainW * 2184f / 1968f).toInt()
        // Cover drawn at the same mm-per-pixel as the main panel: 65.0/135.8 of its width.
        val coverW = (mainW * fold7.cover.widthMm!! / fold7.main!!.widthMm!!).toInt()
        val coverH = (coverW * 2520f / 1080f).toInt()
        val sheet = Bitmap.createBitmap(mainW + coverW + 36, mainH + 40, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(16, 20, 26))
        val m = Bitmap.createBitmap(mainW, mainH, Bitmap.Config.ARGB_8888)
        SceneDrawer().draw(Canvas(m), mainW, mainH, main, null)
        val cv = Bitmap.createBitmap(coverW, coverH, Bitmap.Config.ARGB_8888)
        SceneDrawer().draw(Canvas(cv), coverW, coverH, cover, null)
        c.drawBitmap(cv, 12f, 30f, null)
        c.drawBitmap(m, coverW + 24f, 30f, null)
        val crease = Paint().apply { color = Color.argb(70, 255, 255, 255); strokeWidth = 1f }
        c.drawLine(coverW + 24f + mainW / 2f, 30f, coverW + 24f + mainW / 2f, 30f + mainH, crease)
        c.drawText(label, 12f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f })
        main.recycle(); cover.recycle(); m.recycle(); cv.recycle()
        return sheet
    }

    private fun writeGrid(items: List<Bitmap>, file: File, columns: Int = 2) {
        val cellW = items.maxOf { it.width }
        val cellH = items.maxOf { it.height }
        val rows = (items.size + columns - 1) / columns
        val grid = Bitmap.createBitmap(cellW * columns, cellH * rows, Bitmap.Config.ARGB_8888)
        val c = Canvas(grid)
        c.drawColor(Color.rgb(16, 20, 26))
        items.forEachIndexed { i, b -> c.drawBitmap(b, (i % columns) * cellW.toFloat(), (i / columns) * cellH.toFloat(), null) }
        file.outputStream().use { grid.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
