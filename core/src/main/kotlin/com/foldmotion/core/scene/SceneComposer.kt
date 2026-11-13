package com.foldmotion.core.scene

import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.motion.MotionIntensity
import kotlinx.serialization.Serializable

@Serializable
enum class GenerationMode { CUSTOM, RANDOM }

/** What the user asked for. Mirrors the body of POST /v1/generations in the blueprint. */
data class GenerationRequest(
    val idempotencyKey: String,
    val deviceProfileId: String,
    val mode: GenerationMode,
    val prompt: String = "",
    val ideaId: String? = null,
    val family: SceneFamily? = null,
    val motionIntensity: MotionIntensity = MotionIntensity.LOW,
    val seed: Long,
    /** Colours from a reference photo; only the colours, never the photo. CUSTOM mode only. */
    val reference: ReferenceColors? = null,
)

data class ComposedScene(
    val spec: SceneSpec,
    val interpretation: PromptInterpretation,
    val idea: SceneIdea?,
)

/** Job states from blueprint section 16. */
enum class JobStatus { QUEUED, GENERATING, COMPOSING, VALIDATING, READY, FAILED, CANCEL_REQUESTED, CANCELLED }

/** Pipeline stages from blueprint section 09, reported as they really happen. */
enum class PipelineStage(val status: JobStatus) {
    PROMPT_CHECK(JobStatus.GENERATING),
    STORYBOARD(JobStatus.GENERATING),
    ANCHOR(JobStatus.GENERATING),
    DERIVE_COVER(JobStatus.COMPOSING),
    LAYERS(JobStatus.COMPOSING),
    MOTION_PACK(JobStatus.COMPOSING),
    QA(JobStatus.VALIDATING),
}

/**
 * Storyboard step: turns a request into one deterministic [SceneSpec].
 *
 * Same request and seed always give the same scene. Anything the prompt did not specify is filled
 * from family defaults using the seed, so results vary between seeds but never within one.
 */
object SceneComposer {

    fun compose(request: GenerationRequest, profile: DeviceProfile): ComposedScene {
        val random = SeededRandom(request.seed)

        if (request.mode == GenerationMode.RANDOM) {
            val idea = IdeaBank.byId(request.ideaId) ?: IdeaBank.pick(request.seed)
            return ComposedScene(fromIdea(idea, request.seed, profile, random), PromptInterpretation(), idea)
        }

        val seen = PromptAnalyzer.analyze(request.prompt)
        val family = request.family ?: seen.family ?: random.pick(SceneFamily.entries)
        val fromPhoto = request.reference?.let { ReferencePalette.toPalette(it) }
        val time = seen.timeOfDay ?: fromPhoto?.second ?: defaultTime(family, seen, random)
        // A reference photo decides the colours; otherwise the curated palettes do.
        val palette = fromPhoto?.first ?: Palettes.choose(family, time, seen.hue, random.fork(1))
        val celestial = seen.celestial ?: defaultCelestial(family, time, random.fork(2))
        val night = time == TimeOfDay.NIGHT
        val stars = when {
            seen.stars -> 0.95f
            family == SceneFamily.SPACE -> random.range(0.8f, 1f)
            family == SceneFamily.ABSTRACT -> 0f
            family == SceneFamily.MINIMAL -> if (night) 0.25f else 0f
            night -> random.range(0.6f, 0.95f)
            time == TimeOfDay.DUSK -> random.range(0.1f, 0.25f)
            else -> 0f
        }
        val aurora = seen.aurora || (family == SceneFamily.MOUNTAINS && night && palette.id == "night_aurora")
        val vegetation = seen.vegetation ?: when (family) {
            SceneFamily.MOUNTAINS -> if (random.chance(0.7f)) Vegetation.PINES else Vegetation.NONE
            SceneFamily.DESERT -> if (random.chance(0.6f)) Vegetation.PALMS else Vegetation.NONE
            else -> Vegetation.NONE
        }
        val spec = SceneSpec(
            family = family,
            seed = request.seed,
            palette = palette,
            timeOfDay = time,
            celestial = celestial,
            starDensity = stars,
            aurora = aurora,
            vegetation = vegetation,
            focusX = SceneGeometry.defaultFocusX(profile, rightHalf = random.chance(0.7f)),
            focusY = focusY(family, random),
            titleAr = "${familyAr(family)} · ${timeAr(time, family)}",
            titleEn = "${familyEn(family)} · ${timeEn(time, family)}",
            tags = listOfNotNull(family.name.lowercase(), time.name.lowercase(), palette.id, if (fromPhoto != null) "reference-colors" else null),
        )
        return ComposedScene(spec, seen, null)
    }

