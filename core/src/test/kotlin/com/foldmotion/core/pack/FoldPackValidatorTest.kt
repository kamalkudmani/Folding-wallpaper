package com.foldmotion.core.pack

import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.motion.AmbientSpec
import com.foldmotion.core.motion.AmbientSystem
import com.foldmotion.core.motion.HaloSpec
import com.foldmotion.core.motion.MotionComposer
import com.foldmotion.core.motion.ParticleBurst
import com.foldmotion.core.motion.RevealSpec
import com.foldmotion.core.motion.SpringSpec
import com.foldmotion.core.motion.MotionIntensity
import com.foldmotion.core.motion.MotionLayer
import com.foldmotion.core.motion.MotionSpec
import com.foldmotion.core.motion.MotionTrack
import com.foldmotion.core.motion.TrackPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FoldPackValidatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Minimal lossless WebP header with the given canvas size; enough for header parsing. */
    private fun fakeWebp(w: Int, h: Int): ByteArray {
        val bytes = ByteArray(40)
        fun put(o: Int, s: String) = s.forEachIndexed { i, c -> bytes[o + i] = c.code.toByte() }
        put(0, "RIFF"); put(8, "WEBP"); put(12, "VP8L")
        bytes[16] = 20
        bytes[20] = 0x2F
        val bits = ((w - 1) and 0x3FFF) or (((h - 1) and 0x3FFF) shl 14)
        bytes[21] = (bits and 0xFF).toByte()
        bytes[22] = ((bits ushr 8) and 0xFF).toByte()
        bytes[23] = ((bits ushr 16) and 0xFF).toByte()
        bytes[24] = ((bits ushr 24) and 0xFF).toByte()
        return bytes
    }

    private fun layer(scene: String, id: String, w: Int, h: Int, overscan: Float, depth: Float, opaque: Boolean) =
        LayerEntry(id, "$scene/$id.webp", w, h, listOf(-overscan, -overscan, 1 + overscan, 1 + overscan), depth, opaque)

    private var packCounter = 0

    /** Writes a complete, valid pack and returns its directory. */
    private fun buildPack(
        mutateManifest: (FoldPackManifest) -> FoldPackManifest = { it },
        openSpec: MotionSpec? = null,
        ambient: AmbientSpec? = null,
    ): File {
        val dir = tmp.newFolder("pack${packCounter++}")
        val cover = SceneEntry(1080, 2520, listOf(0.5f, 0.4f), layers = listOf(
            layer("cover", "far", 600, 1400, 0.04f, 0f, true),
            layer("cover", "near", 600, 1400, 0.09f, 1f, false),
        ))
        val main = SceneEntry(1968, 2184, listOf(0.7f, 0.4f), layers = listOf(
            layer("main", "far", 1100, 1200, 0.04f, 0f, true),
            layer("main", "near", 1100, 1200, 0.09f, 1f, false),
        ))
        for (scene in listOf(cover, main)) for (l in scene.layers) {
            File(dir, l.path).apply { parentFile.mkdirs(); writeBytes(fakeWebp(l.width, l.height)) }
        }
        File(dir, "previews").mkdirs()
        File(dir, "previews/cover.webp").writeBytes(fakeWebp(270, 630))
        File(dir, "previews/main.webp").writeBytes(fakeWebp(492, 546))

        fun ml(s: SceneEntry) = s.layers.map { MotionLayer(it.id, it.depth, it.left, it.top, it.right, it.bottom, it.opaque) }
        val open = openSpec ?: MotionComposer.open(MotionIntensity.LOW, main.focusX, main.focusY, ml(main))
        val close = MotionComposer.close(MotionIntensity.LOW, cover.focusX, cover.focusY, ml(cover))
        File(dir, "motion").mkdirs()
        File(dir, "motion/open.json").writeText(CoreInfo.json.encodeToString(MotionSpec.serializer(), open))
        File(dir, "motion/close.json").writeText(CoreInfo.json.encodeToString(MotionSpec.serializer(), close))

        if (ambient != null) File(dir, AmbientSpec.FILE).writeText(CoreInfo.json.encodeToString(AmbientSpec.serializer(), ambient))
        val manifest = mutateManifest(
            FoldPackManifest(
                id = "pack-test-1",
                title = LocalizedText("اختبار", "Test"),
                createdAt = "2026-11-13T00:00:00Z",
                creationSource = FoldPackManifest.SOURCE_LOCAL,
                profileIds = listOf(DeviceProfileRegistry.FOLD7_ID),
                scenes = Scenes(cover, main),
                transitions = Transitions(
                    TransitionRef("motion/open.json", open.durationMs),
                    TransitionRef("motion/close.json", close.durationMs),
                ),
                previews = Previews("previews/cover.webp", "previews/main.webp"),
                ambient = if (ambient != null) AmbientSpec.FILE else null,
                colors = PackColors("#112233", "#445566", "#778899", "#000000"),
            ),
        )
        File(dir, FoldPackManifest.FILE_NAME).writeText(CoreInfo.json.encodeToString(FoldPackManifest.serializer(), manifest))
        Checksums.write(dir)
        return dir
    }

    private val validator = FoldPackValidator()

    @Test
    fun `a complete pack passes`() {
        val report = validator.validate(buildPack(), DeviceProfileRegistry.FOLD7_ID)
        assertTrue(report.issues.toString(), report.passed)
        assertEquals("PASSED", report.status)
    }

    private val goodAmbient = AmbientSpec(
        halo = HaloSpec(0.4f, 0.36f, 0.17f, "#FFF0C0"),
        systems = listOf(
            AmbientSystem(AmbientSystem.TWINKLE, 70, listOf("#FFFFFF"), listOf(-1f, -0.1f, 1f, 0.6f), 0.001f, 0.004f),
            AmbientSystem(AmbientSystem.FIREFLY, 18, listOf("#FFE38A"), listOf(-1f, 0.7f, 1f, 1f), 0.002f, 0.004f, after = "near"),
        ),
    )

    @Test
    fun `v2 pack with spring motion and living motion passes`() {
        val spring = MotionSpec(
            schemaVersion = 2, kind = MotionSpec.KIND_OPEN, durationMs = 1100, pivot = listOf(0.7f, 0.4f),
            tracks = listOf(MotionTrack("layer:far", TrackPose(scale = 1.06f))),
            spring = SpringSpec.settlingIn(1100, 0.78f),
            reveal = RevealSpec(RevealSpec.UNFOLD, listOf(0.45f, 0f, 0.93f, 1f)),
            particles = listOf(ParticleBurst(ParticleBurst.BURST, 100, listOf("#FFFFFF"), listOf(0.7f, 0.4f), 0.7f, 0.003f, 0.01f)),
        )
        val report = validator.validate(buildPack(openSpec = spring, ambient = goodAmbient))
        assertTrue(report.issues.toString(), report.passed)
    }

    @Test
    fun `v3 fold styles with staggered tracks pass and malformed ones are rejected`() {
        val base = MotionSpec(
            schemaVersion = 3, kind = MotionSpec.KIND_OPEN, durationMs = 1300, pivot = listOf(0.7f, 0.4f),
            tracks = listOf(
                MotionTrack("layer:far", TrackPose(scale = 1.05f), span = 0.9f),
                MotionTrack("layer:near", TrackPose(ty = 0.4f, alpha = 0.2f), delay = 0.15f, span = 0.8f),
            ),
            spring = SpringSpec.settlingIn(1300, 0.7f),
            particles = listOf(ParticleBurst(ParticleBurst.RISE, 80, listOf("#FFD9A0"), listOf(0.5f, 1.02f), 0.55f, 0.003f, 0.008f)),
            style = "RISE",
        )
        fun codes(spec: MotionSpec) = validator.validate(buildPack(openSpec = spec, ambient = goodAmbient)).codes
        assertTrue(codes(base).isEmpty())
        for (reveal in listOf(
            RevealSpec(RevealSpec.BOOK, listOf(0.5f, 104f)),
            RevealSpec(RevealSpec.TILES, listOf(6f, 7f, 0.7f, 0.4f, 0f)),
            RevealSpec(RevealSpec.SWEEP, listOf(22f)),
        )) assertTrue(reveal.type, codes(base.copy(reveal = reveal)).isEmpty())

        // A track that would still be moving after the transition ends.
        val late = base.copy(tracks = base.tracks + MotionTrack("layer:mid", TrackPose(ty = 0.2f), delay = 0.5f, span = 0.8f))
        assertTrue(codes(late).contains(ValidationCode.INVALID_MOTION))
        assertTrue(codes(base.copy(style = "AUTO")).contains(ValidationCode.INVALID_MOTION))
        assertTrue(codes(base.copy(style = "SPIN")).contains(ValidationCode.INVALID_MOTION))
        assertTrue(codes(base.copy(reveal = RevealSpec(RevealSpec.TILES, listOf(60f, 7f, 0.5f, 0.5f, 0f)))).contains(ValidationCode.INVALID_MOTION))
        assertTrue(codes(base.copy(reveal = RevealSpec(RevealSpec.BOOK, listOf(0.5f, 400f)))).contains(ValidationCode.INVALID_MOTION))
        assertTrue(codes(base.copy(particles = listOf(base.particles[0].copy(mode = "LASER")))).contains(ValidationCode.INVALID_MOTION))
    }

    @Test
    fun `living motion out of bounds is rejected`() {
        val tooMany = goodAmbient.copy(systems = goodAmbient.systems.map { it.copy(count = 5000) })
        assertTrue(validator.validate(buildPack(ambient = tooMany)).codes.contains(ValidationCode.INVALID_MOTION))
        val badType = goodAmbient.copy(systems = listOf(goodAmbient.systems[0].copy(type = "EXPLODE")))
        assertTrue(validator.validate(buildPack(ambient = badType)).codes.contains(ValidationCode.INVALID_MOTION))
    }

    @Test
    fun `spring overshoot that would expose the edge is caught`() {
        // Starts zoomed in a little and rests below 1 is fine; a strong bounce past rest at scale
        // 0.97 would open a gap at the far layer's 4% overscan.
        val bouncy = MotionSpec(
            schemaVersion = 2, kind = MotionSpec.KIND_OPEN, durationMs = 1100, pivot = listOf(0.5f, 0.5f),
            tracks = listOf(MotionTrack("layer:far", TrackPose(scale = 1.9f))),
            spring = SpringSpec.settlingIn(1100, 0.5f),
        )
        assertTrue(validator.validate(buildPack(openSpec = bouncy)).codes.contains(ValidationCode.MOTION_EXPOSES_EDGE))
    }

    @Test
    fun `missing main scene is named precisely`() {
        val dir = buildPack()
        val m = File(dir, "manifest.json")
        val obj = CoreInfo.json.parseToJsonElement(m.readText()).toString()
            .replace("\"main\":{\"baseWidth\"", "\"mainX\":{\"baseWidth\"")
        m.writeText(obj)
        Checksums.write(dir)
        assertEquals(listOf(ValidationCode.MISSING_MAIN_SCENE), validator.validate(dir).codes)
    }

    @Test
    fun `missing open motion file is named precisely`() {
        val dir = buildPack()
        File(dir, "motion/open.json").delete()
        Checksums.write(dir)
        assertTrue(validator.validate(dir).codes.contains(ValidationCode.MISSING_OPEN_MOTION))
    }

    @Test
    fun `tampered file fails the checksum`() {
        val dir = buildPack()
        File(dir, "main/near.webp").appendBytes(byteArrayOf(1, 2, 3))
        assertTrue(validator.validate(dir).codes.contains(ValidationCode.CHECKSUM_MISMATCH))
    }

    @Test
    fun `unsupported profile is rejected`() {
        val dir = buildPack(mutateManifest = { it.copy(profileIds = listOf("pixel_fold")) })
        assertTrue(validator.validate(dir).codes.contains(ValidationCode.UNSUPPORTED_PROFILE))
        val other = buildPackIn("pack2") { it.copy(profileIds = listOf(DeviceProfileRegistry.FOLD6_ID)) }
        assertTrue(validator.validate(other, DeviceProfileRegistry.FOLD7_ID).codes.contains(ValidationCode.UNSUPPORTED_PROFILE))
    }

    private fun buildPackIn(name: String, mutate: (FoldPackManifest) -> FoldPackManifest): File {
        val dir = buildPack(mutate)
        val target = File(tmp.root, name)
        dir.renameTo(target)
        return target
    }

    @Test
    fun `static only packs are rejected`() {
        val dir = buildPack(mutateManifest = { it.copy(isStaticOnly = true) })
        assertTrue(validator.validate(dir).codes.contains(ValidationCode.STATIC_ONLY))
    }

    @Test
    fun `future schema is refused with a clear reason`() {
        val dir = buildPack(mutateManifest = { it.copy(schemaVersion = CoreInfo.SCHEMA_VERSION + 1) })
        assertEquals(listOf(ValidationCode.SCHEMA_TOO_NEW), validator.validate(dir).codes)
    }

    @Test
    fun `renderer range is enforced`() {
        val dir = buildPack(mutateManifest = { it.copy(rendererVersion = VersionRange(CoreInfo.RENDERER_VERSION + 1, 9)) })
        assertTrue(validator.validate(dir).codes.contains(ValidationCode.RENDERER_TOO_OLD))
    }

    @Test
    fun `declared size must match the real image`() {
        val dir = buildPack()
        File(dir, "cover/far.webp").writeBytes(fakeWebp(10, 10))
        Checksums.write(dir)
        assertTrue(validator.validate(dir).codes.contains(ValidationCode.INVALID_DIMENSIONS))
    }

    @Test
    fun `a jpeg renamed to webp is caught`() {
        val dir = buildPack()
        File(dir, "cover/near.webp").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(40))
        Checksums.write(dir)
        assertTrue(validator.validate(dir).codes.contains(ValidationCode.UNSUPPORTED_MEDIA_TYPE))
    }

    @Test
    fun `motion that would expose the background is rejected`() {
        val bad = MotionSpec(
            kind = MotionSpec.KIND_OPEN, durationMs = 450, pivot = listOf(0.5f, 0.5f),
            tracks = listOf(MotionTrack("layer:far", from = TrackPose(scale = 0.8f))),
        )
        val dir = buildPack(openSpec = bad)
        assertTrue(validator.validate(dir).codes.contains(ValidationCode.MOTION_EXPOSES_EDGE))
    }

    @Test
    fun `motion duration outside 250 to 650 ms is rejected`() {
        val long = MotionSpec(kind = MotionSpec.KIND_OPEN, durationMs = 2000, tracks = listOf(MotionTrack("overlay:dim", TrackPose(alpha = 0.3f), TrackPose.HIDDEN)))
        val dir = buildPack(openSpec = long, mutateManifest = { it })
        assertTrue(validator.validate(dir).codes.contains(ValidationCode.MOTION_DURATION_OUT_OF_RANGE))
    }

    @Test
    fun `extra unreferenced file is rejected`() {
        val dir = buildPack()
        File(dir, "extra.json").writeText("{}")
        Checksums.write(dir)
        assertTrue(validator.validate(dir).codes.contains(ValidationCode.UNLISTED_FILE))
    }

    @Test
    fun `executable or foreign file types are rejected`() {
        val dir = buildPack()
        File(dir, "payload.dex").writeText("x")
        assertTrue(validator.validate(dir).codes.contains(ValidationCode.UNSUPPORTED_MEDIA_TYPE))
    }

    @Test
    fun `archive round trip keeps the pack valid`() {
        val dir = buildPack()
        val bytes = ByteArrayOutputStream().also { FoldPackArchive.pack(dir, it) }.toByteArray()
        val out = tmp.newFolder("unpacked")
        FoldPackArchive.unpack(ByteArrayInputStream(bytes), out)
        assertTrue(validator.validate(out).passed)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun expectRejected(bytes: ByteArray, code: ValidationCode, limits: PackLimits = PackLimits()) {
        try {
            FoldPackArchive.unpack(ByteArrayInputStream(bytes), tmp.newFolder(), limits)
            fail("expected $code")
        } catch (e: PackArchiveException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun `zip slip is blocked`() {
        expectRejected(zipOf("../../evil.json" to "{}".toByteArray()), ValidationCode.UNSAFE_PATH)
        expectRejected(zipOf("/abs/evil.json" to "{}".toByteArray()), ValidationCode.UNSAFE_PATH)
        expectRejected(zipOf("a\\..\\b.json" to "{}".toByteArray()), ValidationCode.UNSAFE_PATH)
    }

    @Test
    fun `zip bomb is stopped while streaming`() {
        val huge = ByteArray(2 * 1024 * 1024) // compresses to almost nothing
        expectRejected(zipOf("a.webp" to huge), ValidationCode.FILE_TOO_LARGE, PackLimits(maxFileBytes = 1024 * 1024))
        expectRejected(
            zipOf("a.webp" to huge, "b.webp" to huge),
            ValidationCode.PACK_TOO_LARGE,
            PackLimits(maxFileBytes = 3L * 1024 * 1024, maxTotalBytes = 3L * 1024 * 1024),
        )
    }

    @Test
    fun `plain images and videos are refused as not a foldpack`() {
        expectRejected(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(20), ValidationCode.NOT_A_FOLDPACK)
        expectRejected(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(20), ValidationCode.NOT_A_FOLDPACK)
        expectRejected(byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70) + ByteArray(20), ValidationCode.NOT_A_FOLDPACK)
    }

    @Test
    fun `executables inside the archive are refused`() {
        expectRejected(zipOf("classes.dex" to byteArrayOf(1)), ValidationCode.UNSUPPORTED_MEDIA_TYPE)
        expectRejected(zipOf("lib.so" to byteArrayOf(1)), ValidationCode.UNSUPPORTED_MEDIA_TYPE)
    }

    @Test
    fun `safe path rules`() {
        assertTrue(PackPaths.isSafe("main/far.webp"))
        assertFalse(PackPaths.isSafe("main/../far.webp"))
        assertFalse(PackPaths.isSafe("main//far.webp"))
        assertFalse(PackPaths.isSafe("C:/x.webp"))
        assertFalse(PackPaths.isSafe("a/b/c/d.webp"))
        assertFalse(PackPaths.isSafe("main/fär.webp"))
    }

    @Test
    fun `webp header parser reads lossless and rejects others`() {
        val f = tmp.newFile("x.webp")
        f.writeBytes(fakeWebp(1968, 2184))
        assertEquals(WebpInfo.Size(1968, 2184), WebpInfo.read(f))
        f.writeBytes(ByteArray(40))
        assertEquals(null, WebpInfo.read(f))
    }
}
