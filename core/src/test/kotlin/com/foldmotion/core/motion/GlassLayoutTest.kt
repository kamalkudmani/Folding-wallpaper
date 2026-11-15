package com.foldmotion.core.motion

import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.core.pack.VideoGeometry
import com.foldmotion.core.scene.WorldRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class GlassLayoutTest {

    private fun windows(profile: com.foldmotion.core.device.DeviceProfile): Pair<WorldRect, WorldRect> {
        val main = profile.main!!
        val unit = max(main.widthPx, main.heightPx).toFloat()
        val crops = VideoGeometry.crops(profile, main.widthPx, main.heightPx, focusX = 0.75f)
        fun world(r: List<Float>) = WorldRect(r[0] * main.widthPx / unit, r[1] * main.heightPx / unit, r[2] * main.widthPx / unit, r[3] * main.heightPx / unit)
        return world(crops.main) to world(crops.cover)
    }

    @Test
    fun `every preset is well formed glass`() {
        assertEquals(8, LiquidSpec.PRESETS.size)
        for ((name, spec) in LiquidSpec.PRESETS) {
            assertTrue(name, spec.isWellFormed())
            assertTrue(name, spec.isGlass)
        }
        assertTrue(LiquidSpec.METAL.values.all { it.isWellFormed() && !it.isGlass })
        assertTrue(!LiquidSpec.PRESETS.getValue("daybreak").copy(form = "nope").isWellFormed())
    }

    @Test
    fun `closed the drops sit inside the cover on every phone and open they use the whole screen`() {
        for (profile in DeviceProfileRegistry.selectable) {
            val (main, cover) = windows(profile)
            for ((name, form) in GlassLayout.FORMS) {
                val spec = LiquidSpec.PRESETS.values.first().copy(form = name)
                val closed = GlassLayout.frame(spec, main, ScreenKind.COVER, null, 0)
                for (i in form.elements.indices) {
                    val o = i * 4
                    val r = closed.rad[o]
                    for (x in listOf(closed.seg[o], closed.seg[o + 2])) {
                        assertTrue("${profile.id} $name $i x=$x", x - r >= cover.left - 0.005f && x + r <= cover.right + 0.005f)
                    }
                    for (y in listOf(closed.seg[o + 1], closed.seg[o + 3])) assertTrue("$name $i y", y - r >= -0.005f && y + r <= 1.005f)
                }
                val open = GlassLayout.frame(spec, main, ScreenKind.MAIN, null, 0)
                val xs = form.elements.indices.map { open.seg[it * 4] }
                assertTrue("$name spreads left", xs.min() < main.left + main.width * 0.3f)
                assertTrue("$name spreads right", xs.max() > main.left + main.width * 0.6f)
            }
        }
    }

    @Test
    fun `the fold motion continues exactly where the other screen left off`() {
        val (main, _) = windows(DeviceProfileRegistry.fold7)
        val spec = LiquidSpec.PRESETS.getValue("lagoon")
        val t = 12_345L
        fun f(kind: ScreenKind, p: Float?) = GlassLayout.frame(spec, main, kind, p, t)
        // Opening starts from the closed pose and ends at the open rest.
        assertArrayEquals(f(ScreenKind.COVER, null).seg, f(ScreenKind.MAIN, 0f).seg, 1e-5f)
        assertArrayEquals(f(ScreenKind.MAIN, null).seg, f(ScreenKind.MAIN, 1f).seg, 1e-5f)
        // Closing starts from the open pose and ends gathered.
        assertArrayEquals(f(ScreenKind.MAIN, null).seg, f(ScreenKind.COVER, 0f).seg, 1e-5f)
        assertArrayEquals(f(ScreenKind.COVER, null).seg, f(ScreenKind.COVER, 1f).seg, 1e-5f)
        // Mid-way the drops are between the two, and they leave one after another.
        val mid = (0 until 6).map { GlassLayout.spreadOf(it, 6, ScreenKind.MAIN, 0.3f) }
        assertTrue("$mid", mid.zipWithNext().all { (a, b) -> a >= b } && mid.first() > mid.last())
        // No jumps: small steps of progress move the drops a little.
        var prev = f(ScreenKind.MAIN, 0f).seg
        for (i in 1..120) {
            val cur = f(ScreenKind.MAIN, i / 120f).seg
            for (k in cur.indices) assertTrue("step $i", kotlin.math.abs(cur[k] - prev[k]) < 0.03f)
            prev = cur
        }
    }

    @Test
    fun `between folds the drops drift slowly and loop exactly every ten minutes`() {
        val (main, _) = windows(DeviceProfileRegistry.fold7)
        val spec = LiquidSpec.PRESETS.getValue("daybreak")
        val a = GlassLayout.frame(spec, main, ScreenKind.MAIN, null, 1_000)
        val b = GlassLayout.frame(spec, main, ScreenKind.MAIN, null, 1_000 + LiquidSpec.PERIOD_SECONDS * 1000L)
        assertArrayEquals(a.seg, b.seg, 1e-4f)
        val c = GlassLayout.frame(spec, main, ScreenKind.MAIN, null, 1_016)
        for (k in a.seg.indices) assertTrue(kotlin.math.abs(a.seg[k] - c.seg[k]) < 0.001f)
        val d = GlassLayout.frame(spec, main, ScreenKind.MAIN, null, 8_000)
        assertTrue(a.seg.indices.any { kotlin.math.abs(a.seg[it] - d.seg[it]) > 0.002f })
        // Tilt moves the drops and turns the light; without tilt enabled it does nothing.
        val tilted = GlassLayout.frame(spec, main, ScreenKind.MAIN, null, 1_000, tiltX = 1f)
        assertTrue(tilted.seg[0] > a.seg[0] && tilted.lightX != a.lightX)
        val off = GlassLayout.frame(spec.copy(tilt = false), main, ScreenKind.MAIN, null, 1_000, tiltX = 1f)
        assertArrayEquals(a.seg, off.seg, 1e-6f)
    }
}
