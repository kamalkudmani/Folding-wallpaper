package com.foldmotion.core.scene

import com.foldmotion.core.device.DeviceProfileRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePaletteTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Fake photo made of solid colour regions with the given shares. */
    private fun photo(vararg parts: Pair<Int, Int>): IntArray {
        val out = ArrayList<Int>()
        for ((color, count) in parts) repeat(count) { out += color }
        return out.toIntArray()
    }

    @Test
    fun `dark sunset photo gives a night palette in its own hues`() {
        val sunset = photo(rgb(30, 18, 60) to 5000, rgb(90, 30, 90) to 2500, rgb(240, 120, 40) to 900, rgb(10, 8, 20) to 1500)
        val ref = ReferencePalette.extract(sunset)!!
        assertTrue(ref.isDark)
        val (palette, time) = ReferencePalette.toPalette(ref)
        assertEquals(TimeOfDay.NIGHT, time)
        // Main hue follows the dominant purple, the accent the orange.
        assertTrue(ReferencePalette.hueDistance(Hsl.of(palette.skyTop).h, 260f) < 25f)
        assertTrue(ReferencePalette.hueDistance(Hsl.of(palette.accent).h, 25f) < 25f)
        // Readability: light source bright, terrain darker towards the viewer.
        assertTrue(Hsl.of(palette.glow).l > 0.8f)
        assertTrue(Hsl.of(palette.far).l > Hsl.of(palette.mid).l && Hsl.of(palette.mid).l > Hsl.of(palette.near).l)
    }

    @Test
    fun `bright sky photo gives a daylight palette`() {
        val sky = photo(rgb(120, 180, 240) to 6000, rgb(250, 250, 245) to 2500, rgb(60, 140, 70) to 1500)
        val ref = ReferencePalette.extract(sky)!!
        val (palette, time) = ReferencePalette.toPalette(ref)
        assertEquals(TimeOfDay.DUSK, time)
        assertTrue(ReferencePalette.hueDistance(Hsl.of(palette.skyMid).h, 210f) < 60f)
        assertTrue(Hsl.of(palette.skyBottom).l > Hsl.of(palette.skyTop).l)
    }

    @Test
    fun `grey photo stays muted`() {
        val grey = photo(rgb(120, 120, 120) to 4000, rgb(60, 60, 60) to 3000, rgb(200, 200, 200) to 3000)
        val ref = ReferencePalette.extract(grey)!!
        val (palette, _) = ReferencePalette.toPalette(ref)
        assertTrue(Hsl.of(palette.far).s < 0.2f)
    }

    @Test
    fun `extraction is deterministic and ignores transparent pixels`() {
        val p = photo(rgb(200, 40, 40) to 3000, rgb(40, 40, 200) to 3000, 0x00FFFFFF to 5000)
        assertEquals(ReferencePalette.extract(p), ReferencePalette.extract(p))
        assertNull(ReferencePalette.extract(IntArray(3) { 0 }))
        val ref = ReferencePalette.extract(p)!!
        assertEquals(1f, ref.weights.sum(), 0.001f)
    }

    @Test
    fun `reference colours drive the composed scene but the prompt still picks the place`() {
        val fold7 = DeviceProfileRegistry.fold7
        val ref = ReferencePalette.extract(photo(rgb(20, 60, 50) to 5000, rgb(230, 200, 90) to 1000))
        assertNotNull(ref)
        val req = GenerationRequest("k", fold7.id, GenerationMode.CUSTOM, prompt = "desert dunes", seed = 9L, reference = ref)
        val spec = SceneComposer.compose(req, fold7).spec
        assertEquals(SceneFamily.DESERT, spec.family)
        assertEquals("reference_night", spec.palette.id)
        assertTrue("reference-colors" in spec.tags)
    }

    @Test
    fun `hsl round trip`() {
        for (c in listOf(rgb(255, 0, 0), rgb(18, 181, 176), rgb(226, 184, 97), rgb(7, 24, 36))) {
            val back = Hsl.of(c).toColor()
            for (shift in listOf(16, 8, 0)) {
                assertTrue(kotlin.math.abs(((c ushr shift) and 0xFF) - ((back ushr shift) and 0xFF)) <= 1)
            }
        }
    }
}
