package com.foldmotion.core.poc

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** How One UI delivered the cover/main switch to the wallpaper during the test. */
enum class EngineStrategy {
    /** One engine survived and its surface was resized to the other panel. */
    SAME_ENGINE_RESIZE,

    /** A new engine was created for the other panel (one engine per display). */
    ENGINE_PER_DISPLAY,

    /** Both happened during the session. */
    MIXED,

    /** No switch was observed. */
    UNKNOWN,
}

enum class Verdict { GO, WARN, NO_GO, INSUFFICIENT_DATA }

data class Stats(val count: Int, val p50: Long, val p95: Long, val max: Long) {
    companion object {
        fun of(values: List<Long>): Stats? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            fun pct(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
            return Stats(sorted.size, pct(0.5), pct(0.95), sorted.last())
        }
    }
}

data class FrameStats(val transitions: Int, val frames: Int, val jankFrames: Int, val worstGapMs: Long, val averageFps: Int) {
    val jankPercent: Double get() = if (frames == 0) 0.0 else jankFrames * 100.0 / frames
}

/** One row of the feasibility table on page 4 of the blueprint. */
data class PocCheck(val id: String, val question: String, val measured: String, val threshold: String, val verdict: Verdict)

data class PocSummary(
    val liveEngines: Int,
    val previewEngines: Int,
    val switches: Int,
    val switchesSameEngine: Int,
    val switchesNewEngine: Int,
    val strategy: EngineStrategy,
    val hingeChanges: Int,
    /** Surface switch time minus the nearest hinge posture change; negative when the hinge came later. */
    val hingeToSurfaceMs: Stats?,
    val transitionsStarted: Int,
    val transitionsCompleted: Int,
    val transitionsCut: Map<String, Int>,
    /** Switches after which no transition started within the freshness window. */
    val staticFallbacks: Int,
    val switchToMotionMs: Stats?,
    val frames: FrameStats,
    val sceneLoadMs: Stats?,
    val sceneFailures: Int,
    val checks: List<PocCheck>,
) {
    val overall: Verdict
        get() = when {
            switches < PocThresholds.MIN_SWITCHES -> Verdict.INSUFFICIENT_DATA
            checks.any { it.verdict == Verdict.NO_GO } -> Verdict.NO_GO
            checks.any { it.verdict == Verdict.WARN || it.verdict == Verdict.INSUFFICIENT_DATA } -> Verdict.WARN
            else -> Verdict.GO
        }

    /** Plain-text block for the exported trace header and the POC report. */
    fun toReport(): String = buildString {
        appendLine("overall=$overall switches=$switches (sameEngine=$switchesSameEngine newEngine=$switchesNewEngine) strategy=$strategy")
        appendLine("engines live=$liveEngines preview=$previewEngines hingeChanges=$hingeChanges")
        appendLine("hingeToSurfaceMs=${hingeToSurfaceMs.fmt()} switchToMotionMs=${switchToMotionMs.fmt()} staticFallbacks=$staticFallbacks")
        appendLine("transitions started=$transitionsStarted completed=$transitionsCompleted cut=$transitionsCut")
        appendLine("frames=${frames.frames} jank=${"%.1f".format(java.util.Locale.ROOT, frames.jankPercent)}% worstGapMs=${frames.worstGapMs} avgFps=${frames.averageFps}")
        appendLine("sceneLoadMs=${sceneLoadMs.fmt()} sceneFailures=$sceneFailures")
        for (c in checks) appendLine("check ${c.id} ${c.verdict}: ${c.measured} (target ${c.threshold})")
    }.trimEnd()

    private fun Stats?.fmt() = if (this == null) "n/a" else "p50=$p50 p95=$p95 max=$max n=$count"
}

/**
 * Provisional pass marks. They are the team's starting targets (blueprint pages 4, 24), to be
 * revised after the first device runs; they are not Android or Samsung figures.
 */
object PocThresholds {
    /** Page 26 asks for 100 fold cycles; below 20 switches nothing is concluded. */
    const val MIN_SWITCHES = 20
    const val MOTION_START_P95_GO_MS = 250L
    const val MOTION_START_P95_WARN_MS = 500L
    const val FALLBACK_GO_PERCENT = 10.0
    const val FALLBACK_WARN_PERCENT = 25.0
    const val SCENE_READY_P95_MS = 700L
    const val JANK_GO_PERCENT = 5.0
    const val JANK_WARN_PERCENT = 10.0

