package com.foldmotion.wallpapers

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.foldmotion.wallpapers.apply.ApplyOutcome
import com.foldmotion.wallpapers.apply.ApplyStart
import com.foldmotion.wallpapers.engine.FoldWallpaperService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class WallpaperServiceTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val container = AppContainer.get(app)

    @Before
    fun warmUp() {
        // Initialise native graphics on this thread before the service renders in the background.
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bmp.byteCount))
        java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
        container.packStore.list().forEach { container.packStore.dir(it.id).deleteRecursively() }
    }

    @Test
    fun `service started with no packs creates a valid default pack and engines`() {
        val service = Robolectric.buildService(FoldWallpaperService::class.java).create().get()
        assertNotNull(service.onCreateEngine())
        val deadline = System.currentTimeMillis() + 60_000
        while (container.packStore.list().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        val packs = container.packStore.list()
        assertEquals(1, packs.size)
        assertTrue(container.validator.validate(packs.first().dir).passed)
        // The default pack is shown, but it was never "applied" by the user.
        assertNull(container.settings.activePackId)
    }

    @Test
    fun `apply without confirmation does not change the active pack`() {
        val service = Robolectric.buildService(FoldWallpaperService::class.java).create().get()
        assertNotNull(service)
        val deadline = System.currentTimeMillis() + 60_000
        while (container.packStore.list().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        val id = container.packStore.list().first().id
        container.settings.activePackId = null
        when (val start = container.apply.begin(id)) {
            is ApplyStart.NeedsSystemPicker -> {
                assertEquals(id, container.settings.pendingPackId)
                // The user backs out of the system screen: no live engine was created afterwards.
                container.settings.lastLiveEngineAtMs = 0
                assertEquals(ApplyOutcome.NOT_APPLIED, container.apply.onPickerReturned())
                assertNull(container.settings.activePackId)
                assertNull(container.settings.pendingPackId)
            }
            else -> throw AssertionError("expected the system picker, got $start")
        }
    }

    @Test
    fun `active pack cannot be deleted`() {
        val service = Robolectric.buildService(FoldWallpaperService::class.java).create().get()
        assertNotNull(service)
        val deadline = System.currentTimeMillis() + 60_000
        while (container.packStore.list().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        val id = container.packStore.list().first().id
        container.settings.activePackId = id
        assertEquals(false, container.packStore.delete(id))
        assertNotNull(container.packStore.get(id))
        container.settings.activePackId = null
    }
}
