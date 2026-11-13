package com.foldmotion.wallpapers.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.scene.JobStatus
import com.foldmotion.core.scene.PipelineStage
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.apply.ApplyOutcome
import com.foldmotion.wallpapers.apply.ApplyStart
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.components.ReadableColumn
import com.foldmotion.wallpapers.ui.components.SceneFrame
import com.foldmotion.wallpapers.ui.components.SectionTitle
import com.foldmotion.wallpapers.ui.components.SideBySide
import com.foldmotion.wallpapers.ui.components.errorLabel
import com.foldmotion.wallpapers.ui.components.intensityLabel
import com.foldmotion.wallpapers.ui.components.profileName
import com.foldmotion.wallpapers.ui.components.rememberPackScenes
import com.foldmotion.wallpapers.ui.components.stageLabel
import com.foldmotion.wallpapers.ui.theme.FmColors

@Composable
fun GeneratingScreen(vm: MainViewModel, onReady: (String) -> Unit, onEdit: () -> Unit, onClose: () -> Unit) {
    val job by vm.job.collectAsStateWithLifecycle()
    val j = job
    ReadableColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gen_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
        }
        Spacer(Modifier.height(20.dp))
        // Each line is a real pipeline stage, ticked when it finishes. No invented percentages.
        for (stage in PipelineStage.entries) {
            val done = j != null && (stage in j.completed || j.status == JobStatus.READY)
            val current = j?.stage == stage && j.isRunning
            Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        done -> Icon(Icons.Filled.Check, contentDescription = null, tint = FmColors.Teal)
                        current -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else -> Box(Modifier.size(8.dp).padding(2.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(stageLabel(stage)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done || current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        when {
            j == null -> Unit
            j.isRunning -> {
                Text(stringResource(R.string.gen_background_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = vm::cancelGeneration, enabled = j.status != JobStatus.CANCEL_REQUESTED, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            j.status == JobStatus.READY && j.packId != null -> {
                Text(stringResource(R.string.gen_ready, j.durationMs / 1000f), color = FmColors.Teal)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onReady(j.packId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.action_view_pack)) }
            }
            j.status == JobStatus.CANCELLED -> {
                Text(stringResource(R.string.gen_cancelled))
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_edit_prompt)) }
            }
            else -> {
                Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.gen_failed), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(errorLabel(j.error), j.validationCodes.joinToString()))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.generate() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_retry)) }
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_edit_prompt)) }
                }
            }
        }
    }
}

private enum class PreviewMode { COVER, MAIN, BOTH }

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
    val (cover, main) = rememberPackScenes(pack.dir, longSide)
    var mode by remember { mutableStateOf<PreviewMode?>(null) }
    var playCover by remember { mutableIntStateOf(0) }
    var playMain by remember { mutableIntStateOf(0) }
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
        val effectiveMode = mode ?: if (wide) PreviewMode.BOTH else PreviewMode.COVER
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ReadableColumn(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pack.manifest.title.forLanguage(language),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        vm.shareIntent(pack.id)?.let { context.startActivity(android.content.Intent.createChooser(it, context.getString(R.string.share_chooser))) }
                    }) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share)) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete)) }
                }
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = listOf(PreviewMode.COVER to R.string.preview_cover, PreviewMode.MAIN to R.string.preview_main, PreviewMode.BOTH to R.string.preview_both)
                    modes.forEachIndexed { i, (m, label) ->
                        SegmentedButton(selected = effectiveMode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(stringResource(label)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                val frameHeight = if (wide) 460.dp else 420.dp
                when (effectiveMode) {
                    PreviewMode.COVER -> SceneFrame(cover, ScreenKind.COVER, profile, Modifier.height(frameHeight), playCover)
                    PreviewMode.MAIN -> SceneFrame(main, ScreenKind.MAIN, profile, Modifier.height(frameHeight).widthIn(max = 520.dp), playMain)
                    PreviewMode.BOTH -> SideBySide(cover, main, profile, frameHeight, Modifier.widthIn(max = 720.dp), playCover, playMain)
                }
            }
            ReadableColumn(Modifier.padding(bottom = 32.dp)) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = {
                            if (effectiveMode == PreviewMode.COVER) mode = PreviewMode.MAIN
                            if (!reduced) playMain++
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_play_open)) }
                    FilledTonalButton(
                        onClick = {
                            if (effectiveMode == PreviewMode.MAIN) mode = PreviewMode.COVER
                            if (!reduced) playCover++
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_play_close)) }
                }
                Text(stringResource(R.string.preview_simulation_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(16.dp))
                Button(onClick = ::apply, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                    Text(stringResource(R.string.action_apply), style = MaterialTheme.typography.titleMedium)
                }

                SectionTitle(stringResource(R.string.preview_details))
                DetailRow(stringResource(R.string.preview_check), if (report.passed) stringResource(R.string.preview_check_passed) else stringResource(R.string.preview_check_failed, report.codes.joinToString()))
                DetailRow(stringResource(R.string.preview_profile), pack.manifest.profileIds.map { id -> DeviceProfileRegistry.byId(id)?.let { profileName(it) } ?: id }.joinToString())
                DetailRow(
                    stringResource(R.string.preview_motion),
                    stringResource(R.string.preview_motion_value, intensityLabel(pack.manifest.motionIntensity), pack.manifest.transitions.open.durationMs, pack.manifest.transitions.close.durationMs),
                )
                DetailRow(stringResource(R.string.preview_size), stringResource(R.string.preview_size_value, pack.sizeBytes / 1_048_576f))
                DetailRow(stringResource(R.string.preview_source), stringResource(if (pack.imported) R.string.source_imported else R.string.source_created))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(if (pack.id == active) R.string.delete_active else R.string.delete_confirm_body)) },
            confirmButton = {
                if (pack.id != active) {
                    TextButton(onClick = {
                        confirmDelete = false
                        if (vm.delete(pack.id)) onBack()
                    }) { Text(stringResource(R.string.action_delete)) }
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium)
            Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
