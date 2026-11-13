package com.foldmotion.core.scene

/**
 * A curated, fully specified scene idea. "Surprise me" draws from this list, so a random request
 * always yields a coherent two-state scene with a title, never an arbitrary parameter soup.
 */
data class SceneIdea(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val promptAr: String,
    val promptEn: String,
    val family: SceneFamily,
    val timeOfDay: TimeOfDay,
    val paletteId: String,
    val celestial: Celestial = Celestial.NONE,
    val starDensity: Float = 0f,
    val aurora: Boolean = false,
    val vegetation: Vegetation = Vegetation.NONE,
)

object IdeaBank {

    val ideas: List<SceneIdea> = listOf(
        SceneIdea(
            "aurora_peaks", "شفق فوق القمم", "Aurora over the peaks",
            "ليل قطبي هادئ: عند الفتح يمتد الشفق فوق سلسلة الجبال، وعند الإغلاق يتجمع الضوء حول القمر.",
            "A calm polar night: opening spreads the aurora across the range, closing gathers the light around the moon.",
            SceneFamily.MOUNTAINS, TimeOfDay.NIGHT, "night_aurora", Celestial.FULL_MOON, 0.9f, true, Vegetation.PINES,
        ),
        SceneIdea(
            "indigo_valley", "قمر الوادي الأزرق", "Blue valley moon",
            "وادٍ أزرق تحت بدر مكتمل: عند الفتح تظهر السلسلة كاملة، وعند الإغلاق يبقى القمر فوق قمة واحدة.",
            "A blue valley under a full moon: opening reveals the whole range, closing keeps the moon above one peak.",
            SceneFamily.MOUNTAINS, TimeOfDay.NIGHT, "night_indigo", Celestial.FULL_MOON, 0.75f, false, Vegetation.PINES,
        ),
        SceneIdea(
            "violet_summit", "قمم بنفسجية", "Violet summits",
            "قمم بنفسجية وهلال رفيع: السماء تتسع بالنجوم عند الفتح وتضيق حول الهلال عند الإغلاق.",
            "Violet summits and a thin crescent: the sky fills with stars on opening and narrows on the crescent on closing.",
            SceneFamily.MOUNTAINS, TimeOfDay.NIGHT, "night_violet", Celestial.CRESCENT, 1f, false, Vegetation.NONE,
        ),
        SceneIdea(
            "ember_range", "جبال عند الغروب", "Range at sunset",
            "غروب دافئ خلف الجبال: الفتح يكشف طبقات السلسلة، والإغلاق يجمع الوهج حول الشمس.",
            "A warm sunset behind the mountains: opening reveals the layered range, closing gathers the glow around the sun.",
            SceneFamily.MOUNTAINS, TimeOfDay.DUSK, "dusk_ember", Celestial.SUN, 0.15f, false, Vegetation.PINES,
        ),
        SceneIdea(
            "dawn_valley", "فجر الوادي", "Valley dawn",
            "فجر وردي فوق وادٍ هادئ: المشهد يتسع مع الضوء عند الفتح.",
            "A pink dawn over a quiet valley: the scene widens with the light on opening.",
            SceneFamily.MOUNTAINS, TimeOfDay.DAWN, "dawn_peach", Celestial.SUN, 0f, false, Vegetation.NONE,
        ),
        SceneIdea(
            "golden_dunes", "كثبان ذهبية", "Golden dunes",
            "كثبان ذهبية ونخيل عند المغيب: الفتح يمد الكثبان إلى الأفق، والإغلاق يبقي النخلة والشمس.",
            "Golden dunes and palms at sunset: opening stretches the dunes to the horizon, closing keeps the palm and the sun.",
            SceneFamily.DESERT, TimeOfDay.DUSK, "desert_gold", Celestial.SUN, 0f, false, Vegetation.PALMS,
        ),
        SceneIdea(
            "desert_crescent", "هلال الصحراء", "Desert crescent",
            "ليل صحراوي صافٍ بهلال ونجوم: السماء كاملة عند الفتح، والهلال فوق النخيل عند الإغلاق.",
            "A clear desert night with a crescent and stars: the full sky on opening, the crescent above the palms on closing.",
            SceneFamily.DESERT, TimeOfDay.NIGHT, "desert_night", Celestial.CRESCENT, 0.95f, false, Vegetation.PALMS,
        ),
        SceneIdea(
            "teal_horizon", "أفق فيروزي", "Teal horizon",
            "بحر فيروزي عند الشروق: الفتح يوسّع الأفق، والإغلاق يبقي انعكاس الشمس على الماء.",
            "A teal sea at sunrise: opening widens the horizon, closing keeps the sun's reflection on the water.",
            SceneFamily.OCEAN, TimeOfDay.DAWN, "ocean_teal", Celestial.SUN, 0f,
        ),
        SceneIdea(
            "sunset_sea", "بحر الغروب", "Sunset sea",
            "شمس تغيب في البحر: مسار الضوء على الماء يبقى في الحالتين.",
            "The sun sinking into the sea: the path of light on the water stays in both states.",
            SceneFamily.OCEAN, TimeOfDay.DUSK, "ocean_sunset", Celestial.SUN, 0.1f,
        ),
        SceneIdea(
            "moonlit_sea", "بحر مقمر", "Moonlit sea",
            "بدر فوق بحر ليلي هادئ ونجوم تتسع عند الفتح.",
            "A full moon over a calm night sea, with stars spreading on opening.",
            SceneFamily.OCEAN, TimeOfDay.NIGHT, "night_indigo", Celestial.FULL_MOON, 0.7f,
        ),
        SceneIdea(
            "ringed_world", "كوكب الحلقات", "Ringed world",
            "كوكب بحلقات وسط سديم بنفسجي: الفتح يكشف السديم، والإغلاق يقرّب الكوكب.",
            "A ringed planet in a violet nebula: opening reveals the nebula, closing draws in on the planet.",
            SceneFamily.SPACE, TimeOfDay.NIGHT, "space_nebula", Celestial.NONE, 1f,
        ),
        SceneIdea(
            "teal_cosmos", "سديم فيروزي", "Teal nebula",
            "سديم فيروزي وكوكب ذهبي الحافة في فضاء عميق.",
            "A teal nebula and a gold-rimmed planet in deep space.",
            SceneFamily.SPACE, TimeOfDay.NIGHT, "space_teal", Celestial.NONE, 0.9f,
        ),
        SceneIdea(
            "neon_skyline", "أفق نيون", "Neon skyline",
            "مدينة ليلية بأضواء نيون: الأفق كاملاً عند الفتح، وبرج واحد تحت القمر عند الإغلاق.",
            "A night city in neon light: the full skyline on opening, one tower under the moon on closing.",
            SceneFamily.CITY, TimeOfDay.NIGHT, "city_neon", Celestial.FULL_MOON, 0.4f,
        ),
        SceneIdea(
            "dusk_city", "مدينة الغسق", "City at dusk",
            "أبراج عند الغسق ونوافذ تضيء تدريجياً.",
            "Towers at dusk with windows lighting up.",
            SceneFamily.CITY, TimeOfDay.DUSK, "city_dusk", Celestial.SUN, 0.1f,
        ),
        SceneIdea(
            "brand_flow", "تدفق فيروزي ذهبي", "Teal and gold flow",
            "تدرجات فيروزية وذهبية سائلة تتمدد عند الفتح وتلتف حول كرة ضوء عند الإغلاق.",
            "Liquid teal and gold gradients that spread on opening and wrap around an orb of light on closing.",
            SceneFamily.ABSTRACT, TimeOfDay.NIGHT, "abstract_teal_gold",
        ),
        SceneIdea(
            "candy_flow", "ألوان سائلة", "Liquid colour",
            "ألوان بنفسجية ووردية سائلة بطبقات شفافة.",
            "Liquid violet and pink in translucent layers.",
            SceneFamily.ABSTRACT, TimeOfDay.NIGHT, "abstract_candy",
        ),
        SceneIdea(
            "emerald_flow", "زمرد متموّج", "Emerald waves",
            "تموجات زمردية هادئة حول نقطة ضوء.",
            "Calm emerald ripples around a point of light.",
            SceneFamily.ABSTRACT, TimeOfDay.NIGHT, "abstract_emerald",
        ),
        SceneIdea(
            "sand_minimal", "رمل هادئ", "Quiet sand",
            "تلال مسطحة بألوان الرمل وقرص شمس بسيط.",
            "Flat hills in sand tones with a simple sun disc.",
            SceneFamily.MINIMAL, TimeOfDay.DAY, "minimal_sand", Celestial.SUN,
        ),
        SceneIdea(
            "slate_minimal", "ليل بسيط", "Simple night",
            "تلال داكنة مسطحة وقمر ذهبي بخطوط نظيفة.",
            "Flat dark hills and a golden moon in clean lines.",
            SceneFamily.MINIMAL, TimeOfDay.NIGHT, "minimal_slate", Celestial.FULL_MOON, 0.25f,
        ),
        SceneIdea(
            "mint_minimal", "تلال النعناع", "Mint hills",
            "تلال خضراء ناعمة وشمس مرجانية.",
            "Soft green hills and a coral sun.",
            SceneFamily.MINIMAL, TimeOfDay.DAY, "minimal_mint", Celestial.SUN,
        ),
    )

    fun byId(id: String?): SceneIdea? = ideas.firstOrNull { it.id == id }

    /** Picks an idea for the seed, never the one just shown, so "change idea" always changes. */
    fun pick(seed: Long, excludeId: String? = null): SceneIdea {
        val pool = ideas.filter { it.id != excludeId }
        return SeededRandom(seed).pick(pool)
    }
}
