package com.foldmotion.wallpapers.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.ui.theme.FmColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

fun foldStyleLabelRes(style: FoldStyle): Int = when (style) {
    FoldStyle.AUTO -> R.string.style_auto
    FoldStyle.UNFOLD -> R.string.style_unfold
    FoldStyle.BOOK -> R.string.style_book
    FoldStyle.RISE -> R.string.style_rise
    FoldStyle.WARP -> R.string.style_warp
    FoldStyle.TILES -> R.string.style_tiles
    FoldStyle.SWEEP -> R.string.style_sweep
    FoldStyle.IRIS -> R.string.style_iris
    FoldStyle.RIPPLE -> R.string.style_ripple
    FoldStyle.FLOW -> R.string.style_flow
    FoldStyle.GLASS -> R.string.style_glass
    FoldStyle.NATURAL -> R.string.style_natural
}

private fun foldStyleDescriptionRes(style: FoldStyle): Int = when (style) {
    FoldStyle.AUTO -> R.string.style_auto_desc
    FoldStyle.UNFOLD -> R.string.style_unfold_desc
    FoldStyle.BOOK -> R.string.style_book_desc
    FoldStyle.RISE -> R.string.style_rise_desc
    FoldStyle.WARP -> R.string.style_warp_desc
    FoldStyle.TILES -> R.string.style_tiles_desc
    FoldStyle.SWEEP -> R.string.style_sweep_desc
    FoldStyle.IRIS -> R.string.style_iris_desc
    FoldStyle.RIPPLE -> R.string.style_ripple_desc
    FoldStyle.FLOW -> R.string.style_flow_desc
    FoldStyle.GLASS -> R.string.style_glass_desc
    FoldStyle.NATURAL -> R.string.style_natural_desc
}

@Composable
fun foldStyleLabel(style: FoldStyle): String = stringResource(foldStyleLabelRes(style))

/**
 * A grid of fold styles, four to a row, each with a small looping sketch of its motion so the
 * choice can be made by eye; the selected style's description sits underneath.
 */
@Composable
fun StylePicker(
    styles: List<FoldStyle>,
    selected: FoldStyle,
    onSelect: (FoldStyle) -> Unit,
    animate: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier) {
        // Rows of four equal tiles; a short last row keeps the same tile size.
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in styles.chunked(4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (style in row) {
                        StyleTile(style, style == selected, enabled, animate, Modifier.weight(1f)) { onSelect(style) }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        AnimatedContent(selected, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "styleDesc") { s ->
            Text(
                stringResource(foldStyleDescriptionRes(s)),
                style = MaterialTheme.typography.bodySmall,
                color = FmColors.TextMuted,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun StyleTile(style: FoldStyle, chosen: Boolean, enabled: Boolean, animate: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    val label = foldStyleLabel(style)
    val glow by animateFloatAsState(if (chosen) 1f else 0f, tween(260), label = "tileGlow")
    Column(
        modifier
            .pressScale(interaction, 0.94f)
            .clip(shape)
            .background(if (chosen) FmColors.GlassStrong else FmColors.Glass)
            .border(if (chosen) 2.dp else 1.dp, if (chosen) FmColors.primaryGradient else FmColors.glassBorder, shape)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = chosen
                contentDescription = label
            }
            .testTag("style_${style.name.lowercase()}")
            .alpha(if (enabled) 1f else 0.5f)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StyleGlyph(style, animate = animate && (chosen || enabled), emphasis = glow, modifier = Modifier.fillMaxWidth().aspectRatio(0.9f).clip(RoundedCornerShape(12.dp)))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (chosen) FmColors.Text else FmColors.TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
    }
}

/**
 * A tiny screen playing the idea of a fold style on a sketched night scene. Pure Compose drawing,
 * looping every two seconds with a pause on the finished scene; still when [animate] is false.
 */
@Composable
fun StyleGlyph(style: FoldStyle, animate: Boolean, modifier: Modifier = Modifier, emphasis: Float = 1f) {
    val t = if (animate) {
        val loop = rememberInfiniteTransition(label = "glyph")
        val raw by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2_200, easing = LinearEasing)), label = "glyphT")
        // Play over the first 70% of the loop, then hold the finished scene.
        min(1f, raw / 0.7f)
    } else {
        0.62f
    }
    Canvas(modifier) {
        drawRect(Color(0xFF040A12))
        when (style) {
            FoldStyle.AUTO -> autoGlyph(t)
            FoldStyle.UNFOLD -> unfoldGlyph(t)
            FoldStyle.BOOK -> bookGlyph(t)
            FoldStyle.RISE -> riseGlyph(t)
            FoldStyle.WARP -> warpGlyph(t)
            FoldStyle.TILES -> tilesGlyph(t)
            FoldStyle.SWEEP -> sweepGlyph(t)
            FoldStyle.IRIS -> irisGlyph(t)
            FoldStyle.RIPPLE -> rippleGlyph(t)
            FoldStyle.FLOW -> flowGlyph(t)
            FoldStyle.GLASS -> glassGlyph(t)
            FoldStyle.NATURAL -> naturalGlyph(t)
        }
        if (emphasis > 0f) drawRect(FmColors.Aurora.copy(alpha = 0.06f * emphasis), blendMode = BlendMode.Plus)
    }
}

// ---------------------------------------------------------------------- sketches

private val SkyTop = Color(0xFF0D3346)
private val SkyMid = Color(0xFF1D7C74)
private val SkyLow = Color(0xFF0B2230)
private val HillFar = Color(0xFF12505A)
private val HillNear = Color(0xFF061A23)
private val Moon = Color(0xFFEFFFF7)
private val Light = Color(0xFFBFFFF0)

private fun ease(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x) * (1f - x)
}

