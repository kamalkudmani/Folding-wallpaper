package com.foldmotion.wallpapers.ui.screens

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
import com.foldmotion.wallpapers.ui.components.stageLabel
import com.foldmotion.wallpapers.ui.components.staggerIn
import com.foldmotion.wallpapers.ui.theme.FmColors
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GeneratingScreen(vm: MainViewModel, onReady: (String) -> Unit, onEdit: () -> Unit, onClose: () -> Unit) {
    val job by vm.job.collectAsStateWithLifecycle()
    val j = job
    val doneCount = when {
        j == null -> 0
        j.status == JobStatus.READY -> PipelineStage.entries.size
        else -> j.completed.distinct().size
    }
    val fraction by animateFloatAsState(doneCount / PipelineStage.entries.size.toFloat(), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), label = "progress")

    ReadableColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 18.dp, bottom = NavClearance)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gen_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            GlassIconButton(Icons.Filled.Close, stringResource(R.string.action_close), onClose)
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
            FormingOrb(fraction, running = j?.isRunning == true, ready = j?.status == JobStatus.READY)
        }
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            // Each line is a real pipeline stage, ticked when it finishes. No invented percentages.
            for ((i, stage) in PipelineStage.entries.withIndex()) {
                val done = j != null && (stage in j.completed || j.status == JobStatus.READY)
                val current = j?.stage == stage && j.isRunning
                val stateText = stringResource(
                    when {
                        done -> R.string.a11y_stage_done
                        current -> R.string.a11y_stage_running
                        else -> R.string.a11y_stage_waiting
                    },
                )
                Row(
                    Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) { stateDescription = stateText },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StageDot(done, current)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        stringResource(stageLabel(stage)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (done || current) FmColors.Text else FmColors.TextMuted.copy(alpha = 0.55f),
                        modifier = Modifier.staggerIn(i),
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        AnimatedContent(j?.status, transitionSpec = { (fadeIn(tween(260)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(150)) }, label = "outcome") { status ->
            Column {
                when {
                    j == null -> Unit
                    status != null && j.isRunning -> {
                        Text(stringResource(R.string.gen_background_note), style = MaterialTheme.typography.bodyMedium, color = FmColors.TextMuted)
                        Spacer(Modifier.height(14.dp))
                        GlassButton(stringResource(R.string.action_cancel), vm::cancelGeneration, Modifier.fillMaxWidth(), enabled = j.status != JobStatus.CANCEL_REQUESTED)
                    }
                    status == JobStatus.READY && j.packId != null -> {
                        Text(stringResource(R.string.gen_ready, j.durationMs / 1000f), color = FmColors.Aurora, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(14.dp))
                        GradientButton(stringResource(R.string.action_view_pack), { onReady(j.packId) }, Modifier.fillMaxWidth(), leading = Icons.Filled.PlayArrow)
                    }
                    status == JobStatus.CANCELLED -> {
                        Text(stringResource(R.string.gen_cancelled))
                        Spacer(Modifier.height(14.dp))
                        GlassButton(stringResource(R.string.action_edit_prompt), onEdit, Modifier.fillMaxWidth())
                    }
                    else -> {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.gen_failed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(errorLabel(j.error), j.validationCodes.joinToString()))
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GradientButton(stringResource(R.string.action_retry), { vm.generate() }, Modifier.weight(1f), leading = Icons.Filled.Refresh)
                            GlassButton(stringResource(R.string.action_edit_prompt), onEdit, Modifier.weight(1f), height = 56.dp)
                        }
                    }
                }
            }
        }
    }
}

/** Orbiting light that fills its ring as real stages complete, and blooms when the pack is ready. */
@Composable
private fun FormingOrb(fraction: Float, running: Boolean, ready: Boolean) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(2_600, easing = LinearEasing)), label = "spin")
    val breath by t.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "breath")
    val bloom by animateFloatAsState(if (ready) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow), label = "bloom")
    Canvas(Modifier.size(220.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.36f
        drawCircle(Brush.radialGradient(listOf(FmColors.Aurora.copy(alpha = 0.35f + 0.3f * bloom), FmColors.Violet.copy(alpha = 0.12f), Color.Transparent), center = c, radius = r * (1.6f + 0.6f * bloom)), r * (1.6f + 0.6f * bloom), c)
        drawCircle(Color.White.copy(alpha = 0.08f), r, c, style = Stroke(width = 6.dp.toPx()))
        drawArc(
            Brush.sweepGradient(listOf(FmColors.Aurora, FmColors.Violet, FmColors.Gold, FmColors.Aurora), center = c),
            startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
            topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = Stroke(width = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        val coreR = r * 0.32f * (if (running) breath else 1f) * (1f + 0.4f * bloom)
        drawCircle(Brush.radialGradient(listOf(Color.White, FmColors.Aurora, FmColors.Aurora.copy(alpha = 0f)), center = c, radius = coreR * 2.2f), coreR * 2.2f, c)
        if (running || ready) {
            for (i in 0 until 14) {
                val a = spin * (if (i % 2 == 0) 1f else -0.7f) + i * 0.449f
                val rr = r * (0.55f + 0.5f * ((i * 37) % 10) / 10f)
                val p = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Transparent), center = p, radius = 7.dp.toPx()), 7.dp.toPx(), p)
            }
        }
    }
}

