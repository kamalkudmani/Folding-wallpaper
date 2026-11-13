package com.foldmotion.core.scene

/** Colour families a prompt can ask for. */
enum class Hue { BLUE, TEAL, GREEN, PURPLE, PINK, ORANGE, GOLD, RED }

data class PaletteEntry(
    val palette: Palette,
    val families: Set<SceneFamily>,
    val times: Set<TimeOfDay>,
    val hues: Set<Hue>,
)

object Palettes {

    private fun c(rgb: Int): Int = rgb or (0xFF shl 24)

    private fun palette(
        id: String,
        skyTop: Int, skyMid: Int, skyBottom: Int, glow: Int,
        far: Int, mid: Int, near: Int, accent: Int,
    ) = Palette(id, c(skyTop), c(skyMid), c(skyBottom), c(glow), c(far), c(mid), c(near), c(accent))

    private val landscape = setOf(SceneFamily.MOUNTAINS, SceneFamily.OCEAN, SceneFamily.CITY, SceneFamily.DESERT)

    val all: List<PaletteEntry> = listOf(
        PaletteEntry(
            palette("night_indigo", 0x05061A, 0x121A45, 0x2B3A7A, 0xE8EEFF, 0x24305F, 0x151C3D, 0x080B1C, 0x7FD6FF),
            setOf(SceneFamily.MOUNTAINS, SceneFamily.OCEAN, SceneFamily.CITY), setOf(TimeOfDay.NIGHT), setOf(Hue.BLUE),
        ),
        PaletteEntry(
            palette("night_aurora", 0x030B14, 0x06242F, 0x0F4A4A, 0xD6FFF0, 0x103A40, 0x0A2329, 0x040D12, 0x3DF0B0),
            setOf(SceneFamily.MOUNTAINS, SceneFamily.OCEAN), setOf(TimeOfDay.NIGHT), setOf(Hue.TEAL, Hue.GREEN),
        ),
        PaletteEntry(
            palette("night_violet", 0x0A0418, 0x2A0F4A, 0x5B2A7E, 0xFFE3F6, 0x3A1E5C, 0x1F0F38, 0x0B0516, 0xFF7AD9),
            setOf(SceneFamily.MOUNTAINS, SceneFamily.CITY, SceneFamily.DESERT), setOf(TimeOfDay.NIGHT), setOf(Hue.PURPLE, Hue.PINK),
        ),
        PaletteEntry(
            palette("dusk_ember", 0x1B1035, 0x7A2E5A, 0xF58A4B, 0xFFD9A0, 0x5A2A52, 0x33183A, 0x150A1C, 0xFFB347),
            landscape, setOf(TimeOfDay.DUSK), setOf(Hue.ORANGE, Hue.RED),
        ),
        PaletteEntry(
            palette("dusk_rose", 0x23153F, 0x8E4A84, 0xF7A9A0, 0xFFE6D5, 0x6A4172, 0x3D2550, 0x180E26, 0xFF8FA3),
            landscape, setOf(TimeOfDay.DUSK, TimeOfDay.DAWN), setOf(Hue.PINK, Hue.PURPLE),
        ),
        PaletteEntry(
            palette("dawn_peach", 0x3A5A8C, 0xF2A3A0, 0xFFE0B8, 0xFFF6DF, 0x8C86B5, 0x5A5A8C, 0x2B2D52, 0xFFC27A),
            landscape, setOf(TimeOfDay.DAWN, TimeOfDay.DAY), setOf(Hue.PINK, Hue.ORANGE, Hue.BLUE),
        ),
        PaletteEntry(
            palette("desert_gold", 0x2A1B4A, 0xC65A3A, 0xFFC56B, 0xFFF0C2, 0xC77B3E, 0x8F4A26, 0x3F1E12, 0xFFD27A),
            setOf(SceneFamily.DESERT), setOf(TimeOfDay.DUSK, TimeOfDay.DAWN, TimeOfDay.DAY), setOf(Hue.GOLD, Hue.ORANGE),
        ),
        PaletteEntry(
            palette("desert_night", 0x040817, 0x101C40, 0x2C3566, 0xFFF4D6, 0x3A3555, 0x231F38, 0x0E0C1A, 0xE9C46A),
            setOf(SceneFamily.DESERT), setOf(TimeOfDay.NIGHT), setOf(Hue.BLUE, Hue.GOLD),
        ),
        PaletteEntry(
            palette("ocean_teal", 0x06283D, 0x1B6E82, 0x7FD8C9, 0xFFFBE8, 0x1E7A86, 0x0F4F63, 0x06222E, 0xA8FFF0),
            setOf(SceneFamily.OCEAN), setOf(TimeOfDay.DAWN, TimeOfDay.DAY), setOf(Hue.TEAL, Hue.BLUE, Hue.GREEN),
        ),
        PaletteEntry(
            palette("ocean_sunset", 0x1A1F4B, 0xC0526B, 0xFFB26B, 0xFFE7B0, 0x7A3F6B, 0x3E2757, 0x14102A, 0xFFC07A),
            setOf(SceneFamily.OCEAN), setOf(TimeOfDay.DUSK), setOf(Hue.ORANGE, Hue.PINK, Hue.RED),
        ),
        PaletteEntry(
            palette("space_nebula", 0x02030C, 0x140A33, 0x2A1458, 0xFFD7FA, 0x6A3FB8, 0x2B1B5E, 0x0A0718, 0x58E6FF),
            setOf(SceneFamily.SPACE), setOf(TimeOfDay.NIGHT), setOf(Hue.PURPLE, Hue.PINK, Hue.BLUE),
        ),
        PaletteEntry(
            palette("space_teal", 0x01060C, 0x052330, 0x0B4252, 0xD8FFF6, 0x1FA3A3, 0x0E3C4A, 0x040F14, 0xFFC857),
            setOf(SceneFamily.SPACE), setOf(TimeOfDay.NIGHT), setOf(Hue.TEAL, Hue.GREEN, Hue.GOLD),
        ),
        PaletteEntry(
            palette("city_neon", 0x0A0620, 0x3B1466, 0xD13C8C, 0xFFE08A, 0x3A2466, 0x1C1238, 0x08050F, 0x38F5E8),
            setOf(SceneFamily.CITY), setOf(TimeOfDay.NIGHT, TimeOfDay.DUSK), setOf(Hue.PURPLE, Hue.PINK, Hue.RED),
        ),
        PaletteEntry(
            palette("city_dusk", 0x101B3A, 0x4A4E8A, 0xF2A07B, 0xFFE2A8, 0x39406E, 0x1E2444, 0x0A0D1C, 0xFFCF70),
            setOf(SceneFamily.CITY), setOf(TimeOfDay.DUSK, TimeOfDay.DAWN), setOf(Hue.BLUE, Hue.ORANGE, Hue.GOLD),
        ),
        PaletteEntry(
            palette("abstract_teal_gold", 0x06202E, 0x0F6E78, 0x1AA6A6, 0xF2D18B, 0x147F8A, 0x0B4A5C, 0x072433, 0xD9B25F),
            setOf(SceneFamily.ABSTRACT), TimeOfDay.entries.toSet(), setOf(Hue.TEAL, Hue.GOLD, Hue.BLUE),
        ),
        PaletteEntry(
            palette("abstract_candy", 0x2B1055, 0x7B2FF7, 0xF107A3, 0xFFE29A, 0xFF6FB5, 0x5B2BD6, 0x1B0B3A, 0x5CE1E6),
            setOf(SceneFamily.ABSTRACT), TimeOfDay.entries.toSet(), setOf(Hue.PURPLE, Hue.PINK, Hue.RED),
        ),
        PaletteEntry(
            palette("abstract_emerald", 0x021B14, 0x0B5D46, 0x3ED598, 0xE9FFD0, 0x1E8F6B, 0x0C4A3A, 0x04201A, 0xF9F871),
            setOf(SceneFamily.ABSTRACT), TimeOfDay.entries.toSet(), setOf(Hue.GREEN),
        ),
        PaletteEntry(
            palette("abstract_sunrise", 0x2A0E3F, 0xC2410C, 0xFDBA74, 0xFFF3C4, 0xFB7185, 0x9A3412, 0x3B0A2A, 0xFDE68A),
            setOf(SceneFamily.ABSTRACT), TimeOfDay.entries.toSet(), setOf(Hue.ORANGE, Hue.GOLD, Hue.RED),
        ),
        PaletteEntry(
            palette("minimal_sand", 0xF3E9DC, 0xF0D9C0, 0xE8C3A0, 0xE2725B, 0xD9A679, 0xB5764F, 0x5C3D2E, 0x2F4858),
            setOf(SceneFamily.MINIMAL), setOf(TimeOfDay.DAY, TimeOfDay.DAWN), setOf(Hue.ORANGE, Hue.GOLD, Hue.RED),
        ),
        PaletteEntry(
            palette("minimal_slate", 0x1E2A38, 0x2E4057, 0x48627F, 0xF4D35E, 0x3C546E, 0x26384C, 0x121C28, 0xEE964B),
            setOf(SceneFamily.MINIMAL), setOf(TimeOfDay.NIGHT, TimeOfDay.DUSK), setOf(Hue.BLUE, Hue.GOLD),
        ),
        PaletteEntry(
            palette("minimal_mint", 0xDFF5EC, 0xBFE8D8, 0x98D6C2, 0xFF8A5B, 0x6FB7A4, 0x3F8C7C, 0x1F4E48, 0x1B2A41),
            setOf(SceneFamily.MINIMAL), setOf(TimeOfDay.DAY, TimeOfDay.DAWN), setOf(Hue.GREEN, Hue.TEAL),
        ),
        PaletteEntry(
            palette("minimal_plum", 0x2D1B36, 0x4A2B55, 0x7A4A7E, 0xFFB4A2, 0x5E3A66, 0x3A2244, 0x1A0F22, 0xFFCDB2),
            setOf(SceneFamily.MINIMAL), setOf(TimeOfDay.NIGHT, TimeOfDay.DUSK), setOf(Hue.PURPLE, Hue.PINK),
        ),
    )

    fun byId(id: String): Palette? = all.firstOrNull { it.palette.id == id }?.palette

    /**
     * Best palette for the request. Constraints are relaxed in order (hue is the user's explicit
     * wish, so it is kept longest) and the seed only breaks ties, so the result is deterministic.
     */
    fun choose(family: SceneFamily, time: TimeOfDay?, hue: Hue?, random: SeededRandom): Palette {
        val forFamily = all.filter { family in it.families }
        val candidates = sequenceOf(
            forFamily.filter { (time == null || time in it.times) && (hue == null || hue in it.hues) },
            forFamily.filter { hue != null && hue in it.hues },
            forFamily.filter { time != null && time in it.times },
            forFamily,
            all,
        ).first { it.isNotEmpty() }
        return random.pick(candidates).palette
    }
}
