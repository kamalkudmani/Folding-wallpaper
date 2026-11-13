package com.foldmotion.core.scene

/** What the on-device generator understood from a prompt. Null means "not mentioned". */
data class PromptInterpretation(
    val family: SceneFamily? = null,
    val timeOfDay: TimeOfDay? = null,
    val hue: Hue? = null,
    val celestial: Celestial? = null,
    val stars: Boolean = false,
    val aurora: Boolean = false,
    val vegetation: Vegetation? = null,
    /** Stable concept keys such as "family.mountains", shown to the user as chips. */
    val recognized: List<String> = emptyList(),
) {
    val understoodAnything: Boolean get() = recognized.isNotEmpty()
}

sealed interface PromptCheck {
    data class Ok(val cleaned: String) : PromptCheck
    data object Empty : PromptCheck
    data class TooLong(val length: Int) : PromptCheck
}

/** Input rules from blueprint section 08: 1-500 characters. */
object PromptRules {
    const val MAX_LENGTH = 500

    /** Strips invisible direction/format controls that could disguise text, then measures. */
    fun check(raw: String): PromptCheck {
        val cleaned = raw.filterNot { it.isBidiOrFormatControl() }.trim().replace(Regex("\\s+"), " ")
        val length = cleaned.codePointCount(0, cleaned.length)
        return when {
            length == 0 -> PromptCheck.Empty
            length > MAX_LENGTH -> PromptCheck.TooLong(length)
            else -> PromptCheck.Ok(cleaned)
        }
    }

    private fun Char.isBidiOrFormatControl(): Boolean =
        this in '‪'..'‮' || this in '⁦'..'⁩' || this == '‎' || this == '‏' ||
            this == '﻿' || (isISOControl() && this != '\n')
}

/**
 * Keyword interpreter for Arabic and English prompts.
 *
 * This is deliberately not presented as AI: it recognises a fixed vocabulary and reports exactly
 * which concepts it found, so the user can see what the local generator will and will not honour.
 */
object PromptAnalyzer {

    private val familyWords: Map<SceneFamily, List<String>> = mapOf(
        SceneFamily.MOUNTAINS to listOf(
            "جبل", "جبال", "جبلي", "قمم", "قمه", "تلال", "وادي", "غابه", "غابات",
            "mountain", "peak", "alps", "hill", "valley", "forest", "ridge", "summit",
        ),
        SceneFamily.DESERT to listOf(
            "صحراء", "صحراوي", "كثبان", "كثيب", "رمال", "رمل", "باديه", "واحه",
            "desert", "dune", "sand", "oasis", "sahara",
        ),
        SceneFamily.OCEAN to listOf(
            "بحر", "بحار", "محيط", "موج", "امواج", "شاطي", "ساحل", "بحيره",
            "ocean", "sea", "wave", "beach", "shore", "lake", "coast",
        ),
        SceneFamily.SPACE to listOf(
            "فضاء", "كوكب", "كواكب", "مجره", "سديم", "زحل", "كوني", "فلك",
            "space", "planet", "galaxy", "nebula", "cosmos", "cosmic", "saturn",
        ),
        SceneFamily.CITY to listOf(
            "مدينه", "مدن", "ابراج", "برج", "ناطحات", "عمران", "نيون",
            "city", "skyline", "tower", "skyscraper", "neon", "urban", "cyberpunk",
        ),
        SceneFamily.ABSTRACT to listOf(
            "تجريد", "تجريدي", "سائل", "سائله", "تموج", "الوان", "تدرج",
            "abstract", "fluid", "gradient", "liquid", "flow", "mesh",
        ),
        SceneFamily.MINIMAL to listOf(
            "بسيط", "بسيطه", "مينيمال", "هندسي", "مسطح",
            "minimal", "minimalist", "geometric", "flat", "simple",
        ),
    )

    private val timeWords: Map<TimeOfDay, List<String>> = mapOf(
        TimeOfDay.NIGHT to listOf("ليل", "ليله", "ليلي", "ليليه", "night", "midnight", "nocturnal"),
        TimeOfDay.DUSK to listOf("غروب", "مغيب", "مساء", "غسق", "sunset", "dusk", "evening", "twilight"),
        TimeOfDay.DAWN to listOf("شروق", "فجر", "صباح", "sunrise", "dawn", "morning"),
        TimeOfDay.DAY to listOf("نهار", "ظهيره", "day", "noon", "daylight", "daytime"),
    )

