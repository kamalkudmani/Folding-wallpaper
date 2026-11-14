package com.foldmotion.wallpapers.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.scene.JobStatus
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.VideoFrames
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.VideoJob
import com.foldmotion.wallpapers.ui.components.DeviceImage
import com.foldmotion.wallpapers.ui.components.FoldDemo
import com.foldmotion.wallpapers.ui.components.FoldPair
import com.foldmotion.wallpapers.ui.components.FoldedDevice
import com.foldmotion.wallpapers.ui.components.GlassIconButton
import com.foldmotion.wallpapers.ui.components.GradientButton
import com.foldmotion.wallpapers.ui.components.CardShape
import com.foldmotion.wallpapers.ui.components.FoldCardArt
import com.foldmotion.wallpapers.ui.components.SectionTitle
import com.foldmotion.wallpapers.ui.components.StylePicker
import com.foldmotion.wallpapers.ui.components.UnfoldedDevice
import com.foldmotion.wallpapers.ui.components.familyLabel
import com.foldmotion.wallpapers.ui.components.foldStyleLabel
import com.foldmotion.wallpapers.ui.components.pressScale
import com.foldmotion.wallpapers.ui.components.rememberDeviceImage
import com.foldmotion.wallpapers.ui.components.sharedArt
import com.foldmotion.wallpapers.ui.screens.NavClearance
import com.foldmotion.wallpapers.ui.theme.FmColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Rhythm of the wall: two card heights alternate so neighbouring columns stagger gently. */
private val rhythm = listOf(CardShape.CLOSED, CardShape.OPEN, CardShape.OPEN, CardShape.CLOSED)

/**
 * The home screen: a Pinterest-style wall of wallpapers, each shown on the closed and the open
 * phone. Tapping a card grows it into its detail.
 */
@Composable
fun DiscoverScreen(vm: MainViewModel, onOpen: (String) -> Unit, onCreate: () -> Unit) {
    val context = LocalContext.current
    val language = vm.language()
    val items = remember { DiscoverCatalog.load(context) }
    var category by rememberSaveable { mutableStateOf(DiscoverCategory.ALL) }
    val shown = remember(category, items) { items.filter { category.accepts(it) } }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(168.dp),
        modifier = Modifier.fillMaxSize().testTag("discover_grid"),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = NavClearance + 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 18.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text(stringResource(R.string.discover_title), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.discover_subtitle), style = MaterialTheme.typography.bodyMedium, color = FmColors.TextMuted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        item(span = StaggeredGridItemSpan.FullLine) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                items(DiscoverCategory.entries) { c -> CategoryChip(stringResource(c.label), c == category) { category = c } }
            }
        }
        if (category == DiscoverCategory.ALL) {
            item(key = "create") { CreateCard(onCreate) }
        }
        itemsIndexed(shown, key = { _, it -> it.id }) { index, item ->
            DiscoverCard(item, rhythm[index % rhythm.size], language) { onOpen(item.id) }
        }
    }
}

@Composable
private fun CategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .background(if (selected) Color.White else FmColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color(0xFF0A0B0F) else FmColors.Text, maxLines = 1)
    }
}