private fun DrawScope.moonCenter() = Offset(size.width * 0.68f, size.height * 0.3f)

private fun DrawScope.hill(base: Float, amp: Float, color: Color, lift: Float = 0f, alpha: Float = 1f) {
    val w = size.width
    val h = size.height
    val y0 = h * base + lift * h
    val path = Path().apply {
        moveTo(0f, y0)
        cubicTo(w * 0.25f, y0 - amp * h, w * 0.45f, y0 + amp * h * 0.6f, w * 0.65f, y0 - amp * h * 0.4f)
        cubicTo(w * 0.8f, y0 - amp * h, w * 0.9f, y0, w, y0 - amp * h * 0.3f)
        lineTo(w, h + lift * h + 2f)
        lineTo(0f, h + lift * h + 2f)
        close()
    }
    drawPath(path, color, alpha = alpha)
}

/** The sketched scene every style reveals: sky, moon and two ranges of hills. */
private fun DrawScope.scene(skyAlpha: Float = 1f, farLift: Float = 0f, nearLift: Float = 0f, hillAlpha: Float = 1f) {
    drawRect(Brush.verticalGradient(listOf(SkyTop, SkyMid, SkyLow)), alpha = skyAlpha)
    drawCircle(Moon.copy(alpha = 0.25f * skyAlpha), radius = size.minDimension * 0.17f, center = moonCenter())
    drawCircle(Moon, radius = size.minDimension * 0.085f, center = moonCenter(), alpha = skyAlpha)
    hill(0.62f, 0.06f, HillFar, farLift, hillAlpha)
    hill(0.8f, 0.05f, HillNear, nearLift, hillAlpha)
}

private fun DrawScope.autoGlyph(t: Float) {
    scene()
    drawRect(Color.Black.copy(alpha = 0.35f))
    // A four-pointed sparkle that breathes: "the app chooses".
    val c = Offset(size.width / 2f, size.height / 2f)
    val pulse = 0.75f + 0.25f * sin(t * PI.toFloat() * 2f)
    val r = size.minDimension * 0.3f * pulse
    val k = r * 0.22f
    val star = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
        quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
        quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
        quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
        close()
    }
    drawCircle(Light.copy(alpha = 0.25f), radius = r * 0.9f, center = c, blendMode = BlendMode.Plus)
    drawPath(star, Brush.linearGradient(listOf(FmColors.Aurora, FmColors.Violet), start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r)))
}

