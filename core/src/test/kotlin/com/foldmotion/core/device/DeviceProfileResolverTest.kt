package com.foldmotion.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileResolverTest {

    private val fold7 = DeviceProfileRegistry.fold7

    @Test
    fun `exact model code resolves fold7 for every regional suffix`() {
        for (model in listOf("SM-F966B", "SM-F966U", "SM-F966U1", "sm-f966n", "SM-F9660")) {
            val r = DeviceProfileResolver.resolve(DeviceSignals("samsung", model, 1080, 2520))
            assertEquals(model, DeviceProfileRegistry.FOLD7_ID, r.profile.id)
            assertEquals(MatchConfidence.EXACT_MODEL, r.confidence)
            assertEquals(ScreenKind.COVER, r.currentScreen)
        }
    }

    @Test
    fun `unknown model code falls back to exact panel geometry`() {
        val r = DeviceProfileResolver.resolve(DeviceSignals("samsung", "SM-X999", 2184, 1968))
        assertEquals(DeviceProfileRegistry.FOLD7_ID, r.profile.id)
        assertEquals(MatchConfidence.GEOMETRY, r.confidence)
        assertEquals(ScreenKind.MAIN, r.currentScreen)
    }

    @Test
    fun `other manufacturers never get a samsung profile`() {
        val r = DeviceProfileResolver.resolve(DeviceSignals("google", "Pixel Fold", 1080, 2520))
        assertEquals(DeviceProfileRegistry.GENERIC_ID, r.profile.id)
        assertEquals(MatchConfidence.GENERIC, r.confidence)
    }

    @Test
    fun `classifier separates cover and main and ignores rotation`() {
        assertEquals(ScreenKind.COVER, ScreenClassifier.classify(fold7, 1080, 2520))
        assertEquals(ScreenKind.COVER, ScreenClassifier.classify(fold7, 2520, 1080))
        assertEquals(ScreenKind.MAIN, ScreenClassifier.classify(fold7, 1968, 2184))
        assertEquals(ScreenKind.MAIN, ScreenClassifier.classify(fold7, 2184, 1968))
        // Scaled surfaces (renderScale, display size settings) keep the same shape.
        assertEquals(ScreenKind.COVER, ScreenClassifier.classify(fold7, 810, 1890))
        assertEquals(ScreenKind.MAIN, ScreenClassifier.classify(fold7, 1476, 1638))
    }

    @Test
    fun `classifier tolerates insets cutting into the surface`() {
        // A surface that excludes a status bar is still clearly one or the other.
        assertEquals(ScreenKind.COVER, ScreenClassifier.classify(fold7, 1080, 2400))
        assertEquals(ScreenKind.MAIN, ScreenClassifier.classify(fold7, 1968, 2050))
    }

    @Test
    fun `classifier rejects empty surfaces`() {
        assertEquals(null, ScreenClassifier.classify(fold7, 0, 2520))
    }

    @Test
    fun `exact panel check`() {
        assertTrue(ScreenClassifier.isExactPanel(fold7, ScreenKind.MAIN, 2184, 1968))
        assertFalse(ScreenClassifier.isExactPanel(fold7, ScreenKind.MAIN, 1080, 2520))
    }

    @Test
    fun `fold7 physical sizes are consistent with published numbers`() {
        // 6.5 in cover and 8.0 in main at the published resolutions.
        val coverH = fold7.cover.heightMm!!
        val mainH = fold7.main!!.heightMm!!
        val coverW = fold7.cover.widthMm!!
        val mainW = fold7.main!!.widthMm!!
        assertEquals(151.7f, coverH, 1.5f)
        assertEquals(65.0f, coverW, 1.5f)
        assertEquals(150.7f, mainH, 1.5f)
        assertEquals(135.8f, mainW, 1.5f)
    }

    @Test
    fun `nothing claims to be verified before a device test`() {
        for (p in DeviceProfileRegistry.all) {
            assertTrue(p.id, p.verification.status != VerificationStatus.VERIFIED)
        }
    }
}
