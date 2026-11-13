package com.foldmotion.core.motion

import com.foldmotion.core.scene.SceneFamily

/**
 * The character of a pack's fold animations. Each style has its own OPEN (the main screen
 * appears) and CLOSE (the cover appears) variant; they share an idea so a pack feels consistent.
 *
 *  - UNFOLD: the scene opens outwards from the slice the cover was showing, light on both edges.
 *  - BOOK: the right half swings flat in perspective like a page; on the cover the whole scene
 *    swings shut like a door.
 *  - RISE: the scene wakes from the dark and its layers rise into place one after another, with
 *    embers floating up.
 *  - WARP: a jump to light speed: stars streak outwards and the layers fly in from close up.
 *  - TILES: the scene assembles from tiles flipping over in a wave.
 *  - SWEEP: a band of light crosses the screen and the scene appears behind it.
 *  - IRIS: light gathers at the focal point and the scene opens from it as a circle.
 *
 * Liquid styles (renderer 5) bend the picture itself with a GPU shader, on Android 13 and later;
 * older phones play a close cousin drawn without one (ripple as an iris, flow as a sweep, glass
 * as a fade):
 *  - RIPPLE: a drop falls on the focal point and the scene spreads out with the rings.
 *  - FLOW: the scene pours in across the screen as a thick liquid with a wavy, glossy front.
 *  - GLASS: the scene comes into focus through melting glass that refracts and clears.
 */
enum class FoldStyle {
    AUTO, UNFOLD, BOOK, RISE, WARP, TILES, SWEEP, IRIS, RIPPLE, FLOW, GLASS;

    /** Styles drawn with a liquid shader (they need renderer 5). */
    val isLiquid: Boolean get() = this in LIQUID

    companion object {
        /** Styles that can actually be played; AUTO resolves to one of them. */
        val CONCRETE: List<FoldStyle> = entries.filter { it != AUTO }

        val LIQUID: Set<FoldStyle> = setOf(RIPPLE, FLOW, GLASS)

        fun parse(name: String?): FoldStyle? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /**
         * AUTO picks a style that suits the scene, alternating between two by seed so packs of the
         * same kind do not all move alike.
         */
        fun resolve(style: FoldStyle, family: SceneFamily, seed: Long): FoldStyle {
            if (style != AUTO) return style
            val (first, second) = when (family) {
                SceneFamily.MOUNTAINS -> RISE to UNFOLD
                SceneFamily.DESERT -> SWEEP to RISE
                SceneFamily.OCEAN -> RIPPLE to IRIS
                SceneFamily.SPACE -> WARP to IRIS
                SceneFamily.CITY -> TILES to BOOK
                SceneFamily.ABSTRACT -> FLOW to WARP
                SceneFamily.MINIMAL -> GLASS to BOOK
            }
            return if ((seed ushr 3) and 1L == 0L) first else second
        }
    }
}
