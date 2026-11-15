package com.foldmotion.wallpapers.ui.screens

import androidx.compose.ui.draw.blur

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.pack.PackChoreography
import com.foldmotion.core.scene.JobStatus
import com.foldmotion.core.scene.PipelineStage
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.apply.ApplyOutcome
import com.foldmotion.wallpapers.apply.ApplyStart
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.components.FoldDemo
import com.foldmotion.wallpapers.ui.components.GlassButton
import com.foldmotion.wallpapers.ui.components.GlassCard
import com.foldmotion.wallpapers.ui.components.GlassIconButton
import com.foldmotion.wallpapers.ui.components.GradientButton
import com.foldmotion.wallpapers.ui.components.PillToggle
import com.foldmotion.wallpapers.ui.components.ReadableColumn
import com.foldmotion.wallpapers.ui.components.SceneFrame
import com.foldmotion.wallpapers.ui.components.SectionTitle
import com.foldmotion.wallpapers.ui.components.StylePicker
import com.foldmotion.wallpapers.ui.components.foldStyleLabel
import com.foldmotion.wallpapers.ui.components.SideBySide
import com.foldmotion.wallpapers.ui.components.errorLabel
import com.foldmotion.wallpapers.ui.components.intensityLabel
import com.foldmotion.wallpapers.ui.components.profileName
import com.foldmotion.wallpapers.ui.components.rememberPackScenes
import com.foldmotion.wallpapers.ui.components.staggerIn
import com.foldmotion.wallpapers.ui.theme.FmColors
import kotlin.math.cos
import kotlin.math.sin

private enum class PreviewMode { DEMO, COVER, MAIN, BOTH }

