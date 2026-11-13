package com.foldmotion.core.device

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Which physical panel a surface belongs to on a book-style foldable. */
@Serializable
enum class ScreenKind { COVER, MAIN }

@Serializable
enum class FoldType { BOOK, NONE }

/**
 * Result of testing a profile on a real device (blueprint section 25).
 * Nothing is VERIFIED until a physical test report exists for that build.
 */
@Serializable
enum class VerificationStatus { VERIFIED, PARTIAL, UNVERIFIED, UNSUPPORTED }

/** One panel. Width and height are stored in portrait orientation. */
@Serializable
data class ScreenSpec(
    val widthPx: Int,
    val heightPx: Int,
    val diagonalInches: Float? = null,
) {
    /** width / height in portrait. */
    val aspect: Float get() = widthPx.toFloat() / heightPx.toFloat()

    /** Orientation independent shape descriptor, always >= 1. */
    val longOverShort: Float
        get() = max(widthPx, heightPx).toFloat() / min(widthPx, heightPx).toFloat()

    /** Pixel density derived from the diagonal, or null when the diagonal is unknown. */
    val ppi: Float?
        get() = diagonalInches?.let {
            sqrt((widthPx.toDouble() * widthPx + heightPx.toDouble() * heightPx)).toFloat() / it
        }

    val widthMm: Float? get() = ppi?.let { widthPx / it * 25.4f }
    val heightMm: Float? get() = ppi?.let { heightPx / it * 25.4f }
}

/** Fold line of the main screen, in fractions of the portrait main screen width. */
@Serializable
data class CreaseSpec(
    val positionFraction: Float = 0.5f,
    /** Half-width of the band where faces, text and focal points must not be placed. */
    val avoidBandFraction: Float = 0.06f,
)

@Serializable
data class ProfileVerification(
    val status: VerificationStatus,
    val lastVerifiedOs: String? = null,
    val lastVerifiedOneUi: String? = null,
    val testDate: String? = null,
    val knownIssues: List<String> = emptyList(),
)

/**
 * Per-device description. Published numbers are only a starting point: at runtime the engine
 * always draws to the measured surface size and treats this profile as a classifier.
 */
@Serializable
data class DeviceProfile(
    val id: String,
    val profileVersion: Int,
    val manufacturer: String,
    val marketingName: String,
    /** Prefixes of android.os.Build.MODEL, e.g. "SM-F966" matches SM-F966B, SM-F966U1... */
    val modelCodePrefixes: List<String>,
    val foldType: FoldType,
    val cover: ScreenSpec,
    val main: ScreenSpec? = null,
    val crease: CreaseSpec? = null,
    val recommendedFps: Int = 60,
    /** Scale applied to generated layer textures relative to the panel resolution. */
    val renderScale: Float = 0.85f,
    val transitionSupport: String = "pending_device_poc",
    val verification: ProfileVerification,
) {
    val isFoldable: Boolean get() = foldType == FoldType.BOOK && main != null

    fun screen(kind: ScreenKind): ScreenSpec = when (kind) {
        ScreenKind.COVER -> cover
        ScreenKind.MAIN -> main ?: cover
    }
}
