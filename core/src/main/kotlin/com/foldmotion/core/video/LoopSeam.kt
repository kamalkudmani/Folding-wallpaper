package com.foldmotion.core.video

/**
 * Timing of the seamless loop: two players take turns. While the active one plays the last
 * [crossfadeMs] of the clip, the standby one starts from the beginning and the picture
 * cross-fades into it; when the active one reaches the end they swap roles. Any clip loops without
 * a jump, and a clip made to loop perfectly uses crossfadeMs = 0 (one player, plain repeat).
 */
object LoopSeam {

    /** When the standby player must start, as a position of the active one. */
    fun standbyStartMs(durationMs: Long, crossfadeMs: Int): Long =
        if (crossfadeMs <= 0) Long.MAX_VALUE else (durationMs - crossfadeMs).coerceAtLeast(0L)

    /** Share of the standby picture, 0..1, at [positionMs] of the active player. */
    fun mix(positionMs: Long, durationMs: Long, crossfadeMs: Int): Float {
        if (crossfadeMs <= 0 || durationMs <= 0) return 0f
        val t = (positionMs - standbyStartMs(durationMs, crossfadeMs)).toFloat() / crossfadeMs
        // Smoothstep: no visible kink where the fade starts or ends.
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** True once the active player is done and the roles should swap. */
    fun shouldSwap(positionMs: Long, durationMs: Long, ended: Boolean): Boolean =
        ended || (durationMs > 0 && positionMs >= durationMs - END_TOLERANCE_MS)

    /** Players report the end a few milliseconds early or late; this much counts as the end. */
    const val END_TOLERANCE_MS = 20L
}
