package com.foldmotion.wallpapers.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.IdeaBank
import com.foldmotion.core.scene.Palettes
import com.foldmotion.core.scene.PromptRules
import com.foldmotion.core.scene.ReferencePalette
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.ui.CreateForm
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.VideoJob
import com.foldmotion.wallpapers.ui.VideoPick
import com.foldmotion.wallpapers.ui.components.GlassButton
import com.foldmotion.wallpapers.ui.components.GlassCard
import com.foldmotion.wallpapers.ui.components.GlassChip
import com.foldmotion.wallpapers.ui.components.GradientButton
import com.foldmotion.wallpapers.ui.components.PillToggle
import com.foldmotion.wallpapers.ui.components.ReadableColumn
import com.foldmotion.wallpapers.ui.components.ScreenTitle
import com.foldmotion.wallpapers.ui.components.SectionTitle
import com.foldmotion.wallpapers.ui.components.StylePicker
import com.foldmotion.wallpapers.ui.components.conceptLabel
import com.foldmotion.wallpapers.ui.components.familyLabel
import com.foldmotion.wallpapers.ui.components.intensityLabel
import com.foldmotion.wallpapers.ui.components.staggerIn
import com.foldmotion.wallpapers.ui.theme.FmColors

@Composable
fun CreateScreen(vm: MainViewModel, onGenerate: () -> Unit, onOpenPack: (String) -> Unit = {}) {
    val form by vm.form.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val videoJob by vm.videoJob.collectAsStateWithLifecycle()
    val busy = job?.isRunning == true || videoJob is VideoJob.Running
    // A finished video wallpaper opens straight away, like a generated one.
    LaunchedEffect(videoJob) {
        (videoJob as? VideoJob.Done)?.let {
            vm.dismissVideoJob()
            onOpenPack(it.packId)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // On the open main screen: description on one side, a live sketch of the result on the other.
        val twoPane = maxWidth >= 600.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState()).padding(bottom = NavClearance)) {
                    CreateForm(vm, form, busy, onGenerate)
                }
                Column(Modifier.weight(0.9f).padding(top = 76.dp, start = 8.dp, end = 16.dp)) {
                    SectionTitle(stringResource(R.string.create_preview_label))
                    val pick = form.video
                    if (form.videoMode && pick != null) VideoWindows(pick, device.active) else {
                        ShapeHint(form, device.active.screen(ScreenKind.COVER).aspect, device.active.screen(ScreenKind.MAIN).aspect)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = NavClearance)) {
                CreateForm(vm, form, busy, onGenerate)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateForm(vm: MainViewModel, form: CreateForm, busy: Boolean, onGenerate: () -> Unit) {
    val language = vm.language()
    ReadableColumn(Modifier.padding(top = 14.dp)) {
        ScreenTitle(stringResource(R.string.create_title), Modifier.staggerIn(0))
        PillToggle(
            listOf(stringResource(R.string.create_mode_custom), stringResource(R.string.create_mode_random), stringResource(R.string.create_mode_video)),
            selected = if (form.videoMode) 2 else if (form.mode == GenerationMode.CUSTOM) 0 else 1,
            onSelect = { i ->
                vm.updateForm {
                    when (i) {
                        0 -> it.copy(mode = GenerationMode.CUSTOM, videoMode = false)
                        1 -> it.copy(mode = GenerationMode.RANDOM, videoMode = false)
                        else -> it.copy(videoMode = true)
                    }
                }
            },
            modifier = Modifier.staggerIn(1),
        )
        Spacer(Modifier.height(16.dp))

        val section = if (form.videoMode) 2 else form.mode.ordinal
        AnimatedContent(section, transitionSpec = { (fadeIn(tween(260)) + scaleIn(initialScale = 0.97f)) togetherWith fadeOut(tween(160)) }, label = "mode") { which ->
            Column {
                when {
                    which == 2 -> VideoSection(vm, form, busy)
                    form.mode == GenerationMode.CUSTOM -> CustomSection(vm, form, busy)
                    else -> RandomSection(vm, form, busy, language)
                }
            }
        }

        SectionTitle(stringResource(R.string.create_motion))
        PillToggle(
            MotionIntensity.entries.map { intensityLabel(it) },
            selected = MotionIntensity.entries.indexOf(form.intensity),
            onSelect = { i -> vm.updateForm { it.copy(intensity = MotionIntensity.entries[i]) } },
        )
        Text(stringResource(R.string.create_motion_note), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(top = 8.dp))

        SectionTitle(stringResource(R.string.style_title))
        val reduced by vm.reducedMotion.collectAsStateWithLifecycle()
        StylePicker(
            styles = FoldStyle.entries,
            selected = form.style,
            onSelect = { style -> vm.updateForm { it.copy(style = style) } },
            animate = !reduced,
            enabled = !busy,
        )

        Spacer(Modifier.height(24.dp))
        val videoJob by vm.videoJob.collectAsStateWithLifecycle()
        VideoProgress(vm, videoJob)
        GradientButton(
            stringResource(if (form.videoMode) R.string.video_create else R.string.action_generate),
            onClick = {
                if (form.videoMode) vm.createFromVideo() else if (vm.generate()) onGenerate()
            },
            enabled = !busy && (!form.videoMode || form.video != null),
            leading = Icons.Filled.Star,
            modifier = Modifier.fillMaxWidth().testTag("generate_button"),
            height = 60.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.create_generator_note), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomSection(vm: MainViewModel, form: CreateForm, busy: Boolean) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
        OutlinedTextField(
            value = form.prompt,
            onValueChange = { v -> vm.updateForm { it.copy(prompt = v.take(PromptRules.MAX_LENGTH + 50)) } },
            label = { Text(stringResource(R.string.create_prompt_label)) },
            placeholder = { Text(stringResource(R.string.create_prompt_hint)) },
            minLines = 3,
            maxLines = 6,
            isError = form.error != null || form.promptLength > PromptRules.MAX_LENGTH,
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = FmColors.Aurora,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            supportingText = {
                Row {
                    Text(form.error?.let { stringResource(it) } ?: "", modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.create_prompt_counter, form.promptLength, PromptRules.MAX_LENGTH))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(stringResource(R.string.create_examples), style = MaterialTheme.typography.labelLarge, color = FmColors.TextMuted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (res in listOf(R.string.example_1, R.string.example_2, R.string.example_3, R.string.example_4)) {
            val text = stringResource(res)
            GlassChip(text, Modifier.widthIn(max = 520.dp)) { vm.updateForm { it.copy(prompt = text) } }
        }
    }

    ReferencePhotoSection(vm, form, busy)

    val seen = form.interpretation
    SectionTitle(stringResource(R.string.create_understood))
    if (!seen.understoodAnything) {
        Text(stringResource(R.string.create_understood_none), color = FmColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (key in seen.recognized) {
                conceptLabel(key)?.let { label ->
                    AnimatedVisibility(true, enter = scaleIn() + fadeIn()) {
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(Brush.horizontalGradient(listOf(FmColors.Aurora.copy(alpha = 0.3f), FmColors.Violet.copy(alpha = 0.3f))))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
    }
    SectionTitle(stringResource(R.string.create_type))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassChip(stringResource(R.string.type_auto), selected = form.family == null) { vm.updateForm { it.copy(family = null) } }
        for (f in SceneFamily.entries) {
            GlassChip(familyLabel(f), selected = form.family == f) { vm.updateForm { it.copy(family = f) } }
        }
    }
}

@Composable
private fun RandomSection(vm: MainViewModel, form: CreateForm, busy: Boolean, language: String) {
    val idea = IdeaBank.byId(form.ideaId) ?: IdeaBank.ideas.first()
    val pal = Palettes.byId(idea.paletteId)
    GlassCard(Modifier.fillMaxWidth(), strong = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        AnimatedContent(idea.id, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) }, label = "idea") { _ ->
            Box(
                Modifier.fillMaxWidth().height(130.dp).background(
                    Brush.verticalGradient(if (pal != null) listOf(Color(pal.skyTop), Color(pal.skyMid), Color(pal.skyBottom), Color(pal.near)) else listOf(Color.DarkGray, Color.Gray)),
                ),
            ) {
                if (pal != null) Box(Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(pal.glow), Color(pal.glow).copy(alpha = 0.15f)))))
            }
        }
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.create_idea_label), style = MaterialTheme.typography.labelMedium, color = FmColors.TextMuted)
            Text(if (language.startsWith("ar")) idea.titleAr else idea.titleEn, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(if (language.startsWith("ar")) idea.promptAr else idea.promptEn, style = MaterialTheme.typography.bodyMedium, color = FmColors.TextMuted)
            Spacer(Modifier.height(14.dp))
            GlassButton(stringResource(R.string.action_change_idea), vm::changeIdea, enabled = !busy, leading = Icons.Filled.Refresh, height = 46.dp)
        }
    }
}

@Composable
private fun ReferencePhotoSection(vm: MainViewModel, form: CreateForm, busy: Boolean) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setReferencePhoto(uri)
    }
    SectionTitle(stringResource(R.string.reference_title))
    val ref = form.reference
    GlassCard(Modifier.fillMaxWidth()) {
        if (ref == null) {
            GlassButton(
                stringResource(R.string.reference_pick),
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy,
                height = 46.dp,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), modifier = Modifier.weight(1f)) {
                    for (c in ref.swatches) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c)).border(2.dp, FmColors.Night, CircleShape))
                    }
                }
                TextButton(onClick = vm::clearReferencePhoto, enabled = !busy) { Text(stringResource(R.string.reference_remove), color = FmColors.Aurora) }
            }
            Spacer(Modifier.height(6.dp))
            // Shows the palette the scene will actually use, not just the raw photo colours.
            val (palette, _) = ReferencePalette.toPalette(ref)
            Box(
                Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color(palette.skyTop), Color(palette.skyMid), Color(palette.skyBottom), Color(palette.glow), Color(palette.accent)))),
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.reference_in_use), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.reference_note), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Live sketch of the two screens in the chosen colours, with a slow light sweep. */
@Composable
private fun ShapeHint(form: CreateForm, coverAspect: Float, mainAspect: Float) {
    val pal = when {
        form.mode == GenerationMode.RANDOM -> IdeaBank.byId(form.ideaId)?.paletteId?.let { Palettes.byId(it) }
        form.reference != null -> ReferencePalette.toPalette(form.reference).first
        else -> null
    }
    val colors = if (pal != null) listOf(Color(pal.skyTop), Color(pal.skyMid), Color(pal.skyBottom), Color(pal.near)) else listOf(Color(0xFF123B52), FmColors.Aurora, FmColors.Violet)
    val t = rememberInfiniteTransition(label = "hint")
    val sweep by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(4_000, easing = LinearEasing)), label = "sweep")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        for ((aspect, crease) in listOf(coverAspect to false, mainAspect to true)) {
            Box(
                Modifier.weight(aspect).aspectRatio(aspect).clip(RoundedCornerShape(20.dp))
                    .background(Brush.verticalGradient(colors))
                    .border(2.dp, FmColors.glassBorder, RoundedCornerShape(20.dp)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = sweep * size.width
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent), start = Offset(x - size.width * 0.3f, 0f), end = Offset(x + size.width * 0.3f, size.height)))
                }
                if (pal != null) Box(Modifier.align(Alignment.TopCenter).padding(top = 40.dp).size(20.dp).clip(CircleShape).background(Color(pal.glow)))
                if (crease) Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.22f)))
            }
        }
    }
}


