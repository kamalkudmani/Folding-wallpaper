package com.foldmotion.core.pack

import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.motion.AmbientSpec
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.motion.MotionSafety
import com.foldmotion.core.motion.ParticleBurst
import com.foldmotion.core.motion.RevealSpec
import com.foldmotion.core.motion.MotionSampler
import com.foldmotion.core.motion.MotionSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Machine-readable rejection reasons. The UI maps each to a human message. */
enum class ValidationCode {
    NOT_A_FOLDPACK,
    INVALID_MANIFEST,
    WRONG_ASSET_TYPE,
    SCHEMA_TOO_NEW,
    SCHEMA_TOO_OLD,
    RENDERER_TOO_OLD,
    RENDERER_TOO_NEW,
    STATIC_ONLY,
    MISSING_COVER_SCENE,
    MISSING_MAIN_SCENE,
    MISSING_OPEN_MOTION,
    MISSING_CLOSE_MOTION,
    INVALID_MOTION,
    MOTION_DURATION_OUT_OF_RANGE,
    MOTION_EXPOSES_EDGE,
    UNSUPPORTED_PROFILE,
    FILE_MISSING,
    UNLISTED_FILE,
    UNSUPPORTED_MEDIA_TYPE,
    INVALID_DIMENSIONS,
    TOO_MANY_LAYERS,
    FILE_TOO_LARGE,
    PACK_TOO_LARGE,
    TOO_MANY_ENTRIES,
    UNSAFE_PATH,
    CHECKSUM_MISSING,
    CHECKSUM_MISMATCH,
}

data class ValidationIssue(val code: ValidationCode, val detail: String = "")

data class ValidationReport(
    val issues: List<ValidationIssue>,
    val manifest: FoldPackManifest?,
) {
    val passed: Boolean get() = issues.isEmpty() && manifest != null
    val codes: List<ValidationCode> get() = issues.map { it.code }
    val status: String get() = if (passed) "PASSED" else "FAILED"
}

/**
 * Structural and semantic validation of an unpacked FoldPack directory.
 *
 * The same class is meant to run on the device and on the server so both reject the same packs.
 * It never trusts what the manifest claims: every referenced file must exist, be listed in the
 * checksum file, hash correctly, really be WebP and have the declared pixel size.
 */
private fun isHex(s: String): Boolean = s.length == 7 && s[0] == '#' && s.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

