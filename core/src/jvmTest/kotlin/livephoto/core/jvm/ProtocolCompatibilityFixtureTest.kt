package livephoto.core.jvm

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import livephoto.core.*
import livephoto.core.binary.orThrow
import livephoto.core.memory.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Fixed compatibility samples; not device certification.
 * Delta storage copies only byte-identical ranges from the approved reference video.
 * SHA256 of every whole reconstructed asset must match its manifest digest. */
class ProtocolCompatibilityFixtureTest {
    private val context = Context(Limits(32_000_000uL, 32_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val resourceRoot = "/protocol-compatibility/v1/"
    private val originalHash = "d334d2c36a821dfa82c2d0f1bb09948125596bf77fd8eff7034ae6c53b739b64"
    private val movHash = "233e954e8b53e8b707b6e38fcdea554ef987a0bfb7d4bc8413c68a4d3d69d0e7"
    private val huaweiHash = "b1da3aef46cf6014cfc647f6b62bffa30eb900592092b069a21ef43530b863ba"

    @Test fun googleV1Mp4ReadsAndExtractsOriginalVideo() = check("video_MicroVideo_JPEG+MP4.jpg", ProtocolIds.GoogleV1, Verdict.Warning, emptySet(), 294469, 8121397, originalHash)
    @Test fun googleV1MovRetainsKnownParserLimitButExtractsExactCapturedVideo() = check("video_MicroVideo_JPEG+MOV.jpg", ProtocolIds.GoogleV1, Verdict.Invalid, setOf("CORRUPTED_CONTAINER"), 294469, 8121451, movHash)
    @Test fun googleV2Mp4ReportsNonCanonicalPaddingWithoutLosingRawExtraction() = check("video_MotionPhoto_JPEG+MP4.jpg", ProtocolIds.GoogleV2, Verdict.Invalid, setOf("MALFORMED_XMP"), 294926, 8121397, originalHash)
    @Test fun googleV2MovReportsPaddingAndParserLimitWithoutChangingCapturedBytes() = check("video_MotionPhoto_JPEG+MOV.jpg", ProtocolIds.GoogleV2, Verdict.Invalid, setOf("CORRUPTED_CONTAINER", "MALFORMED_XMP"), 294932, 8121451, movHash)
    @Test fun googleHeicMovReportsPartialScopeAndKeepsExactMovieExtraction() = check("video_MotionPhoto_HEIC+MOV.heic", ProtocolIds.GoogleV2, Verdict.Invalid, setOf("CORRUPTED_CONTAINER", "MALFORMED_XMP"), 1065746, 8121451, movHash)
    @Test fun oplusReadsVendorAndCompatibleBaseWithExactVideo() = check("video_OPPO_OLive_JPEG+MP4.jpg", ProtocolIds.Oplus, Verdict.Warning, emptySet(), 295309, 8121397, originalHash)
    @Test fun vivoReadsVendorAndCompatibleBaseWithExactVideo() = check("video_vivo_LivePhoto_JPEG+MP4.jpg", ProtocolIds.VivoModern, Verdict.Warning, emptySet(), 295793, 8121397, originalHash)
    @Test fun samsungJpegReportsLegacyFooterAndUsesPureVideoNotSefSuffix() = check("video_Samsung_MotionPhoto_JPEG+MP4.jpg", ProtocolIds.Samsung, Verdict.Invalid, setOf("MALFORMED_XMP", "MOTION_VIDEO_LENGTH_MISMATCH", "SEF_DIRECTORY_INVALID"), 294956, 8121397, originalHash)
    @Test fun samsungHeicReportsLegacyFooterAndKeepsDistinctVideoExtents() = check("video_Samsung_MotionPhoto_HEIC+MP4.heic", ProtocolIds.Samsung, Verdict.Invalid, setOf("CORRUPTED_CONTAINER", "MOTION_VIDEO_LENGTH_MISMATCH", "SEF_DIRECTORY_INVALID"), 1065745, 8121397, originalHash)
    @Test fun huaweiJpegKeepsUnknownTimestampSemanticsAndExactTransformedVideo() = check("video_HUAWEI_MovingPhoto_JPEG+MP4.jpg", ProtocolIds.Huawei, Verdict.Warning, setOf("TIMESTAMP_SEMANTICS_UNKNOWN"), 293848, 8121397, huaweiHash)
    @Test fun huaweiHeicCapturesCurrentReaderGapWithoutClaimingMalformedInput() = huaweiHeic("video_HUAWEI_MovingPhoto_HEIC+MP4.heic")
    @Test fun huaweiHeicH265LabelIsNotCodecEvidenceAndCapturesCurrentReaderGap() = huaweiHeic("video_HUAWEI_MovingPhoto_HEIC+MP4 (H.265).heic")
    @Test fun appleJpegMovPairKeepsIfd0MakerNoteOutsideFormalWriterScope() = applePair("jpeg", "jpg")
    @Test fun appleHeicMovPairKeepsIfd0MakerNoteOutsideFormalWriterScope() = applePair("heic", "heic")
    @Test fun vivoLegacyPairCapturesDurationBoundaryGapWithoutCallingItDeviceTested(): Unit = runImmediate {
        val image = captured("vivo-legacy.image.jpg")
        val movie = captured("vivo-legacy.video.mp4")
        // Independently measured video-track fields: mdhd=9009, stts=8*1001=8008.
        assertEquals("mdhd", movie.copyOfRange(296, 300).toString(Charsets.US_ASCII))
        assertEquals(30_000, word(movie, 312))
        assertEquals(9_009, word(movie, 316))
        assertEquals("stts", movie.copyOfRange(657, 661).toString(Charsets.US_ASCII))
        assertEquals(1, word(movie, 665))
        assertEquals(8, word(movie, 669))
        assertEquals(1_001, word(movie, 673))
        val input = SourceSet.Pair(MemoryBinarySource(Bytes(image), SourceId("fixture-vivo-image")), MemoryBinarySource(Bytes(movie), SourceId("fixture-vivo-movie")))
        val result = assertIs<CoreResult.Failure>(DefaultLivePhotoCore().inspect(ReadRequest(input, context)))
        assertEquals("CORRUPTED_CONTAINER", result.error.code.value)
        assertTrue(result.error.message.contains("Sample counts/duration"), "Known captured media-header boundary; do not broaden unrelated rejection")
        assertEquals(Bytes(image), input.image.readAt(0uL, image.size.toUInt()).orThrow())
        assertEquals(Bytes(movie), input.video.readAt(0uL, movie.size.toUInt()).orThrow())
    }

    @Test fun allEighteenAssetsRestoreExactlyAndExportVerifiedManifest() {
        val manifestBytes = javaClass.getResourceAsStream(resourceRoot + "captures.tsv").use { requireNotNull(it).readBytes() }
        val names = manifestBytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).map { it.substringBefore('\t') }.toList()
        assertEquals(18, names.size)
        assertEquals(18, names.toSet().size)
        names.forEach { captured(it) }
        val directory = Path.of("build", "reports", "protocol-compatibility")
        Files.createDirectories(directory)
        Files.write(directory.resolve("verified-captures.tsv"), manifestBytes)
        javaClass.getResourceAsStream(resourceRoot + "asset-manifest.json").use { Files.write(directory.resolve("asset-manifest.json"), requireNotNull(it).readBytes()) }
    }

    private fun referenceVideo(): ByteArray {
        val path = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve("reference/video.mp4") }.firstOrNull(Files::isRegularFile)
        if (System.getenv("LIVEPHOTO_REQUIRE_REFERENCE") == "true") assertNotNull(path, "Required reference is missing")
        else assumeTrue("Skipped(missing-fixture): approved reference video missing; no device claim", path != null)
        return Files.readAllBytes(requireNotNull(path)).also { assertEquals(originalHash, sha(it)) }
    }

