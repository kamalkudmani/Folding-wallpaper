package com.foldmotion.core.scene

import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.ScreenKind

/** The two windows through which one scene is seen, plus the focus point in each. */
data class SceneFrames(
    val cover: WorldRect,
    val main: WorldRect,
    val focusX: Float,
    val focusY: Float,
) {
    fun frame(kind: ScreenKind): WorldRect = if (kind == ScreenKind.MAIN) main else cover

    /** Focus point in the normalised frame of the given screen. */
    fun focusIn(kind: ScreenKind): Pair<Float, Float> {
        val f = frame(kind)
        return Pair(
            ((focusX - f.left) / f.width).coerceIn(0f, 1f),
            ((focusY - f.top) / f.height).coerceIn(0f, 1f),
        )
    }
}

/**
 * Maps a device's two panels onto the scene world.
 *
 * World unit = physical height of the cover panel. On a book foldable both panels have almost
 * the same physical height (Fold7: 151.7 mm vs 150.7 mm) while the cover is about half as wide
 * (65.0 mm vs 135.8 mm). So, at one common physical scale, the cover is a narrow window onto the
 * very same picture the main screen shows in full. Opening the device widens the view; nothing
 * is rescaled or swapped.
 *
 * The cover window is centred on the focal element and clamped inside the main window, which
 * keeps the focal element off the crease: it lands near the middle of one half of the main screen.
 */
object SceneGeometry {

    /** Vertical centre of the world; scenes are composed around it. */
    const val CENTER_Y = 0.5f

    /** World y of the horizon per family. Shared by the painter and the choreographer. */
    fun horizonFor(family: SceneFamily): Float = when (family) {
        SceneFamily.OCEAN -> 0.6f
        SceneFamily.DESERT -> 0.7f
        SceneFamily.CITY -> 0.76f
        else -> 0.72f
    }

    fun frames(profile: DeviceProfile, focusX: Float, focusY: Float): SceneFrames {
        val cover = profile.cover
        val main = profile.main ?: cover

        val coverHeight = 1f
        val coverWidth = cover.aspect

        val coverMm = cover.heightMm
        val mainMm = main.heightMm
        val mainHeight = if (coverMm != null && mainMm != null && coverMm > 0f) mainMm / coverMm else 1f
        val mainWidth = mainHeight * main.aspect

        val mainRect = WorldRect(-mainWidth / 2f, CENTER_Y - mainHeight / 2f, mainWidth / 2f, CENTER_Y + mainHeight / 2f)

        val slack = ((mainWidth - coverWidth) / 2f).coerceAtLeast(0f)
        val coverCentre = focusX.coerceIn(-slack, slack)
        val coverRect = WorldRect(
            coverCentre - coverWidth / 2f,
            CENTER_Y - coverHeight / 2f,
            coverCentre + coverWidth / 2f,
            CENTER_Y + coverHeight / 2f,
        )
        return SceneFrames(coverRect, mainRect, focusX.coerceIn(mainRect.left, mainRect.right), focusY)
    }

    /**
     * Default horizontal focus: the centre of one half of the main screen, away from the crease.
     * @param rightHalf which side of the fold line carries the focal element. Scenes use the right
     *   half: the closed Fold lies over it, so the cover shows that part and the scene opens from it
     *   towards the left. (The random draw that used to choose is still taken, so every other
     *   detail of a scene stays as it was.)
     */
    fun defaultFocusX(profile: DeviceProfile, rightHalf: Boolean): Float {
        val main = profile.main ?: return 0f
        val cover = profile.cover
        val mainHeight = run {
            val c = cover.heightMm
            val m = main.heightMm
            if (c != null && m != null && c > 0f) m / c else 1f
        }
        val quarter = mainHeight * main.aspect / 4f
        return if (rightHalf) quarter else -quarter
    }
}
