package com.foldmotion.core.device

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Raw facts read from the device. Kept free of Android types so the logic is unit-testable. */
data class DeviceSignals(
    val manufacturer: String,
    val model: String,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
)

enum class MatchConfidence {
    /** Build.MODEL starts with one of the profile's model codes. */
    EXACT_MODEL,

    /** Model unknown (regional/carrier code) but the panel geometry matches a profile exactly. */
    GEOMETRY,

    /** Nothing matched; the generic fallback is in use. */
    GENERIC,
}

data class ProfileResolution(
    val profile: DeviceProfile,
    val confidence: MatchConfidence,
    /** Which panel the measured display currently looks like, if it could be told. */
    val currentScreen: ScreenKind?,
)

object DeviceProfileResolver {

    fun resolve(
        signals: DeviceSignals,
        profiles: List<DeviceProfile> = DeviceProfileRegistry.selectable,
        fallback: DeviceProfile = DeviceProfileRegistry.generic,
    ): ProfileResolution {
        val model = signals.model.trim().uppercase()
        val manufacturer = signals.manufacturer.trim().lowercase()

        val byModel = profiles.firstOrNull { p ->
            p.manufacturer == manufacturer && p.modelCodePrefixes.any { model.startsWith(it.uppercase()) }
        }
        if (byModel != null) {
            return ProfileResolution(byModel, MatchConfidence.EXACT_MODEL, classifyOrNull(byModel, signals))
        }

        val byGeometry = profiles.firstOrNull { p ->
            p.manufacturer == manufacturer && matchesAnyPanel(p, signals.displayWidthPx, signals.displayHeightPx)
        }
        if (byGeometry != null) {
            return ProfileResolution(byGeometry, MatchConfidence.GEOMETRY, classifyOrNull(byGeometry, signals))
        }

        return ProfileResolution(fallback, MatchConfidence.GENERIC, classifyOrNull(fallback, signals))
    }

    private fun classifyOrNull(profile: DeviceProfile, s: DeviceSignals): ScreenKind? =
        ScreenClassifier.classify(profile, s.displayWidthPx, s.displayHeightPx)

    private fun matchesAnyPanel(p: DeviceProfile, w: Int, h: Int): Boolean {
        fun same(spec: ScreenSpec?) = spec != null &&
            min(w, h) == min(spec.widthPx, spec.heightPx) && max(w, h) == max(spec.widthPx, spec.heightPx)
        return same(p.cover) || same(p.main)
    }
}

/**
 * Decides whether a surface is the cover or the main panel.
 *
 * Uses the long/short ratio only, so rotation never looks like a fold event: a rotated main
 * screen is still MAIN. Cover panels are ~2.3:1 and main panels ~1.1:1, far enough apart that a
 * nearest-ratio decision in log space is unambiguous.
 */
object ScreenClassifier {

    fun classify(profile: DeviceProfile, widthPx: Int, heightPx: Int): ScreenKind? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val main = profile.main
        if (profile.foldType == FoldType.NONE || main == null) return ScreenKind.COVER
        val ratio = max(widthPx, heightPx).toFloat() / min(widthPx, heightPx).toFloat()
        val toCover = abs(ln(ratio / profile.cover.longOverShort))
        val toMain = abs(ln(ratio / main.longOverShort))
        return if (toMain <= toCover) ScreenKind.MAIN else ScreenKind.COVER
    }

    /** True when the surface has exactly the published pixel size of that panel (any rotation). */
    fun isExactPanel(profile: DeviceProfile, kind: ScreenKind, widthPx: Int, heightPx: Int): Boolean {
        val spec = profile.screen(kind)
        return min(widthPx, heightPx) == min(spec.widthPx, spec.heightPx) &&
            max(widthPx, heightPx) == max(spec.widthPx, spec.heightPx)
    }
}
