package com.foldmotion.core.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceAnalyzerTest {

    private class TraceBuilder {
        val events = ArrayList<TraceEvent>()
        var t = 1_000L

        fun add(source: String, event: String, vararg pairs: Pair<String, Any?>, dt: Long = 0) {
            t += dt
            events += TraceEvent(t, source, event, PocEvents.detail(*pairs))
        }

        fun engine(id: String, preview: Boolean = false) = add(id, PocEvents.ENGINE_CREATE, PocEvents.PREVIEW to preview, PocEvents.DISPLAY to 0)
        fun surface(id: String, kind: String, dt: Long = 0) = add(id, PocEvents.SURFACE_CHANGED, PocEvents.KIND to kind, dt = dt)
        fun hinge(to: String, dt: Long = 0) = add(PocEvents.SOURCE_HINGE, PocEvents.POSTURE_CHANGE, PocEvents.TO to to, dt = dt)
        fun start(id: String, dt: Long) = add(id, PocEvents.TRANSITION_START, PocEvents.KIND to "OPEN", dt = dt)
        fun end(id: String, frames: Int, jank: Int, dt: Long = 480) =
            add(id, PocEvents.TRANSITION_END, PocEvents.FRAMES to frames, PocEvents.JANK_FRAMES to jank, PocEvents.MAX_GAP_MS to 25, PocEvents.ELAPSED_MS to 480, dt = dt)
        fun ready(id: String, ms: Long) = add(id, PocEvents.SCENE_READY, PocEvents.KIND to "MAIN", PocEvents.LOAD_MS to ms)
    }

    @Test
    fun `same engine resized across folds`() {
        val b = TraceBuilder()
        b.engine("engine#1")
        b.surface("engine#1", "COVER")
        b.ready("engine#1", 120)
        repeat(30) { i ->
            val kind = if (i % 2 == 0) "MAIN" else "COVER"
            b.hinge(if (kind == "MAIN") "OPEN" else "CLOSED", dt = 2_000)
            b.surface("engine#1", kind, dt = 90)
            b.start("engine#1", dt = 40)
            b.end("engine#1", frames = 58, jank = 1)
        }
        val s = TraceAnalyzer.analyze(b.events)
        assertEquals(30, s.switches)
        assertEquals(EngineStrategy.SAME_ENGINE_RESIZE, s.strategy)
        assertEquals(90L, s.hingeToSurfaceMs!!.p50)
        assertEquals(40L, s.switchToMotionMs!!.p95)
        assertEquals(0, s.staticFallbacks)
        assertEquals(30, s.transitionsCompleted)
        assertEquals(1740, s.frames.frames)
        assertEquals(121, s.frames.averageFps)
        assertEquals(Verdict.GO, s.overall)
    }

    @Test
    fun `new engine per display is recognised`() {
        val b = TraceBuilder()
        b.engine("engine#1")
        b.surface("engine#1", "COVER")
        repeat(24) { i ->
            val id = "engine#${i + 2}"
            val kind = if (i % 2 == 0) "MAIN" else "COVER"
            b.hinge(if (kind == "MAIN") "OPEN" else "CLOSED", dt = 3_000)
            b.engine(id)
            b.surface(id, kind, dt = 300)
            b.start(id, dt = 120)
            b.end(id, frames = 29, jank = 0)
        }
        val s = TraceAnalyzer.analyze(b.events)
        assertEquals(24, s.switches)
        assertEquals(24, s.switchesNewEngine)
        assertEquals(EngineStrategy.ENGINE_PER_DISPLAY, s.strategy)
        assertEquals(300L, s.hingeToSurfaceMs!!.p95)
        assertEquals(Verdict.GO, s.checks.first { it.id == "Q2" }.verdict)
    }

    @Test
    fun `late motion and many fallbacks fail the timing check`() {
        val b = TraceBuilder()
        b.engine("engine#1")
        b.surface("engine#1", "COVER")
        repeat(20) { i ->
            val kind = if (i % 2 == 0) "MAIN" else "COVER"
            b.surface("engine#1", kind, dt = 5_000)
            if (i % 3 == 0) b.start("engine#1", dt = 900)
        }
        val s = TraceAnalyzer.analyze(b.events)
        assertEquals(13, s.staticFallbacks)
        assertEquals(Verdict.NO_GO, s.checks.first { it.id == "Q2" }.verdict)
        assertEquals(Verdict.NO_GO, s.overall)
    }

    @Test
    fun `preview engines are ignored`() {
        val b = TraceBuilder()
        b.engine("engine#1", preview = true)
        b.surface("engine#1", "COVER")
        b.surface("engine#1", "MAIN", dt = 1_000)
        b.start("engine#1", dt = 10)
        val s = TraceAnalyzer.analyze(b.events)
        assertEquals(0, s.switches)
        assertEquals(1, s.previewEngines)
        assertEquals(EngineStrategy.UNKNOWN, s.strategy)
    }

    @Test
    fun `small samples never claim a verdict`() {
        val b = TraceBuilder()
        b.engine("engine#1")
        b.surface("engine#1", "COVER")
        b.surface("engine#1", "MAIN", dt = 1_000)
        b.start("engine#1", dt = 50)
        val s = TraceAnalyzer.analyze(b.events)
        assertEquals(Verdict.INSUFFICIENT_DATA, s.overall)
        assertTrue(s.toReport().contains("overall=INSUFFICIENT_DATA"))
    }

    @Test
    fun `heavy jank is reported`() {
        val b = TraceBuilder()
        b.engine("engine#1")
        b.surface("engine#1", "COVER")
        repeat(20) { i ->
            b.surface("engine#1", if (i % 2 == 0) "MAIN" else "COVER", dt = 2_000)
            b.start("engine#1", dt = 30)
            b.end("engine#1", frames = 30, jank = 6)
        }
        val s = TraceAnalyzer.analyze(b.events)
        assertEquals(20.0, s.frames.jankPercent, 0.01)
        assertEquals(Verdict.NO_GO, s.checks.first { it.id == "Q4" }.verdict)
    }

    @Test
    fun `detail strings round trip`() {
        val d = PocEvents.detail("kind" to "MAIN", "w" to 1968, "missing" to null, "note" to "two words")
        assertEquals(mapOf("kind" to "MAIN", "w" to "1968", "note" to "two_words"), PocEvents.parse(d))
        assertNull(TraceEvent(0, "x", "y", d).long("missing"))
    }

    @Test
    fun `percentiles`() {
        val s = Stats.of((1L..100L).toList())!!
        assertEquals(50L, s.p50)
        assertEquals(95L, s.p95)
        assertEquals(100L, s.max)
    }
}