/** A card: the wallpaper edge to edge with its other state inset, title underneath. */
@Composable
private fun DiscoverCard(item: DiscoverItem, shape: CardShape, language: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val cover = rememberDeviceImage(DeviceImage.Asset(item.coverAsset), 360)
    val main = rememberDeviceImage(DeviceImage.Asset(item.mainAsset), 520)
    val title = item.title(language, stringResource(R.string.discover_sample_title))
    Column(
        Modifier.pressScale(interaction, 0.97f)
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = title }
            .testTag("discover_${item.id}"),
    ) {
        Box(Modifier.fillMaxWidth().sharedArt("art-${item.id}")) {
            FoldCardArt(cover, main, shape)
            if (item.isLive) {
                // Live liquid: a small "Live" capsule with a glowing dot.
                Row(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(item.glow)))
                    Text(stringResource(R.string.badge_live), style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(start = 5.dp))
                }
            } else if (item.isVideo) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).size(28.dp).clip(CircleShape).background(Color(0x99000000)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.badge_video), tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp))
        Text(
            when {
                item.isLiquid -> stringResource(R.string.discover_liquid)
                item.isVideo -> stringResource(R.string.badge_video)
                else -> familyLabel(item.family)
            },
            style = MaterialTheme.typography.labelMedium, color = FmColors.TextMuted, modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

@Composable
private fun CreateCard(onCreate: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        // At least as tall as a short card, taller when the text needs it (never clipped).
        Modifier.fillMaxWidth().heightIn(min = 200.dp).pressScale(interaction, 0.97f).clip(RoundedCornerShape(22.dp))
            .background(FmColors.Surface2)
            .border(1.dp, FmColors.Hairline, RoundedCornerShape(22.dp))
            .clickable(interaction, indication = null, onClick = onCreate)
            .padding(18.dp)
            .testTag("discover_create"),
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.Bottom),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(FmColors.primaryGradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color(0xFF04131A))
        }
        Column {
            Text(stringResource(R.string.discover_create_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.discover_create_body), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ---------------------------------------------------------------------- detail

/**
 * A Discover wallpaper up close: the phone folding and unfolding with it, in the fold style
 * you choose (previewed live before anything is made), both phones side by side, and one button
 * that makes it yours.
 */
@Composable
fun DiscoverDetailScreen(vm: MainViewModel, itemId: String, onBack: () -> Unit, onPackReady: (String) -> Unit) {
    val context = LocalContext.current
    val language = vm.language()
    val item = remember(itemId) { DiscoverCatalog.byId(context, itemId) } ?: run {
        onBack()
        return
    }
    val reduced by vm.reducedMotion.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val videoJob by vm.videoJob.collectAsStateWithLifecycle()
    var style by rememberSaveable(itemId) { mutableStateOf(FoldStyle.AUTO) }
    var waiting by rememberSaveable(itemId) { mutableStateOf(false) }
    val cover = rememberDeviceImage(DeviceImage.Asset(item.coverAsset), 720)
    val main = rememberDeviceImage(DeviceImage.Asset(item.mainAsset), 1080)

    // A video wallpaper plays its real clip on both screens of the demo, as on the phone.
    var video by remember(item.id) { mutableStateOf<VideoFrames?>(null) }
    var videoChecked by remember(item.id) { mutableStateOf(!item.isVideo) }
    LaunchedEffect(item.id) {
        if (item.isVideo) {
            // Always finish making the source, then keep it or release it: leaving the page
            // while it is being made must not leak a player.
            val made = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { DiscoverCatalog.videoSource(context, item) }
            if (!isActive) {
                made?.release()
                return@LaunchedEffect
            }
            video = made
            videoChecked = true
        }
    }
    DisposableEffect(item.id) { onDispose { video?.release() } }
    LaunchedEffect(video, reduced) { video?.setPlaying(!reduced) }

    // Live scenes for the 3D demo, rebuilt when the style changes.
    var scenes by remember { mutableStateOf<Pair<LoadedScene, LoadedScene>?>(null) }
    LaunchedEffect(item.id, style, videoChecked) {
        if (!videoChecked) return@LaunchedEffect
        val built = withContext(Dispatchers.Default) { DiscoverCatalog.scenes(context, item, style) }
        built?.first?.videoFrames = video
        built?.second?.videoFrames = video
        val old = scenes
        scenes = built
        kotlinx.coroutines.delay(600)
        old?.first?.recycle()
        old?.second?.recycle()
    }

    // When the wallpaper is ready, open it as a pack of your own.
    LaunchedEffect(job, videoJob, waiting) {
        if (!waiting) return@LaunchedEffect
        val j = job
        if (!item.isMoving && j != null && j.status == JobStatus.READY && j.packId != null) {
            waiting = false
            vm.dismissJob()
            onPackReady(j.packId)
        }
        val v = videoJob
        if (item.isMoving && v is VideoJob.Done) {
            waiting = false
            vm.dismissVideoJob()
            onPackReady(v.packId)
        }
        if ((j != null && j.status == JobStatus.FAILED) || v is VideoJob.Failed) waiting = false
    }
    val busy = waiting && ((job?.isRunning == true) || videoJob is VideoJob.Running)

    val hero: @Composable (Modifier, androidx.compose.ui.unit.Dp) -> Unit = { modifier, height ->
        Box(modifier.sharedArt("art-${item.id}"), contentAlignment = Alignment.Center) {
            val sc = scenes
            if (sc != null) FoldDemo(sc.first, sc.second, DeviceProfileRegistry.fold7, height, Modifier.fillMaxWidth(), animate = !reduced)
            else Box(Modifier.height(height))
        }
    }
    val info: @Composable () -> Unit = {
        Text(item.title(language, stringResource(R.string.discover_sample_title)), style = MaterialTheme.typography.headlineMedium)
        Text(
            item.description(language) ?: stringResource(if (item.isLiquid) R.string.discover_liquid_body else R.string.discover_sample_body),
            style = MaterialTheme.typography.bodyMedium, color = FmColors.TextMuted, modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        GradientButton(
            stringResource(if (busy) R.string.discover_preparing else R.string.discover_get),
            onClick = {
                waiting = true
                val liquid = item.entry.liquid
                // The page previews AUTO as the item's own style: make exactly that.
                val chosen = if (style == FoldStyle.AUTO) item.autoStyle else style
                when {
                    liquid != null -> vm.createLiquidWallpaper(
                        liquid, chosen,
                        com.foldmotion.core.pack.LocalizedText(item.entry.titleAr ?: "سائل حيّ", item.entry.titleEn ?: "Live liquid"),
                    )
                    item.isVideo -> vm.createSampleVideoWallpaper(chosen, item)
                    else -> vm.getDiscoverIdea(item, style)
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("discover_get"),
            enabled = !busy,
            height = 56.dp,
        )
        SectionTitle(stringResource(R.string.discover_both))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(0.34f), horizontalAlignment = Alignment.CenterHorizontally) {
                FoldedDevice(cover, Modifier.fillMaxWidth(), Color(item.glow), elevation = 10.dp)
                Text(stringResource(R.string.discover_closed), style = MaterialTheme.typography.labelMedium, color = FmColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
            }
            Column(Modifier.weight(0.66f), horizontalAlignment = Alignment.CenterHorizontally) {
                UnfoldedDevice(main, Modifier.fillMaxWidth(), Color(item.glow), elevation = 12.dp)
                Text(stringResource(R.string.discover_open), style = MaterialTheme.typography.labelMedium, color = FmColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
            }
        }
        SectionTitle(stringResource(R.string.style_title))
        Text(stringResource(R.string.discover_style_hint, foldStyleLabel(if (style == FoldStyle.AUTO) item.autoStyle else style)), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(bottom = 12.dp))
        StylePicker(FoldStyle.entries, style, { style = it }, animate = !reduced, enabled = !busy)
    }

    DetailLayout(backdrop = main, onBack = onBack, hero = hero, info = info)
}

/**
 * Layout of a wallpaper page. Narrow (cover): back button, the picture, then the details below.
 * Wide (open phone): the picture stays on the left while the details scroll on the right, both
 * starting on the same line under the back button.
 */
@Composable
fun DetailLayout(
    backdrop: androidx.compose.ui.graphics.ImageBitmap?,
    onBack: () -> Unit,
    hero: @Composable (Modifier, androidx.compose.ui.unit.Dp) -> Unit,
    info: @Composable () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    narrowHeroHeight: androidx.compose.ui.unit.Dp = 400.dp,
    wideHeroMax: androidx.compose.ui.unit.Dp = 520.dp,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 680.dp
        // The wallpaper's own light behind the page, blurred, fading into the background.
        backdrop?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(440.dp).blur(56.dp), alpha = 0.5f)
        }
        Box(Modifier.fillMaxWidth().height(442.dp).background(Brush.verticalGradient(listOf(Color(0x330A0B0F), FmColors.Night))))
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
                Spacer(Modifier.weight(1f))
                actions()
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BoxWithConstraints(Modifier.weight(1.05f).fillMaxHeight().padding(bottom = NavClearance)) {
                        hero(Modifier.fillMaxWidth().align(Alignment.TopCenter), minOf(maxHeight, wideHeroMax))
                    }
                    Column(Modifier.weight(0.95f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 12.dp, bottom = NavClearance)) {
                        info()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = NavClearance)) {
                    hero(Modifier.fillMaxWidth().padding(horizontal = 16.dp), narrowHeroHeight)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) { info() }
                }
            }
        }
    }
}
