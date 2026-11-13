package com.foldmotion.core.scene

import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SceneTest {

    private val fold7 = DeviceProfileRegistry.fold7

    @Test
    fun `arabic prompt from the blueprint is understood`() {
        val r = PromptAnalyzer.analyze("غابة ليلية مضيئة؛ عند الفتح تظهر السماء كاملةً وتتسع النجوم بلطف، وعند الإغلاق تتجمع حول شجرة واحدة")
        assertEquals(SceneFamily.MOUNTAINS, r.family)
        assertEquals(TimeOfDay.NIGHT, r.timeOfDay)
        assertTrue(r.stars)
        assertEquals(Vegetation.PINES, r.vegetation)
    }

    @Test
    fun `english prompt is understood`() {
        val r = PromptAnalyzer.analyze("Golden desert dunes at sunset with palm trees")
        assertEquals(SceneFamily.DESERT, r.family)
        assertEquals(TimeOfDay.DUSK, r.timeOfDay)
        assertEquals(Hue.GOLD, r.hue)
        assertEquals(Vegetation.PALMS, r.vegetation)
    }

    @Test
    fun `arabic prefixes and spelling variants match`() {
        assertEquals(SceneFamily.OCEAN, PromptAnalyzer.analyze("وبالبحر الهادئ").family)
        assertEquals(SceneFamily.DESERT, PromptAnalyzer.analyze("الصحراء").family)
        assertEquals(Celestial.CRESCENT, PromptAnalyzer.analyze("هِلالٌ").celestial)
        assertEquals(Hue.PURPLE, PromptAnalyzer.analyze("سماء بنفسجيّة").hue)
    }

    @Test
    fun `english words are not matched inside other words`() {
        val r = PromptAnalyzer.analyze("season of research")
        assertEquals(null, r.family)
        assertFalse(r.understoodAnything)
    }

    @Test
    fun `prompt rules enforce 1 to 500 characters and strip direction controls`() {
        assertEquals(PromptCheck.Empty, PromptRules.check("   ‮  "))
        assertTrue(PromptRules.check("x".repeat(501)) is PromptCheck.TooLong)
        val ok = PromptRules.check("  جبال‮   ليلية ")
        assertEquals(PromptCheck.Ok("جبال ليلية"), ok)
    }

    @Test
    fun `same request and seed give the same scene`() {
        val req = GenerationRequest("k", fold7.id, GenerationMode.CUSTOM, "night mountains", seed = 42L)
        assertEquals(SceneComposer.compose(req, fold7).spec, SceneComposer.compose(req, fold7).spec)
    }

    @Test
    fun `prompt choices are honoured`() {
        val req = GenerationRequest("k", fold7.id, GenerationMode.CUSTOM, "بحر عند الغروب", seed = 7L)
        val spec = SceneComposer.compose(req, fold7).spec
        assertEquals(SceneFamily.OCEAN, spec.family)
        assertEquals(TimeOfDay.DUSK, spec.timeOfDay)
    }

    @Test
    fun `random mode uses a curated idea and never repeats the excluded one`() {
        val ids = (0L until 50L).map { IdeaBank.pick(it, excludeId = "aurora_peaks").id }
        assertFalse(ids.contains("aurora_peaks"))
        assertTrue(ids.toSet().size > 5)
        val req = GenerationRequest("k", fold7.id, GenerationMode.RANDOM, ideaId = "golden_dunes", seed = 3L)
        val composed = SceneComposer.compose(req, fold7)
        assertEquals("golden_dunes", composed.idea?.id)
        assertEquals(SceneFamily.DESERT, composed.spec.family)
    }

    @Test
    fun `every idea references an existing palette`() {
        for (idea in IdeaBank.ideas) assertTrue(idea.id, Palettes.byId(idea.paletteId) != null)
        assertEquals(IdeaBank.ideas.size, IdeaBank.ideas.map { it.id }.toSet().size)
    }

    @Test
    fun `cover window is a slice of the main window at the same physical scale`() {
        val focus = SceneGeometry.defaultFocusX(fold7, rightHalf = true)
        val f = SceneGeometry.frames(fold7, focus, 0.36f)
        // Cover sits inside main horizontally.
        assertTrue(f.cover.left >= f.main.left - 1e-4f && f.cover.right <= f.main.right + 1e-4f)
        // Same physical scale: main is about twice as wide, about as tall.
        assertEquals(2.09f, f.main.width / f.cover.width, 0.05f)
        assertEquals(1f, f.main.height / f.cover.height, 0.02f)
        // Focus is visible in both windows.
        for (k in ScreenKind.entries) {
            val (x, y) = f.focusIn(k)
            assertTrue(x in 0.05f..0.95f && y in 0.05f..0.95f)
        }
    }

    @Test
    fun `default focus keeps clear of the crease`() {
        for (right in listOf(true, false)) {
            val focus = SceneGeometry.defaultFocusX(fold7, right)
            val f = SceneGeometry.frames(fold7, focus, 0.4f)
            val (x, _) = f.focusIn(ScreenKind.MAIN)
            val crease = fold7.crease!!
            assertTrue(abs(x - crease.positionFraction) > crease.avoidBandFraction * 2)
        }
    }

    @Test
    fun `seeded random is stable across runs`() {
        val a = SeededRandom(123L)
        val first = List(5) { a.nextLong() }
        val b = SeededRandom(123L)
        assertEquals(first, List(5) { b.nextLong() })
        assertNotEquals(first, List(5) { SeededRandom(124L).nextLong() })
        repeat(1000) { val f = a.nextFloat(); assertTrue(f >= 0f && f < 1f) }
    }

    @Test
    fun `noise is continuous`() {
        var prev = ValueNoise.fbm(0f, 9)
        var x = 0f
        while (x < 10f) {
            x += 0.001f
            val v = ValueNoise.fbm(x, 9)
            assertTrue(abs(v - prev) < 0.02f)
            prev = v
        }
    }
}
