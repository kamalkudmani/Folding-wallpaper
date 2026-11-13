package com.foldmotion.wallpapers

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.GenerationRequest
import com.foldmotion.wallpapers.ui.FoldMotionApp
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.Routes
import com.foldmotion.wallpapers.ui.theme.FoldMotionTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Renders every main screen on the Fold7 cover (411 x 960 dp) and main (750 x 832 dp) sizes,
 * in English and Arabic (RTL), and writes PNGs to build/screenshots for review.
 * It also proves each screen composes without crashing in both sizes and directions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("foldmotion.screenshotDir") ?: "build/screenshots", "ui").apply { mkdirs() }

    /**
     * Robolectric's native graphics caches JNI class references the first time they are needed.
     * If that first use happens on a background decoder thread it can fail and abort the JVM, so
     * every graphics path the screens use is touched once here, on the test thread.
     */
    @Before
    fun warmUpNativeGraphics() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.RED)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bmp.byteCount))
        val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }.toByteArray()
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        java.nio.LongBuffer.allocate(1)
        java.nio.FloatBuffer.allocate(1)
    }

    private fun seedPacks(app: Application): List<String> {
        val c = AppContainer.get(app)
        c.settings.onboardingDone = true
        if (c.packStore.list().size >= 2) return c.packStore.list().map { it.id }
        val fold7 = DeviceProfileRegistry.fold7
        return runBlocking {
            listOf("aurora_peaks", "golden_dunes", "neon_skyline").mapIndexed { i, idea ->
                c.generator.generate(
                    GenerationRequest("seed$i", fold7.id, GenerationMode.RANDOM, ideaId = idea, seed = 40L + i, motionIntensity = MotionIntensity.LOW),
                    fold7,
                ) {}.pack.id
            }
        }
    }

    private fun settle() {
        repeat(6) {
            ShadowLooper.idleMainLooper()
            rule.waitForIdle()
            Thread.sleep(250)
        }
    }

    private fun shoot(name: String) {
        settle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tour(prefix: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ids = seedPacks(app)
        // Screenshots must be deterministic: hold the looping fold demo and living motion still.
        AppContainer.get(app).settings.reducedMotion = true
        val vm = MainViewModel(app)
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            FoldMotionTheme { FoldMotionApp(vm, nav) }
        }
        shoot("${prefix}_01_home")
        rule.runOnUiThread {
            val photo = IntArray(4000) { i -> if (i < 2400) 0xFF46144A.toInt() else if (i < 3400) 0xFF1E0A28.toInt() else 0xFFFA8C3C.toInt() }
            vm.updateForm {
                it.copy(
                    prompt = if (prefix.contains("ar")) "جبال ليلية تحت هلال ونجوم" else "Night mountains under a crescent moon with stars",
                    reference = com.foldmotion.core.scene.ReferencePalette.extract(photo),
                )
            }
            nav.navigate(Routes.CREATE)
        }
        shoot("${prefix}_02_create")
        rule.runOnUiThread { vm.startRandom("golden_dunes") }
        shoot("${prefix}_03_create_random")
        rule.runOnUiThread { nav.navigate(Routes.preview(ids.first())) }
        Thread.sleep(1500)
        shoot("${prefix}_04_preview")
        for (tag in listOf("frame_cover", "frame_main")) {
            val nodes = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
            nodes.forEach { println("BOUNDS $prefix $tag ${it.boundsInRoot}") }
        }
        rule.runOnUiThread { nav.navigate(Routes.LIBRARY) }
        shoot("${prefix}_05_library")
        rule.runOnUiThread { nav.navigate(Routes.SETTINGS) }
        shoot("${prefix}_06_settings")
        rule.runOnUiThread { nav.navigate(Routes.LAB) }
        shoot("${prefix}_07_lab")
        rule.runOnUiThread {
            vm.startRandom("aurora_peaks")
            vm.generate()
            nav.navigate(Routes.GENERATING)
        }
        Thread.sleep(4000)
        shoot("${prefix}_08_generated")
    }

    @Test
    @Config(sdk = [36], qualifiers = "en-w411dp-h960dp-port-xhdpi")
    fun coverEnglish() = tour("cover_en")

    @Test
    @Config(sdk = [36], qualifiers = "ar-ldrtl-w411dp-h960dp-port-xhdpi")
    fun coverArabic() = tour("cover_ar")

    @Test
    @Config(sdk = [36], qualifiers = "en-w750dp-h832dp-port-xhdpi")
    fun mainEnglish() = tour("main_en")

    @Test
    @Config(sdk = [36], qualifiers = "ar-ldrtl-w750dp-h832dp-port-xhdpi")
    fun mainArabic() = tour("main_ar")

    @Test
    @Config(sdk = [36], qualifiers = "en-w750dp-h832dp-port-xhdpi")
    fun foldDemoAngles() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val id = seedPacks(app).first()
        val dir = AppContainer.get(app).packStore.dir(id)
        val cover = com.foldmotion.wallpapers.render.SceneLoader.load(dir, com.foldmotion.core.device.ScreenKind.COVER, 900)
        val main = com.foldmotion.wallpapers.render.SceneLoader.load(dir, com.foldmotion.core.device.ScreenKind.MAIN, 900)
        rule.setContent {
            FoldMotionTheme {
                androidx.compose.foundation.layout.Box {
                    com.foldmotion.wallpapers.ui.components.AuroraBackground(animate = false)
                    androidx.compose.foundation.layout.Column {
                        for (row in listOf(listOf(-180f, -140f, -105f), listOf(-70f, -35f, 0f))) {
                            androidx.compose.foundation.layout.Row {
                                for (a in row) {
                                    com.foldmotion.wallpapers.ui.components.FoldDemo(
                                        cover, main, DeviceProfileRegistry.fold7, 300.dp,
                                        androidx.compose.ui.Modifier.width(250.dp), animate = false, staticAngle = a,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        shoot("fold_demo_angles")
    }

    @Test
    @Config(sdk = [36], qualifiers = "ar-ldrtl-w411dp-h960dp-port-xhdpi")
    fun onboardingArabic() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppContainer.get(app).settings.onboardingDone = false
        val vm = MainViewModel(app)
        rule.setContent { FoldMotionTheme { FoldMotionApp(vm) } }
        shoot("cover_ar_00_onboarding")
    }
}
