package com.foldmotion.wallpapers.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** The app's shared-transition scope, so a card can grow into its detail screen. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The navigation destination's animation scope, provided around each screen. */
val LocalNavScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks the artwork of a card and of its detail with the same [key]: when one screen replaces
 * the other, the picture morphs between the two places instead of cutting.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedArt(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    return with(shared) { this@sharedArt.sharedBounds(rememberSharedContentState(key), animatedVisibilityScope = nav) }
}
