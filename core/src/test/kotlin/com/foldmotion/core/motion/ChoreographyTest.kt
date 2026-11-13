package com.foldmotion.core.motion

import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.scene.Celestial
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.core.scene.SceneGeometry
import com.foldmotion.core.scene.TimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChoreographyTest {

    private val fold7 = DeviceProfileRegistry.fold7
    private val layers = listOf(
        MotionLayer("far", 0f, -0.04f, -0.04f, 1.04f, 1.04f, opaque = true),
        MotionLayer("mid", 0.5f, -0.06f, 0.55f, 1.06f, 1.06f, opaque = false),
        MotionLayer("near", 1f, -0.09f, 0.75f, 1.09f, 1.09f, opaque = false),
    )

    private fun input(family: SceneFamily, time: TimeOfDay, intensity: MotionIntensity = MotionIntensity.LOW): ChoreoInput {
        val focusX = SceneGeometry.defaultFocusX(fold7, rightHalf = true)
        return ChoreoInput(
            family, time, if (time == TimeOfDay.NIGHT) Celestial.FULL_MOON else Celestial.SUN, intensity,
            SceneGeometry.frames(fold7, focusX, 0.36f), layers, layers, 0xFFFFF0C0.toInt(), 0xFF19B5B0.toInt(), 42L,
        )
    }

    @Test
    fun `spring ends exactly at rest and overshoots only a little`() {
        for (zeta in listOf(0.68f, 0.78f, 0.8f, 1f)) {
            val spec = MotionSpec(kind = "OPEN", durationMs = 1100, tracks = emptyList(), spring = SpringSpec.settlingIn(1100, zeta))
            assertEquals(0f, spec.ease(0f), 0f)
            assertEquals(1f, spec.ease(1f), 0f)
            // Settled before the end, so snapping to 1 on the last frame is invisible.
            assertEquals(1f, spec.ease(0.97f), 0.006f)
            val (lo, hi) = spec.easeRange()
            assertTrue(lo >= 0f)
            assertTrue("zeta $zeta overshoot $hi", hi < 1.06f)
        }
    }

    @Test
    fun `every family and intensity composes motion that stays inside the safe envelope`() {
        for (family in SceneFamily.entries) for (time in TimeOfDay.entries) for (intensity in MotionIntensity.entries) {
            val c = ChoreographyComposer.compose(input(family, time, intensity))
            for (spec in listOf(c.open, c.close)) {
                assertTrue(spec.durationMs in MotionSpec.MIN_DURATION_MS..MotionSpec.MAX_DURATION_MS)
                val (lo, hi) = spec.easeRange()
                val far = spec.tracks.firstOrNull { it.target == "layer:far" } ?: continue
                for (e in listOf(lo, hi)) {
                    assertTrue("$family $time ${spec.kind}", MotionSafety.covers(far.from.lerp(far.to, e), spec.pivotX, spec.pivotY, -0.04f, -0.04f, 1.04f, 1.04f))
                }
                assertTrue(spec.particles.size <= MotionSpec.MAX_BURSTS)
                assertTrue(spec.particles.all { it.count <= MotionSpec.MAX_PARTICLES_PER_BURST })
            }
            assertTrue(c.ambient.systems.sumOf { it.count } <= AmbientSpec.MAX_TOTAL)
            if (intensity == MotionIntensity.STATIC_END) assertTrue(c.ambient.isStill)
        }
    }

    @Test
    fun `open unfolds from exactly the slice the cover was showing`() {
        val inp = input(SceneFamily.MOUNTAINS, TimeOfDay.NIGHT)
        val open = ChoreographyComposer.open(inp)
        val r = open.reveal!!
        assertEquals(RevealSpec.UNFOLD, r.type)
        val widthFraction = r.from[2] - r.from[0]
        // Cover is 65.0 mm wide, main 135.8 mm: the starting slice is 48% of the main width.
        assertEquals(65.0f / 135.8f, widthFraction, 0.02f)
        val (fx, _) = inp.frames.focusIn(com.foldmotion.core.device.ScreenKind.MAIN)
        assertTrue(fx in r.from[0]..r.from[2])
        assertEquals(0f, r.progress(0f), 0f)
        assertEquals(1f, r.progress(r.end), 1e-4f)
    }

    @Test
    fun `close gathers into the focus then opens as an iris`() {
        val close = ChoreographyComposer.close(input(SceneFamily.OCEAN, TimeOfDay.DUSK))
        assertEquals(RevealSpec.IRIS, close.reveal!!.type)
        // The iris only starts opening once the light has mostly gathered.
        assertEquals(0f, close.reveal!!.progress(close.particles.first().end * 0.6f), 0f)
        assertEquals(ParticleBurst.CONVERGE, close.particles.first().mode)
        assertTrue(close.particles.first().end <= close.particles[1].end)
    }

    @Test
    fun `ambient life matches the scene`() {
        fun types(f: SceneFamily, t: TimeOfDay) = ChoreographyComposer.ambient(input(f, t)).systems.map { it.type }.toSet()
        assertTrue(AmbientSystem.TWINKLE in types(SceneFamily.MOUNTAINS, TimeOfDay.NIGHT))
        assertTrue(AmbientSystem.SHOOTING_STAR in types(SceneFamily.MOUNTAINS, TimeOfDay.NIGHT))
        assertTrue(AmbientSystem.FIREFLY in types(SceneFamily.MOUNTAINS, TimeOfDay.DUSK))
        assertTrue(AmbientSystem.GLINT in types(SceneFamily.OCEAN, TimeOfDay.DAWN))
        assertTrue(AmbientSystem.DRIFT in types(SceneFamily.DESERT, TimeOfDay.DUSK))
        assertTrue(AmbientSystem.FLOW in types(SceneFamily.ABSTRACT, TimeOfDay.NIGHT))
        assertTrue(AmbientSystem.TWINKLE !in types(SceneFamily.OCEAN, TimeOfDay.DAY))
        assertNotNull(ChoreographyComposer.ambient(input(SceneFamily.MOUNTAINS, TimeOfDay.NIGHT)).halo)
    }

    @Test
    fun `v2 specs round trip through json`() {
        val c = ChoreographyComposer.compose(input(SceneFamily.SPACE, TimeOfDay.NIGHT, MotionIntensity.MEDIUM))
        val json = CoreInfo.json
        assertEquals(c.open, json.decodeFromString(MotionSpec.serializer(), json.encodeToString(MotionSpec.serializer(), c.open)))
        assertEquals(c.ambient, json.decodeFromString(AmbientSpec.serializer(), json.encodeToString(AmbientSpec.serializer(), c.ambient)))
    }

    @Test
    fun `v1 motion files still decode`() {
        val v1 = """{"schemaVersion":1,"kind":"OPEN","durationMs":480,"pivot":[0.5,0.4],"tracks":[{"target":"layer:far","from":{"scale":1.05}}]}"""
        val spec = CoreInfo.json.decodeFromString(MotionSpec.serializer(), v1)
        assertEquals(null, spec.spring)
        assertTrue(abs(spec.ease(0.5f) - spec.easing.ease(0.5f)) < 1e-6f)
    }
}
