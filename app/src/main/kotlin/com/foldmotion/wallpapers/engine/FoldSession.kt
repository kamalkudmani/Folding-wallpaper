package com.foldmotion.wallpapers.engine

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.poc.PocEvents
import com.foldmotion.wallpapers.telemetry.PocTrace

/**
 * Process-wide fold facts shared between engines.
 *
 * One UI may give the cover and the main display separate wallpaper engines. An engine that has
 * just been created for the main display never saw the cover, so it would not know a fold just
 * happened. These shared hints let it decide correctly (see FoldEvent.VisibilityChanged).
 */
object FoldSession {
    /** Last screen kind any engine presented. */
    @Volatile
    var lastShown: ScreenKind? = null
        private set

    /** elapsedRealtime when [lastShown] was set. */
    @Volatile
    var lastShownAtMs: Long = 0L
        private set

    /** elapsedRealtime of the last posture change seen by an engine or by the hinge sensor. */
    @Volatile
    var postureChangedAtMs: Long? = null

    /**
     * Last posture the hinge sensor reported, kept for the whole process: One UI may tear an
     * engine down and build a new one in the middle of a fold, and the new listener must still
     * know where the hinge was before.
     */
    @Volatile
    var hingePosture: HingeMonitor.Posture = HingeMonitor.Posture.UNKNOWN

    fun markShown(kind: ScreenKind) {
        lastShown = kind
        lastShownAtMs = SystemClock.elapsedRealtime()
    }

    fun markPostureChange(source: String, detail: String) {
        postureChangedAtMs = SystemClock.elapsedRealtime()
        PocTrace.log(source, PocEvents.POSTURE_CHANGE, detail)
    }

    /**
     * An engine is about to present [kind] while the process last showed the other panel and no
     * fold was recorded since: the fold happened where no listener could see it (an engine being
     * rebuilt, the hinge listener between owners). It is happening now, so record it now.
     */
    fun inferSwitch(source: String, kind: ScreenKind) {
        val last = lastShown ?: return
        if (last == kind) return
        val posture = postureChangedAtMs
        if (posture != null && posture >= lastShownAtMs) return
        markPostureChange(source, PocEvents.detail(PocEvents.FROM to last, PocEvents.TO to kind, PocEvents.VIA to "inferred"))
    }

    /** For tests: forget everything. */
    fun reset() {
        lastShown = null
        lastShownAtMs = 0L
        postureChangedAtMs = null
        hingePosture = HingeMonitor.Posture.UNKNOWN
    }
}

/**
 * Optional early signal from the hinge angle sensor (Android 11+). The engine never depends on it:
 * the surface switch is the authoritative event. It only timestamps the fold moment earlier, and
 * it records angles in the POC trace so the team can see how far ahead of the surface it fires.
 *
 * Hinge sensors report on change and are cheap; the listener is held only while an engine exists.
 */
class HingeMonitor(
    private val sensors: SensorManager?,
    /** Called on the main thread when the phone becomes fully open (MAIN) or closed (COVER). */
    private val onPosture: (ScreenKind) -> Unit = {},
) : SensorEventListener {

    private var users = 0
    private var lastLoggedAngle = -100f
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var listening = false
    private val stopLater = Runnable { stop() }

    enum class Posture { UNKNOWN, CLOSED, HALF, OPEN }

    val available: Boolean get() = sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null

    fun acquire() {
        users++
        handler.removeCallbacks(stopLater)
        if (listening) return
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) ?: return
        listening = sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, handler)
        PocTrace.log(PocEvents.SOURCE_HINGE, "LISTEN", PocEvents.detail("sensor" to sensor.name))
    }

    /**
     * Keeps listening a few seconds after the last engine goes: an engine rebuilt during a fold
     * would otherwise miss the very movement it needs to animate.
     */
    fun release() {
        if (users == 0) return
        if (--users > 0) return
        handler.removeCallbacks(stopLater)
        handler.postDelayed(stopLater, LINGER_MS)
    }

    private fun stop() {
        if (users > 0 || !listening) return
        sensors?.unregisterListener(this)
        listening = false
        PocTrace.log(PocEvents.SOURCE_HINGE, "STOP")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val angle = event.values.firstOrNull() ?: return
        val posture = FoldSession.hingePosture
        // Hysteresis so a phone held near a threshold does not flap.
        val next = when {
            angle <= 25f -> Posture.CLOSED
            angle >= 150f -> Posture.OPEN
            posture == Posture.CLOSED && angle < 40f -> Posture.CLOSED
            posture == Posture.OPEN && angle > 135f -> Posture.OPEN
            else -> Posture.HALF
        }
        if (kotlin.math.abs(angle - lastLoggedAngle) >= 15f) {
            lastLoggedAngle = angle
            PocTrace.log(PocEvents.SOURCE_HINGE, "ANGLE", PocEvents.detail(PocEvents.ANGLE to angle.toInt()))
        }
        if (next != posture) {
            val previous = posture
            FoldSession.hingePosture = next
            if (previous != Posture.UNKNOWN && next != Posture.HALF) {
                FoldSession.markPostureChange(
                    PocEvents.SOURCE_HINGE,
                    PocEvents.detail(PocEvents.FROM to previous, PocEvents.TO to next, PocEvents.ANGLE to angle.toInt(), PocEvents.VIA to "hinge"),
                )
                onPosture(if (next == Posture.OPEN) ScreenKind.MAIN else ScreenKind.COVER)
            }
        }
    }

    companion object {
        const val LINGER_MS = 8_000L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