// ---------------------------------------------------------------------- video wallpapers

@Composable
private fun VideoSection(vm: MainViewModel, form: CreateForm, busy: Boolean) {
    val device by vm.device.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.pickVideo(uri)
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    val v = form.video
    GlassCard(Modifier.fillMaxWidth()) {
        if (v == null) {
            Text(stringResource(R.string.video_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            GradientButton(stringResource(R.string.video_pick), ::pick, Modifier.fillMaxWidth(), enabled = !busy, height = 52.dp)
            Spacer(Modifier.height(10.dp))
            GlassButton(stringResource(R.string.video_sample), vm::pickSampleVideo, Modifier.fillMaxWidth(), enabled = !busy, height = 46.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (v.sample) stringResource(R.string.video_sample_label) else stringResource(R.string.video_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.video_info, v.width, v.height, v.durationMs / 1000f),
                        style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted,
                    )
                }
                TextButton(onClick = ::pick, enabled = !busy) { Text(stringResource(R.string.video_change), color = FmColors.Aurora) }
            }
            Spacer(Modifier.height(10.dp))
            VideoWindows(v, device.active)
            Text(stringResource(R.string.video_windows), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(top = 8.dp))

            if (v.durationMs > 1_500) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.video_range, v.startMs / 1000f, v.endMs / 1000f), style = MaterialTheme.typography.titleSmall)
                androidx.compose.material3.RangeSlider(
                    value = (v.startMs / 1000f)..(v.endMs / 1000f),
                    onValueChange = { r -> vm.setVideoRange((r.start * 1000).toLong(), (r.endInclusive * 1000).toLong()) },
                    valueRange = 0f..(v.durationMs / 1000f),
                    enabled = !busy,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = FmColors.Aurora,
                        activeTrackColor = FmColors.Aurora,
                        inactiveTrackColor = FmColors.GlassStrong,
                    ),
                )
                Text(stringResource(R.string.video_range_note), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.video_crossfade), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.video_crossfade_body), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted)
                }
                androidx.compose.material3.Switch(
                    checked = v.crossfade,
                    onCheckedChange = vm::setVideoCrossfade,
                    enabled = !busy,
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = FmColors.Aurora),
                )
            }
        }
        Text(stringResource(R.string.video_note), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(top = 10.dp))
    }
}