    private fun captured(name: String, depth: Int = 0): ByteArray {
        assertTrue(depth <= 1, "Captured-byte base must not recurse or form a cycle")
        val manifest = javaClass.getResourceAsStream(resourceRoot + "captures.tsv").use {
            requireNotNull(it).reader().readText().lineSequence().filter(String::isNotBlank).map { line -> line.split('\t') }.toList()
        }
        val entry = manifest.single { it[0] == name }
        assertEquals(5, entry.size)
        val expectedSize = entry[1].toInt()
        assertTrue(expectedSize in 1..32_000_000)
        val base = if (entry[4] == "reference/video.mp4") referenceVideo() else captured(entry[4], depth + 1)
        val bytes = javaClass.getResourceAsStream(resourceRoot + entry[3]).use { stream ->
            DataInputStream(GZIPInputStream(requireNotNull(stream))).use { input ->
                val magic = ByteArray(8).also(input::readFully)
                assertContentEquals("LPBFIX01".toByteArray(Charsets.US_ASCII), magic)
                assertEquals(expectedSize, input.readInt())
                val output = ByteArrayOutputStream(expectedSize)
                while (true) {
                    when (val record = input.readUnsignedByte()) {
                        0 -> {
                            val length = input.readInt()
                            assertTrue(length in 1..(expectedSize - output.size()))
                            output.write(ByteArray(length).also(input::readFully))
                        }
                        1 -> {
                            val offset = input.readInt(); val length = input.readInt()
                            assertTrue(length in 1..(expectedSize - output.size()))
                            assertTrue(offset >= 0 && offset.toLong() + length <= base.size)
                            output.write(base, offset, length)
                        }
                        255 -> break
                        else -> fail("Unknown captured-byte record $record")
                    }
                }
                assertEquals(expectedSize, output.size())
                assertEquals(-1, input.read(), "Unexpected trailing captured-byte records")
                output.toByteArray()
            }
        }
        assertEquals(entry[2], sha(bytes), "Whole fixture changed: $name")
        return bytes
    }

