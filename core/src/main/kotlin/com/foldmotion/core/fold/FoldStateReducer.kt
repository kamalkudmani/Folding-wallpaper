package com.foldmotion.core.fold

import com.foldmotion.core.device.ScreenKind

/**
 * Pure state machine for the wallpaper engine: (previous state, event) -> (next state, commands).
 *
 * Design rules it enforces:
 *  - Replaying an event never restarts a transition or starts two timers (idempotent).
 *  - Rotation or resize of the same panel is a relayout, never a fold event.
 *  - A transition is tied to the fold moment; when the new panel is first seen too late, or
 *    motion is not allowed, the end state is drawn directly.
 *  - Losing the surface or visibility cuts motion and frees the frame loop at once.
 */
object FoldStateReducer {

    fun reduce(prev: FoldState, event: FoldEvent): Reduction {
        val out = ArrayList<FoldCommand>(4)
        val next = when (event) {
            is FoldEvent.SurfaceCreated -> prev
            is FoldEvent.SurfaceChanged -> onSurfaceChanged(prev, event, out)
            is FoldEvent.SurfaceDestroyed -> onSurfaceDestroyed(prev, out)
            is FoldEvent.VisibilityChanged -> onVisibility(prev, event, out)
            is FoldEvent.SceneReady -> onSceneReady(prev, event, out)
            is FoldEvent.SceneFailed -> onSceneFailed(prev, event, out)
            is FoldEvent.TransitionFinished -> onTransitionFinished(prev, out)
            is FoldEvent.PolicyChanged -> onPolicyChanged(prev, event, out)
            is FoldEvent.PackChanged -> onPackChanged(prev, event, out)
            is FoldEvent.PostureChanged -> onPosture(prev, event, out)
        }
        return Reduction(next, out)
    }

    private fun onSurfaceChanged(
        s: FoldState,
        e: FoldEvent.SurfaceChanged,
        out: MutableList<FoldCommand>,
    ): FoldState {
        val incoming = SurfaceInfo(e.width, e.height, e.kind)
        val previous = s.surface
        if (previous == incoming) return s

        val kindChanged = s.lastSurfaceKind != null && s.lastSurfaceKind != e.kind
        var st = s.copy(
            surface = incoming,
            lastSurfaceKind = e.kind,
            // Another engine may have shown the other panel since this one last drew.
            lastShown = if (s.activeTransition == null) e.sessionLastShown ?: s.lastShown else s.lastShown,
            postureChangedAtMs = latest(s.postureChangedAtMs, e.sessionPostureChangedAtMs),
        )
        if (kindChanged) {
            // Cover <-> main switch. It may arrive as a resize of the same surface or as a brand
            // new surface after the old one was destroyed; both count as the fold moment.
            if (st.activeTransition != null) {
                out += FoldCommand.CutTransition(CutReason.PROFILE_CHANGED)
                st = st.copy(activeTransition = null, transitionFrom = null)
            }
            st = st.copy(postureChangedAtMs = e.atMs, liveSwitch = s.visible)
            st = present(st, e.atMs, out)
        } else if (previous == null || st.activeTransition == null) {
            // First surface, a surface restored for the same panel, or a rotation/resize.
            // A running transition simply continues on the new geometry.
            st = present(st, e.atMs, out)
        }
        return st
    }

    private fun onSurfaceDestroyed(s: FoldState, out: MutableList<FoldCommand>): FoldState {
        if (s.surface == null) return s
        var st = s
        if (s.activeTransition != null) {
            out += FoldCommand.CutTransition(CutReason.SURFACE_DESTROYED)
            st = unshow(st, out)
        }
        out += FoldCommand.StopFrames
        return st.copy(surface = null, activeTransition = null, phase = FoldPhase.SURFACE_LOST)
    }

    /**
     * A transition was cut before it could be seen: the new screen has not really been shown, so
     * the next presentation (within the freshness window) plays it again.
     */
    private fun unshow(s: FoldState, out: MutableList<FoldCommand>): FoldState {
        val from = s.transitionFrom ?: return s
        out += FoldCommand.MarkShown(from)
        return s.copy(lastShown = from, transitionFrom = null)
    }

    private fun latest(a: Long?, b: Long?): Long? = listOfNotNull(a, b).maxOrNull()

    private fun onVisibility(
        s: FoldState,
        e: FoldEvent.VisibilityChanged,
        out: MutableList<FoldCommand>,
    ): FoldState {
        if (e.visible == s.visible) return s
        if (!e.visible) {
            var hidden = s
            if (s.activeTransition != null) {
                out += FoldCommand.CutTransition(CutReason.VISIBILITY_LOST)
                hidden = unshow(hidden, out)
            }
            out += FoldCommand.StopFrames
            return hidden.copy(
                visible = false,
                liveSwitch = false,
                activeTransition = null,
                phase = if (s.surface == null) FoldPhase.SURFACE_LOST else FoldPhase.SUSPENDED,
            )
        }
        val posture = listOfNotNull(s.postureChangedAtMs, e.sessionPostureChangedAtMs).maxOrNull()
        val st = s.copy(
            visible = true,
            lastShown = e.sessionLastShown ?: s.lastShown,
            postureChangedAtMs = posture,
        )
        return present(st, e.atMs, out)
    }

    private fun onSceneReady(
        s: FoldState,
        e: FoldEvent.SceneReady,
        out: MutableList<FoldCommand>,
    ): FoldState {
        val st = s.copy(
            readyScene = e.kind,
            loadingScene = if (s.loadingScene == e.kind) null else s.loadingScene,
        )
        // A load that finished for a panel we already left is kept but not presented.
        return if (st.surface?.kind == e.kind) present(st, e.atMs, out) else st
    }

