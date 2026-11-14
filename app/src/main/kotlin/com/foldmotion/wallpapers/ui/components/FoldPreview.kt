package com.foldmotion.wallpapers.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.render.FrameState
import com.foldmotion.wallpapers.render.LiquidMotion
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import com.foldmotion.wallpapers.ui.theme.FmColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Draws a [LoadedScene] with the engine's own [SceneDrawer] and the same living motion, so the
 * preview and the home screen cannot disagree about crop, layering or motion.
 */
class ScenePreviewView(context: Context) : View(context) {
    private val drawer = SceneDrawer()
    private var animator: ValueAnimator? = null
    private var progress: Float? = null
    private var looping = false
    private var lastFrame = 0L

    var scene: LoadedScene? = null
        set(value) {
            if (field !== value) {
                field?.videoFrames?.removeListener(videoListener)
                if (isAttachedToWindow) value?.videoFrames?.addListener(videoListener)
            }
            field = value
            invalidate()
            updateLoop()
        }

    /** Video packs: redraw whenever a new frame is decoded. */
    private val videoListener: () -> Unit = { invalidate() }

    /** Living motion between transitions. */
    var living: Boolean = true
        set(value) {
            field = value
            updateLoop()
        }

    /** When set, the entry transition is drawn at this progress (driven by the fold demo). */
    var externalProgress: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Horizontal slice of the scene this view shows, as fractions; the fold demo's halves use it. */
    var sliceStart: Float = 0f
    var sliceEnd: Float = 1f