/** The scene opens from the right part (where the closed phone was) out to the left. */
private fun DrawScope.unfoldGlyph(t: Float) {
    val e = ease(t)
    val l = size.width * (0.52f * (1f - e))
    clipRect(l, 0f, size.width, size.height) { scene() }
    val a = 1f - e
    if (a > 0.02f) drawLine(Light.copy(alpha = a), Offset(l, 0f), Offset(l, size.height), strokeWidth = 2.5f, blendMode = BlendMode.Plus)
}

/** The left page swings down from right to left, the way the Fold's front panel opens. */
private fun DrawScope.bookGlyph(t: Float) {
    val e = ease(t)
    val half = size.width / 2f
    clipRect(half, 0f, size.width, size.height) { scene() }
    val angle = 95f * (1f - e)
    val open = cos(angle / 180f * PI.toFloat()).coerceAtLeast(0f)
    if (open > 0.01f) {
        // The left page, foreshortened and taller at its free edge as it lifts towards us.
        val lift = 1f + 0.25f * sin(angle / 180f * PI.toFloat())
        withTransform({ scale(open, 1f, pivot = Offset(half, size.height / 2f)) }) {
            clipRect(0f, 0f, half, size.height) {
                withTransform({ scale(1f, lift, pivot = Offset(0f, size.height / 2f)) }) { scene() }
                drawRect(Color.Black.copy(alpha = 0.75f * (1f - open)))
            }
        }
    }
    drawRect(
        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.5f * (1f - e)), Color.Transparent), startX = half, endX = half * 1.4f),
        topLeft = Offset(half, 0f), size = Size(half, size.height),
    )
}

private fun DrawScope.riseGlyph(t: Float) {
    val sky = ease(t / 0.6f)
    val far = ease((t - 0.1f) / 0.6f)
    val near = ease((t - 0.25f) / 0.6f)
    drawRect(Brush.verticalGradient(listOf(SkyTop, SkyMid, SkyLow)), alpha = 0.25f + 0.75f * sky)
    drawCircle(Moon, radius = size.minDimension * 0.085f, center = moonCenter(), alpha = 0.3f + 0.7f * sky)
    hill(0.62f, 0.06f, HillFar, lift = 0.4f * (1f - far))
    hill(0.8f, 0.05f, HillNear, lift = 0.5f * (1f - near))
    // Embers floating up.
    for (i in 0 until 7) {
        val p = ((t * 1.3f + i * 0.17f) % 1f)
        val x = size.width * (0.12f + 0.12f * i) + sin(p * 6f + i) * size.width * 0.02f
        val y = size.height * (1.02f - 0.6f * p)
        drawCircle(Color(0xFFFFE0A8), radius = size.minDimension * 0.018f, center = Offset(x, y), alpha = sin(p * PI.toFloat()) * (1f - t * 0.6f), blendMode = BlendMode.Plus)
    }
}

/** NATURAL: nothing on top; the sky settles and the hills glide in from the right in depth. */
private fun DrawScope.naturalGlyph(t: Float) {
    val sky = ease(t / 0.8f)
    val far = ease((t - 0.05f) / 0.75f)
    val near = ease((t - 0.15f) / 0.75f)
    val w = size.width
    val zoom = 1f + 0.12f * (1f - sky)
    val pivot = Offset(w * 0.75f, size.height * 0.5f)
    scale(zoom, pivot) {
        drawRect(Brush.verticalGradient(listOf(SkyTop, SkyMid, SkyLow)))
        drawCircle(Moon.copy(alpha = 0.25f), radius = size.minDimension * 0.17f, center = moonCenter())
        drawCircle(Moon, radius = size.minDimension * 0.085f, center = moonCenter())
    }
    translate(left = w * 0.22f * (1f - far)) { hill(0.62f, 0.06f, HillFar) }
    translate(left = w * 0.45f * (1f - near)) { hill(0.8f, 0.05f, HillNear) }
}

