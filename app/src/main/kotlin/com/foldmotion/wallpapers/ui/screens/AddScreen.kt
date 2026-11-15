package com.foldmotion.wallpapers.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.pack.VideoGeometry
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.ui.CreateForm
import com.foldmotion.wallpapers.ui.MainViewModel
import com.foldmotion.wallpapers.ui.PhotoPick
import com.foldmotion.wallpapers.ui.VideoJob
import com.foldmotion.wallpapers.ui.VideoPick
import com.foldmotion.wallpapers.ui.components.FoldedDevice
import com.foldmotion.wallpapers.ui.components.GlassCard
import com.foldmotion.wallpapers.ui.components.GradientButton
import com.foldmotion.wallpapers.ui.components.ReadableColumn
import com.foldmotion.wallpapers.ui.components.ScreenTitle
import com.foldmotion.wallpapers.ui.components.SectionTitle
import com.foldmotion.wallpapers.ui.components.StylePicker
import com.foldmotion.wallpapers.ui.components.UnfoldedDevice
import com.foldmotion.wallpapers.ui.components.pressScale
import com.foldmotion.wallpapers.ui.theme.FmColors

/**
 * Add your own: a photo or a video from the gallery becomes a wallpaper for both screens. A
 * photo gets the motion you choose (Auto lifts a person or pet out of it in 3D); a video is its
 * own motion and plays as it is. Everything is made on the phone.
 */
