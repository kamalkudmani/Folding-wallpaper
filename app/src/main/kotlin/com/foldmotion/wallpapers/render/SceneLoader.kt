package com.foldmotion.wallpapers.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.motion.AmbientSpec
import com.foldmotion.core.motion.MotionSpec
import com.foldmotion.core.pack.PackChoreography
import com.foldmotion.core.scene.WorldRect
import com.foldmotion.core.pack.FoldPackManifest
import com.foldmotion.core.pack.LayerEntry
import java.io.File
import kotlin.math.max

/**
 * Reads a validated pack from disk into a [LoadedScene]. Runs off the main thread.
 *
 * The pack was validated (checksums, sizes, types) before it was stored; this class still fails
 * closed on any decode problem, returning null rather than a half-built scene.
 */
object SceneLoader {

    fun readManifest(packDir: File): FoldPackManifest? = try {
        CoreInfo.json.decodeFromString(FoldPackManifest.serializer(), File(packDir, FoldPackManifest.FILE_NAME).readText())
    } catch (e: Exception) {
        null
    }

    /**
     * @param targetLongSide longest side of the surface in pixels, used to decode at a reduced
     *   sample size when the stored layer is much larger than needed (saves memory on small previews).
     */
    /**
     * @param gpu decode straight into GPU memory (Bitmap.Config.HARDWARE). The live wallpaper
     *   uses it so the first frames of a fold animation never wait for a texture upload; previews
     *   and tests, which may draw in software, do not.
     */
    fun load(packDir: File, kind: ScreenKind, targetLongSide: Int = 0, gpu: Boolean = false): LoadedScene? {
        val manifest = readManifest(packDir) ?: return null
        val scene = manifest.scene(kind)
        val video = manifest.video
        // A video pack's layer is only a poster until the first frame; it need not be full size.
        val decodeLongSide = if (video != null && (targetLongSide == 0 || targetLongSide > VIDEO_POSTER_LONG_SIDE)) VIDEO_POSTER_LONG_SIDE else targetLongSide
        val layers = ArrayList<Pair<LayerEntry, Bitmap>>(scene.layers.size)
        try {
            for (layer in scene.layers) {
                val file = File(packDir, layer.path)
                fun decode(config: Bitmap.Config): Bitmap? = BitmapFactory.decodeFile(
                    file.path,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = config
                        inSampleSize = sampleSize(layer, scene.baseWidth, scene.baseHeight, decodeLongSide)
                    },
                )
                val bitmap = (if (gpu) decode(Bitmap.Config.HARDWARE) else null)
                    ?: decode(Bitmap.Config.ARGB_8888)
                    ?: throw IllegalStateException("decode ${layer.path}")
                bitmap.prepareToDraw()
                layers += layer to bitmap
            }
        } catch (e: Throwable) {
            layers.forEach { it.second.recycle() }
            return null
        }
        val motionFile = if (kind == ScreenKind.MAIN) manifest.transitions.open.file else manifest.transitions.close.file
        val stored = try {
            CoreInfo.json.decodeFromString(MotionSpec.serializer(), File(packDir, motionFile).readText())
        } catch (e: Exception) {
            null
        }
        val storedAmbient = manifest.ambient?.let { path ->
            try {
                CoreInfo.json.decodeFromString(AmbientSpec.serializer(), File(packDir, path).readText())
            } catch (e: Exception) {
                null
            }
        }
        // Packs from before renderer 2 get the new fold choreography and living motion computed
        // from their own geometry, and this app's own packs from before fold styles get the style
        // their scene would get today; nothing on disk changes.
        val restyle = PackChoreography.wantsStyleUpgrade(manifest, stored?.style)
        val legacy = if (stored == null || stored.schemaVersion < 2 || storedAmbient == null || restyle) PackChoreography.upgrade(manifest) else null
        val motion = if (stored != null && stored.schemaVersion >= 2 && !restyle) stored else legacy?.choreography?.let { if (kind == ScreenKind.MAIN) it.open else it.close }
        val ambient = storedAmbient ?: legacy?.choreography?.ambient
        val world = scene.world?.takeIf { it.size == 4 }?.let { WorldRect(it[0], it[1], it[2], it[3]) }
            ?: legacy?.frames?.frame(kind)
            ?: PackChoreography.framesFor(manifest, DeviceProfileRegistry.byId(manifest.profileIds.firstOrNull()) ?: DeviceProfileRegistry.fold7).frame(kind)
        return LoadedScene(
            packId = manifest.id,
            kind = kind,
            baseWidth = scene.baseWidth,
            baseHeight = scene.baseHeight,
            focusX = scene.focusX,
            focusY = scene.focusY,
            layers = layers,
            entryMotion = if (manifest.isStaticOnly) null else motion,
            background = LoadedScene.backgroundOf(manifest),
            glow = LoadedScene.glowOf(manifest),
            // The video itself is the living motion; no particles drawn over it between folds.
            ambient = if (video != null) null else ambient,
            world = world,
            video = video,
            videoFile = video?.let { File(packDir, it.file) },
        )
    }

    private const val VIDEO_POSTER_LONG_SIDE = 1280

    /** Small preview image for lists, decoded at the requested width. */
    fun loadPreview(packDir: File, kind: ScreenKind, maxWidth: Int): Bitmap? {
        val manifest = readManifest(packDir) ?: return null
        val path = File(packDir, manifest.preview(kind)).path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun sampleSize(layer: LayerEntry, baseW: Int, baseH: Int, targetLongSide: Int): Int {
        if (targetLongSide <= 0) return 1
        // Width in pixels the layer occupies when the frame's long side is drawn at targetLongSide.
        val frameWidthPx = targetLongSide * baseW / max(baseW, baseH).toFloat()
        val neededWidth = (layer.right - layer.left) * frameWidthPx
        var sample = 1
        while (layer.width / (sample * 2f) >= neededWidth * 1.1f) sample *= 2
        return sample
    }
}
