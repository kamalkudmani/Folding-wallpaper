package com.foldmotion.wallpapers.apply

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.foldmotion.wallpapers.data.ApplyRecord
import com.foldmotion.wallpapers.data.AppSettings
import com.foldmotion.wallpapers.engine.FoldWallpaperService
import com.foldmotion.wallpapers.pack.PackStore
import com.foldmotion.wallpapers.telemetry.PocTrace

sealed interface ApplyStart {
    /** Our wallpaper was already set: the pack was switched in place, no system screen needed. */
    data object AppliedInPlace : ApplyStart

    /** Android must confirm: launch this intent and call [ApplyController.onPickerReturned] after. */
    data class NeedsSystemPicker(val intent: Intent) : ApplyStart

    data object PackMissing : ApplyStart
    data object WallpaperUnsupported : ApplyStart
}

enum class ApplyOutcome { APPLIED, NOT_APPLIED }

/**
 * Applies a pack through the only official path: the system live-wallpaper preview, where the
 * user confirms (blueprint section 03). Success is never assumed from launching the intent; it is
 * checked when the user comes back (section 04).
 */
class ApplyController(
    private val context: Context,
    private val settings: AppSettings,
    private val store: PackStore,
) {
    private val component = ComponentName(context, FoldWallpaperService::class.java)
    private val wallpaperManager: WallpaperManager? = try {
        WallpaperManager.getInstance(context)
    } catch (e: Exception) {
        null
    }

    fun isOurWallpaperActive(): Boolean = try {
        wallpaperManager?.wallpaperInfo?.component == component
    } catch (e: Exception) {
        false
    }

    fun begin(packId: String): ApplyStart {
        val pack = store.get(packId) ?: return ApplyStart.PackMissing
        val wm = wallpaperManager
        if (wm == null || !wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) return ApplyStart.WallpaperUnsupported

        if (isOurWallpaperActive()) {
            commit(packId, pack.manifest.packageVersion, pack.manifest.profileIds.first())
            PocTrace.log("apply", "IN_PLACE", packId)
            return ApplyStart.AppliedInPlace
        }
        settings.pendingPackId = packId
        settings.applyStartedAtMs = System.currentTimeMillis()
        PocTrace.log("apply", "PICKER_OPEN", packId)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        val target = if (intent.resolveActivity(context.packageManager) != null) {
            intent
        } else {
            Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
        }
        return ApplyStart.NeedsSystemPicker(target)
    }

    /** Checks the real result after the system screen closes. */
    fun onPickerReturned(): ApplyOutcome {
        val pending = settings.pendingPackId
        val startedAt = settings.applyStartedAtMs
        val liveEngineSeen = settings.lastLiveEngineAtMs >= startedAt && startedAt > 0
        if (pending != null && (isOurWallpaperActive() || liveEngineSeen)) {
            val pack = store.get(pending)
            commit(pending, pack?.manifest?.packageVersion ?: 1, pack?.manifest?.profileIds?.firstOrNull() ?: "")
            PocTrace.log("apply", "CONFIRMED", "$pending active=${isOurWallpaperActive()} engine=$liveEngineSeen")
            return ApplyOutcome.APPLIED
        }
        settings.pendingPackId = null
        PocTrace.log("apply", "NOT_CONFIRMED", pending ?: "")
        return ApplyOutcome.NOT_APPLIED
    }

    /** Settles a pending apply left over if the app was killed while the picker was open. */
    fun reconcileOnStart() {
        val pending = settings.pendingPackId ?: return
        if (isOurWallpaperActive()) {
            val pack = store.get(pending)
            if (pack != null) commit(pending, pack.manifest.packageVersion, pack.manifest.profileIds.first())
        }
        settings.pendingPackId = null
    }

    private fun commit(packId: String, version: Int, profileId: String) {
        settings.activePackId = packId
        settings.pendingPackId = null
        settings.recordApply(ApplyRecord(packId, version, profileId, System.currentTimeMillis()))
    }
}
