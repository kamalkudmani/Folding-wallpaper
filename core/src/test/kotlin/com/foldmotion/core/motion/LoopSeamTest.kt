package com.foldmotion.core.motion

import com.foldmotion.core.video.LoopSeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopSeamTest {

    @Test
    fun `cross-fade runs over the last part of the clip and is smooth at both ends`() {
        val d = 8_000L
        assertEquals(7_300L, LoopSeam.standbyStartMs(d, 700))
        assertEquals(0f, LoopSeam.mix(7_000, d, 700), 0f)
        assertEquals(0f, LoopSeam.mix(7_300, d, 700), 0f)
        assertEquals(0.5f, LoopSeam.mix(7_650, d, 700), 1e-4f)
        assertEquals(1f, LoopSeam.mix(8_000, d, 700), 0f)
        // Smoothstep: the slope is flat at the start and at the end.
        assertTrue(LoopSeam.mix(7_310, d, 700) < 0.002f)
        assertTrue(1f - LoopSeam.mix(7_990, d, 700) < 0.002f)
        // Monotonic.
        var last = -1f
        for (p in 7_300L..8_000L step 10) {
            val m = LoopSeam.mix(p, d, 700)
            assertTrue(m >= last)
            last = m
        }
    }

    @Test
    fun `a seamless clip never cross-fades`() {
        assertEquals(Long.MAX_VALUE, LoopSeam.standbyStartMs(5_000, 0))
        assertEquals(0f, LoopSeam.mix(4_999, 5_000, 0), 0f)
    }

    @Test
    fun `roles swap at the end, with a little tolerance`() {
        assertFalse(LoopSeam.shouldSwap(7_900, 8_000, ended = false))
        assertTrue(LoopSeam.shouldSwap(7_985, 8_000, ended = false))
        assertTrue(LoopSeam.shouldSwap(100, 8_000, ended = true))
    }
}
