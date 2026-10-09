package livephoto.core.jvm

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import livephoto.core.*
import livephoto.core.binary.orThrow
import livephoto.core.binary.BinaryReader
import livephoto.core.bmff.BmffVideoProbe
import livephoto.core.binary.ParseBudget
import livephoto.core.implementation.SourceSession
import livephoto.core.jpeg.AppPayloadKind
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
    @Test fun oplusCreationMatchesFixedReferenceProtocolPacketAndMarkerWithoutChangingMedia(): Unit = runImmediate {
        // The fixed file is byte-identical to reference/photos/oppo_jpg+h.264.jpg.
        // Its label is not a codec assertion: the untouched input is HEVC + AAC.
        val expectedBytes = captured("video_OPPO_OLive_JPEG+MP4.jpg")
        assertEquals("3308749b84e583bd4b4f008b20e1dd475939d6a758b45d3981c456404ed4ec5b", sha(expectedBytes))
        val reference = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve("reference/video.jpg") }.firstOrNull(Files::isRegularFile)
        if (System.getenv("LIVEPHOTO_REQUIRE_REFERENCE") == "true") assertNotNull(reference)
        else assumeTrue("Required input cover missing; not device certification", reference != null)
        val imageBytes = Files.readAllBytes(requireNotNull(reference))
        assertEquals("18d7fe28bff459d41fb75fe8358bb6df0f7c5164c04158e8b120789af7da76a9", sha(imageBytes))
        val movieBytes = referenceVideo()
        val image = MemoryBinarySource(Bytes(imageBytes), SourceId("reference-oplus-image"))
        val video = MemoryBinarySource(Bytes(movieBytes), SourceId("reference-oplus-video"))
        val created = DefaultLivePhotoCore().create(CreateRequest(image, video,
            ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail")),
            edits = EditSpec(keyPosition = CoverPosition.FrameIndex(0uL)),
            output = MemoryOutputTransaction(context, "reference-oplus-create"), context = context)).orThrow()
        val actualSource = created.output.assets.single().readableSource!!
        val expectedSource = MemoryBinarySource(Bytes(expectedBytes), SourceId("reference-oplus-oracle"))
        suspend fun open(source: BinarySource) = SourceSession.open(SourceSet.Single(source), context, ParseBudget(context)).orThrow()
        val expected = open(expectedSource)
        val actual = open(actualSource)
        suspend fun packet(session: SourceSession): String {
            val firstApp = session.jpeg!!.segments.first { it.marker in 0xe0..0xef }
            assertEquals(AppPayloadKind.Xmp, firstApp.payloadKind)
            assertEquals(2uL, firstApp.range.offset)
            return session.reader.readExactly(firstApp.payload!!.offset, firstApp.payload.length.toUInt()).orThrow().toByteArray().decodeToString()
        }
        fun protocolDescription(packet: String): String {
            val start = packet.indexOf("<rdf:Description rdf:about=\"\" xmlns:GCamera=")
            assertTrue(start >= 0)
            val end = packet.indexOf("</rdf:Description>", start)
            assertTrue(end > start)
            return packet.substring(start, end + "</rdf:Description>".length)
        }
        val attribution = " xmlns:LivePhotoBox=\"https://github.com/LengxiQwQ/live-photo-box\" LivePhotoBox:Action=\"Merge\" LivePhotoBox:Protocol=\"OppoLivePhoto\" LivePhotoBox:Version=\"2.2.1.0\""
        val expectedDescription = protocolDescription(packet(expected))
        assertTrue(expectedDescription.contains(attribution), "Only the independently observed creator attributes may be excluded")
        assertEquals(expectedDescription.replace(attribution, ""), protocolDescription(packet(actual)), "Fixed protocol RDF, not a self-generated oracle")
        val expectedMarker = expected.exifComments.single().document.ifds.flatMap { it.entries }.single { it.tag == 0x9286u.toUShort() }
        val actualMarker = actual.exifComments.single().document.ifds.flatMap { it.entries }.single { it.tag == 0x9286u.toUShort() }
        assertEquals(expectedMarker.type, actualMarker.type)
        assertEquals(expectedMarker.count, actualMarker.count)
        assertEquals(expectedMarker.value, actualMarker.value)
        assertEquals(expected.exifComments.single().document.endian, actual.exifComments.single().document.endian)
        val body = actual.jpeg!!.segments.first { it.marker != 0xd8 && it.payloadKind !in setOf(AppPayloadKind.Xmp, AppPayloadKind.Exif) }.range.offset
        assertContentEquals(imageBytes.copyOfRange(2, imageBytes.size), actual.reader.readExactly(body, (imageBytes.size - 2).toUInt()).orThrow().toByteArray())
        var offset = 0
        while (offset < movieBytes.size) {
            val length = minOf(64 * 1024, movieBytes.size - offset)
            assertContentEquals(movieBytes.copyOfRange(offset, offset + length),
                actual.reader.readBuffer(actual.jpeg.primary.endExclusive + offset.toULong(), length.toUInt()).orThrow().toByteArray())
            offset += length
        }
        assertTrue(created.execution.none { it.remuxed || it.transcoded })
        assertEquals(Bytes(imageBytes), image.readAt(0uL, imageBytes.size.toUInt()).orThrow())
        assertEquals(Bytes(movieBytes), video.readAt(0uL, movieBytes.size.toUInt()).orThrow())
        // Ordinary tool defaults and creator attribution are intentionally not forged.
        assertFalse(packet(actual).contains("LivePhotoBox:Action"))
    }
    @Test fun vivoReadsVendorAndCompatibleBaseWithExactVideo() = check("video_vivo_LivePhoto_JPEG+MP4.jpg", ProtocolIds.VivoModern, Verdict.Warning, emptySet(), 295793, 8121397, originalHash)
    @Test fun updatedUserVivoReadsAndExtractsExactVideo() = check("user-vivo-20261009.jpg", ProtocolIds.VivoModern, Verdict.Warning, emptySet(), 295793, 8121397, originalHash)
    @Test fun allElevenUserPhotoOutputsHaveWholeFileOracles() {
        val entries = javaClass.getResourceAsStream(resourceRoot + "user-photos.tsv").use {
            requireNotNull(it).reader().readText().lineSequence().filter(String::isNotBlank).map { line -> line.split('\t') }.toList()
        }
        assertEquals(11, entries.size)
        assertEquals(11, entries.map { it[0] }.toSet().size)
        val directory = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve("reference/photos") }.firstOrNull(Files::isDirectory)
        for (entry in entries) {
            assertEquals(3, entry.size)
            assertEquals(entry[2], sha(captured(entry[1])), "Fixed oracle for ${entry[0]}")
            if (directory != null) assertEquals(entry[2], sha(Files.readAllBytes(directory.resolve(entry[0]))), "Supplied file changed: ${entry[0]}")
        }
        if (directory != null) {
            assertEquals(originalHash, sha(Files.readAllBytes(directory.resolve("video.mp4"))))
            assertEquals("18d7fe28bff459d41fb75fe8358bb6df0f7c5164c04158e8b120789af7da76a9", sha(Files.readAllBytes(directory.resolve("video.jpg"))))
        }
    }
    @Test fun samsungJpegReportsLegacyFooterAndUsesPureVideoNotSefSuffix() = check("video_Samsung_MotionPhoto_JPEG+MP4.jpg", ProtocolIds.Samsung, Verdict.Invalid, setOf("MALFORMED_XMP", "MOTION_VIDEO_LENGTH_MISMATCH", "SEF_DIRECTORY_INVALID"), 294956, 8121397, originalHash)
    @Test fun samsungHeicReportsLegacyFooterAndKeepsDistinctVideoExtents() = check("video_Samsung_MotionPhoto_HEIC+MP4.heic", ProtocolIds.Samsung, Verdict.Invalid, setOf("CORRUPTED_CONTAINER", "MOTION_VIDEO_LENGTH_MISMATCH", "SEF_DIRECTORY_INVALID"), 1065745, 8121397, originalHash)
    @Test fun huaweiJpegKeepsUnknownTimestampSemanticsAndExactTransformedVideo() = check("video_HUAWEI_MovingPhoto_JPEG+MP4.jpg", ProtocolIds.Huawei, Verdict.Warning, setOf("TIMESTAMP_SEMANTICS_UNKNOWN"), 293848, 8121397, huaweiHash)
    @Test fun huaweiHeicReadsBoundedEnvelopeAndExtractsExactMovie() = huaweiHeic("video_HUAWEI_MovingPhoto_HEIC+MP4.heic")
    @Test fun huaweiHeicH265LabelIsNotCodecEvidenceAndExtractsExactMovie() = huaweiHeic("video_HUAWEI_MovingPhoto_HEIC+MP4 (H.265).heic")
    @Test fun appleJpegMovPairKeepsIfd0MakerNoteOutsideFormalWriterScope() = applePair("jpeg", "jpg")
    @Test fun appleHeicMovPairKeepsIfd0MakerNoteOutsideFormalWriterScope() = applePair("heic", "heic")
    @Test fun capturedDurationDifferencesAreLastFrameStartsNotCompleteCompositionDurations(): Unit = runImmediate {
        val cases = listOf(
            Triple(captured("vivo-legacy.video.mp4"), listOf(9009L, 8008L, 2002L, 9009L, 10010L, 2002L), 8),
            Triple(captured("video_MicroVideo_JPEG+MOV.jpg").copyOfRange(294469, 294469 + 8121451),
                listOf(152152L, 150150L, 3003L, 152152L, 153153L, 3003L), 150),
        )
        for ((movie, expected, count) in cases) {
            // Independent table arithmetic over SHA-verified fixed bytes. A one/two-frame
            // header difference is not permission to reinterpret or normalize the media.
            assertEquals(expected, capturedVideoTiming(movie, count))
            val source = MemoryBinarySource(Bytes(movie), SourceId("captured-duration-boundary"))
            val before = sha(movie)
            val rejected = assertIs<CoreResult.Failure>(BmffVideoProbe(BinaryReader(source, context)).probe(ByteRange(0uL, movie.size.toULong())))
            assertEquals("CORRUPTED_CONTAINER", rejected.error.code.value)
            assertTrue(rejected.error.message.contains("Sample counts/duration"))
            assertEquals(before, sha(source.readAt(0uL, movie.size.toUInt()).orThrow().toByteArray()))
        }
    }
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

    @Test fun allNineteenAssetsRestoreExactlyAndExportVerifiedManifest() {
        val manifestBytes = javaClass.getResourceAsStream(resourceRoot + "captures.tsv").use { requireNotNull(it).readBytes() }
        val names = manifestBytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).map { it.substringBefore('\t') }.toList()
        assertEquals(19, names.size)
        assertEquals(19, names.toSet().size)
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
        // Independently measured LIVE trailer and media bytes, not this Core's layout.
        assertEquals(huaweiHash, sha(bytes.copyOfRange(1064602, 1064602 + 8121397)))
        val input = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("protocol-compatibility:$name")))
        val core = DefaultLivePhotoCore()
        val result = core.inspect(ReadRequest(input, context)).orThrow()
        assertEquals(ProtocolIds.Huawei, result.detection.primaryProtocol?.protocol)
        assertEquals(Disposition.Candidate, result.detection.disposition)
        assertEquals(ImageFormat.Heic, result.media.first().imageFormat)
        assertEquals(Coverage.Partial, result.media.first().coverage)
        val movie = result.layout.resources.single { it.kind == ResourceKind.Video }
        assertEquals(ByteRange(1064602uL, 8121397uL), movie.extents.single().range)
        assertNull(result.keyPhoto.position)
        val output = MemoryOutputTransaction(context, "huawei-heic-raw:$name")
        val extracted = core.extract(ExtractRequest(input, listOf(movie.id), result.snapshot, output = output, context = context)).orThrow()
        assertEquals(huaweiHash, extracted.output.assets.single().digest.value)
        assertEquals(Bytes(bytes.copyOfRange(1064602, 1064602 + 8121397)), output.committedAssets().values.single())
        assertFalse(core.validate(ValidationRequest(input, context = context)).orThrow().coverage == Coverage.Complete)
        assertEquals(original, sha(input.source.readAt(0uL, bytes.size.toUInt()).orThrow().toByteArray()))
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

    private fun capturedVideoTiming(bytes: ByteArray, expectedCount: Int): List<Long> {
        fun box(start: Int, end: Int, type: String): Pair<Int, Int> {
            var offset = start
            while (offset < end) {
                assertTrue(end - offset >= 8)
                val size = word(bytes, offset)
                assertTrue(size in 8..(end - offset), "Only measured complete 32-bit fixture boxes")
                if (bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII) == type)
                    return (offset + 8) to (offset + size)
                offset += size
            }
            error("Missing measured fixture box $type")
        }
        fun Pair<Int, Int>.child(type: String) = box(first, second, type)
        fun table(range: Pair<Int, Int>): List<Long> {
            val (start, end) = range
            assertEquals(0, word(bytes, start), "Measured unsigned v0 table")
            val runs = word(bytes, start + 4)
            assertTrue(runs in 1..expectedCount)
            assertEquals(start + 8 + runs * 8, end)
            return buildList {
                repeat(runs) { index ->
                    val count = word(bytes, start + 8 + index * 8)
                    val value = word(bytes, start + 12 + index * 8).toLong()
                    assertTrue(count in 1..(expectedCount - size)); assertTrue(value >= 0)
                    repeat(count) { add(value) }
                }
                assertEquals(expectedCount, size)
            }
        }
        val track = (0 to bytes.size).child("moov").child("trak")
        val media = track.child("mdia")
        val handler = media.child("hdlr").first + 8
        assertEquals("vide", bytes.copyOfRange(handler, handler + 4).toString(Charsets.US_ASCII))
        val header = media.child("mdhd").first
        assertEquals(0, word(bytes, header)); assertEquals(30000, word(bytes, header + 12))
        val samples = media.child("minf").child("stbl")
        val deltas = table(samples.child("stts")); val offsets = table(samples.child("ctts"))
        var dts = 0L
        val pts = deltas.indices.map { index -> (dts + offsets[index]).also { dts += deltas[index] } }
        val edits = track.child("edts").child("elst").first
        assertEquals(0, word(bytes, edits)); assertEquals(1, word(bytes, edits + 4))
        return listOf(word(bytes, header + 16).toLong(), dts, pts.first(), pts.last(),
            pts.indices.maxOf { pts[it] + deltas[it] }, word(bytes, edits + 12).toLong())
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun word(bytes: ByteArray, offset: Int): Int = (0..3).fold(0) { value, index -> (value shl 8) or (bytes[offset + index].toInt() and 255) }
}