    /** A switch and a hinge change further apart than this are unrelated. */
    const val PAIRING_WINDOW_MS = 3_000L
}

/**
 * Turns a raw engine trace into the measurements the feasibility gate needs. Pure function of
 * the events, so the same code can analyse a trace on the phone or on a laptop.
 */
object TraceAnalyzer {

    private data class Switch(val atMs: Long, val sameEngine: Boolean)

    fun analyze(events: List<TraceEvent>): PocSummary {
        val sorted = events.sortedBy { it.atMs }
        val previewEngines = HashSet<String>()
        val liveEngines = HashSet<String>()
        val engineKind = HashMap<String, String>()
        var globalKind: String? = null
        val switches = ArrayList<Switch>()
        val hinge = ArrayList<Long>()
        val starts = ArrayList<Long>()
        var completed = 0
        val cuts = LinkedHashMap<String, Int>()
        var frames = 0
        var jank = 0
        var worstGap = 0L
        var frameTimeMs = 0L
        var frameTransitions = 0
        val loads = ArrayList<Long>()
        var failures = 0

        for (e in sorted) {
            val engine = e.source.startsWith(PocEvents.ENGINE_PREFIX)
            if (engine && e.source in previewEngines) continue
            when (e.event) {
                PocEvents.ENGINE_CREATE -> if (engine) {
                    if (e.fields[PocEvents.PREVIEW] == "true") previewEngines += e.source else liveEngines += e.source
                }
                PocEvents.SURFACE_CHANGED -> if (engine) {
                    val kind = e.fields[PocEvents.KIND] ?: continue
                    val previousOwn = engineKind[e.source]
                    when {
                        previousOwn != null && previousOwn != kind -> switches += Switch(e.atMs, sameEngine = true)
                        previousOwn == null && globalKind != null && globalKind != kind -> switches += Switch(e.atMs, sameEngine = false)
                    }
                    engineKind[e.source] = kind
                    globalKind = kind
                }
                PocEvents.POSTURE_CHANGE -> if (e.source == PocEvents.SOURCE_HINGE) hinge += e.atMs
                PocEvents.TRANSITION_START -> starts += e.atMs
                PocEvents.TRANSITION_END -> {
                    completed++
                    val f = e.int(PocEvents.FRAMES)
                    if (f != null) {
                        frameTransitions++
                        frames += f
                        jank += e.int(PocEvents.JANK_FRAMES) ?: 0
                        worstGap = maxOf(worstGap, e.long(PocEvents.MAX_GAP_MS) ?: 0)
                        frameTimeMs += e.long(PocEvents.ELAPSED_MS) ?: 0
                    }
                }
                PocEvents.TRANSITION_CUT -> {
                    val reason = e.fields[PocEvents.REASON] ?: "UNKNOWN"
                    cuts[reason] = (cuts[reason] ?: 0) + 1
                }
                PocEvents.SCENE_READY -> e.long(PocEvents.LOAD_MS)?.let { loads += it }
                PocEvents.SCENE_FAILED -> failures++
            }
        }

        val hingeLatency = switches.mapNotNull { s ->
            hinge.minByOrNull { abs(s.atMs - it) }?.let { h -> (s.atMs - h).takeIf { abs(it) <= PocThresholds.PAIRING_WINDOW_MS } }
        }
        var fallbacks = 0
        val toMotion = ArrayList<Long>()
        for ((i, s) in switches.withIndex()) {
            val next = switches.getOrNull(i + 1)?.atMs ?: Long.MAX_VALUE
            val start = starts.firstOrNull { it >= s.atMs && it - s.atMs <= PocThresholds.PAIRING_WINDOW_MS && it < next }
            if (start == null) fallbacks++ else toMotion += start - s.atMs
        }
        val same = switches.count { it.sameEngine }
        val newEngine = switches.size - same
        val strategy = when {
            switches.isEmpty() -> EngineStrategy.UNKNOWN
            same > 0 && newEngine > 0 -> EngineStrategy.MIXED
            same > 0 -> EngineStrategy.SAME_ENGINE_RESIZE
            else -> EngineStrategy.ENGINE_PER_DISPLAY
        }
        val frameStats = FrameStats(
            transitions = frameTransitions,
            frames = frames,
            jankFrames = jank,
            worstGapMs = worstGap,
            averageFps = if (frameTimeMs <= 0) 0 else (frames * 1000.0 / frameTimeMs).roundToInt(),
        )
        val motionStats = Stats.of(toMotion)
        val loadStats = Stats.of(loads)
        val checks = buildChecks(switches.size, strategy, failures, motionStats, fallbacks, loadStats, frameStats)

        return PocSummary(
            liveEngines = liveEngines.size,
            previewEngines = previewEngines.size,
            switches = switches.size,
            switchesSameEngine = same,
            switchesNewEngine = newEngine,
            strategy = strategy,
            hingeChanges = hinge.size,
            hingeToSurfaceMs = Stats.of(hingeLatency),
            transitionsStarted = starts.size,
            transitionsCompleted = completed,
            transitionsCut = cuts,
            staticFallbacks = fallbacks,
            switchToMotionMs = motionStats,
            frames = frameStats,
            sceneLoadMs = loadStats,
            sceneFailures = failures,
            checks = checks,
        )
    }

