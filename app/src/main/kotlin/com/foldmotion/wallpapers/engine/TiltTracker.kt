package com.foldmotion.wallpapers.engine

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI

/**
 * Device tilt for the parallax effect, in [-1, 1] on both axes.
 *
 * Uses the game rotation vector (gyroscope + accelerometer, no magnetometer: cheaper and free of
 * compass jumps). The neutral pose slowly follows how the user is holding the phone, so only
 * changes in tilt move the layers; lying in bed or standing up both rest at zero.
 * Registered only while living motion is on screen.
 */
class TiltTracker(private val sensors: SensorManager?) : SensorEventListener {

    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var basePitch = Float.NaN
    private var baseRoll = Float.NaN
    private var targetX = 0f
    private var targetY = 0f
    private var running = false

    /** Smoothed values, advanced once per drawn frame by [step]. */
    var x = 0f
        private set
    var y = 0f
        private set

    val available: Boolean get() = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null

    fun start() {
        if (running) return
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return
        running = sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        basePitch = Float.NaN
    }

    fun stop() {
        if (!running) return
        sensors?.unregisterListener(this)
        running = false
        targetX = 0f
        targetY = 0f
    }

    /** Eases the drawn value towards the latest reading; call once per frame. */
    fun step(): Boolean {
        val nx = x + (targetX - x) * 0.18f
        val ny = y + (targetY - y) * 0.18f
        val moved = kotlin.math.abs(nx - x) > 1e-4f || kotlin.math.abs(ny - y) > 1e-4f
        x = nx
        y = ny
        return moved
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        val pitch = orientation[1]
        val roll = orientation[2]
        if (basePitch.isNaN()) {
            basePitch = pitch
            baseRoll = roll
        }
        // Neutral pose drifts towards the current one over a few seconds.
        basePitch += wrap(pitch - basePitch) * 0.02f
        baseRoll += wrap(roll - baseRoll) * 0.02f
        targetX = (wrap(roll - baseRoll) / RANGE).coerceIn(-1f, 1f)
        targetY = (wrap(pitch - basePitch) / RANGE).coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun wrap(a: Float): Float {
        var v = a
        while (v > PI) v -= (2 * PI).toFloat()
        while (v < -PI) v += (2 * PI).toFloat()
        return v
    }

    private companion object {
        /** About 20 degrees of tilt gives the full parallax. */
        const val RANGE = 0.35f
    }
}
