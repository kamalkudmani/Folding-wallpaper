package com.foldmotion.wallpapers.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.foldmotion.core.device.DeviceProfileRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

// ---------------------------------------------------------------------- images

/** A picture for a device mockup: a bundled catalogue asset or a stored pack's preview. */
sealed interface DeviceImage {
    val key: String

    data class Asset(val path: String) : DeviceImage {
        override val key get() = "asset:$path"
    }

    data class File(val file: java.io.File, val version: Int = 0) : DeviceImage {
        override val key get() = "file:${file.path}@$version"
    }
}

private object DeviceImageCache {
    val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun load(context: Context, image: DeviceImage, maxWidth: Int): Bitmap? {
        val key = image.key + "/" + maxWidth
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        fun open() = when (image) {
            is DeviceImage.Asset -> context.assets.open(image.path)
            is DeviceImage.File -> image.file.inputStream()
        }
        return try {
            open().use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            val bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            bmp?.also { cache.put(key, it) }
        } catch (e: Exception) {
            null
        }
    }
}

/** Loads [image] off the main thread, sized for about [maxWidthPx]; null until ready. */
@Composable
fun rememberDeviceImage(image: DeviceImage?, maxWidthPx: Int = 720): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(image?.key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image?.key, maxWidthPx) {
        if (image == null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) { DeviceImageCache.load(context, image, maxWidthPx) }?.asImageBitmap()
    }
    return bitmap
}

// ---------------------------------------------------------------------- devices

/** Galaxy Z Fold7 proportions: the cover and the inner screen, both about 151 mm tall. */
object FoldProportions {
    private val fold7 = DeviceProfileRegistry.fold7
    val coverAspect: Float = fold7.cover.aspect
    val mainAspect: Float = fold7.main!!.aspect
    /** Width of the closed phone relative to the open one at the same height. */
    val coverToMainWidth: Float = coverAspect / mainAspect

    /** A phone's frame adds a thin border around the screen. */
    const val FRAME = 0.028f
}

private val FrameBrush = Brush.linearGradient(listOf(Color(0xFF3A3D44), Color(0xFF1A1C20), Color(0xFF2B2E34)), start = Offset.Zero, end = Offset.Infinite)
private val FrameEdge = Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x0DFFFFFF), Color(0x26FFFFFF)))

/**
 * The closed phone: the cover screen in a slim graphite frame with its punch-hole camera, a glass
 * reflection and a soft shadow tinted with the wallpaper's light.
 */
@Composable
fun FoldedDevice(image: ImageBitmap?, modifier: Modifier = Modifier, glow: Color = Color(0xFF2DE1C2), elevation: Dp = 18.dp) {
    BoxWithConstraints(modifier.aspectRatio(FoldProportions.coverAspect * (1f + 2 * FoldProportions.FRAME) / (1f + 2 * FoldProportions.FRAME * FoldProportions.coverAspect))) {
        val w = maxWidth
        val outer = RoundedCornerShape(w * 0.13f)
        val inset = w * FoldProportions.FRAME * 1.4f
        val inner = RoundedCornerShape(w * 0.13f - inset)
        Box(
            Modifier.fillMaxSize()
                .shadow(elevation, outer, ambientColor = glow.copy(alpha = 0.45f), spotColor = glow.copy(alpha = 0.7f))
                .clip(outer)
                .background(FrameBrush)
                .border(1.dp, FrameEdge, outer)
                .padding(inset),
        ) {
            Screen(image, inner)
            // Punch-hole camera, top centre.
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = w * 0.045f)
                    .width(w * 0.055f).aspectRatio(1f).clip(RoundedCornerShape(50))
                    .background(Brush.radialGradient(listOf(Color(0xFF1B2230), Color(0xFF020304)))),
            )
        }
    }
}

/**
 * The open phone: the inner screen in its frame, with the crease catching light down the middle
 * and the under-display camera at the top of the right half.
 */
