package com.foldmotion.wallpapers

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.wallpapers.ui.FoldMotionApp
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.Routes
import com.foldmotion.wallpapers.ui.theme.FoldMotionTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The owner could not get back to Home after creating a wallpaper. These walk the real UI the
 * way a person does: create, wait for the result, open it, then leave by the visible buttons.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w411dp-h960dp-port-xhdpi")
class NavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var vm: MainViewModel
    private lateinit var nav: NavHostController

    @Before
    fun warmUpNativeGraphics() {
        val bmp = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.RED)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bmp.byteCount))
        val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }.toByteArray()
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        java.nio.LongBuffer.allocate(1)
        java.nio.FloatBuffer.allocate(1)
    }

    private fun start() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppContainer.get(app).settings.onboardingDone = true
        AppContainer.get(app).settings.reducedMotion = true
        vm = MainViewModel(app)
        rule.setContent {
            nav = rememberNavController()
            FoldMotionTheme { FoldMotionApp(vm, nav) }
        }
        settle()
    }

    private fun settle(times: Int = 4) = repeat(times) {
        ShadowLooper.idleMainLooper()
        rule.waitForIdle()
        Thread.sleep(150)
    }

    private fun route() = nav.currentDestination?.route

    private val tabIndex = mapOf("Home" to 0, "Create" to 1, "Library" to 2, "Settings" to 3)

    /** Taps a button of the floating navigation bar. */
    private fun tab(name: String) {
        rule.onNodeWithTag("nav_${tabIndex.getValue(name)}").performClick()
        settle(2)
    }

    /** Create from the Create tab, wait for the pack and open it. */
    private fun createAndOpen() {
        tab("Create")
        assertEquals(Routes.CREATE, route())
        rule.runOnUiThread { vm.startRandom("aurora_peaks") }
        settle()
        rule.onNodeWithText("Generate").performScrollTo().performClick()
        settle()
        assertEquals(Routes.GENERATING, route())
        val deadline = System.currentTimeMillis() + 60_000
        while (rule.onAllNodesWithText("Open pack").fetchSemanticsNodes().isEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(200)
        }
        rule.onNodeWithText("Open pack").performScrollTo().performClick()
        settle()
        assertEquals(Routes.PREVIEW, route())
    }

    @Test
    fun `home is one tap away from the new wallpaper`() {
        start()
        createAndOpen()
        tab("Home")
        assertEquals(Routes.HOME, route())
        // And Create opens Create again, not the old preview.
        tab("Create")
        assertEquals(Routes.CREATE, route())
        tab("Library")
        assertEquals(Routes.LIBRARY, route())
        tab("Home")
        assertEquals(Routes.HOME, route())
    }

    @Test
    fun `back from the new wallpaper returns to create, then home`() {
        start()
        createAndOpen()
        rule.onNodeWithContentDescription("Back").performClick()
        settle()
        assertEquals(Routes.CREATE, route())
        tab("Home")
        assertEquals(Routes.HOME, route())
    }

    @Test
    fun `every tab is reachable from every tab`() {
        start()
        for (from in listOf("Library", "Settings", "Create", "Home")) {
            for (to in listOf("Home", "Create", "Library", "Settings")) {
                tab(from)
                tab(to)
                assertEquals("$from -> $to", to.lowercase(), route())
            }
        }
    }
}