class FoldPackValidator(
    private val limits: PackLimits = PackLimits(),
    private val rendererVersion: Int = CoreInfo.RENDERER_VERSION,
    private val knownProfileIds: Set<String> = DeviceProfileRegistry.knownIds,
) {

    /**
     * @param requiredProfileId when set, the pack must list this device profile.
     */
    fun validate(packDir: File, requiredProfileId: String? = null): ValidationReport {
        val issues = ArrayList<ValidationIssue>()
        fun fail(code: ValidationCode, detail: String = "") {
            issues += ValidationIssue(code, detail)
        }

        val manifestFile = File(packDir, FoldPackManifest.FILE_NAME)
        if (!packDir.isDirectory || !manifestFile.isFile) {
            return ValidationReport(listOf(ValidationIssue(ValidationCode.NOT_A_FOLDPACK, "manifest.json not found")), null)
        }

        val files = Checksums.listFiles(packDir)
        if (files.size > limits.maxEntries) fail(ValidationCode.TOO_MANY_ENTRIES, "${files.size}")
        var total = 0L
        for (path in files) {
            if (!PackPaths.isSafe(path, limits)) fail(ValidationCode.UNSAFE_PATH, path)
            if (PackPaths.extension(path) !in PackPaths.ALLOWED_EXTENSIONS) fail(ValidationCode.UNSUPPORTED_MEDIA_TYPE, path)
            val size = File(packDir, path).length()
            if (size > limits.maxFileBytes) fail(ValidationCode.FILE_TOO_LARGE, path)
            total += size
        }
        if (total > limits.maxTotalBytes) fail(ValidationCode.PACK_TOO_LARGE, "$total")
        if (issues.isNotEmpty()) return ValidationReport(issues, null)

        val root: JsonObject = try {
            CoreInfo.json.parseToJsonElement(manifestFile.readText()).jsonObject
        } catch (e: Exception) {
            return ValidationReport(listOf(ValidationIssue(ValidationCode.INVALID_MANIFEST, e.message ?: "")), null)
        }

        // Presence checks first, so the creator gets a precise reason instead of a parser error.
        val schema = root["schemaVersion"]?.toString()?.toIntOrNull()
        when {
            schema == null -> fail(ValidationCode.INVALID_MANIFEST, "schemaVersion")
            schema > CoreInfo.SCHEMA_VERSION -> fail(ValidationCode.SCHEMA_TOO_NEW, "$schema")
            schema < CoreInfo.MIN_SCHEMA_VERSION -> fail(ValidationCode.SCHEMA_TOO_OLD, "$schema")
        }
        if (issues.isNotEmpty()) return ValidationReport(issues, null)

        val scenes = root["scenes"] as? JsonObject
        if (scenes?.get("cover") !is JsonObject) fail(ValidationCode.MISSING_COVER_SCENE)
        if (scenes?.get("main") !is JsonObject) fail(ValidationCode.MISSING_MAIN_SCENE)
        val transitions = root["transitions"] as? JsonObject
        if (transitions?.get("open") !is JsonObject) fail(ValidationCode.MISSING_OPEN_MOTION)
        if (transitions?.get("close") !is JsonObject) fail(ValidationCode.MISSING_CLOSE_MOTION)
        if (issues.isNotEmpty()) return ValidationReport(issues, null)

        val manifest: FoldPackManifest = try {
            CoreInfo.json.decodeFromJsonElement(root)
        } catch (e: Exception) {
            return ValidationReport(listOf(ValidationIssue(ValidationCode.INVALID_MANIFEST, e.message ?: "")), null)
        }

        if (manifest.assetType != CoreInfo.ASSET_TYPE) fail(ValidationCode.WRONG_ASSET_TYPE, manifest.assetType)
        if (manifest.isStaticOnly) fail(ValidationCode.STATIC_ONLY)
        if (manifest.rendererVersion.min > rendererVersion) fail(ValidationCode.RENDERER_TOO_OLD, "needs ${manifest.rendererVersion.min}")
        if (manifest.rendererVersion.max < CoreInfo.MIN_RENDERER_VERSION) fail(ValidationCode.RENDERER_TOO_NEW, "max ${manifest.rendererVersion.max}")
        if (!PackPaths.isSafe(manifest.id, limits) || manifest.id.contains('/')) fail(ValidationCode.INVALID_MANIFEST, "id")

        if (manifest.profileIds.isEmpty() || manifest.profileIds.none { it in knownProfileIds }) {
            fail(ValidationCode.UNSUPPORTED_PROFILE, manifest.profileIds.joinToString())
        } else if (requiredProfileId != null && requiredProfileId !in manifest.profileIds) {
            fail(ValidationCode.UNSUPPORTED_PROFILE, requiredProfileId)
        }

        val referenced = LinkedHashSet<String>()
        referenced += FoldPackManifest.FILE_NAME

        fun checkImage(path: String, expectWidth: Int?, expectHeight: Int?) {
            referenced += path
            if (!PackPaths.isSafe(path, limits)) return fail(ValidationCode.UNSAFE_PATH, path)
            val file = File(packDir, path)
            if (!file.isFile) return fail(ValidationCode.FILE_MISSING, path)
            val size = WebpInfo.read(file) ?: return fail(ValidationCode.UNSUPPORTED_MEDIA_TYPE, path)
            if (size.width > limits.maxImageDimension || size.height > limits.maxImageDimension) {
                fail(ValidationCode.INVALID_DIMENSIONS, "$path ${size.width}x${size.height}")
            } else if (expectWidth != null && (size.width != expectWidth || size.height != expectHeight)) {
                fail(ValidationCode.INVALID_DIMENSIONS, "$path declared ${expectWidth}x$expectHeight, is ${size.width}x${size.height}")
            }
        }

        fun checkScene(name: String, scene: SceneEntry) {
            if (scene.baseWidth <= 0 || scene.baseHeight <= 0 || scene.focus.size != 2) {
                fail(ValidationCode.INVALID_MANIFEST, "$name geometry")
            }
            if (scene.layers.isEmpty()) {
                fail(if (name == "main") ValidationCode.MISSING_MAIN_SCENE else ValidationCode.MISSING_COVER_SCENE, "no layers")
                return
            }
            if (scene.layers.size > limits.maxLayersPerScene) fail(ValidationCode.TOO_MANY_LAYERS, name)
            if (scene.layers.map { it.id }.toSet().size != scene.layers.size) fail(ValidationCode.INVALID_MANIFEST, "$name duplicate layer id")
            val first = scene.layers.first()
            if (!first.opaque || !MotionSafety.covers(com.foldmotion.core.motion.TrackPose.REST, 0.5f, 0.5f, first.left, first.top, first.right, first.bottom)) {
                fail(ValidationCode.INVALID_MANIFEST, "$name first layer must be opaque and cover the frame")
            }
            for (layer in scene.layers) {
                if (layer.rect.size != 4 || layer.right <= layer.left || layer.bottom <= layer.top) {
                    fail(ValidationCode.INVALID_MANIFEST, "$name/${layer.id} rect")
                }
                checkImage(layer.path, layer.width, layer.height)
            }
        }

        checkScene("cover", manifest.scenes.cover)
        checkScene("main", manifest.scenes.main)
        checkImage(manifest.previews.cover, null, null)
        checkImage(manifest.previews.main, null, null)

        fun checkMotion(ref: TransitionRef, expectedKind: String, scene: SceneEntry, missing: ValidationCode) {
            referenced += ref.file
            if (!PackPaths.isSafe(ref.file, limits)) return fail(ValidationCode.UNSAFE_PATH, ref.file)
            val file = File(packDir, ref.file)
            if (!file.isFile) return fail(missing, ref.file)
            val spec: MotionSpec = try {
                CoreInfo.json.decodeFromString(MotionSpec.serializer(), file.readText())
            } catch (e: Exception) {
                return fail(ValidationCode.INVALID_MOTION, "${ref.file}: ${e.message}")
            }
            if (spec.kind != expectedKind || spec.pivot.size != 2 || spec.tracks.isEmpty()) {
                fail(ValidationCode.INVALID_MOTION, ref.file)
            }
            if (spec.durationMs !in MotionSpec.MIN_DURATION_MS..MotionSpec.MAX_DURATION_MS || spec.durationMs != ref.durationMs) {
                fail(ValidationCode.MOTION_DURATION_OUT_OF_RANGE, "${ref.file}: ${spec.durationMs}ms")
            }
            val e = spec.easing
            if (e.x1 !in 0f..1f || e.x2 !in 0f..1f || e.y1 !in 0f..1f || e.y2 !in 0f..1f) {
                fail(ValidationCode.INVALID_MOTION, "${ref.file}: easing overshoots")
            }
            spec.spring?.let { sp ->
                if (sp.dampingRatio !in 0.5f..1f || sp.stiffness !in 5f..2000f) fail(ValidationCode.INVALID_MOTION, "${ref.file}: spring")
            }
            spec.style?.let { if (FoldStyle.parse(it) == null || it == FoldStyle.AUTO.name) fail(ValidationCode.INVALID_MOTION, "${ref.file}: style") }
            for (t in spec.tracks) {
                if (t.delay !in 0f..0.9f || t.span !in 0.1f..1f || t.delay + t.span > 1.0001f) fail(ValidationCode.INVALID_MOTION, "${ref.file}: ${t.target} timing")
            }
            spec.reveal?.let { r ->
                if (!r.isWellFormed() || r.end !in 0.05f..1f || r.start !in 0f..r.end || r.softness !in 0f..0.5f || r.shade !in 0f..1f || r.edgeGlow !in 0f..1f) {
                    fail(ValidationCode.INVALID_MOTION, "${ref.file}: reveal")
                }
            }
            if (spec.particles.size > MotionSpec.MAX_BURSTS) fail(ValidationCode.INVALID_MOTION, "${ref.file}: too many bursts")
            for (b in spec.particles) {
                val ok = b.mode in ParticleBurst.MODES &&
                    b.count in 1..MotionSpec.MAX_PARTICLES_PER_BURST && b.colors.isNotEmpty() && b.colors.all(::isHex) &&
                    b.origin.size == 2 && b.spread in 0f..1.5f && b.sizeMin in 0f..0.08f && b.sizeMax in b.sizeMin..0.08f &&
                    b.start in 0f..1f && b.end in b.start..1f
                if (!ok) fail(ValidationCode.INVALID_MOTION, "${ref.file}: particles")
            }
            // Springs overshoot: check coverage at the furthest the layers actually travel, not
            // only at the two ends. Coverage is linear in the pose, so the extremes suffice.
            val (lo, hi) = spec.easeRange()
            for (layer in scene.layers.filter { it.opaque }) {
                val track = spec.tracks.firstOrNull { it.target == MotionSampler.layerTarget(layer.id) } ?: continue
                for (pose in listOf(track.from.lerp(track.to, lo), track.from.lerp(track.to, hi), track.to)) {
                    if (!MotionSafety.covers(pose, spec.pivotX, spec.pivotY, layer.left, layer.top, layer.right, layer.bottom)) {
                        fail(ValidationCode.MOTION_EXPOSES_EDGE, "${ref.file}: ${layer.id}")
                    }
                }
            }
        }

        fun checkWorld(name: String, scene: SceneEntry) {
            val w = scene.world ?: return
            if (w.size != 4 || w[2] <= w[0] || w[3] <= w[1] || w.any { it !in -10f..10f }) fail(ValidationCode.INVALID_MANIFEST, "$name world")
        }
        checkWorld("cover", manifest.scenes.cover)
        checkWorld("main", manifest.scenes.main)

        manifest.ambient?.let { path ->
            referenced += path
            if (!PackPaths.isSafe(path, limits)) return@let fail(ValidationCode.UNSAFE_PATH, path)
            val file = File(packDir, path)
            if (!file.isFile) return@let fail(ValidationCode.FILE_MISSING, path)
            val amb: AmbientSpec = try {
                CoreInfo.json.decodeFromString(AmbientSpec.serializer(), file.readText())
            } catch (e: Exception) {
                return@let fail(ValidationCode.INVALID_MOTION, "$path: ${e.message}")
            }
            val total = amb.systems.sumOf { it.count }
            val ok = amb.fps in AmbientSpec.FPS_RANGE && amb.sway in 0f..AmbientSpec.MAX_SWAY && amb.tilt in 0f..AmbientSpec.MAX_TILT &&
                amb.swayPeriodMs in 1_000..120_000 && amb.systems.size <= AmbientSpec.MAX_SYSTEMS && total <= AmbientSpec.MAX_TOTAL &&
                amb.systems.all { sys ->
                    sys.type in AmbientSpec.TYPES && sys.count in 1..AmbientSpec.MAX_COUNT && sys.region.size == 4 &&
                        sys.right > sys.left && sys.bottom > sys.top && sys.colors.isNotEmpty() && sys.colors.all(::isHex) &&
                        sys.sizeMin in 0f..0.5f && sys.sizeMax in sys.sizeMin..0.5f && sys.speed in 0f..5f &&
                        sys.periodMs in 100..120_000 && sys.alpha in 0f..1f && sys.after in AmbientSpec.LAYERS && sys.direction.size == 2
                } &&
                (amb.halo?.let { h -> isHex(h.color) && h.radius in 0f..2f && h.alphaMax in h.alphaMin..1f && h.periodMs in 500..120_000 } ?: true)
            if (!ok) fail(ValidationCode.INVALID_MOTION, "$path: out of bounds")
        }

        checkMotion(manifest.transitions.open, MotionSpec.KIND_OPEN, manifest.scenes.main, ValidationCode.MISSING_OPEN_MOTION)
        checkMotion(manifest.transitions.close, MotionSpec.KIND_CLOSE, manifest.scenes.cover, ValidationCode.MISSING_CLOSE_MOTION)

        val checksumFile = File(packDir, PackPaths.CHECKSUMS)
        if (!checksumFile.isFile) {
            fail(ValidationCode.CHECKSUM_MISSING)
        } else {
            val expected = Checksums.parse(checksumFile.readText())
            for (path in files) {
                if (path == PackPaths.CHECKSUMS) continue
                val want = expected[path]
                when {
                    want == null -> fail(ValidationCode.UNLISTED_FILE, path)
                    path !in referenced -> fail(ValidationCode.UNLISTED_FILE, "$path is not referenced by the manifest")
                    Sha256.of(File(packDir, path)) != want -> fail(ValidationCode.CHECKSUM_MISMATCH, path)
                }
            }
            for (path in expected.keys) {
                if (path !in files) fail(ValidationCode.FILE_MISSING, path)
            }
        }

        return ValidationReport(issues.distinct(), manifest)
    }
}
