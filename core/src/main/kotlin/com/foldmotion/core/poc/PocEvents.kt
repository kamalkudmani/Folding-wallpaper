package com.foldmotion.core.poc

/**
 * Vocabulary of the proof-of-concept trace. The engine writes these names and keys; the analyzer
 * reads them. Details are space-separated key=value pairs so they stay readable in a text file
 * and parseable without a JSON library on the device.
 */
object PocEvents {
    const val ENGINE_CREATE = "ENGINE_CREATE"
    const val ENGINE_DESTROY = "ENGINE_DESTROY"
    const val SURFACE_CREATED = "SURFACE_CREATED"
    const val SURFACE_CHANGED = "SURFACE_CHANGED"
    const val SURFACE_DESTROYED = "SURFACE_DESTROYED"
    const val VISIBILITY = "VISIBILITY"
    const val POSTURE_CHANGE = "POSTURE_CHANGE"
    const val TRANSITION_START = "TRANSITION_START"
    const val TRANSITION_END = "TRANSITION_END"
    const val TRANSITION_CUT = "TRANSITION_CUT"
    const val SCENE_READY = "SCENE_READY"
    const val SCENE_FAILED = "SCENE_FAILED"

    const val SOURCE_HINGE = "hinge"
    const val ENGINE_PREFIX = "engine#"

    // Keys
    const val PREVIEW = "preview"
    const val DISPLAY = "display"
    const val KIND = "kind"
    const val W = "w"
    const val H = "h"
    const val VISIBLE = "visible"
    const val FROM = "from"
    const val TO = "to"
    const val VIA = "via"
    const val ANGLE = "angle"
    const val DURATION_MS = "durationMs"
    const val SINCE_POSTURE_MS = "sincePostureMs"
    const val FRAMES = "frames"
    const val MAX_GAP_MS = "maxGapMs"
    const val JANK_FRAMES = "jankFrames"
    const val REFRESH_HZ = "refreshHz"
    const val ELAPSED_MS = "elapsedMs"
    const val REASON = "reason"
    const val LOAD_MS = "loadMs"

    /** Builds a detail string from pairs, skipping null values. */
    fun detail(vararg pairs: Pair<String, Any?>): String =
        pairs.filter { it.second != null }.joinToString(" ") { "${it.first}=${it.second.toString().replace(' ', '_')}" }

    fun parse(detail: String): Map<String, String> =
        detail.split(' ').mapNotNull { token ->
            val i = token.indexOf('=')
            if (i <= 0) null else token.substring(0, i) to token.substring(i + 1)
        }.toMap()
}

data class TraceEvent(val atMs: Long, val source: String, val event: String, val detail: String = "") {
    val fields: Map<String, String> by lazy { PocEvents.parse(detail) }
    fun long(key: String): Long? = fields[key]?.toLongOrNull()
    fun int(key: String): Int? = fields[key]?.toIntOrNull()
}
