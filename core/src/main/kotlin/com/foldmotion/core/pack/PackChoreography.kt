package com.foldmotion.core.pack

import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.motion.Choreography
import com.foldmotion.core.motion.ChoreoInput
import com.foldmotion.core.motion.ChoreographyComposer
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.motion.MotionLayer
import com.foldmotion.core.scene.Celestial
import com.foldmotion.core.scene.SceneFamily
import com.foldmotion.core.scene.SceneFrames
import com.foldmotion.core.scene.SceneGeometry
import com.foldmotion.core.scene.TimeOfDay
import com.foldmotion.core.scene.WorldRect

/**
 * Gives packs written before renderer 2 (a plain zoom, no ambient motion) the new choreography,
 * computed in memory from what their manifest already records: geometry, focus, layers, family,
 * time of day and colours. Nothing on disk changes, so checksums and signatures stay valid.
 */
object PackChoreography {

    data class Resolved(val choreography: Choreography, val frames: SceneFrames)

    fun needsUpgrade(manifest: FoldPackManifest): Boolean =
        manifest.ambient == null || manifest.rendererVersion.min < 2

    /**
     * Packs this app generated before fold styles existed (0.3 and older) all moved alike. Their
     * motion is recomputed in memory with the style their scene would get today; packs from
     * anywhere else keep exactly the motion they declare.
     */
    fun wantsStyleUpgrade(manifest: FoldPackManifest, storedStyle: String?): Boolean =
        manifest.creationSource == FoldPackManifest.SOURCE_LOCAL && manifest.motionStyle == null && storedStyle == null

    /** The style a pack plays, or what AUTO would pick for it when it records none. */
    fun styleOf(manifest: FoldPackManifest): FoldStyle =
        FoldStyle.parse(manifest.motionStyle)?.takeIf { it != FoldStyle.AUTO }
            ?: FoldStyle.resolve(FoldStyle.AUTO, familyOf(manifest), seedOf(manifest))

    fun upgrade(manifest: FoldPackManifest, style: FoldStyle = FoldStyle.AUTO): Resolved {
        val profile = DeviceProfileRegistry.byId(manifest.profileIds.firstOrNull()) ?: DeviceProfileRegistry.fold7
        val frames = framesFor(manifest, profile)
        val family = familyOf(manifest)
        val time = timeOf(manifest)
        val celestial = when {
            family == SceneFamily.SPACE || family == SceneFamily.ABSTRACT -> Celestial.NONE
            time == TimeOfDay.NIGHT -> Celestial.FULL_MOON
            else -> Celestial.SUN
        }
        fun layers(scene: SceneEntry) = scene.layers.map { MotionLayer(it.id, it.depth, it.left, it.top, it.right, it.bottom, it.opaque) }
        val input = ChoreoInput(
            family = family,
            time = time,
            celestial = celestial,
            intensity = manifest.motionIntensity,
            frames = frames,
            mainLayers = layers(manifest.scenes.main),
            coverLayers = layers(manifest.scenes.cover),
            glow = parseHex(manifest.colors.primary, 0xFFFFFFFF.toInt()),
            accent = parseHex(manifest.colors.secondary, 0xFF19B5B0.toInt()),
            seed = seedOf(manifest),
        )
        return Resolved(ChoreographyComposer.compose(input, style), frames)
    }

    /** Recovers the world windows from the profile and the recorded focus of the main scene. */
    fun framesFor(manifest: FoldPackManifest, profile: DeviceProfile): SceneFrames {
        manifest.scenes.main.world?.let { m ->
            manifest.scenes.cover.world?.let { c ->
                val mw = WorldRect(m[0], m[1], m[2], m[3])
                val cw = WorldRect(c[0], c[1], c[2], c[3])
                val main = manifest.scenes.main
                return SceneFrames(cw, mw, mw.left + main.focusX * mw.width, mw.top + main.focusY * mw.height)
            }
        }
        val mainRect = SceneGeometry.frames(profile, 0f, SceneGeometry.CENTER_Y).main
        val main = manifest.scenes.main
        val fx = mainRect.left + main.focusX * mainRect.width
        val fy = mainRect.top + main.focusY * mainRect.height
        return SceneGeometry.frames(profile, fx, fy)
    }

    private fun seedOf(m: FoldPackManifest): Long = m.generation?.seed ?: m.id.hashCode().toLong()

    private fun familyOf(m: FoldPackManifest): SceneFamily {
        val name = m.generation?.family ?: m.tags.firstOrNull()
        return SceneFamily.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SceneFamily.MOUNTAINS
    }

    private fun timeOf(m: FoldPackManifest): TimeOfDay =
        TimeOfDay.entries.firstOrNull { t -> m.tags.any { it.equals(t.name, ignoreCase = true) } } ?: TimeOfDay.NIGHT

    fun parseHex(hex: String, fallback: Int): Int {
        if (hex.length != 7 || hex[0] != '#') return fallback
        return hex.substring(1).toIntOrNull(16)?.let { it or (0xFF shl 24) } ?: fallback
    }
}
