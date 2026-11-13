package com.foldmotion.core.motion

import com.foldmotion.core.CoreInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {

    @Test
    fun `easing is monotonic and pinned at both ends`() {
        val e = EasingSpec()
        assertEquals(0f, e.ease(0f), 0f)
        assertEquals(1f, e.ease(1f), 0f)
        var prev = -1f
        for (i in 0..200) {
            val v = e.ease(i / 200f)
            assertTrue(v >= prev - 1e-4f)
            assertTrue(v in 0f..1f)
            prev = v
        }
    }

    @Test
    fun `coverage check detects exposed edges`() {
        // A layer exactly the frame size cannot zoom out at all.
        assertFalse(MotionSafety.covers(TrackPose(scale = 0.98f), 0.5f, 0.5f, 0f, 0f, 1f, 1f))
        assertTrue(MotionSafety.covers(TrackPose(scale = 1.1f), 0.5f, 0.5f, 0f, 0f, 1f, 1f))
        // Overscan allows some zoom out.
        assertTrue(MotionSafety.covers(TrackPose(scale = 0.95f), 0.5f, 0.5f, -0.04f, -0.04f, 1.04f, 1.04f))
        // Translation can expose an edge even when zoomed in.
        assertFalse(MotionSafety.covers(TrackPose(scale = 1.02f, tx = 0.2f), 0.5f, 0.5f, 0f, 0f, 1f, 1f))
    }

    @Test
    fun `minimum safe scale matches the coverage check`() {
        val rect = floatArrayOf(-0.05f, -0.03f, 1.05f, 1.03f)
        for (pivot in listOf(0.5f to 0.5f, 0.2f to 0.35f, 0.9f to 0.1f)) {
            val s = MotionSafety.minSafeScale(pivot.first, pivot.second, rect[0], rect[1], rect[2], rect[3])
            assertTrue(MotionSafety.covers(TrackPose(scale = s + 1e-3f), pivot.first, pivot.second, rect[0], rect[1], rect[2], rect[3]))
            assertFalse(MotionSafety.covers(TrackPose(scale = s - 0.01f), pivot.first, pivot.second, rect[0], rect[1], rect[2], rect[3]))
        }
    }

    private val layers = listOf(
        MotionLayer("far", 0f, -0.04f, -0.04f, 1.04f, 1.04f, opaque = true),
        MotionLayer("mid", 0.5f, -0.06f, -0.06f, 1.06f, 1.06f, opaque = false),
        MotionLayer("near", 1f, -0.09f, -0.09f, 1.09f, 1.09f, opaque = false),
    )

    @Test
    fun `composed motion stays in the agreed duration range and never exposes the background`() {
        for (intensity in MotionIntensity.entries) {
            for (focus in listOf(0.5f to 0.4f, 0.15f to 0.3f, 0.85f to 0.6f)) {
                val open = MotionComposer.open(intensity, focus.first, focus.second, layers)
                val close = MotionComposer.close(intensity, focus.first, focus.second, layers)
                for (spec in listOf(open, close)) {
                    assertTrue(spec.durationMs in MotionSpec.MIN_DURATION_MS..MotionSpec.MAX_DURATION_MS)
                    val far = spec.tracks.firstOrNull { it.target == "layer:far" } ?: continue
                    for (pose in listOf(far.from, far.to)) {
                        assertTrue(
                            "$intensity ${spec.kind} $focus",
                            MotionSafety.covers(pose, spec.pivotX, spec.pivotY, -0.04f, -0.04f, 1.04f, 1.04f),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `nearer layers move further on open`() {
        val open = MotionComposer.open(MotionIntensity.LOW, 0.5f, 0.4f, layers)
        val scales = listOf("far", "mid", "near").map { id -> open.tracks.first { it.target == "layer:$id" }.from.scale }
        assertTrue(scales[0] < scales[1] && scales[1] < scales[2])
    }

    @Test
    fun `static end has no layer motion`() {
        val spec = MotionComposer.open(MotionIntensity.STATIC_END, 0.5f, 0.5f, layers)
        assertTrue(spec.tracks.none { it.target.startsWith("layer:") })
    }

    @Test
    fun `every track ends at rest so the last frame equals the static frame`() {
        val spec = MotionComposer.close(MotionIntensity.MEDIUM, 0.3f, 0.4f, layers)
        assertEquals(TrackPose.REST, MotionSampler.poseFor(spec, "layer:far", 1f))
        assertEquals(0f, MotionSampler.poseFor(spec, MotionSpec.TARGET_DIM, 1f).alpha, 1e-6f)
        assertEquals(0f, MotionSampler.poseFor(spec, MotionSpec.TARGET_GLOW, 1f).alpha, 1e-6f)
    }

    @Test
    fun `motion json round trips`() {
        val spec = MotionComposer.open(MotionIntensity.LOW, 0.4f, 0.35f, layers)
        val text = CoreInfo.json.encodeToString(MotionSpec.serializer(), spec)
        assertEquals(spec, CoreInfo.json.decodeFromString(MotionSpec.serializer(), text))
    }
}
