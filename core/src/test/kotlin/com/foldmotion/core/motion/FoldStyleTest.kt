package com.foldmotion.core.motion

import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.scene.Celestial
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.core.scene.SceneGeometry
import com.foldmotion.core.scene.TimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldStyleTest {

    private val fold7 = DeviceProfileRegistry.fold7
    private val layers = listOf(
        MotionLayer("far", 0f, -0.04f, -0.04f, 1.04f, 1.04f, opaque = true),
        MotionLayer("mid", 0.5f, -0.06f, 0.55f, 1.06f, 1.06f, opaque = false),
        MotionLayer("near", 1f, -0.09f, 0.75f, 1.09f, 1.09f, opaque = false),
    )

    private fun input(family: SceneFamily, intensity: MotionIntensity = MotionIntensity.LOW, seed: Long = 42L, focusY: Float = 0.36f) = ChoreoInput(
        family, TimeOfDay.NIGHT, Celestial.FULL_MOON, intensity,
        SceneGeometry.frames(fold7, SceneGeometry.defaultFocusX(fold7, rightHalf = true), focusY),
        layers, layers, 0xFFFFF0C0.toInt(), 0xFF19B5B0.toInt(), seed,
    )

    /** What a viewer would notice first: how the scene is uncovered and what the light does. */
    private fun signature(spec: MotionSpec): String =
        "${spec.reveal?.type ?: "-"}|${spec.particles.map { it.mode }.distinct().sorted()}|" +
            spec.tracks.filter { it.target.startsWith("layer:") }.joinToString { "%.2f/%.2f/%.2f".format(it.from.scale, it.from.ty, it.delay) }

    @Test
    fun `every style is safe on every scene, screen and intensity`() {
        for (style in FoldStyle.entries) for (family in SceneFamily.entries) for (intensity in MotionIntensity.entries) for (seed in listOf(1L, 8L, 99L)) {
            for (fy in listOf(0.2f, 0.36f, 0.6f)) {
                val c = ChoreographyComposer.compose(input(family, intensity, seed, fy), style)
                assertTrue(c.style != FoldStyle.AUTO)
                if (style != FoldStyle.AUTO) assertEquals(style, c.style)
                for (spec in listOf(c.open, c.close)) {
                    val where = "$style $family $intensity ${spec.kind}"
                    assertEquals(where, c.style.name, spec.style)
                    assertTrue(where, spec.durationMs in MotionSpec.MIN_DURATION_MS..MotionSpec.MAX_DURATION_MS)
                    assertTrue(where, spec.tracks.isNotEmpty())
                    spec.reveal?.let { assertTrue("$where reveal ${it.from}", it.isWellFormed()) }
                    for (t in spec.tracks) assertTrue("$where ${t.target}", t.delay in 0f..0.9f && t.delay + t.span <= 1.0001f && t.span >= 0.1f)
                    assertTrue(spec.particles.size <= MotionSpec.MAX_BURSTS)
                    assertTrue(spec.particles.all { it.mode in ParticleBurst.MODES && it.count in 1..MotionSpec.MAX_PARTICLES_PER_BURST })
                    // The opaque layer covers the frame at the furthest the spring carries it.
                    val (lo, hi) = spec.easeRange()
                    val far = spec.tracks.firstOrNull { it.target == "layer:far" } ?: continue
                    for (e in listOf(lo, hi, 1f)) {
                        assertTrue(where, MotionSafety.covers(far.from.lerp(far.to, e), spec.pivotX, spec.pivotY, -0.04f, -0.04f, 1.04f, 1.04f))
                    }
                    // Every track lands exactly on its rest pose.
                    for (t in spec.tracks.filter { it.target.startsWith("layer:") }) {
                        val end = MotionSampler.poseFor(spec, t.target, 1f)
                        assertEquals(where, 1f, end.scale, 1e-5f)
                        assertEquals(where, 0f, end.tx, 1e-5f)
                        assertEquals(where, 0f, end.ty, 1e-5f)
                        assertEquals(where, 1f, end.alpha, 1e-5f)
                    }
                }
            }
        }
    }

    @Test
    fun `the seven styles look different from each other on both screens`() {
        val inp = input(SceneFamily.MOUNTAINS)
        for (kind in listOf("open", "close")) {
            val signatures = FoldStyle.CONCRETE.associateWith { style ->
                val c = ChoreographyComposer.compose(inp, style)
                signature(if (kind == "open") c.open else c.close)
            }
            assertEquals("$kind: $signatures", FoldStyle.CONCRETE.size, signatures.values.toSet().size)
        }
        // And the reveal or the particles alone already tell most of them apart.
        val reveals = FoldStyle.CONCRETE.map { ChoreographyComposer.compose(inp, it).open.reveal?.type }.toSet()
        assertTrue(reveals.containsAll(listOf(RevealSpec.UNFOLD, RevealSpec.IRIS, RevealSpec.BOOK, RevealSpec.TILES, RevealSpec.SWEEP, null)))
    }

    @Test
    fun `auto gives different scenes different styles, and both styles of a scene appear`() {
        val picked = HashMap<SceneFamily, MutableSet<FoldStyle>>()
        for (family in SceneFamily.entries) for (seed in 0L until 64L) {
            picked.getOrPut(family) { HashSet() } += ChoreographyComposer.compose(input(family, seed = seed)).style
        }
        for ((family, styles) in picked) assertEquals("$family $styles", 2, styles.size)
        val all = picked.values.flatten().toSet()
        assertEquals(FoldStyle.CONCRETE.toSet(), all)
        assertNotEquals(picked[SceneFamily.SPACE], picked[SceneFamily.CITY])
    }

    @Test
    fun `layers arrive one after another in rise`() {
        val open = ChoreographyComposer.compose(input(SceneFamily.MOUNTAINS), FoldStyle.RISE).open
        val mid = open.tracks.first { it.target == "layer:mid" }
        val near = open.tracks.first { it.target == "layer:near" }
        assertTrue(near.delay > mid.delay)
        assertTrue(near.from.ty > mid.from.ty)
        // Before its delay a track holds its starting pose, then it moves.
        assertEquals(near.from, MotionSampler.poseFor(open, "layer:near", near.delay * 0.5f))
        assertTrue(MotionSampler.poseFor(open, "layer:near", near.delay + 0.2f).ty < near.from.ty)
        assertEquals(ParticleBurst.RISE, open.particles.single().mode)
    }

    @Test
    fun `book swings the right half on main and the whole scene on the cover`() {
        val c = ChoreographyComposer.compose(input(SceneFamily.MINIMAL), FoldStyle.BOOK)
        assertEquals(listOf(0.5f, 104f), c.open.reveal!!.from)
        assertEquals(0f, c.close.reveal!!.from[0])
    }

    @Test
    fun `tiles are close to square on both screens`() {
        val inp = input(SceneFamily.CITY)
        val c = ChoreographyComposer.compose(inp, FoldStyle.TILES)
        for ((spec, frame) in listOf(c.open to inp.frames.main, c.close to inp.frames.cover)) {
            val (cols, rows) = spec.reveal!!.from
            val tileAspect = (frame.width / cols) / (frame.height / rows)
            assertTrue("${spec.kind} $tileAspect", tileAspect in 0.75f..1.33f)
        }
        // Main: a wave from the focal point; cover: top to bottom around the horizontal axis.
        assertEquals(0f, c.open.reveal!!.from[4])
        assertEquals(listOf(0.5f, 0f, 1f), c.close.reveal!!.from.subList(2, 5))
    }

    @Test
    fun `style survives json and older specs default to none`() {
        val c = ChoreographyComposer.compose(input(SceneFamily.SPACE), FoldStyle.WARP)
        val json = CoreInfo.json
        val back = json.decodeFromString(MotionSpec.serializer(), json.encodeToString(MotionSpec.serializer(), c.open))
        assertEquals(c.open, back)
        assertEquals("WARP", back.style)
        val v2 = """{"schemaVersion":2,"kind":"OPEN","durationMs":900,"tracks":[{"target":"layer:far","from":{"scale":1.05}}]}"""
        val old = json.decodeFromString(MotionSpec.serializer(), v2)
        assertEquals(null, old.style)
        assertEquals(0f, old.tracks.single().delay)
        assertEquals(1f, old.tracks.single().span)
    }

    @Test
    fun `malformed reveals are rejected`() {
        assertTrue(!RevealSpec(RevealSpec.TILES, listOf(2.5f, 4f, 0.5f, 0.5f, 0f)).isWellFormed())
        assertTrue(!RevealSpec(RevealSpec.TILES, listOf(40f, 4f, 0.5f, 0.5f, 0f)).isWellFormed())
        assertTrue(!RevealSpec(RevealSpec.BOOK, listOf(0.5f)).isWellFormed())
        assertTrue(!RevealSpec(RevealSpec.SWEEP, listOf(720f)).isWellFormed())
        assertTrue(!RevealSpec("SPIRAL", listOf(0.5f)).isWellFormed())
        assertTrue(RevealSpec(RevealSpec.SWEEP, listOf(90f)).isWellFormed())
    }
}
