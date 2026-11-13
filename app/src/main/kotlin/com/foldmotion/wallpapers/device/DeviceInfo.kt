package com.foldmotion.wallpapers.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.view.WindowManager
import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.DeviceProfileResolver
import com.foldmotion.core.device.DeviceSignals
import com.foldmotion.core.device.MatchConfidence
import com.foldmotion.core.device.ProfileResolution
import com.foldmotion.wallpapers.data.AppSettings

data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val build: String,
    val maxWidthPx: Int,
    val maxHeightPx: Int,
    val hasHingeSensor: Boolean,
    val detected: ProfileResolution,
    /** Profile in use: the user's choice when set, otherwise the detected one. */
    val active: DeviceProfile,
    val manuallyChosen: Boolean,
) {
    /** True when the profile in use is not the one this phone was detected as. */
    val mismatch: Boolean get() = manuallyChosen && active.id != detected.profile.id
    val supported: Boolean get() = detected.confidence != MatchConfidence.GENERIC
}

/** Wraps Build and WindowManager so the pure resolver in :core gets plain values. */
class DeviceInfo(private val context: Context, private val settings: AppSettings) {

    fun snapshot(): DeviceSnapshot {
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        val signals = DeviceSignals(Build.MANUFACTURER ?: "", Build.MODEL ?: "", bounds.width(), bounds.height())
        val detected = DeviceProfileResolver.resolve(signals)
        val override = DeviceProfileRegistry.byId(settings.profileOverride)
        val sensors = context.getSystemService(SensorManager::class.java)
        return DeviceSnapshot(
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            androidRelease = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            build = Build.DISPLAY ?: "",
            maxWidthPx = bounds.width(),
            maxHeightPx = bounds.height(),
            hasHingeSensor = sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null,
            detected = detected,
            active = override ?: detected.profile,
            manuallyChosen = override != null,
        )
    }

    /** Profile the engine uses to classify surfaces: always the real device, never a preview choice. */
    fun engineProfile(): DeviceProfile {
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        return DeviceProfileResolver.resolve(DeviceSignals(Build.MANUFACTURER ?: "", Build.MODEL ?: "", bounds.width(), bounds.height())).profile
    }
}
