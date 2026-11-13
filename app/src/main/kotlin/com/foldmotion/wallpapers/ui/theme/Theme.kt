package com.foldmotion.wallpapers.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Night glass": a deep night background with moving aurora light, translucent glass surfaces,
 * and a teal-to-violet accent with a warm gold highlight.
 */
object FmColors {
    // Near-black, slightly cool: wallpapers bring the colour (0.6 "content first" redesign).
    val Night = Color(0xFF0A0B0F)
    val NightHigh = Color(0xFF16171D)
    val Aurora = Color(0xFF2DE1C2)
    val AuroraDeep = Color(0xFF0FA89A)
    val Violet = Color(0xFF8B7CFF)
    val Gold = Color(0xFFF2C26B)
    val Text = Color(0xFFF2F6FA)
    val TextMuted = Color(0xFFA1A7B2)
    val Error = Color(0xFFFF8A8A)

    // Kept for components drawn on the scene previews.
    val Teal = Aurora
    val TealSoft = Color(0xFF9DEFE0)
    val Navy = Night
    val Surface = NightHigh

    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0x24FFFFFF)
    val GlassBorderTop = Color(0x33FFFFFF)
    val GlassBorderBottom = Color(0x0AFFFFFF)

    // Surfaces of the 0.7 design system: flat, solid, a hairline edge.
    val Surface1 = Color(0xFF121318)
    val Surface2 = Color(0xFF1B1C22)
    val Hairline = Color(0x1AFFFFFF)

    // Selected states and the primary action are white; colour comes from the wallpapers.
    val primaryGradient = Brush.horizontalGradient(listOf(Color.White, Color(0xFFE8EBF0)))
    val glassBorder = Brush.verticalGradient(listOf(GlassBorderTop, GlassBorderBottom))
}

private val scheme = darkColorScheme(
    primary = FmColors.Aurora,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0x332DE1C2),
    onPrimaryContainer = Color(0xFFBFF8EC),
    secondary = FmColors.Violet,
    onSecondary = Color(0xFF140B3D),
    secondaryContainer = Color(0x338B7CFF),
    onSecondaryContainer = Color(0xFFDCD6FF),
    tertiary = FmColors.Gold,
    background = FmColors.Night,
    onBackground = FmColors.Text,
    surface = FmColors.Night,
    onSurface = FmColors.Text,
    surfaceVariant = Color(0x1FFFFFFF),
    onSurfaceVariant = FmColors.TextMuted,
    surfaceContainerLowest = Color(0xFF03070D),
    surfaceContainerLow = Color(0x0FFFFFFF),
    surfaceContainer = Color(0x17FFFFFF),
    surfaceContainerHigh = Color(0x1FFFFFFF),
    surfaceContainerHighest = Color(0x29FFFFFF),
    outline = Color(0x40FFFFFF),
    outlineVariant = Color(0x1AFFFFFF),
    error = FmColors.Error,
    errorContainer = Color(0x33FF8A8A),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val base = Typography()
private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.1.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.5.sp),
)

@Composable
fun FoldMotionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography) {
        // No Scaffold or Surface sits behind the screens (the aurora does), so set the default
        // text and icon colour here; otherwise untinted text falls back to black.
        CompositionLocalProvider(LocalContentColor provides FmColors.Text, content = content)
    }
}
