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

    /** elapsedRealtime of the last posture change seen by an engine or by the hinge sensor. */
    @Volatile
    var postureChangedAtMs: Long? = null

    fun markPostureChange(source: String, detail: String) {
        postureChangedAtMs = SystemClock.elapsedRealtime()
        PocTrace.log(source, PocEvents.POSTURE_CHANGE, detail)
    }
}

/**
 * Optional early signal from the hinge angle sensor (Android 11+). The engine never depends on it:
 * the surface switch is the authoritative event. It only timestamps the fold moment earlier, and
 * it records angles in the POC trace so the team can see how far ahead of the surface it fires.
 *
 * Hinge sensors report on change and are cheap; the listener is held only while an engine exists.
 */
class HingeMonitor(private val sensors: SensorManager?) : SensorEventListener {

    private var users = 0
    private var posture: Posture = Posture.UNKNOWN
    private var lastLoggedAngle = -100f

    enum class Posture { UNKNOWN, CLOSED, HALF, OPEN }

    val available: Boolean get() = sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null

    fun acquire() {
        if (users++ > 0) return
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) ?: return
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        PocTrace.log(PocEvents.SOURCE_HINGE, "LISTEN", PocEvents.detail("sensor" to sensor.name))
    }

    fun release() {
        if (users == 0) return
        if (--users > 0) return
        sensors?.unregisterListener(this)
        PocTrace.log(PocEvents.SOURCE_HINGE, "STOP")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val angle = event.values.firstOrNull() ?: return
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
            posture = next
            if (previous != Posture.UNKNOWN && next != Posture.HALF) {
                FoldSession.markPostureChange(
                    PocEvents.SOURCE_HINGE,
                    PocEvents.detail(PocEvents.FROM to previous, PocEvents.TO to next, PocEvents.ANGLE to angle.toInt(), PocEvents.VIA to "hinge"),
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
