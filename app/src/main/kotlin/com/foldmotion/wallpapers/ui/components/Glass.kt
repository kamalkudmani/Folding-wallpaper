package com.foldmotion.wallpapers.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foldmotion.wallpapers.ui.theme.FmColors
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Slowly drifting aurora behind every screen. Three soft light fields orbit on long, unrelated
 * periods so the pattern never visibly repeats. Drawn once per frame on the GPU; costs little.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, animate: Boolean = true) {
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "a")
    val b by t.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(37_000, easing = LinearEasing)), label = "b")
    val c by t.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(31_000, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Canvas(modifier.fillMaxSize()) {
        val pa = if (animate) a else 0.6f
        val pb = if (animate) b else 2.1f
        val pc = if (animate) c else 4f
        drawRect(Brush.verticalGradient(listOf(FmColors.Night, FmColors.NightHigh, FmColors.Night)))
        fun blob(cx: Float, cy: Float, r: Float, color: Color, alpha: Float) {
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r, center = Offset(cx, cy),
            )
        }
        val w = size.width
        val h = size.height
        val m = maxOf(w, h)
        blob(w * (0.25f + 0.2f * cos(pa)), h * (0.18f + 0.08f * sin(pa * 2f)), m * 0.55f, FmColors.Aurora, 0.20f)
        blob(w * (0.85f + 0.15f * sin(pb)), h * (0.42f + 0.12f * cos(pb)), m * 0.5f, FmColors.Violet, 0.17f)
        blob(w * (0.4f + 0.25f * sin(pc)), h * (0.95f + 0.05f * cos(pc)), m * 0.45f, FmColors.Gold, 0.08f)
    }
}

/** Translucent card with a light top edge, the base surface of the redesign. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    strong: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(if (strong) FmColors.GlassStrong else FmColors.Glass)
            .border(1.dp, FmColors.glassBorder, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Scales down a little while pressed, springs back on release. */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.95f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Fades and lifts content in, delayed by its index, so lists arrive in a soft wave. */
fun Modifier.staggerIn(index: Int, stepMs: Long = 55L): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(12) * stepMs)
        progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
    }
    val density = LocalDensity.current
    graphicsLayer {
        alpha = progress.value
        translationY = with(density) { (1f - progress.value) * 22.dp.toPx() }
    }
}

/** Primary action: gradient pill with a light sweep, press spring and a haptic tick. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    height: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val sheen = rememberInfiniteTransition(label = "sheen")
    val sweep by sheen.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(3_400, easing = LinearEasing)), label = "sweep")
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .heightIn(min = height)
            .pressScale(interaction)
            .clip(shape)
            .background(if (enabled) FmColors.primaryGradient else Brush.horizontalGradient(listOf(FmColors.GlassStrong, FmColors.GlassStrong)))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) {
            BoxWithConstraints(Modifier.matchParentSize()) {
                val w = constraints.maxWidth.toFloat()
                Canvas(Modifier.fillMaxSize()) {
                    val x = sweep * w
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent), start = Offset(x - w * 0.25f, 0f), end = Offset(x + w * 0.25f, size.height)))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 22.dp)) {
            if (leading != null) {
                Icon(leading, contentDescription = null, tint = if (enabled) Color(0xFF04131A) else FmColors.TextMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color(0xFF04131A) else FmColors.TextMuted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Secondary action: glass pill. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    height: Dp = 52.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .heightIn(min = height)
            .pressScale(interaction)
            .clip(shape)
            .background(FmColors.GlassStrong)
            .border(1.dp, FmColors.glassBorder, shape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp)) {
            if (leading != null) {
                Icon(leading, contentDescription = null, tint = FmColors.Text, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) FmColors.Text else FmColors.TextMuted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Round glass icon button. */
@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(46.dp)
            .pressScale(interaction, 0.88f)
            .clip(RoundedCornerShape(50))
            .background(FmColors.Glass)
            .border(1.dp, FmColors.glassBorder, RoundedCornerShape(50))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = FmColors.Text, modifier = Modifier.size(21.dp))
    }
}

/**
 * Segmented control with a gradient capsule that springs between options. Works in RTL: the
 * capsule follows the visual position of the selected option.
 */
@Composable
fun PillToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(FmColors.Glass)
            .border(1.dp, FmColors.glassBorder, shape)
            .padding(4.dp),
    ) {
        val count = options.size.coerceAtLeast(1)
        val itemWidth = maxWidth / count
        // Modifier.offset follows the layout direction, so the capsule tracks the logical index
        // in both LTR and RTL without mirroring by hand.
        val offset by animateDpAsState(itemWidth * selected, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "pill")
        Box(
            Modifier
                .offset(x = offset)
                .width(itemWidth)
                .fillMaxHeight()
                .clip(shape)
                .background(FmColors.primaryGradient),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val chosen = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable(role = Role.Tab) {
                            if (!chosen) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        }
                        .testTag("pill_$i"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (chosen) Color(0xFF04131A) else FmColors.TextMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

data class NavItem(val label: String, val icon: ImageVector)

/** Floating glass navigation bar with a sliding gradient indicator. */
@Composable
fun FloatingNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(shape)
            .background(Color(0xCC0B1626))
            .border(1.dp, FmColors.glassBorder, shape)
            .padding(6.dp),
    ) {
        val itemWidth = maxWidth / items.size
        val offset by animateDpAsState(itemWidth * selected.coerceAtLeast(0), spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "nav")
        if (selected >= 0) {
            Box(
                Modifier
                    .offset(x = offset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(Brush.horizontalGradient(listOf(FmColors.Aurora.copy(alpha = 0.32f), FmColors.Violet.copy(alpha = 0.32f)))),
            )
        }
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val chosen = i == selected
                val scale by animateFloatAsState(if (chosen) 1.12f else 1f, spring(dampingRatio = 0.5f), label = "icon")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable(role = Role.Tab) {
                            if (!chosen) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        }
                        .testTag("nav_$i"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        item.icon, contentDescription = null,
                        tint = if (chosen) FmColors.Aurora else FmColors.TextMuted,
                        modifier = Modifier.size(22.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                    )
                    Text(item.label, style = MaterialTheme.typography.labelMedium, color = if (chosen) FmColors.Text else FmColors.TextMuted, maxLines = 1)
                }
            }
        }
    }
}

/** Small glass chip. */
@Composable
fun GlassChip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction, 0.93f)
            .clip(shape)
            .background(if (selected) FmColors.primaryGradient else Brush.horizontalGradient(listOf(FmColors.Glass, FmColors.Glass)))
            .border(1.dp, if (selected) Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)) else FmColors.glassBorder, shape)
            .let { if (onClick != null) it.clickable(interaction, indication = null, onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color(0xFF04131A) else FmColors.Text, maxLines = 2)
    }
}

/** Large screen title with an optional trailing slot. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Container that keeps a glass card's children laid out like a Box. */
@Composable
fun GlassBox(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.large, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.clip(shape).background(FmColors.Glass).border(1.dp, FmColors.glassBorder, shape),
        content = content,
    )
}