@Composable
fun PreviewScreen(vm: MainViewModel, packId: String, onBack: () -> Unit) {
    val packs by vm.packs.collectAsStateWithLifecycle()
    val active by vm.activePackId.collectAsStateWithLifecycle()
    val reduced by vm.reducedMotion.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val pack = packs.firstOrNull { it.id == packId } ?: vm.pack(packId)
    val context = LocalContext.current
    val language = vm.language()

    if (pack == null) {
        ReadableColumn(Modifier.padding(top = 32.dp)) {
            Text(stringResource(R.string.apply_missing))
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        return
    }
    val profile = DeviceProfileRegistry.byId(pack.manifest.profileIds.firstOrNull()) ?: device.active
    val longSide = with(LocalDensity.current) { 900.dp.roundToPx() }
    var mode by remember { mutableStateOf(PreviewMode.DEMO) }
    var playCover by remember { mutableIntStateOf(0) }
    var playMain by remember { mutableIntStateOf(0) }
    val (cover, main) = rememberPackScenes(pack.dir, longSide, pack.manifest.packageVersion, playVideo = !reduced)
    val restyling by vm.restyling.collectAsStateWithLifecycle()
    val style = remember(pack.id, pack.manifest.packageVersion) { PackChoreography.styleOf(pack.manifest) }
    // Replay as soon as a restyled pack has loaded (the 3D demo restarts by itself).
    var loadedOnce by remember(pack.id) { mutableStateOf(false) }
    LaunchedEffect(cover, main) {
        if (cover == null || main == null) return@LaunchedEffect
        if (loadedOnce && !reduced) {
            if (mode == PreviewMode.COVER) playCover++ else playMain++
        }
        loadedOnce = true
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val report = remember(pack.id, pack.manifest.packageVersion) { vm.validate(pack.id) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        when (vm.onPickerReturned()) {
            ApplyOutcome.APPLIED -> vm.emit(R.string.apply_done)
            ApplyOutcome.NOT_APPLIED -> vm.emit(R.string.apply_not_done)
        }
    }

    fun apply() {
        when (val start = vm.beginApply(pack.id)) {
            ApplyStart.AppliedInPlace -> vm.emit(R.string.apply_in_place)
            is ApplyStart.NeedsSystemPicker -> {
                vm.emit(R.string.apply_lock_note)
                try {
                    picker.launch(start.intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    vm.emit(R.string.apply_unsupported)
                }
            }
            ApplyStart.PackMissing -> vm.emit(R.string.apply_missing)
            ApplyStart.WallpaperUnsupported -> vm.emit(R.string.apply_unsupported)
        }
    }

    val version = pack.manifest.packageVersion
    val coverImage = com.foldmotion.wallpapers.ui.components.rememberDeviceImage(
        com.foldmotion.wallpapers.ui.components.DeviceImage.File(java.io.File(pack.dir, pack.manifest.previews.cover), version), 720,
    )
    val mainImage = com.foldmotion.wallpapers.ui.components.rememberDeviceImage(
        com.foldmotion.wallpapers.ui.components.DeviceImage.File(java.io.File(pack.dir, pack.manifest.previews.main), version), 1080,
    )
    val glowColor = remember(pack.manifest.colors.primary) {
        Color(com.foldmotion.wallpapers.render.LoadedScene.parseColor(pack.manifest.colors.primary, android.graphics.Color.WHITE))
    }
    val hero: @Composable (Modifier, androidx.compose.ui.unit.Dp) -> Unit = { modifier, height ->
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            PillToggle(
                listOf(stringResource(R.string.preview_mode_demo), stringResource(R.string.preview_cover), stringResource(R.string.preview_main), stringResource(R.string.preview_both)),
                selected = mode.ordinal,
                onSelect = { mode = PreviewMode.entries[it] },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(14.dp))
            val frameHeight = (height - 140.dp).coerceAtLeast(300.dp)
            AnimatedContent(mode, transitionSpec = { (fadeIn(tween(280)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(160)) }, label = "mode") { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (m) {
                        PreviewMode.DEMO -> FoldDemo(cover, main, profile, frameHeight, Modifier.fillMaxWidth(), animate = !reduced)
                        PreviewMode.COVER -> SceneFrame(cover, ScreenKind.COVER, profile, Modifier.height(frameHeight), playCover, living = !reduced)
                        PreviewMode.MAIN -> SceneFrame(main, ScreenKind.MAIN, profile, Modifier.height(frameHeight).widthIn(max = 540.dp), playMain, living = !reduced)
                        PreviewMode.BOTH -> SideBySide(cover, main, profile, frameHeight, Modifier.widthIn(max = 740.dp), playCover, playMain, living = !reduced)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (mode != PreviewMode.DEMO) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(stringResource(R.string.action_play_open), {
                        if (mode == PreviewMode.COVER) mode = PreviewMode.MAIN
                        if (!reduced) playMain++
                    }, Modifier.weight(1f), leading = Icons.Filled.PlayArrow, height = 46.dp)
                    GlassButton(stringResource(R.string.action_play_close), {
                        if (mode == PreviewMode.MAIN) mode = PreviewMode.COVER
                        if (!reduced) playCover++
                    }, Modifier.weight(1f), leading = Icons.Filled.PlayArrow, height = 46.dp)
                }
            }
        }
    }
    val info: @Composable () -> Unit = {
        Text(pack.manifest.title.forLanguage(language), style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(stringResource(R.string.preview_simulation_note), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(18.dp))
        GradientButton(stringResource(R.string.action_apply), ::apply, Modifier.fillMaxWidth(), leading = Icons.Filled.Check, height = 56.dp)

        SectionTitle(stringResource(R.string.discover_both))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(0.34f), horizontalAlignment = Alignment.CenterHorizontally) {
                com.foldmotion.wallpapers.ui.components.FoldedDevice(coverImage, Modifier.fillMaxWidth(), glowColor, elevation = 10.dp)
                Text(stringResource(R.string.discover_closed), style = MaterialTheme.typography.labelMedium, color = FmColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
            }
            Column(Modifier.weight(0.66f), horizontalAlignment = Alignment.CenterHorizontally) {
                com.foldmotion.wallpapers.ui.components.UnfoldedDevice(mainImage, Modifier.fillMaxWidth(), glowColor, elevation = 12.dp)
                Text(stringResource(R.string.discover_open), style = MaterialTheme.typography.labelMedium, color = FmColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
            }
        }

        // Only your own photos take a chosen motion; every other wallpaper moves by itself.
        if (pack.manifest.creationSource == com.foldmotion.core.pack.FoldPackManifest.SOURCE_PHOTO) {
            SectionTitle(stringResource(R.string.style_title))
            Text(
                stringResource(if (restyling == pack.id) R.string.style_working else R.string.style_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (restyling == pack.id) FmColors.Aurora else FmColors.TextMuted,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            StylePicker(
                styles = FoldStyle.CONCRETE,
                selected = style,
                onSelect = { if (it != style) vm.restyle(pack.id, it) },
                animate = !reduced,
                enabled = restyling == null,
            )
        } else {
            SectionTitle(stringResource(R.string.discover_motion_title))
            Text(
                stringResource(
                    when {
                        pack.manifest.liquid?.isGlass == true -> R.string.discover_glass_body
                        pack.manifest.video != null -> R.string.add_video_motion
                        else -> R.string.discover_motion_drawn
                    },
                ),
                style = MaterialTheme.typography.bodyMedium, color = FmColors.TextMuted,
            )
        }

        SectionTitle(stringResource(R.string.preview_details))
        GlassCard(Modifier.fillMaxWidth().testTag("preview_details")) {
            DetailRow(stringResource(R.string.preview_check), if (report.passed) stringResource(R.string.preview_check_passed) else stringResource(R.string.preview_check_failed, report.codes.joinToString()))
            DetailRow(stringResource(R.string.preview_profile), pack.manifest.profileIds.map { id -> DeviceProfileRegistry.byId(id)?.let { profileName(it) } ?: id }.joinToString())
            DetailRow(
                stringResource(R.string.preview_motion),
                stringResource(R.string.preview_motion_value, intensityLabel(pack.manifest.motionIntensity), main?.entryMotion?.durationMs ?: pack.manifest.transitions.open.durationMs, cover?.entryMotion?.durationMs ?: pack.manifest.transitions.close.durationMs),
            )
            DetailRow(stringResource(R.string.style_title), foldStyleLabel(style))
            pack.manifest.video?.let { v ->
                DetailRow(stringResource(R.string.preview_scene), stringResource(R.string.preview_scene_video, v.width, v.height, v.durationMs / 1000f))
            }
            DetailRow(stringResource(R.string.preview_size), stringResource(R.string.preview_size_value, pack.sizeBytes / 1_048_576f))
            DetailRow(stringResource(R.string.preview_source), stringResource(if (pack.imported) R.string.source_imported else R.string.source_created), last = true)
        }
    }

    com.foldmotion.wallpapers.ui.discover.DetailLayout(
        backdrop = mainImage,
        onBack = onBack,
        hero = hero,
        info = info,
        narrowHeroHeight = 590.dp,
        wideHeroMax = 660.dp,
        actions = {
            GlassIconButton(Icons.Filled.Share, stringResource(R.string.action_share), {
                vm.shareIntent(pack.id)?.let { context.startActivity(android.content.Intent.createChooser(it, context.getString(R.string.share_chooser))) }
            })
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.Filled.Delete, stringResource(R.string.action_delete), { confirmDelete = true })
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = FmColors.NightHigh,
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(if (pack.id == active) R.string.delete_active else R.string.delete_confirm_body)) },
            confirmButton = {
                if (pack.id != active) {
                    TextButton(onClick = {
                        confirmDelete = false
                        if (vm.delete(pack.id)) onBack()
                    }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String, last: Boolean = false) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(label, color = FmColors.TextMuted, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium)
            Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
        }
        if (!last) HorizontalDivider(color = FmColors.GlassStrong)
    }
}
