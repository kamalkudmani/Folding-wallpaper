package com.foldmotion.wallpapers.ui.screens

import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.IdeaBank
import com.foldmotion.core.scene.Palettes
import com.foldmotion.core.scene.PromptRules
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.ui.CreateForm
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.components.ReadableColumn
import com.foldmotion.wallpapers.ui.components.SectionTitle
import com.foldmotion.wallpapers.ui.components.conceptLabel
import com.foldmotion.wallpapers.ui.components.familyLabel
import com.foldmotion.wallpapers.ui.components.intensityLabel

@Composable
fun CreateScreen(vm: MainViewModel, onGenerate: () -> Unit) {
    val form by vm.form.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val busy = job?.isRunning == true

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // On the open main screen: description on one side, shape of the result on the other.
        val twoPane = maxWidth >= 600.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState())) {
                    CreateForm(vm, form, busy, onGenerate)
                }
                Column(Modifier.weight(0.9f).padding(top = 72.dp, start = 8.dp, end = 16.dp)) {
                    SectionTitle(stringResource(R.string.create_preview_label))
                    ShapeHint(form, device.active.screen(ScreenKind.COVER).aspect, device.active.screen(ScreenKind.MAIN).aspect)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                CreateForm(vm, form, busy, onGenerate)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateForm(vm: MainViewModel, form: CreateForm, busy: Boolean, onGenerate: () -> Unit) {
    val language = vm.language()
    ReadableColumn(Modifier.padding(top = 16.dp, bottom = 32.dp)) {
        Text(stringResource(R.string.create_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = listOf(GenerationMode.CUSTOM to R.string.create_mode_custom, GenerationMode.RANDOM to R.string.create_mode_random)
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = form.mode == mode,
                    onClick = { vm.updateForm { it.copy(mode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(stringResource(label)) }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (form.mode == GenerationMode.CUSTOM) {
            OutlinedTextField(
                value = form.prompt,
                onValueChange = { v -> vm.updateForm { it.copy(prompt = v.take(PromptRules.MAX_LENGTH + 50)) } },
                label = { Text(stringResource(R.string.create_prompt_label)) },
                placeholder = { Text(stringResource(R.string.create_prompt_hint)) },
                minLines = 3,
                maxLines = 6,
                isError = form.error != null || form.promptLength > PromptRules.MAX_LENGTH,
                supportingText = {
                    Row {
                        Text(form.error?.let { stringResource(it) } ?: "", modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.create_prompt_counter, form.promptLength, PromptRules.MAX_LENGTH))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.create_examples), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (res in listOf(R.string.example_1, R.string.example_2, R.string.example_3, R.string.example_4)) {
                    val text = stringResource(res)
                    AssistChip(
                        onClick = { vm.updateForm { it.copy(prompt = text) } },
                        label = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp)) },
                        modifier = Modifier.widthMaxChip(),
                    )
                }
            }
            val seen = form.interpretation
            ReferencePhotoSection(vm, form, busy)
            SectionTitle(stringResource(R.string.create_understood))
            if (!seen.understoodAnything) {
                Text(stringResource(R.string.create_understood_none), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (key in seen.recognized) {
                        conceptLabel(key)?.let { label ->
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            SectionTitle(stringResource(R.string.create_type))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = form.family == null, onClick = { vm.updateForm { it.copy(family = null) } }, label = { Text(stringResource(R.string.type_auto)) })
                for (f in SceneFamily.entries) {
                    FilterChip(selected = form.family == f, onClick = { vm.updateForm { it.copy(family = f) } }, label = { Text(familyLabel(f)) })
                }
            }
        } else {
            val idea = IdeaBank.byId(form.ideaId) ?: IdeaBank.ideas.first()
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth()) {
                    val pal = Palettes.byId(idea.paletteId)
                    Box(
                        Modifier.fillMaxWidth().height(96.dp).background(
                            Brush.verticalGradient(if (pal != null) listOf(Color(pal.skyTop), Color(pal.skyBottom), Color(pal.glow)) else listOf(Color.DarkGray, Color.Gray)),
                        ),
                    )
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.create_idea_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (language.startsWith("ar")) idea.titleAr else idea.titleEn, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(if (language.startsWith("ar")) idea.promptAr else idea.promptEn, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = vm::changeIdea, enabled = !busy) { Text(stringResource(R.string.action_change_idea)) }
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.create_motion))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val levels = MotionIntensity.entries
            levels.forEachIndexed { i, level ->
                SegmentedButton(
                    selected = form.intensity == level,
                    onClick = { vm.updateForm { it.copy(intensity = level) } },
                    shape = SegmentedButtonDefaults.itemShape(i, levels.size),
                ) { Text(intensityLabel(level)) }
            }
        }
        Text(stringResource(R.string.create_motion_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { if (vm.generate()) onGenerate() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        ) { Text(stringResource(R.string.action_generate), style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.create_generator_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReferencePhotoSection(vm: MainViewModel, form: CreateForm, busy: Boolean) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setReferencePhoto(uri)
    }
    SectionTitle(stringResource(R.string.reference_title))
    val ref = form.reference
    if (ref == null) {
        OutlinedButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = !busy,
        ) { Text(stringResource(R.string.reference_pick)) }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp), modifier = Modifier.weight(1f)) {
                for (c in ref.swatches) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    )
                }
            }
            TextButton(onClick = vm::clearReferencePhoto, enabled = !busy) { Text(stringResource(R.string.reference_remove)) }
        }
        Text(stringResource(R.string.reference_in_use), style = MaterialTheme.typography.bodyMedium)
    }
    Text(stringResource(R.string.reference_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

private fun Modifier.widthMaxChip(): Modifier = this.then(Modifier.widthIn(max = 520.dp))

/** Two outlines, cover and main at their real proportions, to show what will be produced. */
@Composable
private fun ShapeHint(form: CreateForm, coverAspect: Float, mainAspect: Float) {
    val pal = when (form.mode) {
        GenerationMode.RANDOM -> IdeaBank.byId(form.ideaId)?.paletteId?.let { Palettes.byId(it) }
        GenerationMode.CUSTOM -> null
    }
    val brush = Brush.verticalGradient(
        if (pal != null) listOf(Color(pal.skyTop), Color(pal.skyMid), Color(pal.skyBottom)) else listOf(Color(0xFF123B52), Color(0xFF19B5B0), Color(0xFFE2B861)),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(coverAspect).aspectRatio(coverAspect).clip(RoundedCornerShape(16.dp)).background(brush))
        Box(Modifier.weight(mainAspect * 1.0f).aspectRatio(mainAspect).clip(RoundedCornerShape(14.dp)).background(brush)) {
            Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxSize().background(Color.White.copy(alpha = 0.25f)))
        }
    }
}