    /** Screen-off darkness, 0..1. */
    var dark: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val loop = object : Choreographer.FrameCallback {
        override fun doFrame(frameNanos: Long) {
            if (!looping) return
            // Living motion at 60 fps, like the wallpaper engine on Full.
            if (frameNanos - lastFrame >= 15_000_000L) {
                lastFrame = frameNanos
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun updateLoop() {
        val want = living && isAttachedToWindow && scene?.isAlive == true && windowVisibility == VISIBLE
        if (want && !looping) {
            looping = true
            Choreographer.getInstance().postFrameCallback(loop)
        } else if (!want && looping) {
            looping = false
            Choreographer.getInstance().removeFrameCallback(loop)
        }
    }

    fun play(durationScale: Float = 1f) {
        val s = scene ?: return
        val motion = s.entryMotion ?: return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (motion.durationMs * durationScale).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    progress = null
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scene?.videoFrames?.addListener(videoListener)
        updateLoop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateLoop()
    }

    override fun onDetachedFromWindow() {
        scene?.videoFrames?.removeListener(videoListener)
        animator?.cancel()
        looping = false
        Choreographer.getInstance().removeFrameCallback(loop)
        super.onDetachedFromWindow()
    }

    /** Live liquid: a touch sends out a ripple, as a tap on the home screen does. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val s = scene
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN && s?.liquid?.touch == true && living && width > 0 && height > 0) {
            val span = (sliceEnd - sliceStart).coerceAtLeast(0.05f)
            s.worldAt(sliceStart + event.x / width * span, event.y / height)?.let { (wx, wy) ->
                s.liquidMotion.tap(wx, wy, SystemClock.elapsedRealtime())
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val s = scene ?: return
        if (s.layers.any { it.second.isRecycled }) return
        val span = (sliceEnd - sliceStart).coerceAtLeast(0.05f)
        val fullWidth = (width / span).toInt()
        canvas.save()
        canvas.translate(-sliceStart * fullWidth, 0f)
        drawer.draw(canvas, fullWidth, height, s, progress ?: externalProgress, FrameState(timeMs = SystemClock.elapsedRealtime(), ambient = living))
        canvas.restore()
        if (dark > 0.003f) canvas.drawColor(android.graphics.Color.argb((dark.coerceIn(0f, 1f) * 255).toInt(), 2, 4, 8))
    }
}

/** Loads both scenes of a pack for previewing and frees them when the composable leaves. */
@Composable
fun rememberPackScenes(packDir: File?, longSidePx: Int, revision: Int = 0, playVideo: Boolean = true): Pair<LoadedScene?, LoadedScene?> {
    var scenes by remember(packDir) { mutableStateOf<Pair<LoadedScene?, LoadedScene?>>(null to null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(packDir, longSidePx, revision) {
        if (packDir == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            SceneLoader.load(packDir, ScreenKind.COVER, longSidePx) to SceneLoader.load(packDir, ScreenKind.MAIN, longSidePx)
        }
        // A video pack plays one clip for both screens, as on the phone.
        loaded.second?.let { mainScene ->
            val spec = mainScene.video
            val file = mainScene.videoFile
            if (spec != null && file != null) {
                val overscan = (-(mainScene.layers.firstOrNull()?.first?.left ?: 0f)).coerceIn(0f, 0.25f)
                val source = try {
                    com.foldmotion.wallpapers.render.video.VideoFrameSource(context, file, spec, overscan, maxLongSide = 1440)
                } catch (e: Exception) {
                    null
                }
                loaded.first?.videoFrames = source
                mainScene.videoFrames = source
            }
        }
        val previous = scenes
        scenes = loaded
        // A new revision (new fold style) replaces scenes still on screen: free the old bitmaps
        // only once the views have drawn the new ones.
        if (previous.first != null || previous.second != null) {
            try {
                kotlinx.coroutines.delay(600)
            } finally {
                previous.second?.videoFrames?.release()
                previous.first?.recycle()
                previous.second?.recycle()
            }
        }
    }
    val source = scenes.second?.videoFrames
    LaunchedEffect(source, playVideo) { source?.setPlaying(playVideo) }
    DisposableEffect(packDir) {
        onDispose {
            scenes.second?.videoFrames?.release()
            scenes.first?.recycle()
            scenes.second?.recycle()
        }
    }
    return scenes
}

/** Thin wrapper keeping a reference to the view so callers can drive it. */
@Composable
private fun SceneView(
    scene: LoadedScene?,
    modifier: Modifier = Modifier,
    living: Boolean = true,
    progress: Float? = null,
    sliceStart: Float = 0f,
    sliceEnd: Float = 1f,
    dark: Float = 0f,
    playToken: Int = 0,
) {
    val view = remember { mutableStateOf<ScenePreviewView?>(null) }
    AndroidView(
        factory = { ctx -> ScenePreviewView(ctx).also { view.value = it } },
        update = { v ->
            v.sliceStart = sliceStart
            v.sliceEnd = sliceEnd
            if (v.scene !== scene) v.scene = scene
            v.living = living
            v.externalProgress = progress
            v.dark = dark
        },
        modifier = modifier,
    )
    LaunchedEffect(playToken, scene) {
        if (playToken > 0 && scene != null) view.value?.play()
    }
}

/** Phone-shaped frame around one screen. [playToken] changes trigger the entry motion once. */
@Composable
fun SceneFrame(
    scene: LoadedScene?,
    kind: ScreenKind,
    profile: DeviceProfile,
    modifier: Modifier = Modifier,
    playToken: Int = 0,
    showCrease: Boolean = true,
    living: Boolean = true,
) {
    val spec = profile.screen(kind)
    val shape = RoundedCornerShape(if (kind == ScreenKind.MAIN) 20.dp else 24.dp)
    val frameLabel = stringResource(if (kind == ScreenKind.MAIN) R.string.a11y_frame_main else R.string.a11y_frame_cover)
    Box(
        modifier
            .testTag("frame_${kind.name.lowercase()}")
            .aspectRatio(spec.widthPx / spec.heightPx.toFloat())
            .clip(shape)
            .background(Color(0xFF05080C))
            .border(2.dp, Brush.verticalGradient(listOf(Color(0xFF8DA2B5), Color(0xFF39495A))), shape)
            .padding(4.dp)
            .clip(RoundedCornerShape(if (kind == ScreenKind.MAIN) 16.dp else 20.dp))
            .semantics { contentDescription = frameLabel },
    ) {
        SceneView(scene, Modifier.fillMaxSize(), living = living, playToken = playToken)
        if (kind == ScreenKind.MAIN && showCrease) {
            Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.12f)))
        }
    }
}

/**
 * Cover and main side by side at the same physical scale, as the user will see them on the phone.
 * @param frameHeight height available for the taller of the two frames. Not called maxHeight:
 *   inside BoxWithConstraints that name would silently resolve to the scope's own property.
 */
@Composable
fun SideBySide(
    cover: LoadedScene?,
    main: LoadedScene?,
    profile: DeviceProfile,
    frameHeight: Dp,
    modifier: Modifier = Modifier,
    playCover: Int = 0,
    playMain: Int = 0,
    living: Boolean = true,
) {
    val coverMm = profile.cover.heightMm ?: 1f
    val mainSpec = profile.main ?: profile.cover
    val mainMm = mainSpec.heightMm ?: coverMm
    BoxWithConstraints(modifier) {
        val gap = 12.dp
        val coverWmm = profile.cover.widthMm ?: 1f
        val mainWmm = mainSpec.widthMm ?: 1f
        val totalWmm = coverWmm + mainWmm
        val tallMm = max(coverMm, mainMm)
        val density = LocalDensity.current
        val availW = with(density) { (constraints.maxWidth.toDp() - gap).toPx() }
        val availH = with(density) { frameHeight.toPx() }
        val pxPerMm = minOf(availW / totalWmm, availH / tallMm)
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            SceneFrame(cover, ScreenKind.COVER, profile, Modifier.width(with(density) { (coverWmm * pxPerMm).toDp() }), playCover, living = living)
            SceneFrame(main, ScreenKind.MAIN, profile, Modifier.width(with(density) { (mainWmm * pxPerMm).toDp() }), playMain, living = living)
        }
    }
}

private enum class DemoPhase { CLOSED, UNFOLDING, OPEN, FOLDING }

/**
 * A Fold that folds and unfolds in 3D, on a loop, while the real wallpaper choreography plays on
 * its screens: the left half swings on the crease with perspective (right to left as it opens,
 * like the phone), the cover screen on its back;
 * the main screen lights up and unfolds as the device opens, the cover gathers light as it closes.
 */
@Composable
fun FoldDemo(
    cover: LoadedScene?,
    main: LoadedScene?,
    profile: DeviceProfile,
    height: Dp,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    /** Holds the hinge at this angle instead of looping (design review and tests). */
    staticAngle: Float? = null,
) {
    // Fold angle of the left panel: 0 = flat open, -180 = folded shut with the cover facing us.
    val angle = remember { Animatable(staticAngle ?: -180f) }
    var mainProgress by remember { mutableStateOf<Float?>(null) }
    var coverProgress by remember { mutableStateOf<Float?>(null) }
    var mainDark by remember { mutableFloatStateOf(1f) }
    var phase by remember { mutableStateOf(DemoPhase.CLOSED) }

    LaunchedEffect(animate, cover, main, staticAngle) {
        if (staticAngle != null) {
            angle.snapTo(staticAngle)
            mainDark = if (staticAngle > -60f) 0f else 1f
            phase = if (staticAngle <= -179f) DemoPhase.CLOSED else if (staticAngle >= -1f) DemoPhase.OPEN else DemoPhase.UNFOLDING
            return@LaunchedEffect
        }
        if (!animate || cover == null || main == null) {
            angle.snapTo(if (animate) -180f else 0f)
            mainDark = 0f
            phase = if (animate) DemoPhase.CLOSED else DemoPhase.OPEN
            return@LaunchedEffect
        }
        while (isActive) {
            phase = DemoPhase.CLOSED
            mainDark = 1f
            // Cover appears: it plays its own entry (close) choreography.
            val closeMs = (cover.entryMotion?.durationMs ?: 900).toLong()
            animateValue(closeMs) { coverProgress = it }
            coverProgress = null
            delay(1_300)

            phase = DemoPhase.UNFOLDING
            launch {
                // Main screen turns on near the end of the swing, then plays its unfold.
                delay(520)
                val openMs = (main.entryMotion?.durationMs ?: 1_100).toLong()
                launch { animateValue(260) { mainDark = 1f - it } }
                animateValue(openMs) { mainProgress = it }
                mainProgress = null
            }
            angle.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessVeryLow * 1.6f))
            phase = DemoPhase.OPEN
            delay(2_600)

            phase = DemoPhase.FOLDING
            animateValue(220) { mainDark = it }
            angle.animateTo(-180f, tween(900, easing = FastOutSlowInEasing))
        }
    }

