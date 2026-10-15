package livephoto.core.vivo

import livephoto.core.*
import livephoto.core.binary.TestSource
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

class VivoCoreReadTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolId("vivo.motionphoto"), ProfileId("jpeg"))
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("vivo-read")))

    @Test
    fun nativeTwoItemProfileIsStrongAndGoogleCompatibilityIsScoped(): Unit = runImmediate {
        val fixture = VivoFixtures.photo()
        val source = input(fixture.bytes)
        val inspected = value(core.inspect(ReadRequest(source, context)))
        assertEquals(Disposition.Live, inspected.detection.disposition)
        assertEquals(target, inspected.detection.primaryProtocol)
        assertEquals(MatchStrength.Strong, inspected.detection.matches.single { it.target.protocol == target.protocol }.strength)
        assertEquals(MatchStrength.CompatibleBase, inspected.detection.matches.single { it.target.protocol == ProtocolId("google.motionphoto.v2") }.strength)
        assertEquals(Time(0, 1_000_000u), inspected.keyPhoto.position)
        val movie = inspected.layout.resources.first { it.kind == ResourceKind.Video }
        assertEquals(ByteRange(fixture.jpegEnd.toULong(), fixture.video.size.toULong()), movie.extents.single().range)
        assertTrue(value(core.validateProtocol(ValidationRequest(source, target = target, context = context))).verdict != Verdict.Invalid)
        assertEquals(Verdict.Invalid, value(core.validateProtocol(ValidationRequest(source, target = ProtocolSelector(ProtocolId("google.motionphoto.v2"), ProfileId("jpeg")), context = context))).verdict)
        val transaction = MemoryOutputTransaction(context, "vivo-two-item-raw")
        value(core.extract(ExtractRequest(source, listOf(movie.id), inspected.snapshot, output = transaction, context = context)))
        assertEquals(Bytes(fixture.video), transaction.committedAssets().values.single())
    }

    @Test
    fun nativePresenceRulesAndLengthBoundariesRejectNonCanonicalDirectories(): Unit = runImmediate {
        val gain = VivoFixtures.gainMap()
        val cases = listOf(
            VivoFixtures.photo(primaryAttrs = "item:Length='0'"),
            VivoFixtures.photo(primaryAttrs = "item:Padding='0'"),
            VivoFixtures.photo(motionAttrs = ""),
            VivoFixtures.photo(motionAttrs = "item:Padding='1'"),
            VivoFixtures.photo(videoLength = "0"),
            VivoFixtures.photo(videoLength = ULong.MAX_VALUE.toString()),
            VivoFixtures.photo(videoLength = "18446744073709551616"),
            VivoFixtures.photo(gainMap = gain, gainAttrs = "item:Padding='0'"),
            VivoFixtures.photo(gainMap = gain, gainLength = "0"),
            VivoFixtures.photo(gainMap = gain, gainLength = (gain.size + 1).toString()),
        )
        for ((index, fixture) in cases.withIndex()) {
            val source = input(fixture.bytes)
            val validation = value(core.validateProtocol(ValidationRequest(source, target = target, context = context)))
            assertEquals(Verdict.Invalid, validation.verdict, "vivo invalid directory index=$index")
            assertTrue(value(core.detect(ReadRequest(source, context))).matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong })
            val transaction = MemoryOutputTransaction(context, "vivo-malformed-$index")
            assertIs<CoreResult.Failure>(core.split(SplitRequest(source, output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    @Test
    fun unknownVersionAndConflictingScalarsDoNotCertifyProfileButKeepSafeVideo(): Unit = runImmediate {
        for ((index, fixture) in listOf(VivoFixtures.photo(version = "2"), VivoFixtures.photo(extra = "<v:VMotionPhotoVersion>2</v:VMotionPhotoVersion>")).withIndex()) {
            val source = input(fixture.bytes)
            val inspected = value(core.inspect(ReadRequest(source, context)))
            assertTrue(inspected.detection.matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong })
            val report = value(core.validateProtocol(ValidationRequest(source, target = target, context = context)))
            assertTrue(report.verdict != Verdict.Valid)
            if (index == 0) {
                assertTrue(report.coverage != Coverage.Complete)
                assertTrue((report.issues + report.checks.flatMap { it.issues }).any { it.code == IssueCode("UNKNOWN_PROTOCOL_VARIANT") })
            } else assertTrue((report.issues + report.checks.flatMap { it.issues }).any { it.code == IssueCode("CONFLICTING_METADATA") })
            val movie = inspected.layout.resources.first { it.kind == ResourceKind.Video }
            val transaction = MemoryOutputTransaction(context, "vivo-unknown-raw-$index")
            value(core.extract(ExtractRequest(source, listOf(movie.id), inspected.snapshot, output = transaction, context = context)))
            assertEquals(Bytes(fixture.video), transaction.committedAssets().values.single())
        }
    }

    @Test
    fun namespaceAliasesAndRdfElementFieldsPreserveSemanticAuthority(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val xml = VivoFixtures.xml(video.size.toString()).replace("xmlns:v=", "xmlns:alias=").replace("v:", "alias:")
            .replace(" alias:VMotionPhotoVersion='1'", "")
            .replace("<container:Directory>", "<alias:VMotionPhotoVersion>1</alias:VMotionPhotoVersion><container:Directory>")
        val source = input(GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(xml)) + video)
        assertEquals(target, value(core.detect(ReadRequest(source, context))).primaryProtocol)
        assertTrue(value(core.validateProtocol(ValidationRequest(source, target = target, context = context))).verdict != Verdict.Invalid)
        val future = input(VivoFixtures.photo(source = "future-camera", kit = "3.0.future").bytes)
        assertEquals(target, value(core.detect(ReadRequest(future, context))).primaryProtocol)
        assertTrue(value(core.validateProtocol(ValidationRequest(future, target = target, context = context))).verdict != Verdict.Invalid)
    }

    @Test
    fun vendorTextOrNestedForeignPropertiesDoNotUpgradeOrdinaryGoogleCarrier(): Unit = runImmediate {
        val foreign = "<p:Private xmlns:p='urn:ordinary'><rdf:RDF><rdf:Description xmlns:v='${VivoFixtures.uri}' v:VMotionPhotoVersion='1' v:VMotionPhotoSource='1' v:VMediaKitVersion='1.0.0.9'/></rdf:RDF></p:Private>"
        for (bytes in listOf(GoogleFixtures.v2Photo(extra = foreign), GoogleFixtures.jpeg(GoogleFixtures.segment(0xee, VivoFixtures.uri.encodeToByteArray())))) {
            val found = value(core.detect(ReadRequest(input(bytes), context)))
            assertTrue(found.matches.none { it.target.protocol == target.protocol })
        }
    }

    @Test
    fun ftypStubCannotProduceStrongVivoBindingOrCleanOutput(): Unit = runImmediate {
        val fake = GoogleFixtures.box("ftyp", "isom".encodeToByteArray() + GoogleFixtures.u32(0u) + "mp42".encodeToByteArray()) + GoogleFixtures.box("mdat", byteArrayOf(1, 2))
        val source = input(VivoFixtures.photo(video = fake).bytes)
        assertTrue(value(core.detect(ReadRequest(source, context))).matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong })
        assertEquals(Verdict.Invalid, value(core.validateMedia(ValidationRequest(source, target = target, context = context))).verdict)
        val transaction = MemoryOutputTransaction(context, "vivo-fake-video")
        assertIs<CoreResult.Failure>(core.split(SplitRequest(source, output = transaction, context = context)))
        assertTrue(transaction.committedAssets().isEmpty())
    }

    @Test
    fun keyMissingNegativeOneZeroAndExtractionSnapshotRemainDistinct(): Unit = runImmediate {
        for (timestamp in listOf<String?>(null, "-1", "0")) {
            val facts = value(core.inspect(ReadRequest(input(VivoFixtures.photo(timestamp = timestamp).bytes), context)))
            assertEquals(if (timestamp == null) emptyList() else listOf(Value.Text(timestamp)), facts.keyPhoto.rawFields.map { it.rawValue }.distinct())
            if (timestamp == "0") assertEquals(Time(0, 1_000_000u), facts.keyPhoto.position)
            else assertTrue(facts.keyPhoto.position == null || facts.keyPhoto.source == KeySource.DerivedDefault)
        }
        val source = TestSource(VivoFixtures.photo().bytes)
        val input = SourceSet.Single(source)
        val inspected = value(core.inspect(ReadRequest(input, context)))
        source.currentIdentity = source.currentIdentity.copy(generation = GenerationToken("vivo-new-generation"))
        val transaction = MemoryOutputTransaction(context, "vivo-stale")
        val error = assertIs<CoreResult.Failure>(core.extract(ExtractRequest(input, listOf(inspected.layout.resources.first { it.kind == ResourceKind.Video }.id), inspected.snapshot, output = transaction, context = context))).error
        assertEquals(IssueCode("SOURCE_CHANGED"), error.code)
        assertTrue(transaction.committedAssets().isEmpty())
        assertFalse(source.closed)
    }

    @Test
    fun capabilitiesKeepModernWriterExperimentalAndLegacySeparate() {
        val modern = core.getProtocolCapabilities(target)
        assertEquals(Implementation.Experimental, modern.operations.single { it.operation == Operation.Create }.implementation)
        assertTrue(modern.operations.none { Verification.DeviceTested in it.verification })
        val legacy = core.getProtocolCapabilities(ProtocolSelector(ProtocolId("vivo.legacy-pair"), ProfileId("pair")))
        assertTrue(legacy.operations.single { it.operation == Operation.Create }.implementation != Implementation.Supported)
        assertTrue(core.getProtocolCapabilities(target.copy(profile = ProfileId("unknown"))).operations.all { it.implementation == Implementation.Unsupported })
    }

    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
