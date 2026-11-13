package com.foldmotion.wallpapers.ui.discover

import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.core.pack.LocalizedText

/** Names of the live liquid looks ([LiquidSpec.PRESETS]) and the choices Create offers on top. */
object LiquidLooks {
    data class Look(val name: String, val title: LocalizedText) {
        val spec: LiquidSpec get() = LiquidSpec.PRESETS.getValue(name)
    }

    val all = listOf(
        Look("gold", LocalizedText("ذهب منصهر", "Molten gold")),
        Look("aurora", LocalizedText("حرير الشفق", "Aurora silk")),
        Look("neon", LocalizedText("مدّ النيون", "Neon tide")),
        Look("rose", LocalizedText("كوارتز وردي", "Rose quartz")),
        Look("abyss", LocalizedText("أعماق المحيط", "Deep ocean")),
        Look("lava", LocalizedText("تدفّق الجمر", "Ember flow")),
        Look("pearl", LocalizedText("كروم سائل", "Liquid chrome")),
        Look("emerald", LocalizedText("زجاج زمردي", "Emerald glass")),
    )

    fun byName(name: String): Look = all.firstOrNull { it.name == name } ?: all.first()

    /** Calm, flowing, lively. */
    val PACES = floatArrayOf(0.6f, 1f, 1.7f)

    /** Large, medium, fine waves (noise units per world unit). */
    val SCALES = floatArrayOf(0.9f, 1.25f, 1.9f)

    fun spec(name: String, pace: Int, scale: Int): LiquidSpec =
        byName(name).spec.copy(speed = PACES[pace.coerceIn(0, 2)], span = SCALES[scale.coerceIn(0, 2)])
}