    private val hueWords: Map<Hue, List<String>> = mapOf(
        Hue.BLUE to listOf("ازرق", "زرقاء", "كحلي", "blue", "navy", "azure", "indigo"),
        Hue.TEAL to listOf("فيروزي", "فيروزيه", "تركواز", "سماوي", "teal", "turquoise", "cyan", "aqua"),
        Hue.GREEN to listOf("اخضر", "خضراء", "زمردي", "زمرد", "green", "emerald"),
        Hue.PURPLE to listOf("بنفسجي", "بنفسجيه", "ارجواني", "موف", "purple", "violet", "lavender", "magenta"),
        Hue.PINK to listOf("وردي", "ورديه", "زهري", "pink", "rose", "rosy"),
        Hue.ORANGE to listOf("برتقالي", "برتقاليه", "orange", "amber"),
        Hue.GOLD to listOf("ذهبي", "ذهبيه", "ذهب", "gold", "golden"),
        Hue.RED to listOf("احمر", "حمراء", "قرمزي", "red", "crimson", "scarlet"),
    )

    private val moonWords = listOf("قمر", "بدر", "moon", "moonlit", "moonlight")
    private val crescentWords = listOf("هلال", "crescent")
    private val sunWords = listOf("شمس", "sun", "sunny")
    private val starWords = listOf("نجوم", "نجمه", "نجم", "نجمات", "star", "starry", "starlit")
    private val auroraWords = listOf("شفق", "aurora", "borealis")
    private val treeWords = listOf("شجر", "شجره", "اشجار", "صنوبر", "غابه", "غابات", "tree", "pine", "forest", "woods")
    private val palmWords = listOf("نخل", "نخله", "نخيل", "palm")

    /** Arabic prefixes that may be glued to a noun: conjunctions, prepositions, the article. */
    private val arabicPrefixes = listOf("وال", "بال", "كال", "فال", "لل", "ال", "و", "ب", "ل", "ف")

    fun analyze(prompt: String): PromptInterpretation {
        val tokens = tokenize(normalize(prompt))
        if (tokens.isEmpty()) return PromptInterpretation()

        val recognized = LinkedHashSet<String>()
        fun has(words: List<String>): Boolean = tokens.any { token -> matches(token, words) }
        fun firstIndex(words: List<String>): Int = tokens.indexOfFirst { token -> matches(token, words) }

        val family = familyWords
            .map { (family, words) -> Triple(family, tokens.count { matches(it, words) }, firstIndex(words)) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<SceneFamily, Int, Int>> { it.second }.thenBy { it.third })
            .firstOrNull()?.first
        family?.let { recognized += "family." + it.name.lowercase() }

        val time = timeWords.entries.filter { has(it.value) }.minByOrNull { firstIndex(it.value) }?.key
        time?.let { recognized += "time." + it.name.lowercase() }

        val hue = hueWords.entries.filter { has(it.value) }.minByOrNull { firstIndex(it.value) }?.key
        hue?.let { recognized += "hue." + it.name.lowercase() }

        val celestial = when {
            has(crescentWords) -> Celestial.CRESCENT
            has(moonWords) -> Celestial.FULL_MOON
            has(sunWords) -> Celestial.SUN
            else -> null
        }
        celestial?.let { recognized += "el." + it.name.lowercase() }

        val stars = has(starWords)
        if (stars) recognized += "el.stars"
        val aurora = has(auroraWords)
        if (aurora) recognized += "el.aurora"

        val vegetation = when {
            has(palmWords) -> Vegetation.PALMS
            has(treeWords) -> Vegetation.PINES
            else -> null
        }
        vegetation?.let { recognized += "el." + it.name.lowercase() }

        return PromptInterpretation(family, time, hue, celestial, stars, aurora, vegetation, recognized.toList())
    }

    /** Lower-cases and folds Arabic spelling variants so one keyword matches all of them. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            when (ch) {
                in 'ً'..'ٟ', 'ٰ', 'ـ' -> Unit // tashkeel and tatweel
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                'ؤ' -> sb.append('و')
                'ئ' -> sb.append('ي')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * The token plus what is left after removing up to two glued prefixes, e.g.
     * "وبالبحر" -> "بالبحر" -> "بحر". Stems shorter than three letters are ignored.
     */
    private fun arabicStems(token: String): List<String> {
        val stems = ArrayList<String>(4)
        stems += token
        for (p1 in arabicPrefixes) {
            if (!token.startsWith(p1)) continue
            val rest = token.substring(p1.length)
            if (rest.length >= 3) stems += rest
            for (p2 in arabicPrefixes) {
                if (rest.startsWith(p2) && rest.length - p2.length >= 3) stems += rest.substring(p2.length)
            }
        }
        return stems
    }

    private fun tokenize(normalized: String): List<String> =
        normalized.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() }

    private fun matches(token: String, words: List<String>): Boolean {
        if (token in words) return true
        val first = token[0]
        return if (first in 'a'..'z') {
            // English plurals only; "season" must not match "sea".
            (token.endsWith("s") && token.dropLast(1) in words) ||
                (token.endsWith("es") && token.dropLast(2) in words)
        } else {
            arabicStems(token).any { it in words }
        }
    }
}