@Composable
fun AddScreen(vm: MainViewModel, onOpenPack: (String) -> Unit) {
    val form by vm.form.collectAsStateWithLifecycle()
    val videoJob by vm.videoJob.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val reduced by vm.reducedMotion.collectAsStateWithLifecycle()
    val busy = videoJob is VideoJob.Running
    // A finished wallpaper opens straight away.
    LaunchedEffect(videoJob) {
        (videoJob as? VideoJob.Done)?.let {
            vm.dismissVideoJob()
            onOpenPack(it.packId)
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.pickPhoto(uri) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.pickVideo(uri) }
    fun pickPhoto() = photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    fun pickVideo() = videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = NavClearance)) {
        ReadableColumn(Modifier.padding(top = 14.dp)) {
            ScreenTitle(stringResource(R.string.add_title))
            Text(stringResource(R.string.add_subtitle), style = MaterialTheme.typography.bodyMedium, color = FmColors.TextMuted)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceTile(
                    PhotoIcon, stringResource(R.string.add_photo), stringResource(R.string.add_photo_body),
                    selected = form.photo != null, enabled = !busy, onClick = ::pickPhoto,
                    modifier = Modifier.weight(1f).testTag("add_photo"),
                )
                SourceTile(
                    VideoIcon, stringResource(R.string.add_video), stringResource(R.string.add_video_body),
                    selected = form.video != null, enabled = !busy, onClick = ::pickVideo,
                    modifier = Modifier.weight(1f).testTag("add_video"),
                )
            }

            val photo = form.photo
            val video = form.video
            when {
                photo != null -> PhotoSection(vm, form, photo, device.active, busy, animate = !reduced, onChange = ::pickPhoto)
                video != null -> VideoSection(vm, video, device.active, busy, onChange = ::pickVideo)
                else -> Text(
                    stringResource(R.string.add_hint), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            if (photo != null || video != null) {
                Spacer(Modifier.height(24.dp))
                JobProgress(vm, videoJob)
                GradientButton(
                    stringResource(R.string.add_make),
                    onClick = { if (photo != null) vm.createFromPhoto() else vm.createFromVideo() },
                    enabled = !busy,
                    leading = Icons.Filled.Star,
                    modifier = Modifier.fillMaxWidth().testTag("add_make"),
                    height = 60.dp,
                )
            }
            Text(stringResource(R.string.add_private), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/** Material's image and movie glyphs (the core icon set has neither). */
private fun glyph(name: String, d: String, evenOdd: Boolean) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(
        androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes(),
        pathFillType = if (evenOdd) androidx.compose.ui.graphics.PathFillType.EvenOdd else androidx.compose.ui.graphics.PathFillType.NonZero,
        fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
    )
    .build()

private val PhotoIcon = glyph("photo", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z", evenOdd = true)
private val VideoIcon = glyph("video", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z", evenOdd = false)

@Composable
private fun SourceTile(icon: ImageVector, title: String, body: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.heightIn(min = 150.dp).pressScale(interaction, 0.97f).clip(shape)
            .background(if (selected) FmColors.Surface2.copy(alpha = 0.95f) else FmColors.Surface2)
            .border(if (selected) 2.dp else 1.dp, if (selected) FmColors.Aurora else FmColors.Hairline, shape)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                this.selected = selected
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Bottom),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(FmColors.primaryGradient), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color(0xFF04131A))
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The picked photo on both screens as it will be framed, and the motion to give it. */
@Composable
private fun PhotoSection(vm: MainViewModel, form: CreateForm, photo: PhotoPick, profile: DeviceProfile, busy: Boolean, animate: Boolean, onChange: () -> Unit) {
    val framed = remember(photo, profile.id) { photo.thumbnail?.let { frame(it, profile) } }
    SectionTitle(stringResource(R.string.add_your_photo))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(0.34f), horizontalAlignment = Alignment.CenterHorizontally) {
            FoldedDevice(framed?.first, Modifier.fillMaxWidth(), elevation = 10.dp)
        }
        Column(Modifier.weight(0.66f), horizontalAlignment = Alignment.CenterHorizontally) {
            UnfoldedDevice(framed?.second, Modifier.fillMaxWidth(), elevation = 12.dp)
        }
    }
    TextButton(onClick = onChange, enabled = !busy) { Text(stringResource(R.string.add_change), color = FmColors.Aurora) }

    SectionTitle(stringResource(R.string.add_motion))
    Text(stringResource(R.string.add_motion_hint), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(bottom = 12.dp))
    StylePicker(
        styles = FoldStyle.entries,
        selected = form.style,
        onSelect = { style -> vm.updateForm { it.copy(style = style) } },
        animate = animate,
        enabled = !busy,
    )
}

/** The photo cropped for the cover and the main screen (centred; a found subject may shift it a little). */
private fun frame(photo: Bitmap, profile: DeviceProfile): Pair<ImageBitmap, ImageBitmap> {
    val crops = VideoGeometry.crops(profile, photo.width, photo.height)
    fun cut(r: List<Float>): ImageBitmap {
        val x = (r[0] * photo.width).toInt().coerceIn(0, photo.width - 1)
        val y = (r[1] * photo.height).toInt().coerceIn(0, photo.height - 1)
        val w = ((r[2] - r[0]) * photo.width).toInt().coerceIn(1, photo.width - x)
        val h = ((r[3] - r[1]) * photo.height).toInt().coerceIn(1, photo.height - y)
        return Bitmap.createBitmap(photo, x, y, w, h).asImageBitmap()
    }
    return cut(crops.cover) to cut(crops.main)
}

/** The picked clip: where each screen's window sits, the part to use and how it loops. */
@Composable
private fun VideoSection(vm: MainViewModel, v: VideoPick, profile: DeviceProfile, busy: Boolean, onChange: () -> Unit) {
    SectionTitle(stringResource(R.string.video_title))
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.video_info, v.width, v.height, v.durationMs / 1000f),
                style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChange, enabled = !busy) { Text(stringResource(R.string.add_change), color = FmColors.Aurora) }
        }
        Spacer(Modifier.height(10.dp))
        VideoWindows(v, profile)
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
    Text(stringResource(R.string.add_video_motion), style = MaterialTheme.typography.bodySmall, color = FmColors.TextMuted, modifier = Modifier.padding(top = 10.dp))
}

/** The clip's first frame with the two screens' windows outlined, so the framing is clear. */
@Composable
private fun VideoWindows(v: VideoPick, profile: DeviceProfile) {
    val crops = remember(v.width, v.height, profile.id) { VideoGeometry.crops(profile, v.width, v.height) }
    val aspect = (v.width.toFloat() / v.height).coerceIn(0.4f, 2.4f)
    Box(Modifier.fillMaxWidth().widthIn(max = 520.dp).aspectRatio(aspect).clip(RoundedCornerShape(16.dp)).background(FmColors.Glass)) {
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
private fun JobProgress(vm: MainViewModel, job: VideoJob?) {
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
