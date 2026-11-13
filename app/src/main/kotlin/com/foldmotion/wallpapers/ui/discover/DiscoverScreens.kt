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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.VideoJob
import com.foldmotion.wallpapers.ui.components.DeviceImage
import com.foldmotion.wallpapers.ui.components.FoldDemo
import com.foldmotion.wallpapers.ui.components.FoldPair
import com.foldmotion.wallpapers.ui.components.FoldedDevice
import com.foldmotion.wallpapers.ui.components.GlassIconButton
import com.foldmotion.wallpapers.ui.components.GradientButton
import com.foldmotion.wallpapers.ui.components.PairLayout
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
import kotlinx.coroutines.withContext

/** Rhythm of the wall: card shapes cycle so neighbouring columns never line up. */
private val rhythm = listOf(PairLayout.OVERLAP, PairLayout.SIDE, PairLayout.TALL, PairLayout.SIDE, PairLayout.OVERLAP, PairLayout.TALL)

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
            .background(if (selected) Color.White else Color(0x14FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color(0xFF0A0B0F) else FmColors.Text, maxLines = 1)
    }
}

/** A card: the wallpaper on both phones over its own light, title underneath. */
@Composable
private fun DiscoverCard(item: DiscoverItem, layout: PairLayout, language: String, onClick: () -> Unit) {
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
        Box(
            Modifier.fillMaxWidth()
                .sharedArt("art-${item.id}")
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.radialGradient(
                        listOf(Color(item.glow).copy(alpha = 0.32f), Color(item.background).copy(alpha = 0.9f), Color(0xFF0B0C10)),
                    ),
                )
                .padding(10.dp),
        ) {
            FoldPair(cover, main, layout, glow = Color(item.glow))
        }
        Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (item.isVideo) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.badge_video), tint = FmColors.TextMuted, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            if (item.isVideo) stringResource(R.string.badge_video) else familyLabel(item.family),
            style = MaterialTheme.typography.labelMedium, color = FmColors.TextMuted, modifier = Modifier.padding(start = 6.dp, top = 2.dp),
        )
    }
}

@Composable
private fun CreateCard(onCreate: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().pressScale(interaction, 0.97f).clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF14323A), Color(0xFF1A1440))))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(26.dp))
            .clickable(interaction, indication = null, onClick = onCreate)
            .padding(18.dp)
            .testTag("discover_create"),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(FmColors.primaryGradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color(0xFF04131A))
        }
        Spacer(Modifier.height(36.dp))
        Text(stringResource(R.string.discover_create_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.discover_create_body), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
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

    // Live scenes for the 3D demo, rebuilt when the style changes.
    var scenes by remember { mutableStateOf<Pair<LoadedScene, LoadedScene>?>(null) }
    LaunchedEffect(item.id, style) {
        val built = withContext(Dispatchers.Default) { DiscoverCatalog.scenes(context, item, style) }
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
        if (!item.isVideo && j != null && j.status == JobStatus.READY && j.packId != null) {
            waiting = false
            vm.dismissJob()
            onPackReady(j.packId)
        }
        val v = videoJob
        if (item.isVideo && v is VideoJob.Done) {
            waiting = false
            vm.dismissVideoJob()
            onPackReady(v.packId)
        }
        if ((j != null && j.status == JobStatus.FAILED) || v is VideoJob.Failed) waiting = false
    }
    val busy = waiting && ((job?.isRunning == true) || videoJob is VideoJob.Running)

    Box(Modifier.fillMaxSize()) {
        // The wallpaper's own light behind everything, blurred, fading into the background.
        main?.let {
            Image(
                it, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(460.dp).blur(48.dp),
                alpha = 0.55f,
            )
        }
        Box(Modifier.fillMaxWidth().height(462.dp).background(Brush.verticalGradient(listOf(Color(0x330A0B0F), Color(0xFF0A0B0F)))))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = NavClearance)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp).sharedArt("art-${item.id}"), contentAlignment = Alignment.Center) {
                val height = if (maxWidth >= 600.dp) 460.dp else 400.dp
                val s = scenes
                if (s != null) {
                    FoldDemo(s.first, s.second, DeviceProfileRegistry.fold7, height, Modifier.fillMaxWidth(), animate = !reduced)
                } else {
                    Box(Modifier.height(height))
                }
            }
            Column(Modifier.fillMaxWidth().widthIn(max = 760.dp).align(Alignment.CenterHorizontally).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))
                Text(item.title(language, stringResource(R.string.discover_sample_title)), style = MaterialTheme.typography.headlineMedium)
                Text(
                    item.description(language) ?: stringResource(R.string.discover_sample_body),
                    style = MaterialTheme.typography.bodyMedium, color = FmColors.TextMuted, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(16.dp))
                GradientButton(
                    stringResource(if (busy) R.string.discover_preparing else R.string.discover_get),
                    onClick = {
                        waiting = true
                        if (item.isVideo) vm.createSampleVideoWallpaper(style) else vm.getDiscoverIdea(item, style)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("discover_get"),
                    enabled = !busy,
                    height = 58.dp,
                )

                SectionTitle(stringResource(R.string.discover_both))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(0.34f), horizontalAlignment = Alignment.CenterHorizontally) {
                            FoldedDevice(cover, Modifier.fillMaxWidth(), Color(item.glow))
                            Text(stringResource(R.string.discover_closed), style = MaterialTheme.typography.labelMedium, color = FmColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
                        }
                        Column(Modifier.weight(0.66f), horizontalAlignment = Alignment.CenterHorizontally) {
                            UnfoldedDevice(main, Modifier.fillMaxWidth(), Color(item.glow))
                            Text(stringResource(R.string.discover_open), style = MaterialTheme.typography.labelMedium, color = FmColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }

                SectionTitle(stringResource(R.string.style_title))
                Text(stringResource(R.string.discover_style_hint, foldStyleLabel(if (style == FoldStyle.AUTO) item.autoStyle else style)), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(bottom = 12.dp))
                StylePicker(FoldStyle.entries, style, { style = it }, animate = !reduced, enabled = !busy)
            }
        }
    }
}
