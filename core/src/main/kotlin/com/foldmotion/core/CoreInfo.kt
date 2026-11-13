package com.foldmotion.core

import kotlinx.serialization.json.Json

/** Version constants shared by the pack format, the validator and the renderer. */
object CoreInfo {
    /** Version of the FoldPack manifest schema this build reads and writes. */
    const val SCHEMA_VERSION = 1

    /** Oldest manifest schema this build can still read (no migrations exist yet). */
    const val MIN_SCHEMA_VERSION = 1

    /**
     * Version of the runtime renderer. 2 adds springs, reveals, particles and ambient motion;
     * 3 adds fold styles (book, tiles and sweep reveals, streak and rise particles, staggered
     * tracks). Packs declare the minimum they need; this build still plays version-1 packs.
     */
    const val RENDERER_VERSION = 3
    const val MIN_RENDERER_VERSION = 1

    const val ASSET_TYPE = "FOLD_LIVE_WALLPAPER"

    /** Single JSON configuration so the app, the engine and the tests agree on the wire format. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        explicitNulls = false
    }
}
