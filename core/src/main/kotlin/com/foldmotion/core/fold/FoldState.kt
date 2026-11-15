package com.foldmotion.core.fold

import com.foldmotion.core.device.ScreenKind

/**
 * Phases from blueprint section 07. They describe what the wallpaper is doing, which is not the
 * same thing as the physical hinge posture or the surface state.
 */
enum class FoldPhase {
    UNKNOWN,
    COVER_STABLE,
    OPEN_PENDING,
    MAIN_ENTER,
    MAIN_STABLE,
    CLOSE_PENDING,
    COVER_ENTER,
    SUSPENDED,
    SURFACE_LOST,
}

enum class TransitionKind { OPEN, CLOSE }

/** Why a running transition was cut short. Recorded in the diagnostic trace. */
enum class CutReason {
    SURFACE_DESTROYED,
    VISIBILITY_LOST,
    PROFILE_CHANGED,
    POWER_SAVE,
    REDUCED_MOTION,
    PACK_CHANGED,
}

data class SurfaceInfo(val width: Int, val height: Int, val kind: ScreenKind)

/** Everything that can veto or shape a transition, supplied by the platform layer. */
data class MotionPolicy(
    val reducedMotion: Boolean = false,
    val powerSave: Boolean = false,
    /**
     * A transition only makes sense close to the fold moment. If the new screen first becomes
     * visible later than this after the posture change, the end state is shown without motion.
     */
    val freshnessWindowMs: Long = DEFAULT_FRESHNESS_MS,
    /**
     * When the panels switch while the screen stays on, the system shows its own switch
     * animation over the wallpaper for a moment. The first frame is held this long so the fold
     * animation is not over before it can be seen.
     */
    val liveSwitchHoldMs: Long = DEFAULT_LIVE_SWITCH_HOLD_MS,
    /**
     * Like Samsung's own wallpapers: while the lock screen shows, the wallpaper stays still, and
     * the animation of the screen in use plays once the phone is unlocked. Folding or unfolding
     * an unlocked phone plays it straight away.
     */
    val waitForUnlock: Boolean = true,
) {
    val motionAllowed: Boolean get() = !reducedMotion && !powerSave

    companion object {
        const val DEFAULT_FRESHNESS_MS = 3_000L
        const val DEFAULT_LIVE_SWITCH_HOLD_MS = 250L

        /** An unlock still plays the animation if the wallpaper becomes visible this soon after. */
        const val UNLOCK_WINDOW_MS = 2_000L

        /**
         * Right after an unlock the lock screen is still fading out and can still report itself
         * as showing; a "locked" reading this soon after an unlock is stale and is ignored.
         */
        const val STALE_LOCK_MS = 1_500L
        val LIVE_SWITCH_HOLD_CHOICES = listOf(0L, 150L, 250L, 400L, 600L)
    }
}

data class FoldState(
    val phase: FoldPhase = FoldPhase.UNKNOWN,
    val surface: SurfaceInfo? = null,
    /** Panel of the most recent surface, remembered across surface loss to detect a switch. */
    val lastSurfaceKind: ScreenKind? = null,
    val visible: Boolean = false,
    /** Scene whose assets are loaded for the current pack. */
    val readyScene: ScreenKind? = null,
    /** Scene currently being loaded, to avoid issuing the same load twice. */
    val loadingScene: ScreenKind? = null,
    /** Last screen kind a frame was actually presented for. Null until the first frame. */
    val lastShown: ScreenKind? = null,
    /** Monotonic time of the last cover/main switch, if known. */
    val postureChangedAtMs: Long? = null,
    val activeTransition: TransitionKind? = null,
    /**
     * What was on screen before the running transition. If the transition is cut before the user
     * could see it (the system hides the wallpaper for a moment during a switch with the screen
     * on), the new screen counts as not yet shown, so it plays when it comes back.
     */
    val transitionFrom: ScreenKind? = null,
    /** The panels switched while the screen was on; the next transition holds its first frame. */
    val liveSwitch: Boolean = false,
    val policy: MotionPolicy = MotionPolicy(),
    /** The lock screen (keyguard) is showing. */
    val locked: Boolean = false,
    /** When the user last unlocked, until the animation that unlock owes has played. */
    val unlockedAtMs: Long? = null,
    /** When the last unlock was taken into account, so the two signals of one unlock count once. */
    val unlockHandledAtMs: Long? = null,
    /** System wallpaper picker preview: plays the entry motion once as a demonstration. */
    val isPreview: Boolean = false,
    val previewDemoPlayed: Boolean = false,
)