    val mainSpec = profile.main ?: profile.cover
    val mainAspect = mainSpec.widthPx / mainSpec.heightPx.toFloat()
    val coverWidthOfHalf = ((profile.cover.widthMm ?: 1f) / ((mainSpec.widthMm ?: 2f) / 2f)).coerceIn(0.7f, 1f)
    val coverHeightRatio = ((profile.cover.heightMm ?: 1f) / (mainSpec.heightMm ?: 1f)).coerceIn(0.9f, 1.05f)

    // Physical motion is described in screen space, so lay it out left-to-right in every language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                val deviceH = minOf(height * 0.94f, maxWidth / mainAspect * 0.94f)
                val deviceW = deviceH * mainAspect
                val half = deviceW / 2
                val density = LocalDensity.current
                val a = angle.value
                val closedness = (-a / 180f).coerceIn(0f, 1f)
                // Like the real Fold: the left panel carries the cover screen on its back and swings
                // over onto the right half, which stays put. Keep the closed phone centred by
                // shifting left by a quarter as it folds.
                val shift = -with(density) { (half / 2).toPx() } * closedness
                Box(
                    Modifier
                        .size(deviceW, deviceH)
                        .graphicsLayer { translationX = shift },
                ) {
                    val bezel = Color(0xFF0A0E13)
                    val rim = Brush.verticalGradient(listOf(Color(0xFF9FB2C4), Color(0xFF3B4A59)))
                    // Right half: the main screen's right side, which stays still. Its hinge-side
                    // corners round off as the phone closes so they never peek out from under the
                    // folded panel.
                    val hingeCorner = 20.dp * closedness
                    val rightShape = RoundedCornerShape(topStart = hingeCorner, bottomStart = hingeCorner, topEnd = 18.dp, bottomEnd = 18.dp)
                    val leftShape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
                    Box(
                        Modifier
                            .offset(x = half)
                            .size(half, deviceH)
                            .clip(rightShape)
                            .background(bezel)
                            .border(2.dp, rim, rightShape)
                            .padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        SceneView(main, Modifier.fillMaxSize().clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)), living = animate, progress = mainProgress, sliceStart = 0.5f, sliceEnd = 1f, dark = mainDark)
                        // The folding panel casts a shadow over this side while it is above it.
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f * sin(Math.toRadians(-a.toDouble())).toFloat().coerceAtLeast(0f))))
                    }
                    // Left half: turns on the crease (its right edge). Front = main left side,
                    // back = cover screen. Opening, it swings from right to left.
                    Box(
                        Modifier
                            .size(half, deviceH)
                            .graphicsLayer {
                                rotationY = -a
                                transformOrigin = TransformOrigin(1f, 0.5f)
                                cameraDistance = 14f * density.density
                            },
                    ) {
                        if (a > -90f) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(leftShape)
                                    .background(bezel)
                                    .border(2.dp, rim, leftShape)
                                    .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                            ) {
                                SceneView(main, Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)), living = animate, progress = mainProgress, sliceStart = 0f, sliceEnd = 0.5f, dark = mainDark)
                                // Light falls off as the panel turns away.
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f * (abs(a) / 90f).coerceIn(0f, 1f))))
                            }
                        } else {
                            // Back face: mirrored back so the cover reads the right way round.
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { scaleX = -1f }
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(bezel)
                                    .border(2.dp, rim, RoundedCornerShape(20.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(coverWidthOfHalf * 0.96f)
                                        .fillMaxHeight((coverHeightRatio * 0.97f).coerceAtMost(0.97f))
                                        .clip(RoundedCornerShape(18.dp)),
                                ) {
                                    SceneView(cover, Modifier.fillMaxSize(), living = animate, progress = coverProgress)
                                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f * ((180f - abs(a)) / 90f).coerceIn(0f, 1f))))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            AnimatedContent(phase, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "phase") { p ->
                Text(
                    stringResource(
                        when (p) {
                            DemoPhase.CLOSED -> R.string.demo_closed
                            DemoPhase.UNFOLDING -> R.string.demo_unfolding
                            DemoPhase.OPEN -> R.string.demo_open
                            DemoPhase.FOLDING -> R.string.demo_folding
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = FmColors.TextMuted,
                )
            }
        }
    }
}

