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
    fun load(packDir: File, kind: ScreenKind, targetLongSide: Int = 0): LoadedScene? {
        val manifest = readManifest(packDir) ?: return null
        val scene = manifest.scene(kind)
        val layers = ArrayList<Pair<LayerEntry, Bitmap>>(scene.layers.size)
        try {
            for (layer in scene.layers) {
                val file = File(packDir, layer.path)
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sampleSize(layer, scene.baseWidth, scene.baseHeight, targetLongSide)
                }
                val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw IllegalStateException("decode ${layer.path}")
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
        // from their own geometry; nothing on disk changes.
        val legacy = if (stored == null || stored.schemaVersion < 2 || storedAmbient == null) PackChoreography.upgrade(manifest) else null
        val motion = if (stored != null && stored.schemaVersion >= 2) stored else legacy?.choreography?.let { if (kind == ScreenKind.MAIN) it.open else it.close }
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
            ambient = ambient,
            world = world,
        )
    }

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