/** The clip's first frame with the two screens' windows outlined, so the framing is clear. */
@Composable
private fun VideoWindows(v: VideoPick, profile: com.foldmotion.core.device.DeviceProfile) {
    val crops = androidx.compose.runtime.remember(v.width, v.height, profile.id) {
        com.foldmotion.core.pack.VideoGeometry.crops(profile, v.width, v.height)
    }
    val aspect = (v.width.toFloat() / v.height).coerceIn(0.4f, 2.4f)
    Box(
        Modifier.fillMaxWidth().widthIn(max = 520.dp).aspectRatio(aspect).clip(RoundedCornerShape(16.dp)).background(FmColors.Glass),
    ) {
        v.thumbnail?.let {
            androidx.compose.foundation.Image(
                it.asImageBitmap(), contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            fun outline(r: List<Float>, color: Color, width: Float) {
                drawRect(
                    color, topLeft = Offset(r[0] * size.width, r[1] * size.height),
                    size = androidx.compose.ui.geometry.Size((r[2] - r[0]) * size.width, (r[3] - r[1]) * size.height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width),
                )
            }
            // Outside the inner screen's window is never shown: dim it.
            val m = crops.main
            drawRect(Color.Black.copy(alpha = 0.45f), size = androidx.compose.ui.geometry.Size(m[0] * size.width, size.height))
            drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(m[2] * size.width, 0f), size = androidx.compose.ui.geometry.Size((1f - m[2]) * size.width, size.height))
            outline(crops.main, FmColors.Aurora, 3.dp.toPx())
            outline(crops.cover, FmColors.Gold, 2.dp.toPx())
        }
    }
}

