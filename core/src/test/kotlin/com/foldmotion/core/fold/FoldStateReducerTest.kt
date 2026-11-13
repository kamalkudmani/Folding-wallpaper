package com.foldmotion.core.fold

import com.foldmotion.core.device.ScreenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldStateReducerTest {

    private val cover = ScreenKind.COVER
    private val main = ScreenKind.MAIN

    /** Runs events in order and keeps every command emitted. */
    private class Harness(initial: FoldState = FoldState()) {
        var state = initial
        val log = ArrayList<FoldCommand>()

        fun send(e: FoldEvent): List<FoldCommand> {
            val r = FoldStateReducer.reduce(state, e)
            state = r.state
            log += r.commands
            return r.commands
        }

        /** Simulates the engine answering every LoadScene immediately. */
        fun sendAndLoad(e: FoldEvent): List<FoldCommand> {
            val out = ArrayList(send(e))
            val loads = out.filterIsInstance<FoldCommand.LoadScene>()
            for (l in loads) out += send(FoldEvent.SceneReady(e.atMs, l.kind))
            return out
        }
    }

    private fun boot(h: Harness, kind: ScreenKind, at: Long = 0) {
        h.send(FoldEvent.SurfaceCreated(at))
        h.sendAndLoad(FoldEvent.SurfaceChanged(at, if (kind == cover) 1080 else 1968, if (kind == cover) 2520 else 2184, kind))
        h.sendAndLoad(FoldEvent.VisibilityChanged(at, true))
    }

    @Test
    fun `first appearance draws statically without motion`() {
        val h = Harness()
        boot(h, cover)
        assertEquals(FoldPhase.COVER_STABLE, h.state.phase)
        assertTrue(h.log.none { it is FoldCommand.StartTransition })
        assertTrue(h.log.contains(FoldCommand.DrawStatic(cover)))
        assertEquals(cover, h.state.lastShown)
    }

    @Test
    fun `opening on the same surface plays the open transition once`() {
        val h = Harness()
        boot(h, cover)
        val out = h.sendAndLoad(FoldEvent.SurfaceChanged(1_000, 1968, 2184, main))
        assertTrue(out.contains(FoldCommand.StartTransition(TransitionKind.OPEN, main, live = true)))
        assertEquals(FoldPhase.MAIN_ENTER, h.state.phase)

        h.send(FoldEvent.TransitionFinished(1_500))
        assertEquals(FoldPhase.MAIN_STABLE, h.state.phase)
        assertNull(h.state.activeTransition)
    }

    @Test
    fun `closing plays the close transition`() {
        val h = Harness()
        boot(h, main)
        val out = h.sendAndLoad(FoldEvent.SurfaceChanged(1_000, 1080, 2520, cover))
        assertTrue(out.contains(FoldCommand.StartTransition(TransitionKind.CLOSE, cover, live = true)))
        assertEquals(FoldPhase.COVER_ENTER, h.state.phase)
    }

    @Test
    fun `switch via a new surface and late visibility still animates within the window`() {
        val h = Harness()
        boot(h, cover)
        h.send(FoldEvent.VisibilityChanged(900, false))
        h.send(FoldEvent.SurfaceDestroyed(910))
        h.send(FoldEvent.SurfaceCreated(950))
        h.sendAndLoad(FoldEvent.SurfaceChanged(960, 1968, 2184, main))
        val out = h.sendAndLoad(FoldEvent.VisibilityChanged(1_200, true))
        assertTrue(out.contains(FoldCommand.StartTransition(TransitionKind.OPEN, main)))
    }

    @Test
    fun `a stale switch shows the end state without motion`() {
        val h = Harness()
        boot(h, cover)
        h.send(FoldEvent.VisibilityChanged(900, false))
        h.sendAndLoad(FoldEvent.SurfaceChanged(1_000, 1968, 2184, main))
        // User opened the phone on another app and only reached Home much later.
        val out = h.sendAndLoad(FoldEvent.VisibilityChanged(60_000, true))
        assertTrue(out.none { it is FoldCommand.StartTransition })
        assertTrue(out.contains(FoldCommand.DrawStatic(main)))
        assertEquals(FoldPhase.MAIN_STABLE, h.state.phase)
    }

    @Test
    fun `returning home on the same screen never replays the transition`() {
        val h = Harness()
        boot(h, main)
        repeat(5) { i ->
            h.send(FoldEvent.VisibilityChanged(1_000L + i * 100, false))
            val out = h.sendAndLoad(FoldEvent.VisibilityChanged(1_050L + i * 100, true))
            assertTrue(out.none { it is FoldCommand.StartTransition })
        }
    }

    @Test
    fun `rotation of the same panel is a relayout not a fold`() {
        val h = Harness()
        boot(h, main)
        val out = h.sendAndLoad(FoldEvent.SurfaceChanged(1_000, 2184, 1968, main))
        assertTrue(out.none { it is FoldCommand.StartTransition })
        assertTrue(out.contains(FoldCommand.DrawStatic(main)))
    }

    @Test
    fun `replayed events are idempotent`() {
        val h = Harness()
        boot(h, cover)
        val e = FoldEvent.SurfaceChanged(1_000, 1968, 2184, main)
        val first = h.sendAndLoad(e)
        val second = h.sendAndLoad(e)
        assertEquals(1, first.count { it is FoldCommand.StartTransition })
        assertTrue(second.isEmpty())
        val dupFinish = h.send(FoldEvent.TransitionFinished(1_600))
        assertFalse(dupFinish.isEmpty())
        assertTrue(h.send(FoldEvent.TransitionFinished(1_601)).isEmpty())
        assertTrue(h.send(FoldEvent.VisibilityChanged(1_700, true)).isEmpty())
    }

    @Test
    fun `losing visibility mid transition cuts it and stops frames`() {
        val h = Harness()
        boot(h, cover)
        h.sendAndLoad(FoldEvent.SurfaceChanged(1_000, 1968, 2184, main))
        val out = h.send(FoldEvent.VisibilityChanged(1_100, false))
        assertTrue(out.contains(FoldCommand.CutTransition(CutReason.VISIBILITY_LOST)))
        assertTrue(out.contains(FoldCommand.StopFrames))
        assertEquals(FoldPhase.SUSPENDED, h.state.phase)
        assertNull(h.state.activeTransition)
    }

    @Test
    fun `surface destroyed mid transition releases everything`() {
        val h = Harness()
        boot(h, cover)
        h.sendAndLoad(FoldEvent.SurfaceChanged(1_000, 1968, 2184, main))
        val out = h.send(FoldEvent.SurfaceDestroyed(1_100))
        assertTrue(out.contains(FoldCommand.CutTransition(CutReason.SURFACE_DESTROYED)))
        assertEquals(FoldPhase.SURFACE_LOST, h.state.phase)
        // Destroy twice: no exception, no command.
        assertTrue(h.send(FoldEvent.SurfaceDestroyed(1_101)).isEmpty())
    }

    @Test
    fun `rapid fold flapping never runs two transitions at once`() {
        val h = Harness()
        boot(h, cover)
        var t = 1_000L
        repeat(100) { i ->
            val kind = if (i % 2 == 0) main else cover
            h.sendAndLoad(FoldEvent.SurfaceChanged(t, if (kind == cover) 1080 else 1968, if (kind == cover) 2520 else 2184, kind))
            t += 37
        }
        // Each StartTransition is preceded by a cut of the previous one.
        var running = 0
        for (c in h.log) {
            when (c) {
                is FoldCommand.StartTransition -> { running++; assertTrue(running <= 1) }
                is FoldCommand.CutTransition -> running = 0
                else -> Unit
            }
        }
        h.send(FoldEvent.TransitionFinished(t))
        assertNull(h.state.activeTransition)
    }

    @Test
    fun `reduced motion draws the end state directly`() {
        val h = Harness(FoldState(policy = MotionPolicy(reducedMotion = true)))
        boot(h, cover)
        val out = h.sendAndLoad(FoldEvent.SurfaceChanged(1_000, 1968, 2184, main))
        assertTrue(out.none { it is FoldCommand.StartTransition })
        assertTrue(out.contains(FoldCommand.DrawStatic(main)))
    }

    @Test
    fun `power save during a transition cuts it`() {
        val h = Harness()
        boot(h, cover)
        h.sendAndLoad(FoldEvent.SurfaceChanged(1_000, 1968, 2184, main))
        val out = h.send(FoldEvent.PolicyChanged(1_050, MotionPolicy(powerSave = true)))
        assertTrue(out.contains(FoldCommand.CutTransition(CutReason.POWER_SAVE)))
        assertTrue(out.contains(FoldCommand.DrawStatic(main)))
    }

    @Test
    fun `scene not ready shows a placeholder then the scene`() {
        val h = Harness()
        h.send(FoldEvent.SurfaceCreated(0))
        h.send(FoldEvent.SurfaceChanged(0, 1080, 2520, cover))
        val shown = h.send(FoldEvent.VisibilityChanged(5, true))
        assertTrue(shown.contains(FoldCommand.DrawPlaceholder(cover)))
        assertEquals(1, h.log.count { it is FoldCommand.LoadScene })
        val ready = h.send(FoldEvent.SceneReady(80, cover))
        assertTrue(ready.contains(FoldCommand.DrawStatic(cover)))
    }

    @Test
    fun `a failed scene load keeps the placeholder and does not crash`() {
        val h = Harness()
        h.send(FoldEvent.SurfaceCreated(0))
        h.send(FoldEvent.SurfaceChanged(0, 1080, 2520, cover))
        h.send(FoldEvent.VisibilityChanged(5, true))
        val out = h.send(FoldEvent.SceneFailed(10, cover))
        assertTrue(out.contains(FoldCommand.DrawPlaceholder(cover)))
        assertNull(h.state.loadingScene)
    }

    @Test
    fun `system preview demonstrates the entry motion once`() {
        val h = Harness(FoldState(isPreview = true))
        h.send(FoldEvent.SurfaceCreated(0))
        h.sendAndLoad(FoldEvent.SurfaceChanged(0, 1968, 2184, main))
        val out = h.sendAndLoad(FoldEvent.VisibilityChanged(10, true))
        assertTrue(out.contains(FoldCommand.StartTransition(TransitionKind.OPEN, main)))
        h.send(FoldEvent.TransitionFinished(600))
        h.send(FoldEvent.VisibilityChanged(700, false))
        val again = h.sendAndLoad(FoldEvent.VisibilityChanged(800, true))
        assertTrue(again.none { it is FoldCommand.StartTransition })
    }

    @Test
    fun `session hints from another engine let a fresh engine animate`() {
        // One engine per display: the main-screen engine never saw the cover.
        val h = Harness()
        h.send(FoldEvent.SurfaceCreated(1_000))
        h.sendAndLoad(FoldEvent.SurfaceChanged(1_000, 1968, 2184, main))
        val out = h.sendAndLoad(
            FoldEvent.VisibilityChanged(1_100, true, sessionLastShown = cover, sessionPostureChangedAtMs = 1_000),
        )
        assertTrue(out.contains(FoldCommand.StartTransition(TransitionKind.OPEN, main)))
    }

    @Test
    fun `screen on - a switch that hides the wallpaper for a moment still plays the animation`() {
        // Reported on the Fold7: with the screen on, folding showed no animation. The system
        // reports the wallpaper hidden for a moment during the switch, which cut the transition,
        // and the new panel already counted as shown, so nothing played when it came back.
        for (from in listOf(cover, main)) {
            val to = if (from == cover) main else cover
            val h = Harness()
            boot(h, from)
            val start = h.sendAndLoad(FoldEvent.SurfaceChanged(1_000, if (to == cover) 1080 else 1968, if (to == cover) 2520 else 2184, to))
            assertTrue(start.any { it is FoldCommand.StartTransition && it.screen == to && it.live })
            h.send(FoldEvent.VisibilityChanged(1_050, false))
            val again = h.sendAndLoad(FoldEvent.VisibilityChanged(1_400, true))
            assertTrue("$from -> $to", again.any { it is FoldCommand.StartTransition && it.screen == to })
            // Once it has finished, coming back to the same panel stays still.
            h.send(FoldEvent.TransitionFinished(2_600))
            h.send(FoldEvent.VisibilityChanged(3_000, false))
            assertTrue(h.sendAndLoad(FoldEvent.VisibilityChanged(3_100, true)).none { it is FoldCommand.StartTransition })
        }
    }

    @Test
    fun `screen on - surface torn down and rebuilt during the switch still animates`() {
        val h = Harness()
        boot(h, cover)
        h.sendAndLoad(FoldEvent.SurfaceChanged(1_000, 1968, 2184, main))
        h.send(FoldEvent.SurfaceDestroyed(1_020))
        h.send(FoldEvent.SurfaceCreated(1_200))
        val out = h.sendAndLoad(FoldEvent.SurfaceChanged(1_210, 1968, 2184, main))
        assertTrue(out.any { it is FoldCommand.StartTransition && it.screen == main })
    }

    @Test
    fun `screen on - a new engine for the other panel animates from its first surface`() {
        // One UI may build a fresh engine for the panel being switched to while the screen is on.
        val h = Harness(FoldState(visible = false))
        h.send(FoldEvent.SurfaceCreated(2_000))
        h.sendAndLoad(FoldEvent.SurfaceChanged(2_000, 1080, 2520, cover, sessionLastShown = main, sessionPostureChangedAtMs = 1_950))
        val out = h.sendAndLoad(FoldEvent.VisibilityChanged(2_050, true, sessionLastShown = main, sessionPostureChangedAtMs = 1_950))
        assertTrue(out.contains(FoldCommand.StartTransition(TransitionKind.CLOSE, cover, live = false)))
    }

    @Test
    fun `screen on - an engine that stays visible hears the fold from the hinge`() {
        // Separate engines per panel whose visibility never toggles: only the hinge tells.
        val h = Harness()
        boot(h, main)
        h.send(FoldEvent.TransitionFinished(100))
        val out = h.sendAndLoad(FoldEvent.PostureChanged(5_000, main, sessionLastShown = cover))
        assertTrue(out.contains(FoldCommand.StartTransition(TransitionKind.OPEN, main, live = true)))
        // The hinge settling on the same posture again does nothing.
        h.send(FoldEvent.TransitionFinished(6_200))
        assertTrue(h.sendAndLoad(FoldEvent.PostureChanged(6_300, main, sessionLastShown = main)).isEmpty())
        // A hinge reading for the other panel only timestamps the fold.
        assertTrue(h.sendAndLoad(FoldEvent.PostureChanged(7_000, cover, sessionLastShown = main)).isEmpty())
        assertEquals(7_000L, h.state.postureChangedAtMs)
    }

    @Test
    fun `locked phone opened still animates, and a switch while hidden is not live`() {
        val h = Harness()
        boot(h, cover)
        h.send(FoldEvent.VisibilityChanged(1_000, false))
        h.sendAndLoad(FoldEvent.SurfaceChanged(5_000, 1968, 2184, main))
        val out = h.sendAndLoad(FoldEvent.VisibilityChanged(5_300, true))
        assertTrue(out.contains(FoldCommand.StartTransition(TransitionKind.OPEN, main, live = false)))
    }

    @Test
    fun `transition clock pauses through stalls instead of skipping the animation`() {
        val ms = 1_000_000L
        val clock = TransitionClock(durationMs = 1_000)
        clock.start(0)
        clock.advance(16 * ms)
        // The display switch freezes frames for 700 ms: the next frame continues, not ends.
        val afterStall = clock.advance(716 * ms)
        assertTrue("progress $afterStall", afterStall < 0.1f)
        var t = 716L
        while (!clock.isDone) {
            t += 16
            clock.advance(t * ms)
        }
        assertEquals(1f, clock.progress, 0f)
        // Smooth frames: about the nominal duration.
        val smooth = TransitionClock(durationMs = 1_000)
        smooth.start(0)
        var n = 0
        while (!smooth.isDone) smooth.advance(++n * 16 * ms)
        assertTrue(n in 62..64)
    }

    @Test
    fun `transition clock holds the first frame for a live switch`() {
        val ms = 1_000_000L
        val clock = TransitionClock(durationMs = 1_000, holdMs = 250)
        clock.start(0)
        var t = 0L
        while (t < 240) {
            t += 16
            assertEquals(0f, clock.advance(t * ms), 0f)
        }
        t += 48
        assertTrue(clock.advance(t * ms) > 0f)
    }

    @Test
    fun `pack change reloads and redraws`() {
        val h = Harness()
        boot(h, main)
        val out = h.send(FoldEvent.PackChanged(5_000))
        assertTrue(out.contains(FoldCommand.LoadScene(main)))
        val ready = h.send(FoldEvent.SceneReady(5_050, main))
        assertTrue(ready.contains(FoldCommand.DrawStatic(main)))
    }
}