@Composable
fun UnfoldedDevice(image: ImageBitmap?, modifier: Modifier = Modifier, glow: Color = Color(0xFF2DE1C2), elevation: Dp = 22.dp, crease: Boolean = true) {
    BoxWithConstraints(modifier.aspectRatio(FoldProportions.mainAspect * (1f + 2 * FoldProportions.FRAME) / (1f + 2 * FoldProportions.FRAME * FoldProportions.mainAspect))) {
        val w = maxWidth
        val outer = RoundedCornerShape(w * 0.065f)
        val inset = w * FoldProportions.FRAME * 0.8f
        val inner = RoundedCornerShape(w * 0.065f - inset)
        Box(
            Modifier.fillMaxSize()
                .shadow(elevation, outer, ambientColor = glow.copy(alpha = 0.45f), spotColor = glow.copy(alpha = 0.7f))
                .clip(outer)
                .background(FrameBrush)
                .border(1.dp, FrameEdge, outer)
                .padding(inset),
        ) {
            Screen(image, inner)
            if (crease) {
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val band = size.width * 0.035f
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color(0x33000000), Color(0x1FFFFFFF), Color(0x33000000), Color.Transparent),
                            startX = cx - band, endX = cx + band,
                        ),
                        topLeft = Offset(cx - band, 0f), size = androidx.compose.ui.geometry.Size(band * 2, size.height),
                    )
                }
            }
        }
    }
}

@Composable
private fun Screen(image: ImageBitmap?, shape: RoundedCornerShape) {
    Box(Modifier.fillMaxSize().clip(shape).background(Color(0xFF07090D))) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Glass: a faint diagonal sheen across the top.
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    listOf(Color(0x1CFFFFFF), Color(0x00FFFFFF), Color(0x00FFFFFF), Color(0x0DFFFFFF)),
                    start = Offset(0f, 0f), end = Offset(900f, 1400f),
                ),
            ),
        )
    }
}

// ---------------------------------------------------------------------- pairs

/** How a card arranges the closed and the open phone; varied heights give the wall its rhythm. */
enum class PairLayout(val aspect: Float) {
    /** Closed and open side by side, same height. */
    SIDE(1.32f),
    /** The open phone large, the closed one standing in front of its corner. */
    OVERLAP(0.92f),
    /** The closed phone large in front, the open one behind it. */
    TALL(0.66f),
}

/** The same wallpaper on the closed and the open phone, arranged as a product shot. */
@Composable
fun FoldPair(cover: ImageBitmap?, main: ImageBitmap?, layout: PairLayout, modifier: Modifier = Modifier, glow: Color = Color(0xFF2DE1C2)) {
    // Phones are objects, not text: they keep their real left-right arrangement in Arabic too.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(layout.aspect)) {
            val w = maxWidth
            when (layout) {
                PairLayout.SIDE -> {
                    val mainW = w * 0.66f
                    val coverW = mainW * FoldProportions.coverToMainWidth * 1.04f
                    FoldedDevice(cover, Modifier.align(Alignment.CenterStart).padding(start = w * 0.02f).width(coverW), glow, 12.dp)
                    UnfoldedDevice(main, Modifier.align(Alignment.CenterEnd).padding(end = w * 0.01f).width(mainW), glow, 16.dp)
                }
                PairLayout.OVERLAP -> {
                    UnfoldedDevice(main, Modifier.align(Alignment.TopEnd).padding(top = w * 0.03f, end = w * 0.02f).width(w * 0.84f), glow, 16.dp)
                    FoldedDevice(
                        cover,
                        Modifier.align(Alignment.BottomStart).padding(start = w * 0.04f, bottom = w * 0.02f).width(w * 0.34f)
                            .graphicsLayer { rotationZ = -5f },
                        glow, 20.dp,
                    )
                }
                PairLayout.TALL -> {
                    UnfoldedDevice(
                        main,
                        Modifier.align(Alignment.TopEnd).padding(top = w * 0.04f).width(w * 0.78f)
                            .graphicsLayer { alpha = 0.92f },
                        glow, 10.dp,
                    )
                    FoldedDevice(cover, Modifier.align(Alignment.BottomStart).padding(start = w * 0.08f, bottom = w * 0.03f).width(w * 0.5f), glow, 22.dp)
                }
            }
        }
    }
}
