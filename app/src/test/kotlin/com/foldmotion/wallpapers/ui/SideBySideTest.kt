package com.foldmotion.wallpapers.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.wallpapers.ui.components.SideBySide
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The two frames must be drawn at one physical scale: same height, cover about 48% as wide. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w750dp-h832dp-xhdpi")
class SideBySideTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun framesShareOnePhysicalScale() {
        val fold7 = DeviceProfileRegistry.fold7
        rule.setContent {
            // Same nesting as PreviewScreen: scrolling column, padded box, width-capped frames.
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    SideBySide(null, null, fold7, 460.dp, Modifier.widthIn(max = 720.dp))
                }
            }
        }
        val cover = rule.onNodeWithTag("frame_cover").getBoundsInRoot()
        val main = rule.onNodeWithTag("frame_main").getBoundsInRoot()
        val coverH = (cover.bottom - cover.top).value
        val mainH = (main.bottom - main.top).value
        val coverW = (cover.right - cover.left).value
        val mainW = (main.right - main.left).value
        println("cover ${coverW}x$coverH main ${mainW}x$mainH")
        assertEquals(coverH, mainH, coverH * 0.02f)
        assertEquals(65.0f / 135.8f, coverW / mainW, 0.02f)
        assertEquals(460f, coverH, 6f)
    }
}