    private fun fromIdea(idea: SceneIdea, seed: Long, profile: DeviceProfile, random: SeededRandom): SceneSpec =
        SceneSpec(
            family = idea.family,
            seed = seed,
            palette = Palettes.byId(idea.paletteId) ?: Palettes.choose(idea.family, idea.timeOfDay, null, random.fork(1)),
            timeOfDay = idea.timeOfDay,
            celestial = idea.celestial,
            starDensity = idea.starDensity,
            aurora = idea.aurora,
            vegetation = idea.vegetation,
            focusX = SceneGeometry.defaultFocusX(profile, rightHalf = random.chance(0.7f)),
            focusY = focusY(idea.family, random),
            titleAr = idea.titleAr,
            titleEn = idea.titleEn,
            tags = listOf(idea.family.name.lowercase(), idea.timeOfDay.name.lowercase(), idea.paletteId, "idea:${idea.id}"),
        )

    private fun defaultTime(family: SceneFamily, seen: PromptInterpretation, random: SeededRandom): TimeOfDay {
        // A named sky object implies its time of day.
        when (seen.celestial) {
            Celestial.FULL_MOON, Celestial.CRESCENT -> return TimeOfDay.NIGHT
            Celestial.SUN -> return if (family == SceneFamily.MINIMAL) TimeOfDay.DAY else TimeOfDay.DUSK
            else -> Unit
        }
        if (seen.stars || seen.aurora) return TimeOfDay.NIGHT
        return when (family) {
            SceneFamily.MOUNTAINS -> random.pick(listOf(TimeOfDay.NIGHT, TimeOfDay.NIGHT, TimeOfDay.DUSK, TimeOfDay.DAWN))
            SceneFamily.DESERT -> random.pick(listOf(TimeOfDay.DUSK, TimeOfDay.NIGHT))
            SceneFamily.OCEAN -> random.pick(listOf(TimeOfDay.DUSK, TimeOfDay.DAWN, TimeOfDay.NIGHT))
            SceneFamily.SPACE, SceneFamily.ABSTRACT -> TimeOfDay.NIGHT
            SceneFamily.CITY -> random.pick(listOf(TimeOfDay.NIGHT, TimeOfDay.DUSK))
            SceneFamily.MINIMAL -> random.pick(listOf(TimeOfDay.DAY, TimeOfDay.NIGHT))
        }
    }

    private fun defaultCelestial(family: SceneFamily, time: TimeOfDay, random: SeededRandom): Celestial = when (family) {
        SceneFamily.SPACE, SceneFamily.ABSTRACT -> Celestial.NONE
        else -> when (time) {
            TimeOfDay.NIGHT -> if (family == SceneFamily.DESERT || random.chance(0.35f)) Celestial.CRESCENT else Celestial.FULL_MOON
            else -> Celestial.SUN
        }
    }

    /** Vertical focus: upper-middle, clear of the status bar and of the dock area. */
    private fun focusY(family: SceneFamily, random: SeededRandom): Float = when (family) {
        SceneFamily.OCEAN -> random.range(0.4f, 0.46f)
        SceneFamily.SPACE, SceneFamily.ABSTRACT -> random.range(0.4f, 0.46f)
        else -> random.range(0.32f, 0.4f)
    }

    fun familyAr(f: SceneFamily): String = when (f) {
        SceneFamily.MOUNTAINS -> "جبال"
        SceneFamily.DESERT -> "صحراء"
        SceneFamily.OCEAN -> "بحر"
        SceneFamily.SPACE -> "فضاء"
        SceneFamily.CITY -> "مدينة"
        SceneFamily.ABSTRACT -> "تجريد"
        SceneFamily.MINIMAL -> "مشهد بسيط"
    }

    fun familyEn(f: SceneFamily): String = when (f) {
        SceneFamily.MOUNTAINS -> "Mountains"
        SceneFamily.DESERT -> "Desert"
        SceneFamily.OCEAN -> "Ocean"
        SceneFamily.SPACE -> "Space"
        SceneFamily.CITY -> "City"
        SceneFamily.ABSTRACT -> "Abstract"
        SceneFamily.MINIMAL -> "Minimal"
    }

    private fun timeless(f: SceneFamily) = f == SceneFamily.SPACE || f == SceneFamily.ABSTRACT

    private fun timeAr(t: TimeOfDay, f: SceneFamily): String = if (timeless(f)) "حركة هادئة" else when (t) {
        TimeOfDay.NIGHT -> "ليل"
        TimeOfDay.DUSK -> "غروب"
        TimeOfDay.DAWN -> "فجر"
        TimeOfDay.DAY -> "نهار"
    }

    private fun timeEn(t: TimeOfDay, f: SceneFamily): String = if (timeless(f)) "calm motion" else when (t) {
        TimeOfDay.NIGHT -> "night"
        TimeOfDay.DUSK -> "dusk"
        TimeOfDay.DAWN -> "dawn"
        TimeOfDay.DAY -> "day"
    }
}
