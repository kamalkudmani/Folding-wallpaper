package com.foldmotion.wallpapers.render

import com.foldmotion.core.device.ScreenKind
import kotlin.math.max
import kotlin.math.min

/**
 * What the phone does to the live liquid on one scene: each wallpaper engine and each preview
 * owns its own (through [LoadedScene.liquidMotion]), so a preview in the app never leaks ripples
 * or drift into the wallpaper, and the two halves of the 3D fold demo, which share one scene,
 * share it too.
 *
 *  - Tilt: the liquid drifts downhill while the phone leans, and very slowly settles back, so the
 *    offset stays bounded.
 *  - Taps: ripples run out from the touch point, at most [MAX] at a time.
 *  - Hinge: while the phone is half open, the liquid pools along the fold (main screen only); the
 *    angle comes from the hinge sensor, one for the whole phone.
 *
 * Main thread only.
 */
class LiquidMotion {

    data class Ripple(val x: Float, val y: Float, val ageSeconds: Float, val strength: Float)

    private data class Tap(val x: Float, val y: Float, val atMs: Long, val strength: Float)

    private val taps = ArrayDeque<Tap>()
    private var flowX = 0f
    private var flowY = 0f
    private var lastMs = -1L

    /** A tap at world ([worldX], [worldY]). */
    fun tap(worldX: Float, worldY: Float, atMs: Long, strength: Float = 1f) {
        taps.addLast(Tap(worldX, worldY, atMs, strength))
        while (taps.size > MAX) taps.removeFirst()
    }

    /** This frame's motion on [kind], advancing the tilt drift to [timeMs]. */
    fun frame(timeMs: Long, tiltX: Float, tiltY: Float, kind: ScreenKind, creaseX: Float): LiquidFrame {
        if (lastMs >= 0) {
            val dt = ((timeMs - lastMs) / 1000f).coerceIn(0f, 0.1f)
            flowX += (tiltX * DRIFT - flowX * SETTLE) * dt
            flowY += (tiltY * DRIFT - flowY * SETTLE) * dt
        }
        lastMs = timeMs
        while (taps.isNotEmpty() && timeMs - taps.first().atMs > RIPPLE_LIFE_MS) taps.removeFirst()
        val ripples = taps.map { Ripple(it.x, it.y, max(0f, (timeMs - it.atMs) / 1000f), it.strength) }
        return LiquidFrame(timeMs, flowX, flowY, if (kind == ScreenKind.MAIN) hingeAmount(hingeAngle) else 0f, creaseX, ripples)
    }

    companion object {
        private const val MAX = LiquidField.MAX_RIPPLES
        private const val RIPPLE_LIFE_MS = 3_500L

        /** Noise units per second for a full tilt. */
        private const val DRIFT = 0.32f

        /** Fraction of the offset given back each second when level. */
        private const val SETTLE = 0.04f

        /** Latest hinge angle in degrees (180 = flat), or NaN when unknown. */
        @Volatile
        var hingeAngle: Float = Float.NaN

        /** 0 when flat or closed, rising to 1 between about 60 and 120 degrees open. */
        fun hingeAmount(angle: Float): Float {
            if (angle.isNaN()) return 0f
            fun ramp(a: Float, b: Float, v: Float): Float {
                val k = ((v - a) / (b - a)).coerceIn(0f, 1f)
                return k * k * (3 - 2 * k)
            }
            return min(ramp(175f, 120f, angle), ramp(25f, 60f, angle))
        }
    }
}