    private fun check(name: String, protocol: ProtocolId, verdict: Verdict, issues: Set<String>, offset: Int, length: Int, movieHash: String): Unit = runImmediate {
        val bytes = captured(name)
        val original = sha(bytes)
        val expectedMovie = bytes.copyOfRange(offset, offset + length)
        assertEquals(movieHash, sha(expectedMovie), "Independent captured media-range oracle")
        if (movieHash == movHash) {
            // These literal offsets are measured in the captured media, not produced
            // by our reader/writer. The mdhd/stts disagreement needs CTS/edit review;
            // successful independent decode does not alone prove table validity.
            assertEquals("mdhd", expectedMovie.copyOfRange(8114709, 8114713).toString(Charsets.US_ASCII))
            assertEquals(30_000, word(expectedMovie, 8114725))
            assertEquals(152_152, word(expectedMovie, 8114729))
            assertEquals("stts", expectedMovie.copyOfRange(8115189, 8115193).toString(Charsets.US_ASCII))
            assertEquals(1, word(expectedMovie, 8115197))
            assertEquals(150, word(expectedMovie, 8115201))
            assertEquals(1_001, word(expectedMovie, 8115205))
        }
        val core = DefaultLivePhotoCore()
        val input = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("protocol-compatibility:$name")))
        val inspection = core.inspect(ReadRequest(input, context)).orThrow()
        assertTrue(inspection.detection.matches.any { it.target.protocol == protocol })
        val validation = core.validate(ValidationRequest(input, context = context)).orThrow()
        assertEquals(verdict, validation.verdict)
        assertEquals(issues + "CAPABILITY_UNSUPPORTED", validation.issues.map { it.code.value }.toSet())
        assertFalse(validation.coverage == Coverage.Complete, "No decoder/backend was supplied")
        val video = inspection.layout.resources.single { it.id.value == "${protocol.value}:video" }
        assertEquals(ByteRange(offset.toULong(), length.toULong()), video.extents.single().range)
        if (protocol == ProtocolIds.Oplus || protocol == ProtocolIds.VivoModern || protocol == ProtocolIds.Samsung) {
            assertTrue(inspection.detection.matches.any { it.target.protocol == ProtocolIds.GoogleV2 })
        }
        val output = MemoryOutputTransaction(context, "fixture-raw:$name")
        val extracted = core.extract(ExtractRequest(input, listOf(video.id), inspection.snapshot, output = output, context = context)).orThrow()
        assertEquals(Bytes(expectedMovie), output.committedAssets().values.single())
        assertEquals(movieHash, extracted.output.assets.single().digest.value)
        assertEquals(original, sha(input.source.readAt(0uL, bytes.size.toUInt()).orThrow().toByteArray()), "Source was modified during raw extraction")
        assertEquals(original, sha(bytes), "Captured input is immutable")
        assertTrue(core.getProtocolCapabilities(ProtocolSelector(protocol)).operations.none { Verification.DeviceTested in it.verification })
    }

    private fun huaweiHeic(name: String): Unit = runImmediate {
        val bytes = captured(name)
        val original = sha(bytes)
        // Independently measured LIVE trailer and media bytes, not this Core's failed layout.
        assertEquals(huaweiHash, sha(bytes.copyOfRange(1064602, 1064602 + 8121397)))
        val input = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("protocol-compatibility:$name")))
        val result = assertIs<CoreResult.Failure>(DefaultLivePhotoCore().inspect(ReadRequest(input, context)))
        assertEquals("OFFSET_OUT_OF_BOUNDS", result.error.code.value, "Known HEIC + Huawei trailer reader gap; TODO, not a media corruption verdict")
        assertEquals(original, sha(bytes))
    }

    private fun applePair(profile: String, extension: String): Unit = runImmediate {
        val image = captured("apple-$profile-mov.image.$extension")
        val movie = captured("apple-$profile-mov.video.mov")
        val imageHash = sha(image); val movieHash = sha(movie)
        // Independent fixed bytes in these captures: MakerNote header,
        // tag 0x11, 37-byte UUID+NUL. Do not use a Core writer/parser as the oracle.
        val note = image.toString(Charsets.ISO_8859_1).indexOf("Apple iOS\u0000")
        assertTrue(note >= 0)
        assertContentEquals(byteArrayOf(0, 1, 0, 17, 0, 2, 0, 0, 0, 37, 0, 0, 0, 32), image.copyOfRange(note + 14, note + 28))
        val cid = image.copyOfRange(note + 32, note + 68).toString(Charsets.US_ASCII)
        assertTrue(Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}").matches(cid))
        assertEquals(0, image[note + 68].toInt())
        assertTrue(movie.toString(Charsets.ISO_8859_1).contains(cid), "Paired movie must carry this image's identifier")
        val input = SourceSet.Pair(MemoryBinarySource(Bytes(image), SourceId("fixture-apple-image:$profile")), MemoryBinarySource(Bytes(movie), SourceId("fixture-apple-movie:$profile")))
        val result = assertIs<CoreResult.Failure>(DefaultLivePhotoCore().inspect(ReadRequest(input, context)))
        assertEquals("CONFLICTING_METADATA", result.error.code.value)
        assertTrue(result.error.message.contains("formal ExifIFD"), "Do not silently authorize an IFD0 MakerNote as formal ExifIFD")
        assertEquals(imageHash, sha(input.image.readAt(0uL, image.size.toUInt()).orThrow().toByteArray()))
        assertEquals(movieHash, sha(input.video.readAt(0uL, movie.size.toUInt()).orThrow().toByteArray()))
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun word(bytes: ByteArray, offset: Int): Int = (0..3).fold(0) { value, index -> (value shl 8) or (bytes[offset + index].toInt() and 255) }
}
