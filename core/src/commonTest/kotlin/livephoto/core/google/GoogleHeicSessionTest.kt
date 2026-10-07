package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.memory.*
import livephoto.core.samsung.SamsungFixtures
import livephoto.core.xmp.RDF_URI
import kotlin.test.*

/** Independently encoded item tables and XMP authority; no product HEIF writer is the fixture oracle. */
class GoogleHeicSessionTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic"))
    private fun u(value: UInt, width: Int) = unsignedBytes(value.toULong(), width, Endian.Big).toByteArray()
    private fun full(type: String, payload: ByteArray, version: Int = 0) =
        GoogleFixtures.box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("google-heic")))
    private fun xml(length: Int, padding: String, flag: String, repeated: Boolean, key: String?): ByteArray {
        val directory = "<k:Directory><r:Seq><r:li r:parseType='Resource' i:Semantic='Primary' i:Mime='image/heic' i:Padding='$padding'/>" +
            "<r:li r:parseType='Resource' i:Semantic='MotionPhoto' i:Mime='video/mp4' i:Length='$length'/></r:Seq></k:Directory>"
        return ("<r:RDF xmlns:r='$RDF_URI'><r:Description xmlns:c='$CAMERA_URI' xmlns:k='$CONTAINER_URI' xmlns:i='$ITEM_URI' c:MotionPhoto='$flag' c:MotionPhotoVersion='1'" +
            (key?.let { " c:MotionPhotoPresentationTimestampUs='$it'" } ?: "") + ">" + directory +
            (if (repeated) directory else "") + "</r:Description></r:RDF>").encodeToByteArray()
    }
    private data class Fixture(val bytes: ByteArray, val xmp: ByteArray, val video: ByteArray, val prefixLength: Int)
    private fun fixture(linked: Boolean = true, multiple: Boolean = false, idat: Boolean = false,
        padding: String = "8", lengthBias: Int = 0, flag: String = "1", repeated: Boolean = false,
        key: String? = "0", header: Int = 8, sizeZero: Boolean = false, absent: Boolean = false,
        duplicate: Boolean = false, samsung: Boolean = false, encoding: String = "", protected: Boolean = false,
        contentType: String = "application/rdf+xml", damageVideo: Boolean = false): Fixture {
        val image = GoogleFixtures.video(hevc = true).samples.first()
        val video = GoogleFixtures.video().bytes.copyOf().also { if (damageVideo) it[4] = 'x'.code.toByte() }
        val xml = xml(video.size + lengthBias, padding, flag, repeated, key)
        var data = byteArrayOf()
        val positions = listOf(image, xml).map { payload ->
            val start = data.size.toUInt()
            if (multiple) {
                data += payload.copyOfRange(0, 2) + byteArrayOf(0xa5.toByte(), 0x5a, 0xff.toByte()) + payload.copyOfRange(2, payload.size)
                listOf(start to 2u, start + 5u to (payload.size - 2).toUInt())
            } else { data += payload; listOf(start to payload.size.toUInt()) }
        }
        val ftyp = GoogleFixtures.box("ftyp", "heic".encodeToByteArray() + u(0u, 4) + "mif1heic".encodeToByteArray())
        fun meta(start: UInt): ByteArray {
            val imageInfo = full("infe", u(1u, 2) + u(0u, 2) + "hvc1Primary\u0000".encodeToByteArray(), 2)
            val xmpInfo = full("infe", u(2u, 2) + u(if (protected) 1u else 0u, 2) + "mimeMetadata\u0000$contentType\u0000$encoding\u0000".encodeToByteArray(), 2)
            var locations = byteArrayOf(0x44, 0) + u(2u, 2)
            for ((index, extents) in positions.withIndex()) {
                locations += u((index + 1).toUInt(), 2) + (if (idat) u(1u, 2) else byteArrayOf()) + u(0u, 2) + u(extents.size.toUInt(), 2)
                for ((offset, length) in extents) locations += u(start + offset, 4) + u(length, 4)
            }
            val properties = GoogleFixtures.box("ipco", full("ispe", u(1u, 4) + u(1u, 4)) + GoogleFixtures.box("hvcC", GoogleFixtures.video(hevc = true).configuration))
            val associations = full("ipma", u(1u, 4) + byteArrayOf(0, 1, 2, 0x81.toByte(), 0x82.toByte()))
            return full("meta", full("hdlr", u(0u, 4) + "pict".encodeToByteArray() + ByteArray(12) + byteArrayOf(0)) + full("pitm", u(1u, 2)) +
                full("iinf", u(2u, 2) + imageInfo + xmpInfo) + full("iloc", locations, if (idat) 1 else 0) + GoogleFixtures.box("iprp", properties + associations) +
                (if (linked) full("iref", GoogleFixtures.box("cdsc", u(2u, 2) + u(1u, 2) + u(1u, 2))) else byteArrayOf()) +
                if (idat) GoogleFixtures.box("idat", data) else byteArrayOf())
        }
        val prefix = if (idat) ftyp + meta(0u) else ftyp + meta((ftyp.size + meta(0u).size + 8).toUInt()) + GoogleFixtures.box("mdat", data)
        val mpvd = if (sizeZero) u(0u, 4) + "mpvd".encodeToByteArray() + video else if (header == 16)
            u(1u, 4) + "mpvd".encodeToByteArray() + unsignedBytes((video.size + 16).toULong(), 8, Endian.Big).toByteArray() + video else GoogleFixtures.box("mpvd", video)
        val sef = if (!samsung) byteArrayOf() else GoogleFixtures.box("sefd", SamsungFixtures.trailer(listOf(
            SamsungFixtures.Record(0x0a30, "MotionPhoto_Data", "mpv2".encodeToByteArray() + u((prefix.size + 8).toUInt(), 4) + u(video.size.toUInt(), 4)),
            SamsungFixtures.Record(0x0a31, "MotionPhoto_Version", "mpv3".encodeToByteArray()))))
        return Fixture(prefix + (if (absent) byteArrayOf() else mpvd) + (if (duplicate) mpvd else byteArrayOf()) + sef, xml, video, prefix.size)
    }
    @Test fun publicReadsUseLinkedMetadataAndExtractTheVideoWithoutTheMpvdHeader(): Unit = runImmediate {
        for (multiple in listOf(false, true)) for (idat in listOf(false, true)) {
            val fixture = fixture(multiple = multiple, idat = idat)
            val inspected = core.inspect(ReadRequest(input(fixture.bytes), context)).orThrow()
            assertEquals(target, inspected.detection.primaryProtocol)
            assertEquals(listOf(target), inspected.detection.matches.map { it.target })
            assertEquals(Disposition.Candidate, inspected.detection.disposition)
            assertEquals(ImageFormat.Heic, inspected.media.first().imageFormat)
            assertEquals(Coverage.Partial, inspected.media.first().coverage)
            assertEquals(Time(0, 1_000_000u), core.getKeyPhotoPosition(ReadRequest(input(fixture.bytes), context)).orThrow().position)
            val resource = inspected.layout.resources.single { it.kind == ResourceKind.Video }
            assertEquals(ByteRange((fixture.prefixLength + 8).toULong(), fixture.video.size.toULong()), resource.extents.single().range)
            assertFalse(inspected.layout.resources.single { it.kind == ResourceKind.PrimaryImage }.standalone)
            val output = MemoryOutputTransaction(context, "google-heic-read-$multiple-$idat")
            val result = core.extract(ExtractRequest(input(fixture.bytes), listOf(resource.id, ResourceId("heif:item:2")), inspected.snapshot, output = output, context = context)).orThrow()
            assertEquals(listOf(Bytes(fixture.video), Bytes(fixture.xmp)), output.committedAssets().values.toList())
            assertTrue(result.preservation.records.any { it.guarantee == Guarantee.ExactExtraction && it.outcome == GuaranteeOutcome.Verified })
            val analysis = core.analyze(AnalyzeRequest(input(fixture.bytes), context = context)).orThrow()
            assertEquals(Coverage.Partial, analysis.validation.coverage)
            assertTrue(analysis.validation.checks.none { it.id.startsWith("jpeg.") })
            assertTrue(analysis.validation.checks.any { it.id == "media.decode" && it.coverage == Coverage.NotRun })
        }
    }
    @Test fun UnlinkedUnrecognizedDisabledAndProtectedXmpDoNotBecomeProtocolAuthorityOrSamsung(): Unit = runImmediate {
        for (fixture in listOf(fixture(linked = false), fixture(contentType = "application/octet-stream"), fixture(flag = "0"),
            fixture(encoding = "gzip"), fixture(protected = true))) {
            val inspected = core.inspect(ReadRequest(input(fixture.bytes), context)).orThrow()
            assertTrue(inspected.detection.matches.isEmpty())
            assertTrue(inspected.layout.resources.none { it.kind == ResourceKind.Video })
        }
    }
    @Test fun invalidDirectoryRemainsInvalidButTheIndependentStandardMpvdCanBeRawExtracted(): Unit = runImmediate {
        for (fixture in listOf(fixture(lengthBias = -1), fixture(repeated = true), fixture(padding = "0"), fixture(padding = "16"))) {
            val inspected = core.inspect(ReadRequest(input(fixture.bytes), context)).orThrow()
            assertEquals(listOf(target), inspected.detection.matches.map { it.target })
            assertTrue(inspected.detection.matches.single().issues.any { it.layer == Layer.Protocol })
            val validation = core.validateProtocol(ValidationRequest(input(fixture.bytes), target = target, context = context)).orThrow()
            assertTrue(validation.verdict != Verdict.Valid)
            val output = MemoryOutputTransaction(context, "google-heic-invalid-directory-${fixture.bytes.size}")
            core.extract(ExtractRequest(input(fixture.bytes), emptyList(), output = output, context = context)).orThrow()
            assertEquals(Bytes(fixture.video), output.committedAssets().values.single())
        }
    }
    @Test fun MissingDuplicateImplicitAndExtendedMpvdDoNotAcquireATrustedVideoRange(): Unit = runImmediate {
        for (fixture in listOf(fixture(absent = true), fixture(duplicate = true), fixture(sizeZero = true), fixture(header = 16, padding = "16"))) {
            val inspected = core.inspect(ReadRequest(input(fixture.bytes), context)).orThrow()
            assertEquals(listOf(target), inspected.detection.matches.map { it.target })
            assertTrue(inspected.layout.resources.none { it.kind == ResourceKind.Video })
            assertTrue(inspected.issues.any { it.layer == Layer.Protocol })
            val output = MemoryOutputTransaction(context, "google-heic-no-motion-${fixture.bytes.size}")
            assertEquals(IssueCode("MOTION_VIDEO_MISSING"), assertIs<CoreResult.Failure>(core.extract(ExtractRequest(input(fixture.bytes), emptyList(), output = output, context = context))).error.code)
            assertTrue(output.committedAssets().isEmpty())
        }
    }
    @Test fun videoFailureDoesNotPollutePrimaryFramingAndRawResourceStaysByteExact(): Unit = runImmediate {
        val fixture = fixture(damageVideo = true)
        val inspected = core.inspect(ReadRequest(input(fixture.bytes), context)).orThrow()
        assertEquals(1u, inspected.media.first().width)
        assertTrue(inspected.media.first().issues.none { it.layer == Layer.Media && it.severity == Severity.Error })
        val report = core.validateMedia(ValidationRequest(input(fixture.bytes), context = context)).orThrow()
        assertEquals(Verdict.Invalid, report.verdict)
        assertEquals(Verdict.Valid, report.checks.single { it.id == "heif.primary-framing" }.verdict)
        val output = MemoryOutputTransaction(context, "google-heic-damaged-video")
        core.extract(ExtractRequest(input(fixture.bytes), emptyList(), output = output, context = context)).orThrow()
        assertEquals(Bytes(fixture.video), output.committedAssets().values.single())
    }
    @Test fun samsungAndGoogleRemainIndependentMatchesRatherThanSelectingOneFromMpvd(): Unit = runImmediate {
        val fixture = fixture(samsung = true)
        val inspected = core.inspect(ReadRequest(input(fixture.bytes), context)).orThrow()
        assertEquals(setOf(ProtocolIds.GoogleV2, ProtocolIds.Samsung), inspected.detection.matches.map { it.target.protocol }.toSet())
        assertNull(inspected.detection.primaryProtocol)
        val samsung = inspected.detection.matches.single { it.target.protocol == ProtocolIds.Samsung }
        assertEquals(ProfileId("heic-sef-mpv2"), samsung.target.profile)
        assertTrue(inspected.layout.resources.any { it.id.value.startsWith("samsung:sef:record:") })
        val output = MemoryOutputTransaction(context, "google-samsung-heic-multiple")
        core.extract(ExtractRequest(input(fixture.bytes), emptyList(), output = output, context = context)).orThrow()
        assertEquals(Bytes(fixture.video), output.committedAssets().values.single())
    }
    @Test fun readAndFiniteCreateAreExperimentalButBroaderWritesAndDeviceEvidenceAreNotClaimed() {
        val capabilities = core.getProtocolCapabilities(target)
        for (operation in capabilities.operations) {
            assertEquals(if (operation.operation in setOf(Operation.Detect, Operation.Analyze, Operation.Inspect, Operation.Validate, Operation.ExtractRaw, Operation.GetKey, Operation.Create, Operation.SplitClean, Operation.ConvertFrom, Operation.ConvertTo, Operation.SetKey, Operation.Repair)) Implementation.Experimental else Implementation.Planned,
                operation.implementation)
            assertTrue(operation.verification.none { it == Verification.DeviceTested })
        }
    }
    @Test fun metadataBudgetAndCancellationRemainFatal(): Unit = runImmediate {
        val fixture = fixture()
        for ((ctx, code) in listOf(context.copy(limits = context.limits.copy(maxMetadataBytes = 100uL)) to "RESOURCE_LIMIT_EXCEEDED",
            context.copy(cancellation = Cancellation { true }) to "CANCELLED")) {
            assertEquals(IssueCode(code), assertIs<CoreResult.Failure>(core.inspect(ReadRequest(input(fixture.bytes), ctx))).error.code)
        }
    }
}
