package com.foldmotion.core.device

/**
 * Bundled device profiles.
 *
 * Fold7 is the only launch target (P0). Fold6 and Fold5 carry published panel numbers so packs can
 * be sized for them, but stay UNVERIFIED until each gets its own device test (P2).
 * Every status here is honest about what has actually been measured on hardware: nothing yet.
 */
object DeviceProfileRegistry {

    const val FOLD7_ID = "samsung_z_fold7"
    const val FOLD6_ID = "samsung_z_fold6"
    const val FOLD5_ID = "samsung_z_fold5"
    const val GENERIC_ID = "generic_auto"

    val fold7 = DeviceProfile(
        id = FOLD7_ID,
        profileVersion = 1,
        manufacturer = "samsung",
        marketingName = "Galaxy Z Fold7",
        modelCodePrefixes = listOf("SM-F966"),
        foldType = FoldType.BOOK,
        cover = ScreenSpec(widthPx = 1080, heightPx = 2520, diagonalInches = 6.5f),
        main = ScreenSpec(widthPx = 1968, heightPx = 2184, diagonalInches = 8.0f),
        crease = CreaseSpec(),
        recommendedFps = 60,
        verification = ProfileVerification(
            status = VerificationStatus.UNVERIFIED,
            knownIssues = listOf(
                "POC_PENDING: engine lifecycle across the display switch not yet measured",
                "COVER_MAIN_INDEPENDENT: One UI may keep separate wallpapers per screen",
            ),
        ),
    )

    val fold6 = DeviceProfile(
        id = FOLD6_ID,
        profileVersion = 1,
        manufacturer = "samsung",
        marketingName = "Galaxy Z Fold6",
        modelCodePrefixes = listOf("SM-F956"),
        foldType = FoldType.BOOK,
        cover = ScreenSpec(widthPx = 968, heightPx = 2376, diagonalInches = 6.3f),
        main = ScreenSpec(widthPx = 1856, heightPx = 2160, diagonalInches = 7.6f),
        crease = CreaseSpec(),
        verification = ProfileVerification(status = VerificationStatus.UNVERIFIED),
    )

    val fold5 = DeviceProfile(
        id = FOLD5_ID,
        profileVersion = 1,
        manufacturer = "samsung",
        marketingName = "Galaxy Z Fold5",
        modelCodePrefixes = listOf("SM-F946"),
        foldType = FoldType.BOOK,
        cover = ScreenSpec(widthPx = 904, heightPx = 2316, diagonalInches = 6.2f),
        main = ScreenSpec(widthPx = 1812, heightPx = 2176, diagonalInches = 7.6f),
        crease = CreaseSpec(),
        verification = ProfileVerification(status = VerificationStatus.UNVERIFIED),
    )

    /**
     * Fallback for anything else. It only exists so the engine never crashes on an unknown
     * surface; the UI must not advertise fold transitions for it.
     */
    val generic = DeviceProfile(
        id = GENERIC_ID,
        profileVersion = 1,
        manufacturer = "generic",
        marketingName = "Unsupported device",
        modelCodePrefixes = emptyList(),
        foldType = FoldType.BOOK,
        cover = ScreenSpec(widthPx = 1080, heightPx = 2400),
        main = ScreenSpec(widthPx = 1840, heightPx = 2208),
        crease = CreaseSpec(),
        transitionSupport = "unsupported",
        verification = ProfileVerification(status = VerificationStatus.UNSUPPORTED),
    )

    /** Profiles a user may pick for preview, launch target first. */
    val selectable: List<DeviceProfile> = listOf(fold7, fold6, fold5)

    val all: List<DeviceProfile> = selectable + generic

    fun byId(id: String?): DeviceProfile? = all.firstOrNull { it.id == id }

    val knownIds: Set<String> get() = all.mapTo(linkedSetOf()) { it.id }
}