@Composable
private fun VideoProgress(vm: MainViewModel, job: VideoJob?) {
    AnimatedVisibility(job is VideoJob.Running || job is VideoJob.Failed) {
        GlassCard(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            when (job) {
                is VideoJob.Running -> {
                    val label = when (job.stage) {
                        com.foldmotion.wallpapers.generator.video.VideoStage.TRIM -> stringResource(R.string.video_stage_trim, (job.progress * 100).toInt())
                        com.foldmotion.wallpapers.generator.video.VideoStage.POSTERS -> stringResource(R.string.video_stage_posters)
                        com.foldmotion.wallpapers.generator.video.VideoStage.MOTION -> stringResource(R.string.video_stage_motion)
                        com.foldmotion.wallpapers.generator.video.VideoStage.QA -> stringResource(R.string.video_stage_qa)
                    }
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    val overall = (job.stage.ordinal + if (job.stage == com.foldmotion.wallpapers.generator.video.VideoStage.TRIM) job.progress else 0.5f) / com.foldmotion.wallpapers.generator.video.VideoStage.entries.size
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { overall.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                        color = FmColors.Aurora,
                        trackColor = FmColors.GlassStrong,
                    )
                    TextButton(onClick = vm::cancelVideo) { Text(stringResource(R.string.action_cancel), color = FmColors.TextMuted) }
                }
                is VideoJob.Failed -> {
                    Text(stringResource(job.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::dismissVideoJob) { Text(stringResource(R.string.action_close), color = FmColors.Aurora) }
                }
                else -> Unit
            }
        }
    }
}