private fun DrawScope.warpGlyph(t: Float) {
    val e = ease(t)
    val zoom = 1f + 0.5f * (1f - e)
    val c = moonCenter()
    withTransform({ scale(zoom, zoom, pivot = c) }) { scene() }
    drawRect(Color.Black.copy(alpha = 0.55f * (1f - e)))
    val streak = 1f - t
    if (streak > 0.02f) {
        val diag = max(size.width, size.height)
        for (i in 0 until 16) {
            val a = i / 16f * 2f * PI.toFloat() + 0.2f
            val r0 = diag * (0.08f + 0.3f * ((i * 37) % 11) / 11f) + diag * 0.5f * t
            val len = diag * (0.08f + 0.25f * t)
            val d = Offset(cos(a), sin(a))
            drawLine(
                Light.copy(alpha = streak * 0.9f), c + d * r0, c + d * (r0 + len),
                strokeWidth = 2f, cap = StrokeCap.Round, blendMode = BlendMode.Plus,
            )
        }
        drawCircle(Color.White.copy(alpha = 0.6f * streak), radius = size.minDimension * (0.12f + 0.3f * t), center = c, blendMode = BlendMode.Plus)
    }
}

private fun DrawScope.tilesGlyph(t: Float) {
    scene()
    val cols = 3
    val rows = 3
    val cw = size.width / cols
    val ch = size.height / rows
    for (j in 0 until rows) for (i in 0 until cols) {
        // The wave rolls in from the right.
        val start = ((cols - 1 - i) + j * 0.3f) / 2.6f * 0.55f
        val p = ((t - start) / 0.45f).coerceIn(0f, 1f)
        if (p >= 1f) continue
        val left = i * cw
        val top = j * ch
        drawRect(Color(0xFF040A12), Offset(left, top), Size(cw + 0.5f, ch + 0.5f))
        if (p <= 0f) continue
        val open = ease(p)
        withTransform({ scale(open, 1f, pivot = Offset(left + cw / 2f, top + ch / 2f)) }) {
            clipRect(left + 1f, top + 1f, left + cw - 1f, top + ch - 1f) {
                scene()
                drawRect(Color.Black.copy(alpha = 0.6f * (1f - open)))
            }
        }
    }
}

private fun DrawScope.sweepGlyph(t: Float) {
    val e = t.coerceIn(0f, 1f)
    // The band crosses from right to left.
    val angle = 158f
    val extent = size.width + size.height
    val x = -size.height * 0.4f + e * extent
    rotate(angle, pivot = Offset(size.width / 2f, size.height / 2f)) {
        val big = extent
        val path = Path().apply { addRect(androidx.compose.ui.geometry.Rect(-big, -big, x, big * 2f)) }
        clipPath(path) {
            rotate(-angle, pivot = Offset(size.width / 2f, size.height / 2f)) { scene() }
        }
        if (e < 0.98f) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Light.copy(alpha = 0.5f), Color.Transparent), startX = x - 14f, endX = x + 14f), topLeft = Offset(x - 14f, -big), size = Size(28f, big * 3f), blendMode = BlendMode.Plus)
            drawLine(Color.White.copy(alpha = 0.9f), Offset(x, -big), Offset(x, big * 2f), strokeWidth = 2f, blendMode = BlendMode.Plus)
        }
    }
}

private fun DrawScope.irisGlyph(t: Float) {
    val c = moonCenter()
    val gather = (t / 0.35f).coerceIn(0f, 1f)
    val open = ease((t - 0.3f) / 0.7f)
    val radius = max(size.width, size.height) * 1.2f * open
    if (radius > 1f) {
        val path = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, radius)) }
        clipPath(path) { scene() }
        if (open < 0.98f) drawCircle(Light.copy(alpha = 0.7f * (1f - open)), radius = radius, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f), blendMode = BlendMode.Plus)
    }
    if (gather < 1f || open < 0.2f) {
        for (i in 0 until 10) {
            val a = i / 10f * 2f * PI.toFloat() + gather * 2.5f
            val r = size.minDimension * 0.6f * (1f - gather)
            drawCircle(Light, radius = size.minDimension * 0.025f, center = c + Offset(cos(a), sin(a)) * r, alpha = 0.9f * (1f - open * 4f).coerceIn(0f, 1f), blendMode = BlendMode.Plus)
        }
    }
}