/** Runs [onValue] from 0 to 1 over [durationMs] on the frame clock. */
private suspend fun animateValue(durationMs: Long, onValue: (Float) -> Unit) {
    val anim = Animatable(0f)
    anim.animateTo(1f, tween(durationMs.toInt(), easing = LinearEasing)) { onValue(value) }
    onValue(1f)
}

/**
 * Live liquid in one screen's window of its world, as the wallpaper will show it (Create's
 * preview). Touch it for ripples. When motion is off, the still frame shows.
 */
@Composable
fun LiquidLive(
    spec: com.foldmotion.core.pack.LiquidSpec,
    kind: ScreenKind,
    profile: DeviceProfile,
    modifier: Modifier = Modifier,
    living: Boolean = true,
    /** A still of this look (a Discover thumbnail), shown while motion is off. */
    posterAsset: String? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scene = remember(spec, kind, profile, posterAsset) {
        val g = com.foldmotion.wallpapers.generator.liquid.LiquidPackBuilder.geometry(profile)
        val o = com.foldmotion.wallpapers.generator.liquid.LiquidPackBuilder.OVERSCAN
        val window = if (kind == ScreenKind.MAIN) g.main else g.cover
        val screen = profile.screen(kind)
        // Only a stand-in until the GPU draws the live liquid (or while motion is off): a gradient
        // of the look's colours, made on the CPU so composing never waits for the GPU.
        val pw = 96
        val ph = (pw / screen.aspect).toInt().coerceAtLeast(2)
        val colors = spec.palette.map { LoadedScene.parseColor(it, android.graphics.Color.GRAY) }
        val still = posterAsset?.let {
            try {
                context.assets.open(it).use { s -> android.graphics.BitmapFactory.decodeStream(s) }
            } catch (e: Exception) {
                null
            }
        }
        val poster = still ?: android.graphics.Bitmap.createBitmap(pw, ph, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
            android.graphics.Canvas(b).drawRect(
                0f, 0f, pw.toFloat(), ph.toFloat(),
                android.graphics.Paint().apply {
                    shader = android.graphics.LinearGradient(0f, 0f, pw * 0.7f, ph.toFloat(), intArrayOf(colors[3], colors[2], colors[1], colors[0]), null, android.graphics.Shader.TileMode.CLAMP)
                },
            )
        }
        LoadedScene(
            packId = "liquid-preview",
            kind = kind,
            baseWidth = screen.widthPx,
            baseHeight = screen.heightPx,
            focusX = 0.5f,
            focusY = 0.5f,
            // A Discover still covers the window exactly; the stand-in covers it with overscan.
            layers = listOf(
                (if (still != null) com.foldmotion.core.pack.LayerEntry("far", "preview", poster.width, poster.height, listOf(0f, 0f, 1f, 1f), 0f, opaque = true)
                else com.foldmotion.core.pack.LayerEntry("far", "preview", pw, ph, listOf(-o, -o, 1 + o, 1 + o), 0f, opaque = true)) to poster,
            ),
            entryMotion = null,
            background = android.graphics.Color.BLACK,
            glow = LoadedScene.parseColor(spec.palette[3], android.graphics.Color.WHITE),
            world = window,
            liquid = spec,
        )
    }
    DisposableEffect(scene) { onDispose { scene.recycle() } }
    SceneView(scene, modifier, living = living)
}
