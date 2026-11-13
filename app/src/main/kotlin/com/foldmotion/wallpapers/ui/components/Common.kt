package com.foldmotion.wallpapers.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.device.VerificationStatus
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.scene.PipelineStage
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.generator.GenerationErrorCode
import com.foldmotion.wallpapers.pack.StoredPack
import com.foldmotion.wallpapers.ui.theme.FmColors

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(FmColors.primaryGradient))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (accent) Brush.horizontalGradient(listOf(FmColors.Aurora.copy(alpha = 0.28f), FmColors.Violet.copy(alpha = 0.28f))) else Brush.horizontalGradient(listOf(FmColors.Glass, FmColors.Glass)))
            .border(1.dp, FmColors.glassBorder, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (accent) FmColors.Text else FmColors.TextMuted, maxLines = 1)
    }
}

/** Decodes the stored preview of a pack off the main thread. */
@Composable
fun PackThumbnail(
    pack: StoredPack,
    kind: ScreenKind,
    loader: suspend (StoredPack, ScreenKind, Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(pack.id, pack.manifest.packageVersion, kind) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pack.id, kind) { bitmap = loader(pack, kind, if (kind == ScreenKind.MAIN) 540 else 300) }
    val scene = if (kind == ScreenKind.MAIN) pack.manifest.scenes.main else pack.manifest.scenes.cover
    Box(
        modifier
            .aspectRatio(scene.baseWidth / scene.baseHeight.toFloat())
            .clip(RoundedCornerShape(14.dp))
            .background(FmColors.GlassStrong),
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Glass card with cover and main previews side by side, title and badges. */
@Composable
fun PackCard(
    pack: StoredPack,
    language: String,
    active: Boolean,
    loader: suspend (StoredPack, ScreenKind, Int) -> Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .pressScale(interaction, 0.96f)
            .clip(shape)
            .background(FmColors.Glass)
            .border(1.dp, if (active) FmColors.primaryGradient else FmColors.glassBorder, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            PackThumbnail(pack, ScreenKind.COVER, loader, Modifier.weight(0.32f))
            PackThumbnail(pack, ScreenKind.MAIN, loader, Modifier.weight(0.68f))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pack.manifest.title.forLanguage(language),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (active) StatusPill(stringResource(R.string.badge_active), accent = true)
        }
    }
}

/** Marketing name, or a translated label for the generic fallback profile. */
@Composable
fun profileName(profile: DeviceProfile): String =
    if (profile.id == DeviceProfileRegistry.GENERIC_ID) stringResource(R.string.device_unsupported_name) else profile.marketingName

@Composable
fun familyLabel(f: SceneFamily): String = stringResource(
    when (f) {
        SceneFamily.MOUNTAINS -> R.string.family_mountains
        SceneFamily.DESERT -> R.string.family_desert
        SceneFamily.OCEAN -> R.string.family_ocean
        SceneFamily.SPACE -> R.string.family_space
        SceneFamily.CITY -> R.string.family_city
        SceneFamily.ABSTRACT -> R.string.family_abstract
        SceneFamily.MINIMAL -> R.string.family_minimal
    },
)

@Composable
fun intensityLabel(m: MotionIntensity): String = stringResource(
    when (m) {
        MotionIntensity.STATIC_END -> R.string.motion_static
        MotionIntensity.LOW -> R.string.motion_low
        MotionIntensity.MEDIUM -> R.string.motion_medium
    },
)

@Composable
fun statusLabel(s: VerificationStatus): String = stringResource(
    when (s) {
        VerificationStatus.VERIFIED -> R.string.status_verified
        VerificationStatus.PARTIAL -> R.string.status_partial
        VerificationStatus.UNVERIFIED -> R.string.status_unverified
        VerificationStatus.UNSUPPORTED -> R.string.status_unsupported
    },
)

fun stageLabel(stage: PipelineStage): Int = when (stage) {
    PipelineStage.PROMPT_CHECK -> R.string.stage_prompt_check
    PipelineStage.STORYBOARD -> R.string.stage_storyboard
    PipelineStage.ANCHOR -> R.string.stage_anchor
    PipelineStage.DERIVE_COVER -> R.string.stage_derive_cover
    PipelineStage.LAYERS -> R.string.stage_layers
    PipelineStage.MOTION_PACK -> R.string.stage_motion
    PipelineStage.QA -> R.string.stage_qa
}

fun errorLabel(code: GenerationErrorCode?): Int = when (code) {
    GenerationErrorCode.PROMPT_EMPTY -> R.string.gen_error_prompt_empty
    GenerationErrorCode.PROMPT_TOO_LONG -> R.string.gen_error_prompt_too_long
    GenerationErrorCode.STORAGE_FULL -> R.string.gen_error_storage_full
    GenerationErrorCode.OUT_OF_MEMORY -> R.string.gen_error_out_of_memory
    GenerationErrorCode.VALIDATION_FAILED -> R.string.gen_error_validation_failed
    GenerationErrorCode.RENDER_FAILED, null -> R.string.gen_error_render_failed
}

/** Maps a PromptAnalyzer concept key ("family.ocean", "el.stars") to a label, or null. */
@Composable
fun conceptLabel(key: String): String? {
    val res = when (key) {
        "family.mountains" -> R.string.family_mountains
        "family.desert" -> R.string.family_desert
        "family.ocean" -> R.string.family_ocean
        "family.space" -> R.string.family_space
        "family.city" -> R.string.family_city
        "family.abstract" -> R.string.family_abstract
        "family.minimal" -> R.string.family_minimal
        "time.night" -> R.string.concept_time_night
        "time.dusk" -> R.string.concept_time_dusk
        "time.dawn" -> R.string.concept_time_dawn
        "time.day" -> R.string.concept_time_day
        "hue.blue" -> R.string.concept_hue_blue
        "hue.teal" -> R.string.concept_hue_teal
        "hue.green" -> R.string.concept_hue_green
        "hue.purple" -> R.string.concept_hue_purple
        "hue.pink" -> R.string.concept_hue_pink
        "hue.orange" -> R.string.concept_hue_orange
        "hue.gold" -> R.string.concept_hue_gold
        "hue.red" -> R.string.concept_hue_red
        "el.full_moon" -> R.string.concept_el_full_moon
        "el.crescent" -> R.string.concept_el_crescent
        "el.sun" -> R.string.concept_el_sun
        "el.stars" -> R.string.concept_el_stars
        "el.aurora" -> R.string.concept_el_aurora
        "el.pines" -> R.string.concept_el_pines
        "el.palms" -> R.string.concept_el_palms
        else -> null
    } ?: return null
    return stringResource(res)
}

/** Centered column limited to a readable width on the wide main screen. */
@Composable
fun ReadableColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp), content = content)
    }
}
