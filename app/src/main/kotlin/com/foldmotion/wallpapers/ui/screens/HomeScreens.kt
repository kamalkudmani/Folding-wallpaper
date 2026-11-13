package com.foldmotion.wallpapers.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldmotion.core.scene.IdeaBank
import com.foldmotion.core.scene.Palettes
import com.foldmotion.core.scene.SceneIdea
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.components.FoldDemo
import com.foldmotion.wallpapers.ui.components.GlassButton
import com.foldmotion.wallpapers.ui.components.GlassCard
import com.foldmotion.wallpapers.ui.components.GradientButton
import com.foldmotion.wallpapers.ui.components.PackCard
import com.foldmotion.wallpapers.ui.components.ReadableColumn
import com.foldmotion.wallpapers.ui.components.SectionTitle
import com.foldmotion.wallpapers.ui.components.StatusPill
import com.foldmotion.wallpapers.ui.components.pressScale
import com.foldmotion.wallpapers.ui.components.profileName
import com.foldmotion.wallpapers.ui.components.rememberPackScenes
import com.foldmotion.wallpapers.ui.components.staggerIn
import com.foldmotion.wallpapers.ui.components.statusLabel
import com.foldmotion.wallpapers.ui.theme.FmColors

/** Bottom space so content clears the floating navigation bar. */
val NavClearance = 104.dp

@Composable
fun OnboardingScreen(vm: MainViewModel, onDone: () -> Unit) {
    val device by vm.device.collectAsStateWithLifecycle()
    ReadableColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 28.dp)) {
        Box(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 28.dp).staggerIn(0), contentAlignment = Alignment.Center) {
            AnimatedFoldGlyph(Modifier.size(width = 168.dp, height = 132.dp))
        }
        Text(
            stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.displaySmall.copy(brush = Brush.horizontalGradient(listOf(FmColors.Text, FmColors.Aurora))),
            modifier = Modifier.staggerIn(1),
        )
        Spacer(Modifier.height(20.dp))
        GlassCard(Modifier.fillMaxWidth().staggerIn(2)) {
            for (res in listOf(R.string.onboarding_point_scene, R.string.onboarding_point_motion, R.string.onboarding_point_local)) {
                Row(Modifier.padding(vertical = 8.dp)) {
                    Box(Modifier.padding(top = 8.dp).size(8.dp).clip(CircleShape).background(FmColors.primaryGradient))
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(res), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth().staggerIn(3)) {
            Text(
                if (device.supported) stringResource(R.string.onboarding_device_ok, device.detected.profile.marketingName)
                else stringResource(R.string.onboarding_device_unsupported, "${device.manufacturer} ${device.model}"),
                style = MaterialTheme.typography.bodyMedium,
                color = FmColors.TextMuted,
            )
        }
        Spacer(Modifier.height(26.dp))
        GradientButton(stringResource(R.string.onboarding_cta), onDone, Modifier.fillMaxWidth().staggerIn(4))
    }
}

