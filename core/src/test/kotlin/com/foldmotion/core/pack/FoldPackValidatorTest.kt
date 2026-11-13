package com.foldmotion.core.pack

import com.foldmotion.core.CoreInfo
import com.foldmotion.core.device.DeviceProfileRegistry
import com.foldmotion.core.motion.MotionComposer
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
