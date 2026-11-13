package com.foldmotion.wallpapers.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.foldmotion.wallpapers.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Plain View that draws a [LoadedScene] with the engine's own [SceneDrawer], so the preview and
 * the home screen cannot disagree about crop, layering or motion.
 */
class ScenePreviewView(context: Context) : View(context) {
    private val drawer = SceneDrawer()
    private var animator: ValueAnimator? = null
    private var progress: Float? = null

    var scene: LoadedScene? = null
        set(value) {
            field = value
            invalidate()
        }

    fun play(durationScale: Float = 1f) {
        val s = scene ?: return
        val motion = s.entryMotion ?: return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            // Ignore the system animator scale here only for duration math; reduced motion is
            // handled by the caller, which simply does not call play().
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

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val s = scene ?: return
        if (s.layers.any { it.second.isRecycled }) return
        drawer.draw(canvas, width, height, s, progress)
    }
}

/** Loads both scenes of a pack for previewing and frees them when the composable leaves. */
@Composable
fun rememberPackScenes(packDir: File?, longSidePx: Int): Pair<LoadedScene?, LoadedScene?> {
    var scenes by remember(packDir) { mutableStateOf<Pair<LoadedScene?, LoadedScene?>>(null to null) }
    LaunchedEffect(packDir, longSidePx) {
        if (packDir == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            SceneLoader.load(packDir, ScreenKind.COVER, longSidePx) to SceneLoader.load(packDir, ScreenKind.MAIN, longSidePx)
        }
        scenes = loaded
    }
    DisposableEffect(packDir) {
        onDispose {
            scenes.first?.recycle()
            scenes.second?.recycle()
        }
    }
    return scenes
}

/**
 * Phone-shaped frame around a scene. [playToken] changes trigger the entry motion once.
 */
@Composable
fun SceneFrame(
    scene: LoadedScene?,
    kind: ScreenKind,
    profile: DeviceProfile,
    modifier: Modifier = Modifier,
    playToken: Int = 0,
    showCrease: Boolean = true,
) {
    val spec = profile.screen(kind)
    val shape = RoundedCornerShape(if (kind == ScreenKind.MAIN) 18.dp else 22.dp)
    val view = remember { mutableStateOf<ScenePreviewView?>(null) }
    val frameLabel = stringResource(if (kind == ScreenKind.MAIN) R.string.a11y_frame_main else R.string.a11y_frame_cover)
    Box(
        modifier
            .testTag("frame_${kind.name.lowercase()}")
            .aspectRatio(spec.widthPx / spec.heightPx.toFloat())
            .clip(shape)
            .background(Color(0xFF061420))
            .border(3.dp, Color(0xFF6F8FA0), shape),
    ) {
        AndroidView(
            factory = { ctx -> ScenePreviewView(ctx).also { view.value = it } },
            update = { v -> if (v.scene !== scene) v.scene = scene },
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(3.dp).clip(RoundedCornerShape(15.dp))
                .semantics { contentDescription = frameLabel },
        )
        if (kind == ScreenKind.MAIN && showCrease) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.18f)),
            )
        }
    }
    LaunchedEffect(playToken, scene) {
        if (playToken > 0 && scene != null) view.value?.play()
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
) {
    val coverMm = profile.cover.heightMm ?: 1f
    val mainSpec = profile.main ?: profile.cover
    val mainMm = mainSpec.heightMm ?: coverMm
    BoxWithConstraints(modifier) {
        val gap = 12.dp
        // Widths in mm at a common scale, then fitted into the available box.
        val coverWmm = profile.cover.widthMm ?: 1f
        val mainWmm = mainSpec.widthMm ?: 1f
        val totalWmm = coverWmm + mainWmm
        val tallMm = max(coverMm, mainMm)
        val density = LocalDensity.current
        val availW = with(density) { (constraints.maxWidth.toDp() - gap).toPx() }
        val availH = with(density) { frameHeight.toPx() }
        val pxPerMm = minOf(availW / totalWmm, availH / tallMm)
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            SceneFrame(cover, ScreenKind.COVER, profile, Modifier.width(with(density) { (coverWmm * pxPerMm).toDp() }), playCover)
            SceneFrame(main, ScreenKind.MAIN, profile, Modifier.width(with(density) { (mainWmm * pxPerMm).toDp() }), playMain)
        }
    }
}