/** Rings spread from a drop and the scene appears inside the first one. */
private fun DrawScope.rippleGlyph(t: Float) {
    val c = Offset(size.width * 0.5f, size.height * 0.55f)
    val far = max(size.width, size.height) * 0.85f
    val e = ease(t)
    val front = far * e * 1.1f
    if (front > 1f) {
        clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, front)) }) {
            // The picture wobbles slightly inside the rings while they are fresh.
            val wobble = 1f + 0.04f * sin(t * 18f) * (1f - e)
            withTransform({ scale(wobble, wobble, pivot = c) }) { scene() }
        }
    }
    val calm = 1f - e
    if (calm > 0.02f) {
        for (i in 0 until 4) {
            val r = front - i * size.minDimension * 0.09f
            if (r <= 1f) continue
            val a = calm * (1f - i * 0.22f)
            drawCircle(Light.copy(alpha = 0.75f * a), radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(if (i == 0) 2.5f else 1.5f), blendMode = BlendMode.Plus)
        }
        // The drop itself, at the start.
        val drop = (1f - t / 0.2f).coerceIn(0f, 1f)
        if (drop > 0f) drawCircle(Color.White.copy(alpha = drop), radius = size.minDimension * 0.05f, center = c, blendMode = BlendMode.Plus)
    }
}

/** The scene pours in from the right behind a wavy, glossy liquid edge. */
private fun DrawScope.flowGlyph(t: Float) {
    val e = ease(t)
    val w = size.width
    val h = size.height
    val front = w * 1.15f - e * w * 1.35f
    val amp = w * 0.07f * (0.3f + (1f - e))
    fun edgeX(y: Float) = front + amp * (sin(y / h * 7f + t * 6f) * 0.7f + sin(y / h * 15f - t * 9f) * 0.3f)
    val steps = 18
    val edge = Path().apply {
        moveTo(2 * w, -1f)
        lineTo(front, -1f)
        for (k in 0..steps) {
            val y = h * k / steps
            lineTo(edgeX(y), y)
        }
        lineTo(2 * w, h + 1f)
        close()
    }
    clipPath(edge) { scene() }
    if (e < 0.98f) {
        val rim = Path().apply {
            for (k in 0..steps) {
                val y = h * k / steps
                if (k == 0) moveTo(edgeX(y), y) else lineTo(edgeX(y), y)
            }
        }
        drawPath(rim, Light.copy(alpha = 0.85f * (1f - e)), style = androidx.compose.ui.graphics.drawscope.Stroke(3f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    }
}

/** The scene behind frosted, ribbed glass that melts clear while a sheen crosses it. */
private fun DrawScope.glassGlyph(t: Float) {
    val e = ease(t)
    val melt = 1f - e
    val wobble = size.width * 0.03f * melt
    withTransform({ translate(wobble * sin(t * 9f), 0f) }) { scene() }
    if (melt > 0.02f) {
        drawRect(Color.Black.copy(alpha = 0.5f * melt * melt))
        // Ribs of glass: soft light and dark bands that fade as the glass melts.
        val ribs = 5
        val rw = size.width / ribs
        for (i in 0 until ribs) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.28f * melt), Color.Transparent, Color.Black.copy(alpha = 0.18f * melt)), startX = i * rw, endX = (i + 1) * rw),
                topLeft = Offset(i * rw, 0f), size = Size(rw + 0.5f, size.height),
            )
        }
        val x = size.width * (t * 1.6f - 0.3f)
        rotate(20f, pivot = Offset(size.width / 2f, size.height / 2f)) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Light.copy(alpha = 0.55f * melt), Color.Transparent), startX = x - 16f, endX = x + 16f),
                topLeft = Offset(x - 16f, -size.height), size = Size(32f, size.height * 3f), blendMode = BlendMode.Plus,
            )
        }
    }
}