    private fun onSceneFailed(
        s: FoldState,
        e: FoldEvent.SceneFailed,
        out: MutableList<FoldCommand>,
    ): FoldState {
        val st = s.copy(loadingScene = if (s.loadingScene == e.kind) null else s.loadingScene)
        if (st.visible && st.surface?.kind == e.kind) out += FoldCommand.DrawPlaceholder(e.kind)
        return st
    }

    private fun onTransitionFinished(s: FoldState, out: MutableList<FoldCommand>): FoldState {
        if (s.activeTransition == null) return s
        val kind = s.surface?.kind
        out += FoldCommand.StopFrames
        if (kind != null && s.visible) out += FoldCommand.DrawStatic(kind)
        return s.copy(
            activeTransition = null,
            transitionFrom = null,
            phase = if (kind == null) FoldPhase.SURFACE_LOST else stablePhase(kind),
        )
    }

    private fun onPosture(s: FoldState, e: FoldEvent.PostureChanged, out: MutableList<FoldCommand>): FoldState {
        var st = s.copy(postureChangedAtMs = latest(s.postureChangedAtMs, e.atMs))
        if (st.activeTransition != null || !st.visible || st.surface?.kind != e.screen) return st
        // Only when this panel has not been presented since the fold; otherwise nothing to do.
        val shown = e.sessionLastShown ?: st.lastShown
        if (shown == null || shown == e.screen) return st
        st = st.copy(lastShown = shown, liveSwitch = true)
        return present(st, e.atMs, out)
    }

    private fun onPolicyChanged(
        s: FoldState,
        e: FoldEvent.PolicyChanged,
        out: MutableList<FoldCommand>,
    ): FoldState {
        var st = s.copy(policy = e.policy)
        if (!e.policy.motionAllowed && st.activeTransition != null) {
            val reason = if (e.policy.powerSave) CutReason.POWER_SAVE else CutReason.REDUCED_MOTION
            out += FoldCommand.CutTransition(reason)
            out += FoldCommand.StopFrames
            val kind = st.surface?.kind
            if (kind != null && st.visible) out += FoldCommand.DrawStatic(kind)
            st = st.copy(
                activeTransition = null,
                transitionFrom = null,
                phase = if (kind == null) FoldPhase.SURFACE_LOST else stablePhase(kind),
            )
        }
        return st
    }

    private fun onPackChanged(
        s: FoldState,
        e: FoldEvent.PackChanged,
        out: MutableList<FoldCommand>,
    ): FoldState {
        var st = s
        if (st.activeTransition != null) {
            out += FoldCommand.CutTransition(CutReason.PACK_CHANGED)
            out += FoldCommand.StopFrames
            st = st.copy(activeTransition = null, transitionFrom = null)
        }
        st = st.copy(readyScene = null, loadingScene = null)
        return present(st, e.atMs, out)
    }

    /**
     * Central decision: given a surface, decide what should be on screen now. Safe to call
     * repeatedly; it emits nothing new while a transition for the current panel is running.
     */
    private fun present(s: FoldState, atMs: Long, out: MutableList<FoldCommand>): FoldState {
        val surface = s.surface ?: return s
        val kind = surface.kind

        if (s.readyScene != kind) {
            var st = s
            if (st.loadingScene != kind) {
                // Preload even while hidden so the scene is ready by the time the user returns.
                out += FoldCommand.LoadScene(kind)
                st = st.copy(loadingScene = kind)
            }
            if (st.visible) out += FoldCommand.DrawPlaceholder(kind)
            return st.copy(phase = if (st.visible) pendingPhase(st, kind) else hiddenPhase(st))
        }

        if (!s.visible) return s.copy(phase = hiddenPhase(s))
        if (s.activeTransition != null) return s

        return if (shouldAnimate(s, kind, atMs)) {
            val transition = if (kind == ScreenKind.MAIN) TransitionKind.OPEN else TransitionKind.CLOSE
            out += FoldCommand.StartTransition(transition, kind, s.liveSwitch)
            out += FoldCommand.MarkShown(kind)
            s.copy(
                activeTransition = transition,
                transitionFrom = s.lastShown,
                liveSwitch = false,
                phase = if (kind == ScreenKind.MAIN) FoldPhase.MAIN_ENTER else FoldPhase.COVER_ENTER,
                lastShown = kind,
                previewDemoPlayed = true,
            )
        } else {
            out += FoldCommand.DrawStatic(kind)
            out += FoldCommand.MarkShown(kind)
            s.copy(phase = stablePhase(kind), lastShown = kind, previewDemoPlayed = true, liveSwitch = false)
        }
    }

    private fun shouldAnimate(s: FoldState, kind: ScreenKind, atMs: Long): Boolean {
        if (!s.policy.motionAllowed) return false
        if (s.isPreview && !s.previewDemoPlayed) return true
        val last = s.lastShown ?: return false
        if (last == kind) return false
        val changedAt = s.postureChangedAtMs ?: return true
        return atMs - changedAt <= s.policy.freshnessWindowMs
    }

    private fun stablePhase(kind: ScreenKind) =
        if (kind == ScreenKind.MAIN) FoldPhase.MAIN_STABLE else FoldPhase.COVER_STABLE

    private fun pendingPhase(s: FoldState, kind: ScreenKind): FoldPhase = when {
        s.lastShown == null -> FoldPhase.UNKNOWN
        s.lastShown == kind -> stablePhase(kind)
        kind == ScreenKind.MAIN -> FoldPhase.OPEN_PENDING
        else -> FoldPhase.CLOSE_PENDING
    }

    private fun hiddenPhase(s: FoldState) =
        if (s.surface == null) FoldPhase.SURFACE_LOST else FoldPhase.SUSPENDED
}
