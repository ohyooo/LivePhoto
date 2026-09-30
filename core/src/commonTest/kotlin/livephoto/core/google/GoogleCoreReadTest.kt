package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.TestSource
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.memory.MemoryOutputTransaction
import kotlin.test.*

/** Public API tests use literal namespace/protocol IDs and independently assembled media. */
class GoogleCoreReadTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private fun input(bytes: ByteArray, id: String = "golden") = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId(id)))

    @Test
    fun handwrittenV1AndV2DetectInspectValidateAndRawExtractExactVideo(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        for ((index, bytes) in listOf(GoogleFixtures.v1Photo(video), GoogleFixtures.v2Photo(video)).withIndex()) {
            val source = input(bytes, "read-v${index + 1}")
            val detection = value(core.detect(ReadRequest(source, context)))
            assertEquals(Disposition.Live, detection.disposition)
            val expectedId = if (index == 0) "google.microvideo.v1" else "google.motionphoto.v2"
            assertEquals(ProtocolId(expectedId), detection.primaryProtocol?.protocol)
            assertEquals(ProfileId("jpeg"), detection.primaryProtocol?.profile)
            val inspected = value(core.inspect(ReadRequest(source, context)))
            val embedded = inspected.layout.resources.single { it.kind == ResourceKind.Video }
            val extent = embedded.extents.single()
            assertEquals(ByteRange((bytes.size - video.size).toULong(), video.size.toULong()), extent.range)
            assertEquals(Time(0, 1_000_000u), inspected.keyPhoto.position)
            assertEquals(KeySource.ProtocolField, inspected.keyPhoto.source)
            val structure = value(core.validateStructure(ValidationRequest(source, context = context)))
            val protocol = value(core.validateProtocol(ValidationRequest(source, context = context)))
            assertEquals(Verdict.Valid, structure.verdict)
            assertEquals(Coverage.Complete, structure.coverage)
            assertEquals(Verdict.Valid, protocol.verdict)
            assertEquals(Coverage.Complete, protocol.coverage)
            val media = value(core.validateMedia(ValidationRequest(source, context = context)))
            assertEquals(Coverage.Partial, media.coverage)
            assertTrue(media.checks.any { it.coverage == Coverage.NotRun })
            val transaction = MemoryOutputTransaction(context, "extract-v${index + 1}")
            val extracted = value(core.extract(ExtractRequest(source, listOf(embedded.id), inspected.snapshot, output = transaction, context = context)))
            assertEquals(Bytes(video), transaction.committedAssets().values.single())
            assertEquals(TransactionState.Committed, extracted.output.receipt.state)
            assertTrue(extracted.preservation.records.any { it.guarantee == Guarantee.ExactExtraction && it.outcome == GuaranteeOutcome.Verified })
        }
    }

    @Test
    fun missingNegativeOneAndZeroKeyFieldsStayDistinctWhenV2DefaultIsDisclosed(): Unit = runImmediate {
        for (v2 in listOf(false, true)) for (timestamp in listOf<String?>(null, "-1", "0")) {
            val bytes = if (v2) GoogleFixtures.v2Photo(timestamp = timestamp) else GoogleFixtures.v1Photo(timestamp = timestamp)
            val key = value(core.getKeyPhotoPosition(ReadRequest(input(bytes), context)))
            assertEquals(if (timestamp == "0") Time(0, 1_000_000u) else if (v2) Time(40, 1000u) else null, key.position)
            assertEquals(if (v2 && timestamp != "0") KeySource.DerivedDefault else if (timestamp == null) KeySource.Unknown else KeySource.ProtocolField, key.source)
            assertEquals(if (timestamp == null) emptyList() else listOf(Value.Text(timestamp)), key.rawFields.map { it.rawValue })
            assertNull(key.frameIndex)
        }
    }

    @Test
    fun ordinaryJpegIsNonLiveAndTextMentioningMotionDoesNotAuthorizeProtocol(): Unit = runImmediate {
        val ordinary = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:p='urn:private' p:Comment='MotionPhoto=1 MicroVideoOffset=5'/></rdf:RDF>"
        for (bytes in listOf(GoogleFixtures.jpeg(), GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(ordinary)))) {
            val found = value(core.detect(ReadRequest(input(bytes), context)))
            assertEquals(Disposition.NonLive, found.disposition)
            assertTrue(found.matches.isEmpty())
            assertNull(found.primaryProtocol)
        }
    }

    @Test
    fun nestedProtocolNamedFieldsInsideForeignPropertyHaveNoBindingOwnership(): Unit = runImmediate {
        val ordinary = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:p='urn:private'><p:Opaque><rdf:RDF><rdf:Description xmlns:g='http://ns.google.com/photos/1.0/camera/' g:MotionPhoto='private-value'/></rdf:RDF></p:Opaque></rdf:Description></rdf:RDF>"
        val inspected = value(core.inspect(ReadRequest(input(GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(ordinary))), context)))
        assertEquals(Disposition.NonLive, inspected.detection.disposition)
        val entry = inspected.metadata.single { it.selector == "{http://ns.google.com/photos/1.0/camera/}MotionPhoto" }
        assertEquals(Value.Text("private-value"), entry.value)
        assertTrue(entry.owner != Ownership.SourceProtocol && entry.owner != Ownership.TargetProtocol)
    }

    @Test
    fun malformedV1LengthsRemainInvalidCandidatesRatherThanUsableResources(): Unit = runImmediate {
        val valid = GoogleFixtures.v1Photo()
        for (length in listOf("0", valid.size.toString(), ULong.MAX_VALUE.toString(), "-1", "18446744073709551616")) {
            val source = input(GoogleFixtures.v1Photo(length = length))
            val detection = value(core.detect(ReadRequest(source, context)))
            assertTrue(detection.matches.any { it.target.protocol == ProtocolId("google.microvideo.v1") })
            assertTrue(detection.disposition != Disposition.Live)
            val validation = value(core.validateProtocol(ValidationRequest(source, context = context)))
            assertEquals(Verdict.Invalid, validation.verdict)
            assertTrue(validation.issues.any { it.severity == Severity.Error } || validation.checks.any { check -> check.issues.any { it.severity == Severity.Error } })
        }
    }

    @Test
    fun v2VersionDirectoryOrderLengthsAndSecondaryPaddingAreChecked(): Unit = runImmediate {
        val length = GoogleFixtures.video().bytes.size
        val primary = "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='image/jpeg' item:Semantic='Primary' item:Length='0' item:Padding='0'/></rdf:li>"
        fun secondary(semantic: String = "MotionPhoto", bytes: String = length.toString(), padding: String = "") =
            "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='video/mp4' item:Semantic='$semantic' item:Length='$bytes' $padding/></rdf:li>"
        val invalid = listOf(
            GoogleFixtures.v2Photo(version = "2"),
            GoogleFixtures.v2Photo(directory = primary + primary + secondary()),
            GoogleFixtures.v2Photo(directory = secondary() + primary),
            GoogleFixtures.v2Photo(directory = primary + secondary("Auxiliary")),
            GoogleFixtures.v2Photo(directory = primary + secondary(bytes = "0")),
            GoogleFixtures.v2Photo(directory = primary + secondary(bytes = (length + 1).toString())),
            GoogleFixtures.v2Photo(directory = primary + secondary(padding = "item:Padding='1'")),
        )
        for ((index, bytes) in invalid.withIndex()) {
            val validation = value(core.validateProtocol(ValidationRequest(input(bytes), context = context)))
            assertEquals(Verdict.Invalid, validation.verdict, "Invalid directory index=$index; issues=${validation.issues.map { it.code.value }}")
        }
    }

    @Test
    fun scalarElementFieldsAndNamespacePrefixAliasesHaveSameAuthority(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val base = GoogleFixtures.v2Xml(video.size)
        val alias = base.replace("camera:", "c:").replace("xmlns:camera", "xmlns:c")
            .replace("container:", "d:").replace("xmlns:container", "xmlns:d")
            .replace("item:", "i:").replace("xmlns:item", "xmlns:i")
        val elements = alias.replace(" c:MotionPhoto='1' c:MotionPhotoVersion='1'", "")
            .replace("<d:Directory>", "<c:MotionPhoto>1</c:MotionPhoto><c:MotionPhotoVersion>1</c:MotionPhotoVersion><d:Directory>")
        val source = input(GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(elements)) + video)
        assertEquals(Disposition.Live, value(core.detect(ReadRequest(source, context))).disposition)
        assertEquals(Verdict.Valid, value(core.validateProtocol(ValidationRequest(source, context = context))).verdict)
    }

    @Test
    fun duplicateConflictingGoogleScalarIsInvalidInsteadOfFirstOrLastWinning(): Unit = runImmediate {
        val extra = "<camera:MotionPhotoVersion>2</camera:MotionPhotoVersion>"
        val source = input(GoogleFixtures.v2Photo(extra = extra))
        val report = value(core.validateProtocol(ValidationRequest(source, context = context)))
        assertEquals(Verdict.Invalid, report.verdict)
        assertTrue((report.issues + report.checks.flatMap { it.issues }).any { it.code == IssueCode("CONFLICTING_METADATA") })
    }

    @Test
    fun fakeFtypVideoCannotSatisfyMediaValidationOrCleanPublication(): Unit = runImmediate {
        val fake = GoogleFixtures.box("ftyp", "isom".encodeToByteArray() + GoogleFixtures.u32(0u) + "mp42".encodeToByteArray()) + GoogleFixtures.box("mdat", byteArrayOf(1, 2, 3))
        val source = input(GoogleFixtures.v2Photo(fake))
        val report = value(core.validateMedia(ValidationRequest(source, context = context)))
        assertEquals(Verdict.Invalid, report.verdict)
        val transaction = MemoryOutputTransaction(context, "bad-video-split")
        assertIs<CoreResult.Failure>(core.split(SplitRequest(source, output = transaction, context = context)))
        assertTrue(transaction.committedAssets().isEmpty())
        val inspected = value(core.inspect(ReadRequest(source, context)))
        val raw = MemoryOutputTransaction(context, "bad-video-raw-exact")
        val extracted = value(core.extract(ExtractRequest(source, listOf(inspected.layout.resources.single { it.kind == ResourceKind.Video }.id), inspected.snapshot, output = raw, context = context)))
        assertEquals(Bytes(fake), raw.committedAssets().values.single())
        assertTrue(extracted.preservation.records.any { it.guarantee == Guarantee.ExactExtraction && it.outcome == GuaranteeOutcome.Verified })
        assertTrue(extracted.preservation.records.none { it.guarantee == Guarantee.BitstreamPreserving && it.outcome == GuaranteeOutcome.Verified })
    }

    @Test
    fun consistentV1V2BindingsShareResourceExtentsAndKeepBothMatches(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val v1 = "<camera:MicroVideo>1</camera:MicroVideo><camera:MicroVideoVersion>1</camera:MicroVideoVersion><camera:MicroVideoOffset>${video.size}</camera:MicroVideoOffset><camera:MicroVideoPresentationTimestampUs>0</camera:MicroVideoPresentationTimestampUs>"
        val source = input(GoogleFixtures.v2Photo(video, extra = v1))
        val inspected = value(core.inspect(ReadRequest(source, context)))
        assertEquals(Disposition.Live, inspected.detection.disposition)
        assertEquals(setOf(ProtocolId("google.microvideo.v1"), ProtocolId("google.motionphoto.v2")), inspected.detection.matches.map { it.target.protocol }.toSet())
        assertTrue(inspected.detection.matches.all { it.strength == MatchStrength.Strong })
        val resources = inspected.layout.resources.filter { it.kind == ResourceKind.Video }
        assertEquals(2, resources.size)
        assertEquals(resources[0].extents.single().range, resources[1].extents.single().range)
        assertEquals(listOf(resources[1].id), resources[0].sharedWith)
        assertEquals(listOf(resources[0].id), resources[1].sharedWith)
        fun counted(bytes: ByteArray): Pair<SourceSet.Single, () -> Int> {
            var videoReads = 0
            val start = (bytes.size - video.size).toULong()
            val source = TestSource(bytes, overrideRead = { offset, length ->
                if (offset >= start) videoReads++
                CoreResult.Success(Bytes(bytes.copyOfRange(offset.toInt(), offset.toInt() + length.toInt())))
            })
            return SourceSet.Single(source) to { videoReads }
        }
        val (single, singleCount) = counted(GoogleFixtures.v2Photo(video))
        val (dual, dualCount) = counted(GoogleFixtures.v2Photo(video, extra = v1))
        value(core.inspect(ReadRequest(single, context)))
        value(core.inspect(ReadRequest(dual, context)))
        assertTrue(singleCount() > 0)
        assertEquals(singleCount(), dualCount(), "The same BMFF resource must be probed once per session")
    }

    @Test
    fun disabledOrDamagedV2DoesNotEraseIndependentStrongV1Binding(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val v1 = "<camera:MicroVideo>1</camera:MicroVideo><camera:MicroVideoVersion>1</camera:MicroVideoVersion><camera:MicroVideoOffset>${video.size}</camera:MicroVideoOffset>"
        for (disabled in listOf(true, false)) {
            val xml = GoogleFixtures.v2Xml(video.size, version = if (disabled) "1" else "2", extra = v1)
                .let { if (disabled) it.replace("camera:MotionPhoto='1'", "camera:MotionPhoto='0'") else it }
            val source = input(GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(xml)) + video)
            val detection = value(core.detect(ReadRequest(source, context)))
            assertEquals(Disposition.Live, detection.disposition)
            assertEquals(ProtocolId("google.microvideo.v1"), detection.primaryProtocol?.protocol)
            assertTrue(detection.matches.any { it.target.protocol == ProtocolId("google.microvideo.v1") && it.strength == MatchStrength.Strong })
            if (disabled) assertTrue(detection.matches.none { it.target.protocol == ProtocolId("google.motionphoto.v2") })
        }
    }

    @Test
    fun badKeyMetadataDoesNotDiscardOtherwiseSafeVideoExtent(): Unit = runImmediate {
        val bytes = GoogleFixtures.v2Photo(timestamp = "-2")
        val inspected = value(core.inspect(ReadRequest(input(bytes), context)))
        val resource = inspected.layout.resources.single { it.kind == ResourceKind.Video }
        assertEquals(ByteRange((bytes.size - GoogleFixtures.video().bytes.size).toULong(), GoogleFixtures.video().bytes.size.toULong()), resource.extents.single().range)
        assertNull(inspected.keyPhoto.position)
        assertTrue(inspected.issues.any { it.severity == Severity.Error && it.layer == Layer.Protocol })
    }

    @Test
    fun requestedDecoderOrUnknownCheckCannotBeReportedComplete(): Unit = runImmediate {
        val source = input(GoogleFixtures.v2Photo())
        for (required in listOf("media.decode", "unknown.check")) {
            val report = value(core.validate(ValidationRequest(source, requiredChecks = listOf(required), context = context)))
            val check = report.checks.single { it.id == required }
            assertEquals(Coverage.NotRun, check.coverage)
            assertTrue(check.verdict != Verdict.Valid)
            assertTrue(report.coverage != Coverage.Complete)
            assertTrue((check.issues + report.issues).any { it.code == IssueCode("CAPABILITY_UNSUPPORTED") })
        }
    }

    @Test
    fun capabilitiesDistinguishImplementedJpegDefaultFromUnknownProfileAndDeviceEvidence() {
        val target = ProtocolSelector(ProtocolId("google.motionphoto.v2"))
        val entries = core.getProtocolCapabilities(target).operations
        assertEquals(Implementation.Supported, entries.single { it.operation == Operation.Detect }.implementation)
        assertEquals(Implementation.Experimental, entries.single { it.operation == Operation.Create }.implementation)
        assertTrue(entries.none { Verification.DeviceTested in it.verification })
        val unknown = core.getProtocolCapabilities(target.copy(profile = ProfileId("not-a-profile")))
        assertTrue(unknown.operations.all { it.implementation == Implementation.Unsupported })
    }

    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
