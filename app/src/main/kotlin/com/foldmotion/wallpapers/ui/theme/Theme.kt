package com.foldmotion.wallpapers.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Palette taken from the blueprint cover: deep navy, teal and a warm gold accent. */
object FmColors {
    val Navy = Color(0xFF0B2233)
    val NavyDeep = Color(0xFF071824)
    val Surface = Color(0xFF0F2B3D)
    val SurfaceHigh = Color(0xFF163A50)
    val Teal = Color(0xFF19B5B0)
    val TealSoft = Color(0xFF8FD9D6)
    val Gold = Color(0xFFE2B861)
    val Text = Color(0xFFE6F1F3)
    val TextMuted = Color(0xFFA9C3CC)
    val Error = Color(0xFFFF8A80)
}

private val scheme = darkColorScheme(
    primary = FmColors.Teal,
    onPrimary = Color(0xFF002120),
    primaryContainer = Color(0xFF0E4F55),
    onPrimaryContainer = FmColors.TealSoft,
    secondary = FmColors.Gold,
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFF4A3A12),
    onSecondaryContainer = Color(0xFFFFE3A8),
    tertiary = FmColors.TealSoft,
    background = FmColors.Navy,
    onBackground = FmColors.Text,
    surface = FmColors.Navy,
    onSurface = FmColors.Text,
    surfaceVariant = FmColors.SurfaceHigh,
    onSurfaceVariant = FmColors.TextMuted,
    surfaceContainerLowest = FmColors.NavyDeep,
    surfaceContainerLow = Color(0xFF0D2637),
    surfaceContainer = FmColors.Surface,
    surfaceContainerHigh = FmColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF1D465E),
    outline = Color(0xFF3D6375),
    outlineVariant = Color(0xFF24485B),
    error = FmColors.Error,
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun FoldMotionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
}
