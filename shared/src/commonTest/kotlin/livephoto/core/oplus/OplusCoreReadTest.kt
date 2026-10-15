package livephoto.core.oplus

import livephoto.core.*
import livephoto.core.binary.TestSource
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

class OplusCoreReadTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("oplus-read")))
    private val selector = ProtocolSelector(ProtocolId("oplus.olive"))

    @Test
    fun noTailKeepsOplusStrongAndGoogleCompatibleBaseWithoutAmbiguity(): Unit = runImmediate {
        val inspection = value(core.inspect(ReadRequest(input(OplusFixtures.photo()), context)))
        assertEquals(Disposition.Live, inspection.detection.disposition)
        assertEquals(ProtocolSelector(ProtocolId("oplus.olive"), ProfileId("jpeg-no-tail")), inspection.detection.primaryProtocol)
        val oplus = inspection.detection.matches.single { it.target.protocol == ProtocolId("oplus.olive") }
        val google = inspection.detection.matches.single { it.target.protocol == ProtocolId("google.motionphoto.v2") }
        assertEquals(MatchStrength.Strong, oplus.strength)
        assertEquals(MatchStrength.CompatibleBase, google.strength)
        val videos = inspection.layout.resources.filter { it.kind == ResourceKind.Video }
        assertTrue(videos.isNotEmpty())
        assertEquals(1, videos.flatMap { it.extents }.map { it.range }.distinct().size)
        if (videos.size == 2) {
            assertEquals(listOf(videos[1].id), videos[0].sharedWith)
            assertEquals(listOf(videos[0].id), videos[1].sharedWith)
        }
        assertEquals(Verdict.Valid, value(core.validateProtocol(ValidationRequest(input(OplusFixtures.photo()), target = selector, context = context))).verdict)
        assertEquals(Verdict.Valid, value(core.validateProtocol(ValidationRequest(input(OplusFixtures.photo(version = "1", comment = "oplus_8388608")), target = selector, context = context))).verdict)
        val compatibility = input(OplusFixtures.photo(secondaryPadding = "0"))
        val vendor = value(core.validateProtocol(ValidationRequest(compatibility, target = selector, context = context)))
        assertTrue(vendor.verdict != Verdict.Invalid, "Explicit zero secondary padding is a vendor compatibility form")
        val googleReport = value(core.validateProtocol(ValidationRequest(compatibility, target = ProtocolSelector(ProtocolId("google.motionphoto.v2"), ProfileId("jpeg")), context = context)))
        assertEquals(Verdict.Invalid, googleReport.verdict, "Vendor compatibility cannot certify canonical Google directory semantics")
    }

    @Test
    fun tailBearingLayoutAndRawExtractionUseVNotDirectoryD(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val tail = GoogleFixtures.bytes(0x7f, 0xff, 0xd9, 0, 9, 8, 7)
        val bytes = OplusFixtures.photo(video, tail, secondaryPadding = "0")
        val source = input(bytes)
        val inspection = value(core.inspect(ReadRequest(source, context)))
        assertEquals(ProfileId("oneplus-tail-bearing"), inspection.detection.primaryProtocol?.profile)
        assertTrue(value(core.validateProtocol(ValidationRequest(source, target = selector, context = context))).verdict != Verdict.Invalid)
        assertEquals(Verdict.Invalid, value(core.validateProtocol(ValidationRequest(source, target = ProtocolSelector(ProtocolId("google.motionphoto.v2"), ProfileId("jpeg")), context = context))).verdict)
        val movie = inspection.layout.resources.first { it.kind == ResourceKind.Video }
        val trailer = inspection.layout.resources.single { it.kind == ResourceKind.Trailer }
        assertEquals(ByteRange((bytes.size - video.size - tail.size).toULong(), video.size.toULong()), movie.extents.single().range)
        assertEquals(ByteRange((bytes.size - tail.size).toULong(), tail.size.toULong()), trailer.extents.single().range)
        val transaction = MemoryOutputTransaction(context, "oplus-tail-extract")
        val result = value(core.extract(ExtractRequest(source, listOf(movie.id, trailer.id), inspection.snapshot, output = transaction, context = context)))
        assertEquals(2, result.output.assets.size)
        val extractedVideo = result.output.assets.single { it.role == AssetRole.MotionVideo }
        val extractedTail = result.output.assets.single { it.role == AssetRole.VendorTrailer }
        assertEquals(Bytes(video), transaction.committedAssets()[extractedVideo.id])
        assertEquals(Bytes(tail), transaction.committedAssets()[extractedTail.id])
        val records = result.preservation.records.filter { it.guarantee == Guarantee.ExactExtraction }
        assertEquals(2, records.size)
        assertTrue(records.all { it.outcome == GuaranteeOutcome.Verified })
    }

    @Test
    fun malformedVAndDRejectOutOfBoundsOverflowAndPrimaryOverlap(): Unit = runImmediate {
        val length = GoogleFixtures.video().bytes.size
        val cases = listOf(
            OplusFixtures.photo(videoLength = "0"),
            OplusFixtures.photo(videoLength = (length + 1).toString()),
            OplusFixtures.photo(videoLength = "-1"),
            OplusFixtures.photo(videoLength = ULong.MAX_VALUE.toString()),
            OplusFixtures.photo(videoLength = "18446744073709551616"),
            OplusFixtures.photo(directoryLength = "0"),
            OplusFixtures.photo(directoryLength = "999999"),
            OplusFixtures.photo(directoryLength = (length + 1).toString()),
            OplusFixtures.photo(extra = "<o:VideoLength>1</o:VideoLength>"),
        )
        for ((index, bytes) in cases.withIndex()) {
            val report = value(core.validateProtocol(ValidationRequest(input(bytes), target = selector, context = context)))
            assertEquals(Verdict.Invalid, report.verdict, "Malformed V/D index=$index")
            assertTrue(issues(report).any { it.severity == Severity.Error })
            val transaction = MemoryOutputTransaction(context, "oplus-invalid-$index")
            assertIs<CoreResult.Failure>(core.split(SplitRequest(input(bytes), output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
        for ((index, bytes) in listOf(OplusFixtures.photo(owner = "unknown-owner"), OplusFixtures.photo(version = "3")).withIndex()) {
            val report = value(core.validateProtocol(ValidationRequest(input(bytes), target = selector, context = context)))
            assertTrue(report.verdict != Verdict.Valid)
            assertTrue(report.coverage != Coverage.Complete)
            assertTrue(issues(report).any { it.code == IssueCode("UNKNOWN_PROTOCOL_VARIANT") })
            val transaction = MemoryOutputTransaction(context, "oplus-unknown-variant-$index")
            assertIs<CoreResult.Failure>(core.split(SplitRequest(input(bytes), output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    @Test
    fun originalCaptureAndCurrentCoverAreDistinctAndBothRawValuesRemainVisible(): Unit = runImmediate {
        val source = input(OplusFixtures.photo(googleTimestamp = "0", vendorTimestamp = "40000"))
        val inspected = value(core.inspect(ReadRequest(source, context)))
        assertEquals(setOf(Value.Text("0"), Value.Text("40000")), inspected.keyPhoto.rawFields.map { it.rawValue }.toSet())
        assertEquals(Time(0, 1_000_000u), inspected.keyPhoto.position)
        assertTrue((inspected.issues + inspected.keyPhoto.issues).none { it.code == IssueCode("CONFLICTING_METADATA") })
        val report = value(core.validateProtocol(ValidationRequest(source, target = selector, context = context)))
        assertEquals(Verdict.Valid, report.verdict)
        assertTrue(issues(report).none { it.code == IssueCode("CONFLICTING_METADATA") })
    }

    @Test
    fun absentNegativeAndZeroTimestampsRemainDistinctWithoutDroppingSafeVideo(): Unit = runImmediate {
        for (time in listOf<String?>(null, "-1", "0")) {
            val inspected = value(core.inspect(ReadRequest(input(OplusFixtures.photo(googleTimestamp = time, vendorTimestamp = time)), context)))
            assertTrue(inspected.layout.resources.any { it.kind == ResourceKind.Video })
            assertEquals(if (time == null) emptyList() else listOf(Value.Text(time)), inspected.keyPhoto.rawFields.map { it.rawValue }.distinct())
            if (time == "0") {
                assertEquals(Time(0, 1_000_000u), inspected.keyPhoto.position)
                assertEquals(KeySource.ProtocolField, inspected.keyPhoto.source)
            } else assertTrue(inspected.keyPhoto.position == null || inspected.keyPhoto.source == KeySource.DerivedDefault)
        }
    }

    @Test
    fun missingOrOrdinaryUserCommentDoesNotSatisfyVendorMarker(): Unit = runImmediate {
        for (comment in listOf<String?>(null, "ordinary text MotionPhoto", "ordinary ${OplusFixtures.marker} text")) {
            val report = value(core.validateProtocol(ValidationRequest(input(OplusFixtures.photo(comment = comment)), target = selector, context = context)))
            assertTrue(issues(report).any { it.code == IssueCode("MISSING_VENDOR_MARKER") }, "comment=$comment")
            assertTrue(report.verdict != Verdict.Valid)
        }
    }

    @Test
    fun markerInOrdinaryXmpCannotReplaceFinalExifUserComment(): Unit = runImmediate {
        val source = input(OplusFixtures.photo(comment = null, extra = "<p:Template xmlns:p='urn:private'>${OplusFixtures.marker}</p:Template>"))
        val report = value(core.validateProtocol(ValidationRequest(source, target = selector, context = context)))
        assertTrue(issues(report).any { it.code == IssueCode("MISSING_VENDOR_MARKER") })
        assertTrue(report.verdict != Verdict.Valid)
    }

    @Test
    fun prefixAliasesAndRdfElementVendorScalarsPreserveAuthority(): Unit = runImmediate {
        val original = OplusFixtures.photo().decodeToString()
        // Modify only the UTF-8 XML region; preserve arbitrary JPEG/video bytes verbatim.
        val bytes = OplusFixtures.photo()
        val start = indexOf(bytes, "<x:xmpmeta".encodeToByteArray())
        val end = indexOf(bytes, "</x:xmpmeta>".encodeToByteArray()) + "</x:xmpmeta>".length
        assertTrue(original.contains(OplusFixtures.uri))
        val xml = bytes.copyOfRange(start, end).decodeToString().replace("xmlns:o", "xmlns:alias").replace("o:", "alias:")
            .replace(" alias:VideoLength='${GoogleFixtures.video().bytes.size}'", "")
            .replace("<container:Directory>", "<alias:VideoLength>${GoogleFixtures.video().bytes.size}</alias:VideoLength><container:Directory>")
        val updated = GoogleFixtures.jpeg(OplusFixtures.exifSegment(OplusFixtures.marker) + GoogleFixtures.xmpSegment(xml)) + GoogleFixtures.video().bytes
        assertEquals(ProtocolId("oplus.olive"), value(core.detect(ReadRequest(input(updated), context))).primaryProtocol?.protocol)
        assertEquals(Verdict.Valid, value(core.validateProtocol(ValidationRequest(input(updated), target = selector, context = context))).verdict)
    }

    @Test
    fun nestedForeignVendorFieldsDoNotUpgradeGoogleBinding(): Unit = runImmediate {
        val foreign = "<p:Private xmlns:p='urn:private'><rdf:RDF><rdf:Description xmlns:o='${OplusFixtures.uri}' o:MotionPhotoOwner='oplus' o:OLivePhotoVersion='2' o:VideoLength='${GoogleFixtures.video().bytes.size}'/></rdf:RDF></p:Private>"
        val source = input(GoogleFixtures.v2Photo(extra = foreign))
        val inspected = value(core.inspect(ReadRequest(source, context)))
        assertEquals(ProtocolId("google.motionphoto.v2"), inspected.detection.primaryProtocol?.protocol)
        assertTrue(inspected.detection.matches.none { it.target.protocol == ProtocolId("oplus.olive") })
        assertTrue(inspected.metadata.filter { it.selector.contains(OplusFixtures.uri) }.all { it.owner != Ownership.SourceProtocol })
    }

    @Test
    fun fakeFtypCannotMakeStrongVendorCandidateOrPublishCleanOutput(): Unit = runImmediate {
        val fake = GoogleFixtures.box("ftyp", "isom".encodeToByteArray() + GoogleFixtures.u32(0u) + "mp42".encodeToByteArray()) + GoogleFixtures.box("mdat", GoogleFixtures.bytes(1, 2, 3))
        val source = input(OplusFixtures.photo(fake))
        assertTrue(value(core.detect(ReadRequest(source, context))).matches.none { it.target.protocol == ProtocolId("oplus.olive") && it.strength == MatchStrength.Strong })
        assertEquals(Verdict.Invalid, value(core.validateMedia(ValidationRequest(source, target = selector, context = context))).verdict)
        val transaction = MemoryOutputTransaction(context, "oplus-fake")
        assertIs<CoreResult.Failure>(core.split(SplitRequest(source, output = transaction, context = context)))
        assertTrue(transaction.committedAssets().isEmpty())
    }

    @Test
    fun rawTailExtractionRejectsSnapshotChangedAfterInspection(): Unit = runImmediate {
        val source = TestSource(OplusFixtures.photo(tail = GoogleFixtures.bytes(9, 8, 7)))
        val input = SourceSet.Single(source)
        val inspected = value(core.inspect(ReadRequest(input, context)))
        val trailer = inspected.layout.resources.single { it.kind == ResourceKind.Trailer }
        source.currentIdentity = source.currentIdentity.copy(generation = GenerationToken("oplus-changed"))
        val transaction = MemoryOutputTransaction(context, "oplus-stale-tail")
        val failure = assertIs<CoreResult.Failure>(core.extract(ExtractRequest(input, listOf(trailer.id), inspected.snapshot, output = transaction, context = context)))
        assertEquals(IssueCode("SOURCE_CHANGED"), failure.error.code)
        assertTrue(transaction.committedAssets().isEmpty())
        assertFalse(source.closed)
    }

    @Test
    fun deviceEvidenceAndTailWriterCapabilitiesRemainHonest() {
        val noTail = core.getProtocolCapabilities(selector.copy(profile = ProfileId("jpeg-no-tail")))
        assertEquals(Implementation.Experimental, noTail.operations.single { it.operation == Operation.Create }.implementation)
        assertTrue(noTail.operations.none { Verification.DeviceTested in it.verification })
        val tail = core.getProtocolCapabilities(selector.copy(profile = ProfileId("oneplus-tail-bearing")))
        assertTrue(tail.operations.single { it.operation == Operation.Create }.implementation != Implementation.Supported)
        assertTrue(tail.operations.none { Verification.DeviceTested in it.verification })
        assertTrue(core.getProtocolCapabilities(selector.copy(profile = ProfileId("unknown"))).operations.all { it.implementation == Implementation.Unsupported })
    }

    private fun issues(report: ValidationReport) = report.issues + report.checks.flatMap { it.issues }
    private fun indexOf(bytes: ByteArray, pattern: ByteArray): Int = (0..bytes.size - pattern.size).first { start -> pattern.indices.all { bytes[start + it] == pattern[it] } }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
