package com.foldmotion.wallpapers

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.wallpapers.ui.FoldMotionApp
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.Routes
import com.foldmotion.wallpapers.ui.theme.FoldMotionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private val tabIndex = mapOf("Home" to 0, "Add" to 1, "Library" to 2, "Settings" to 3)
    private val tabRoute = mapOf("Home" to Routes.HOME, "Add" to Routes.ADD, "Library" to Routes.LIBRARY, "Settings" to Routes.SETTINGS)

    /** Taps a button of the floating navigation bar. */
    private fun tab(name: String) {
        rule.onNodeWithTag("nav_${tabIndex.getValue(name)}").performClick()
        settle(2)
    }

    /** Gets a Discover wallpaper and waits for it to open as your own. */
    private fun getAndOpen() {
        rule.onNodeWithTag("discover_grid").performScrollToNode(hasTestTag("discover_aurora_peaks"))
        rule.onNodeWithTag("discover_aurora_peaks").performClick()
        settle()
        assertEquals(Routes.DISCOVER, route())
        rule.onNodeWithTag("discover_get").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        val deadline = System.currentTimeMillis() + 60_000
        while (route() != Routes.PREVIEW && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            rule.waitForIdle()
            Thread.sleep(200)
        }
        assertEquals(Routes.PREVIEW, route())
    }

    @Test
    fun `home is one tap away from the new wallpaper`() {
        start()
        getAndOpen()
        tab("Home")
        assertEquals(Routes.HOME, route())
        // And Add opens Add, not the old preview.
        tab("Add")
        assertEquals(Routes.ADD, route())
        tab("Library")
        assertEquals(Routes.LIBRARY, route())
        tab("Home")
        assertEquals(Routes.HOME, route())
    }

    @Test
    fun `the discover add card opens add your own`() {
        start()
        rule.onNodeWithTag("discover_add").performClick()
        settle()
        assertEquals(Routes.ADD, route())
        rule.onNodeWithTag("add_photo").assertExists()
        rule.onNodeWithTag("add_video").assertExists()
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `a discover wallpaper becomes yours in one tap`() {
        start()
        rule.onNodeWithTag("discover_grid").performScrollToNode(hasTestTag("discover_aurora_peaks"))
        rule.onNodeWithTag("discover_aurora_peaks").performClick()
        settle()
        assertEquals(Routes.DISCOVER, route())
        rule.onNodeWithTag("discover_get").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        val deadline = System.currentTimeMillis() + 60_000
        while (route() != Routes.PREVIEW && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            rule.waitForIdle()
            Thread.sleep(200)
        }
        assertEquals(Routes.PREVIEW, route())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val made = AppContainer.get(app).packStore.list().first { it.manifest.title.en == "Aurora over the peaks" }
        // Made with its built-in motion: the picture itself moves.
        assertEquals("NATURAL", made.manifest.motionStyle)
        // Back from the new wallpaper returns to Discover, not to the detail it replaced.
        rule.onNodeWithContentDescription("Back").performClick()
        settle()
        assertEquals(Routes.HOME, route())
        assertTrue(made.manifest.motionStyle != null)
    }

    @Test
    fun `every tab is reachable from every tab`() {
        start()
        for (from in listOf("Library", "Settings", "Add", "Home")) {
            for (to in listOf("Home", "Add", "Library", "Settings")) {
                tab(from)
                tab(to)
                assertEquals("$from -> $to", tabRoute.getValue(to), route())
            }
        }
    }
}
