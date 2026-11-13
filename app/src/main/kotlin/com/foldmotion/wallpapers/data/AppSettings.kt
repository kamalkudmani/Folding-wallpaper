package com.foldmotion.wallpapers.data

import android.content.Context
import android.content.SharedPreferences
import com.foldmotion.core.CoreInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

enum class LivingMotion { FULL, SUBTLE, OFF }

/** One successful apply, newest first. Lets the engine fall back to the last good pack. */
@Serializable
data class ApplyRecord(val packId: String, val packageVersion: Int, val profileId: String, val appliedAtMs: Long)

/**
 * Small key/value settings shared by the UI process and the wallpaper engine (same process).
 * SharedPreferences is used on purpose: the engine needs a synchronous read on the main thread
 * when it is created, and a change listener to follow the active pack.
 */
class AppSettings(context: Context) {

    val prefs: SharedPreferences = context.getSharedPreferences("foldmotion", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDING, v).apply()

    /** Device profile chosen by hand; null means automatic detection. */
    var profileOverride: String?
        get() = prefs.getString(KEY_PROFILE_OVERRIDE, null)
        set(v) = prefs.edit().putString(KEY_PROFILE_OVERRIDE, v).apply()

    /** In-app reduced motion, on top of the system "remove animations" setting. */
    var reducedMotion: Boolean
        get() = prefs.getBoolean(KEY_REDUCED_MOTION, false)
        set(v) = prefs.edit().putBoolean(KEY_REDUCED_MOTION, v).apply()

    /** Motion between folds: FULL, SUBTLE (fewer particles, 20 fps, no tilt) or OFF. */
    var livingMotion: LivingMotion
        get() = LivingMotion.entries.firstOrNull { it.name == prefs.getString(KEY_LIVING, null) } ?: LivingMotion.FULL
        set(v) = prefs.edit().putString(KEY_LIVING, v.name).apply()

    /** Layers follow the phone's tilt while living motion runs. */
    var tiltParallax: Boolean
        get() = prefs.getBoolean(KEY_TILT, true)
        set(v) = prefs.edit().putBoolean(KEY_TILT, v).apply()

    /** Pack the home-screen wallpaper shows. Only changed after Android confirmed the apply. */
    var activePackId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(v) = prefs.edit().putString(KEY_ACTIVE, v).apply()

    /** Pack shown by the system preview while the user is in the wallpaper picker. */
    var pendingPackId: String?
        get() = prefs.getString(KEY_PENDING, null)
        set(v) = prefs.edit().putString(KEY_PENDING, v).apply()

    var applyStartedAtMs: Long
        get() = prefs.getLong(KEY_APPLY_STARTED, 0L)
        set(v) = prefs.edit().putLong(KEY_APPLY_STARTED, v).apply()

    /** Wall-clock time a non-preview engine was last created; evidence that an apply succeeded. */
    var lastLiveEngineAtMs: Long
        get() = prefs.getLong(KEY_LIVE_ENGINE, 0L)
        set(v) = prefs.edit().putLong(KEY_LIVE_ENGINE, v).apply()

    var importedIds: Set<String>
        get() = prefs.getStringSet(KEY_IMPORTED, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_IMPORTED, v).apply()

    var applyHistory: List<ApplyRecord>
        get() = try {
            prefs.getString(KEY_HISTORY, null)?.let { CoreInfo.json.decodeFromString(ListSerializer(ApplyRecord.serializer()), it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        set(v) = prefs.edit().putString(KEY_HISTORY, CoreInfo.json.encodeToString(ListSerializer(ApplyRecord.serializer()), v.take(20))).apply()

    fun recordApply(record: ApplyRecord) {
        applyHistory = listOf(record) + applyHistory.filterNot { it.packId == record.packId }
    }

    /** Emits the changed key on every write. */
    fun changes(): Flow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null) trySend(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_PROFILE_OVERRIDE = "profile_override"
        const val KEY_REDUCED_MOTION = "reduced_motion"
        const val KEY_ACTIVE = "active_pack"
        const val KEY_PENDING = "pending_pack"
        const val KEY_APPLY_STARTED = "apply_started_at"
        const val KEY_LIVE_ENGINE = "live_engine_at"
        const val KEY_IMPORTED = "imported_ids"
        const val KEY_HISTORY = "apply_history"
        const val KEY_LIVING = "living_motion"
        const val KEY_TILT = "tilt_parallax"
    }
}
