package com.foldmotion.wallpapers.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.foldmotion.core.scene.IdeaBank
import com.foldmotion.core.scene.Palettes
import com.foldmotion.core.scene.SceneIdea
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.components.PackCard
import com.foldmotion.wallpapers.ui.components.ReadableColumn
import com.foldmotion.wallpapers.ui.components.SectionTitle
import com.foldmotion.wallpapers.ui.components.StatusPill
import com.foldmotion.wallpapers.ui.components.profileName
import com.foldmotion.wallpapers.ui.components.statusLabel
import com.foldmotion.wallpapers.ui.theme.FmColors

@Composable
fun OnboardingScreen(vm: MainViewModel, onDone: () -> Unit) {
    val device by vm.device.collectAsStateWithLifecycle()
    ReadableColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 32.dp)) {
        FoldGlyph(Modifier.padding(top = 24.dp, bottom = 24.dp))
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        for (res in listOf(R.string.onboarding_point_scene, R.string.onboarding_point_motion, R.string.onboarding_point_local)) {
            Row(Modifier.padding(vertical = 8.dp)) {
                Box(Modifier.padding(top = 7.dp).size(8.dp).background(FmColors.Teal, CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(res), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(20.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Text(
                if (device.supported) stringResource(R.string.onboarding_device_ok, device.detected.profile.marketingName)
                else stringResource(R.string.onboarding_device_unsupported, "${device.manufacturer} ${device.model}"),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

/** Two panels, the right one opening; same mark as the launcher icon. */
@Composable
fun FoldGlyph(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp, 64.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0F3A4D)).border(2.dp, FmColors.TealSoft, RoundedCornerShape(6.dp)))
        Box(
            Modifier.size(56.dp, 64.dp).clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF0E4F55), FmColors.Teal, FmColors.Gold)))
                .border(2.dp, FmColors.Gold, RoundedCornerShape(6.dp)),
        )
    }
}

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onCreate: () -> Unit,
    onSurprise: () -> Unit,
    onIdea: (SceneIdea) -> Unit,
    onOpenPack: (String) -> Unit,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
) {
    val device by vm.device.collectAsStateWithLifecycle()
    val packs by vm.packs.collectAsStateWithLifecycle()
    val active by vm.activePackId.collectAsStateWithLifecycle()
    val language = vm.language()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ReadableColumn(Modifier.padding(top = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusPill(
                    "${profileName(device.active)} · ${statusLabel(device.active.verification.status)}",
                    modifier = Modifier.clickable(onClick = onSettings),
                )
            }
            Spacer(Modifier.height(16.dp))
            Surface(shape = MaterialTheme.shapes.large, color = Color.Transparent) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(listOf(Color(0xFF0E4F55), Color(0xFF123B52), Color(0xFF3C3A2A))))
                        .padding(20.dp),
                ) {
                    Text(stringResource(R.string.home_create_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.home_create_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onCreate, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.nav_create)) }
                        FilledTonalButton(onClick = onSurprise, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.home_surprise)) }
                    }
                }
            }
            SectionTitle(stringResource(R.string.home_ideas))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(IdeaBank.ideas.take(10), key = { it.id }) { idea -> IdeaChip(idea, language) { onIdea(idea) } }
        }
        ReadableColumn {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.home_recent), Modifier.weight(1f))
                if (packs.isNotEmpty()) TextButton(onClick = onLibrary) { Text(stringResource(R.string.home_see_all)) }
            }
            if (packs.isEmpty()) {
                Text(stringResource(R.string.home_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
            }
        }
        if (packs.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(packs.take(8), key = { it.id }) { pack ->
                    PackCard(pack, language, pack.id == active, vm::preview, { onOpenPack(pack.id) }, Modifier.width(240.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IdeaChip(idea: SceneIdea, language: String, onClick: () -> Unit) {
    val pal = Palettes.byId(idea.paletteId)
    val colors = if (pal != null) listOf(Color(pal.skyTop), Color(pal.skyBottom), Color(pal.glow)) else listOf(FmColors.Surface, FmColors.Teal)
    Column(
        Modifier
            .width(132.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(Modifier.fillMaxWidth().height(84.dp).background(Brush.verticalGradient(colors)))
        Text(
            if (language.startsWith("ar")) idea.titleAr else idea.titleEn,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(10.dp),
            maxLines = 2,
        )
    }
}
