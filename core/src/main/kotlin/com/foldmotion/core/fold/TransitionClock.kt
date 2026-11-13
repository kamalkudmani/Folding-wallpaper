package com.foldmotion.core.fold

import kotlin.math.max
import kotlin.math.min

/**
 * Progress clock for one fold transition that cannot be skipped.
 *
 * Wall-clock progress loses the whole animation when the system freezes the screen during a
 * panel switch: frames stop for half a second and the first frame drawn afterwards is already the
 * last one. Here each frame advances the clock by at most [maxStepMs], so a stall pauses the
 * animation instead of jumping over it, and [holdMs] keeps the first frame while the system's own
 * switch animation covers the screen.
 */
class TransitionClock(
    private val durationMs: Int,
    private val holdMs: Long = 0L,
    private val maxStepMs: Float = MAX_STEP_MS,
) {
    private var lastNanos = Long.MIN_VALUE
    private var heldMs = 0f

    /** Animation time played so far, in milliseconds. */
    var elapsedMs = 0f
        private set

    val progress: Float get() = (elapsedMs / max(1, durationMs)).coerceIn(0f, 1f)
    val isDone: Boolean get() = progress >= 1f

    fun start(nowNanos: Long) {
        lastNanos = nowNanos
        heldMs = 0f
        elapsedMs = 0f
    }

    /** Advances to the frame at [frameNanos] and returns the new progress. */
    fun advance(frameNanos: Long): Float {
        if (lastNanos == Long.MIN_VALUE) start(frameNanos)
        val delta = ((frameNanos - lastNanos) / 1_000_000f).coerceAtLeast(0f)
        lastNanos = max(lastNanos, frameNanos)
        var step = min(delta, maxStepMs)
        if (heldMs < holdMs) {
            val hold = min(step, holdMs - heldMs)
            heldMs += hold
            step -= hold
        }
        elapsedMs += step
        return progress
    }

    companion object {
        /** About two frames at 40 Hz: longer gaps are treated as a pause, not as time passing. */
        const val MAX_STEP_MS = 50f
    }
}