    private fun buildChecks(
        switches: Int,
        strategy: EngineStrategy,
        failures: Int,
        motion: Stats?,
        fallbacks: Int,
        load: Stats?,
        frames: FrameStats,
    ): List<PocCheck> {
        val enough = switches >= PocThresholds.MIN_SWITCHES
        val t = PocThresholds
        val fallbackPct = if (switches == 0) 0.0 else fallbacks * 100.0 / switches

        val engineVerdict = when {
            !enough -> Verdict.INSUFFICIENT_DATA
            failures > 0 -> Verdict.NO_GO
            strategy == EngineStrategy.MIXED -> Verdict.WARN
            else -> Verdict.GO
        }
        val motionVerdict = when {
            !enough || motion == null -> Verdict.INSUFFICIENT_DATA
            motion.p95 <= t.MOTION_START_P95_GO_MS && fallbackPct <= t.FALLBACK_GO_PERCENT -> Verdict.GO
            motion.p95 <= t.MOTION_START_P95_WARN_MS && fallbackPct <= t.FALLBACK_WARN_PERCENT -> Verdict.WARN
            else -> Verdict.NO_GO
        }
        val loadVerdict = when {
            load == null -> Verdict.INSUFFICIENT_DATA
            load.p95 <= t.SCENE_READY_P95_MS -> Verdict.GO
            else -> Verdict.WARN
        }
        val jankVerdict = when {
            frames.frames == 0 -> Verdict.INSUFFICIENT_DATA
            frames.jankPercent <= t.JANK_GO_PERCENT -> Verdict.GO
            frames.jankPercent <= t.JANK_WARN_PERCENT -> Verdict.WARN
            else -> Verdict.NO_GO
        }
        return listOf(
            PocCheck(
                "Q1", "Does the wallpaper keep working across the display switch?",
                "$switches switches, strategy $strategy, $failures scene failures",
                ">= ${t.MIN_SWITCHES} switches, 0 failures, one consistent strategy", engineVerdict,
            ),
            PocCheck(
                "Q2", "Is there a signal early enough to play the open/close motion?",
                "start p95 ${motion?.p95 ?: "n/a"} ms, fallback ${"%.0f".format(java.util.Locale.ROOT, fallbackPct)}%",
                "p95 <= ${t.MOTION_START_P95_GO_MS} ms and fallback <= ${t.FALLBACK_GO_PERCENT.toInt()}%", motionVerdict,
            ),
            PocCheck(
                "Q3", "Is the scene ready in time?",
                "load p95 ${load?.p95 ?: "n/a"} ms",
                "p95 <= ${t.SCENE_READY_P95_MS} ms", loadVerdict,
            ),
            PocCheck(
                "Q4", "Is the motion smooth?",
                "jank ${"%.1f".format(java.util.Locale.ROOT, frames.jankPercent)}% over ${frames.frames} frames, worst gap ${frames.worstGapMs} ms",
                "jank <= ${t.JANK_GO_PERCENT.toInt()}%", jankVerdict,
            ),
        )
    }
}