/** Two panels of a book-style Fold, the right one opening and closing on a loop. */
@Composable
fun AnimatedFoldGlyph(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "glyph")
    val angle by t.animateFloat(-165f, 0f, infiniteRepeatable(tween(2_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "angle")
    val density = LocalDensity.current
    // A physical hinge: lay it out left-to-right in every language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Row {
            Box(
                Modifier.size(56.dp, 112.dp).clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF123B52), Color(0xFF0E4F55))))
                    .border(1.5.dp, FmColors.glassBorder, RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)),
            )
            Box(
                Modifier.size(56.dp, 112.dp)
                    .graphicsLayer {
                        rotationY = angle
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        cameraDistance = 10f * density.density
                    }
                    .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                    .background(Brush.linearGradient(listOf(FmColors.Aurora, FmColors.Violet, FmColors.Gold)))
                    .border(1.5.dp, FmColors.glassBorder, RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)),
            )
        }
    }
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
    val reduced by vm.reducedMotion.collectAsStateWithLifecycle()
    val language = vm.language()
    val hero = vm.heroPack(packs, active)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = NavClearance)) {
        ReadableColumn(Modifier.padding(top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.staggerIn(0)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall.copy(brush = Brush.horizontalGradient(listOf(FmColors.Text, FmColors.Aurora))),
                    modifier = Modifier.weight(1f),
                )
                val interaction = remember { MutableInteractionSource() }
                StatusPill(
                    "${profileName(device.active)} · ${statusLabel(device.active.verification.status)}",
                    modifier = Modifier.pressScale(interaction).clickable(interaction, indication = null, onClick = onSettings),
                )
            }
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(Modifier.staggerIn(1)) {
                val wide = maxWidth >= 600.dp
                GlassCard(Modifier.fillMaxWidth(), strong = true, contentPadding = PaddingValues(18.dp)) {
                    if (wide) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HeroVisual(vm, hero, Modifier.weight(1.1f), 330.dp, reduced)
                            Spacer(Modifier.width(18.dp))
                            Column(Modifier.weight(0.9f)) { HeroText(hero != null, onCreate, onSurprise, stacked = true) }
                        }
                    } else {
                        HeroVisual(vm, hero, Modifier.fillMaxWidth(), 300.dp, reduced)
                        Spacer(Modifier.height(10.dp))
                        HeroText(hero != null, onCreate, onSurprise)
                    }
                }
            }
            SectionTitle(stringResource(R.string.home_ideas))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(IdeaBank.ideas.take(12), key = { _, it -> it.id }) { i, idea ->
                IdeaCard(idea, language, Modifier.staggerIn(i + 2)) { onIdea(idea) }
            }
        }
        ReadableColumn {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.home_recent), Modifier.weight(1f))
                if (packs.isNotEmpty()) TextButton(onClick = onLibrary) { Text(stringResource(R.string.home_see_all), color = FmColors.Aurora) }
            }
            if (packs.isEmpty()) {
                Text(stringResource(R.string.home_empty), color = FmColors.TextMuted, modifier = Modifier.padding(bottom = 24.dp))
            }
        }
        if (packs.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(packs.take(8), key = { _, it -> it.id }) { i, pack ->
                    PackCard(pack, language, pack.id == active, vm::preview, { onOpenPack(pack.id) }, Modifier.width(250.dp).staggerIn(i + 3))
                }
            }
        }
    }
}

@Composable
private fun HeroVisual(vm: MainViewModel, hero: com.foldmotion.wallpapers.pack.StoredPack?, modifier: Modifier, height: Dp, reduced: Boolean) {
    if (hero == null) {
        Box(modifier.height(height * 0.7f), contentAlignment = Alignment.Center) { AnimatedFoldGlyph(Modifier.size(200.dp, 150.dp)) }
        return
    }
    val longSide = with(LocalDensity.current) { (height * 1.4f).roundToPx() }
    val (cover, main) = rememberPackScenes(hero.dir, longSide)
    val profile = DeviceProfileRegistry.byId(hero.manifest.profileIds.firstOrNull()) ?: vm.profile
    FoldDemo(cover, main, profile, height, modifier, animate = !reduced)
}

@Composable
private fun HeroText(hasPack: Boolean, onCreate: () -> Unit, onSurprise: () -> Unit, stacked: Boolean = false) {
    Text(stringResource(if (hasPack) R.string.home_hero_title else R.string.home_hero_empty), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.home_hero_body), style = MaterialTheme.typography.bodyMedium, color = FmColors.TextMuted)
    Spacer(Modifier.height(16.dp))
    if (stacked) {
        GradientButton(stringResource(R.string.nav_create), onCreate, Modifier.fillMaxWidth(), leading = Icons.Filled.Add)
        Spacer(Modifier.height(10.dp))
        GlassButton(stringResource(R.string.home_surprise), onSurprise, Modifier.fillMaxWidth(), leading = Icons.Filled.Star, height = 56.dp)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton(stringResource(R.string.nav_create), onCreate, Modifier.weight(1f), leading = Icons.Filled.Add)
            GlassButton(stringResource(R.string.home_surprise), onSurprise, Modifier.weight(1f), leading = Icons.Filled.Star, height = 56.dp)
        }
    }
}

@Composable
private fun IdeaCard(idea: SceneIdea, language: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pal = Palettes.byId(idea.paletteId)
    val colors = if (pal != null) listOf(Color(pal.skyTop), Color(pal.skyMid), Color(pal.skyBottom), Color(pal.near)) else listOf(FmColors.NightHigh, FmColors.Aurora)
    val interaction = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .width(140.dp)
            .pressScale(interaction, 0.94f)
            .clip(shape)
            .background(FmColors.Glass)
            .border(1.dp, FmColors.glassBorder, shape)
            .clickable(interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(colors))) {
            if (pal != null) {
                Box(
                    Modifier.align(Alignment.Center).padding(bottom = 18.dp).size(22.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(pal.glow), Color(pal.glow).copy(alpha = 0.2f)))),
                )
            }
        }
        Text(
            if (language.startsWith("ar")) idea.titleAr else idea.titleEn,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            maxLines = 2,
        )
    }
}