sealed interface FoldEvent {
    /** Monotonic milliseconds. Never wall-clock, so time changes cannot distort animation. */
    val atMs: Long

    data class SurfaceCreated(override val atMs: Long) : FoldEvent

    data class SurfaceChanged(
        override val atMs: Long,
        val width: Int,
        val height: Int,
        val kind: ScreenKind,
        /** Process-wide hints, as for [VisibilityChanged]: a new engine has no history. */
        val sessionLastShown: ScreenKind? = null,
        val sessionPostureChangedAtMs: Long? = null,
    ) : FoldEvent

    data class SurfaceDestroyed(override val atMs: Long) : FoldEvent

    data class VisibilityChanged(
        override val atMs: Long,
        val visible: Boolean,
        /** Process-wide hints, needed when the system uses one engine per panel. */
        val sessionLastShown: ScreenKind? = null,
        val sessionPostureChangedAtMs: Long? = null,
    ) : FoldEvent

    data class SceneReady(override val atMs: Long, val kind: ScreenKind) : FoldEvent
    data class SceneFailed(override val atMs: Long, val kind: ScreenKind) : FoldEvent
    data class TransitionFinished(override val atMs: Long) : FoldEvent
    data class PolicyChanged(override val atMs: Long, val policy: MotionPolicy) : FoldEvent

    /** The lock screen appeared or went ([locked]); only keeps the state current. */
    data class KeyguardChanged(override val atMs: Long, val locked: Boolean) : FoldEvent

    /** The user unlocked the phone (the lock screen was dismissed): the screen in use animates. */
    data class Unlocked(override val atMs: Long) : FoldEvent
    data class PackChanged(override val atMs: Long) : FoldEvent

    /**
     * The hinge sensor says the phone is now fully open ([screen] = MAIN) or closed (COVER). Lets
     * an engine that stays visible on its panel, with no surface change, still notice the fold.
     */
    data class PostureChanged(
        override val atMs: Long,
        val screen: ScreenKind,
        val sessionLastShown: ScreenKind? = null,
    ) : FoldEvent
}

sealed interface FoldCommand {
    /** Load (or fetch from cache) the assets of a scene, then report SceneReady/SceneFailed. */
    data class LoadScene(val kind: ScreenKind) : FoldCommand

    /** Draw the best frame available while assets load: previous scene, preview, or flat colour. */
    data class DrawPlaceholder(val kind: ScreenKind) : FoldCommand

    /** Draw the resting pose once. No frame loop. */
    data class DrawStatic(val kind: ScreenKind) : FoldCommand

    /** [live]: the panels switched while the screen was on, so hold the first frame briefly. */
    data class StartTransition(val kind: TransitionKind, val screen: ScreenKind, val live: Boolean = false) : FoldCommand

    /** Stop the running transition immediately; a later command decides what to draw. */
    data class CutTransition(val reason: CutReason) : FoldCommand

    /** Cancel all scheduled frames and timers. */
    data object StopFrames : FoldCommand

    /** Record that this screen kind has now been shown to the user. */
    data class MarkShown(val kind: ScreenKind) : FoldCommand
}

data class Reduction(val state: FoldState, val commands: List<FoldCommand>)
