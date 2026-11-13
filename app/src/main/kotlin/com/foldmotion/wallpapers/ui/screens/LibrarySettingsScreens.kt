package com.foldmotion.wallpapers.ui.screens

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.wallpapers.BuildConfig
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.telemetry.PocTrace
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.components.PackCard
import com.foldmotion.wallpapers.ui.components.ReadableColumn
import com.foldmotion.wallpapers.ui.components.SectionTitle
import com.foldmotion.wallpapers.ui.components.profileName
import com.foldmotion.wallpapers.ui.components.statusLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LibraryScreen(vm: MainViewModel, onOpenPack: (String) -> Unit) {
    val packs by vm.packs.collectAsStateWithLifecycle()
    val active by vm.activePackId.collectAsStateWithLifecycle()
    val history by vm.applyHistory.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val language = vm.language()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }

    val shown = when (tab) {
        0 -> packs.filter { !it.imported }
        1 -> packs.filter { it.imported }
        else -> history.mapNotNull { h -> packs.firstOrNull { it.id == h.packId } }
    }
    val emptyText = when (tab) {
        0 -> R.string.library_empty_created
        1 -> R.string.library_empty_imported
        else -> R.string.library_empty_applied
    }
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 3 else 1

    Column(Modifier.fillMaxSize()) {
        ReadableColumn(Modifier.padding(top = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.nav_library), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                    Text(stringResource(R.string.action_import))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(R.string.tab_created, R.string.tab_imported, R.string.tab_applied).forEachIndexed { i, res ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(res)) })
            }
        }
        if (shown.isEmpty()) {
            ReadableColumn(Modifier.padding(top = 32.dp)) {
                Text(stringResource(emptyText), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.id }) { pack ->
                    PackCard(pack, language, pack.id == active, vm::preview, { onOpenPack(pack.id) })
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, onLab: () -> Unit) {
    val device by vm.device.collectAsStateWithLifecycle()
    val reduced by vm.reducedMotion.collectAsStateWithLifecycle()
    var language by remember { mutableStateOf(vm.currentLanguageTag()) }

    ReadableColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 32.dp)) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionTitle(stringResource(R.string.settings_device))
        RadioRow(
            selected = !device.manuallyChosen,
            label = stringResource(R.string.settings_device_auto, profileName(device.detected.profile) + " · " + statusLabel(device.detected.profile.verification.status)),
        ) { vm.setProfileOverride(null) }
        for (p in DeviceProfileRegistry.selectable) {
            RadioRow(selected = device.manuallyChosen && device.active.id == p.id, label = p.marketingName + " · " + statusLabel(p.verification.status)) { vm.setProfileOverride(p.id) }
        }
        Text(stringResource(R.string.settings_device_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle(stringResource(R.string.settings_reduced_motion))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_reduced_motion_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = reduced, onCheckedChange = vm::setReducedMotion)
        }

        if (vm.languageSupported) {
            SectionTitle(stringResource(R.string.settings_language))
            for ((tag, label) in listOf(null to stringResource(R.string.lang_system), "ar" to "العربية", "en" to "English")) {
                RadioRow(selected = language == tag, label = label) {
                    language = tag
                    vm.setLanguage(tag)
                }
            }
        }

        SectionTitle(stringResource(R.string.settings_lab))
        Surface(Modifier.fillMaxWidth().clickable(onClick = onLab), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_lab), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.settings_lab_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionTitle(stringResource(R.string.settings_limits))
        Text(stringResource(R.string.settings_limits_body), style = MaterialTheme.typography.bodyMedium)
        SectionTitle(stringResource(R.string.settings_privacy))
        Text(stringResource(R.string.settings_privacy_body), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Fold Lab: the POC instrument. Shows what this app window sees (Jetpack WindowManager posture,
 * hinge angle, window size), the resolved profile, whether our wallpaper is active, and the
 * engine's event trace, which can be exported for the feasibility report.
 */
@Composable
fun LabScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val device by vm.device.collectAsStateWithLifecycle()
    var posture by remember { mutableStateOf("—") }
    var hinge by remember { mutableStateOf<Float?>(null) }
    var events by remember { mutableStateOf(PocTrace.recent().takeLast(150).reversed()) }
    val config = LocalConfiguration.current
    val activity = context as? android.app.Activity

    LaunchedEffect(activity) {
        if (activity == null) return@LaunchedEffect
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            posture = if (fold == null) "no folding feature (cover screen or flat)" else "${fold.state} · ${fold.orientation} · separating=${fold.isSeparating}"
        }
    }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                hinge = event.values.firstOrNull()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        val traceListener = { events = PocTrace.recent().takeLast(150).reversed() }
        PocTrace.addListener(traceListener)
        vm.refreshDevice()
        onDispose {
            sm?.unregisterListener(listener)
            PocTrace.removeListener(traceListener)
        }
    }

    ReadableColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 32.dp)) {
        Text(stringResource(R.string.settings_lab), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val build = "${device.manufacturer} ${device.model} · Android ${device.androidRelease} (SDK ${device.sdkInt}) · ${device.build}"
        SelectionContainer { Text(build, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        SectionTitle(stringResource(R.string.lab_posture))
        LabRow(stringResource(R.string.lab_posture), posture)
        LabRow(stringResource(R.string.lab_window, (config.screenWidthDp * config.densityDpi / 160), (config.screenHeightDp * config.densityDpi / 160)), "max ${device.maxWidthPx}×${device.maxHeightPx}")
        LabRow(stringResource(R.string.lab_hinge), when {
            !device.hasHingeSensor -> stringResource(R.string.lab_hinge_none)
            hinge == null -> "…"
            else -> "${hinge!!.toInt()}°"
        })
        LabRow(stringResource(R.string.lab_profile), "${device.detected.profile.id} · ${statusLabel(device.detected.profile.verification.status)}")
        LabRow(stringResource(R.string.lab_match), device.detected.confidence.name)
        LabRow(stringResource(R.string.lab_wallpaper_active), stringResource(if (vm.container.apply.isOurWallpaperActive()) R.string.yes else R.string.no))

        SectionTitle(stringResource(R.string.lab_steps))
        Text(stringResource(R.string.lab_steps_body), style = MaterialTheme.typography.bodyMedium)

        SectionTitle(stringResource(R.string.lab_events))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = {
                lifecycleOwner.lifecycleScope.launch {
                    val file = File(context.cacheDir, "exports/fold-poc-trace-${System.currentTimeMillis()}.tsv")
                    withContext(Dispatchers.IO) {
                        PocTrace.exportTo(
                            file,
                            "profile=${device.detected.profile.id} match=${device.detected.confidence} hinge=${device.hasHingeSensor} " +
                                "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) active=${vm.container.apply.isOurWallpaperActive()}",
                        )
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.lab_export)))
                }
            }) { Text(stringResource(R.string.lab_export)) }
            OutlinedButton(onClick = { PocTrace.clear() }) { Text(stringResource(R.string.lab_clear)) }
        }
        Spacer(Modifier.height(8.dp))
        if (events.isEmpty()) {
            Text(stringResource(R.string.lab_events_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        } else {
            val first = events.last().elapsedMs
            // Log lines are technical, left-to-right text in every language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small) {
                SelectionContainer {
                    Column(Modifier.padding(10.dp)) {
                        for (e in events) {
                            Text(
                                "+${e.elapsedMs - first}ms  ${e.source}  ${e.event}  ${e.detail}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun LabRow(label: String, value: String) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