@Composable
private fun StageDot(done: Boolean, current: Boolean) {
    val t = rememberInfiniteTransition(label = "dot")
    val pulse by t.animateFloat(0.7f, 1.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val scale by animateFloatAsState(if (done) 1f else 0.9f, spring(dampingRatio = 0.45f), label = "done")
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        when {
            done -> Box(
                Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(FmColors.primaryGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF04131A), modifier = Modifier.size(16.dp)) }
            current -> Box(Modifier.size(14.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.clip(CircleShape).background(FmColors.Aurora))
            else -> Box(Modifier.size(10.dp).clip(CircleShape).background(FmColors.GlassStrong))
        }
    }
}

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
    val (cover, main) = rememberPackScenes(pack.dir, longSide, pack.manifest.packageVersion)
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ReadableColumn(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.staggerIn(0)) {
                    GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
                    Spacer(Modifier.width(12.dp))
                    Text(pack.manifest.title.forLanguage(language), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    GlassIconButton(Icons.Filled.Share, stringResource(R.string.action_share), {
                        vm.shareIntent(pack.id)?.let { context.startActivity(android.content.Intent.createChooser(it, context.getString(R.string.share_chooser))) }
                    })
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(Icons.Filled.Delete, stringResource(R.string.action_delete), { confirmDelete = true })
                }
                Spacer(Modifier.height(12.dp))
                PillToggle(
                    listOf(stringResource(R.string.preview_mode_demo), stringResource(R.string.preview_cover), stringResource(R.string.preview_main), stringResource(R.string.preview_both)),
                    selected = mode.ordinal,
                    onSelect = { mode = PreviewMode.entries[it] },
                    modifier = Modifier.staggerIn(1),
                )
                Spacer(Modifier.height(16.dp))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).staggerIn(2), contentAlignment = Alignment.Center) {
                val frameHeight = if (wide) 470.dp else 430.dp
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
            }
            ReadableColumn(Modifier.padding(bottom = NavClearance)) {
                Spacer(Modifier.height(16.dp))
                if (mode != PreviewMode.DEMO) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(stringResource(R.string.action_play_open), {
                            if (mode == PreviewMode.COVER) mode = PreviewMode.MAIN
                            if (!reduced) playMain++
                        }, Modifier.weight(1f), leading = Icons.Filled.PlayArrow)
                        GlassButton(stringResource(R.string.action_play_close), {
                            if (mode == PreviewMode.MAIN) mode = PreviewMode.COVER
                            if (!reduced) playCover++
                        }, Modifier.weight(1f), leading = Icons.Filled.PlayArrow)
                    }
                }
                Text(stringResource(R.string.preview_simulation_note), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(16.dp))
                GradientButton(stringResource(R.string.action_apply), ::apply, Modifier.fillMaxWidth(), leading = Icons.Filled.Check, height = 60.dp)

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

                SectionTitle(stringResource(R.string.preview_details))
                GlassCard(Modifier.fillMaxWidth().testTag("preview_details")) {
                    DetailRow(stringResource(R.string.preview_check), if (report.passed) stringResource(R.string.preview_check_passed) else stringResource(R.string.preview_check_failed, report.codes.joinToString()))
                    DetailRow(stringResource(R.string.preview_profile), pack.manifest.profileIds.map { id -> DeviceProfileRegistry.byId(id)?.let { profileName(it) } ?: id }.joinToString())
                    DetailRow(
                        stringResource(R.string.preview_motion),
                        stringResource(R.string.preview_motion_value, intensityLabel(pack.manifest.motionIntensity), main?.entryMotion?.durationMs ?: pack.manifest.transitions.open.durationMs, cover?.entryMotion?.durationMs ?: pack.manifest.transitions.close.durationMs),
                    )
                    DetailRow(stringResource(R.string.style_title), foldStyleLabel(style))
                    DetailRow(stringResource(R.string.preview_size), stringResource(R.string.preview_size_value, pack.sizeBytes / 1_048_576f))
                    DetailRow(stringResource(R.string.preview_source), stringResource(if (pack.imported) R.string.source_imported else R.string.source_created), last = true)
                }
            }
        }
    }

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
