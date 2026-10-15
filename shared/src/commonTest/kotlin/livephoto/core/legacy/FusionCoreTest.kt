package livephoto.core.legacy

import livephoto.core.*
import livephoto.core.binary.BinaryReader
import livephoto.core.google.GoogleFixtures
import livephoto.core.jpeg.JpegParser
import livephoto.core.memory.*
import livephoto.core.samsung.SamsungFixtures
import livephoto.core.xmp.XmpReader
import kotlin.test.*

class FusionCoreTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolId("lpb.fusion.legacy"), ProfileId("jpeg"))
    private fun source(bytes: ByteArray, id: String = "fusion-carrier") = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun input(bytes: ByteArray) = SourceSet.Single(source(bytes))

    @Test
    fun fusionIsLegacyOverlayWithIndependentBaseMatchesAndSharedVideo(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val inspected = value(core.inspect(ReadRequest(input(LegacyFixtures.fusion(video)), context)))
        assertEquals(MatchStrength.Legacy, inspected.detection.matches.single { it.target == target }.strength)
        assertTrue(inspected.detection.matches.any { it.target.protocol == ProtocolId("samsung.motionphoto") })
        assertTrue(inspected.detection.matches.any { it.target.protocol == ProtocolId("google.motionphoto.v2") })
        assertTrue(inspected.detection.matches.any { it.target.protocol == ProtocolId("oplus.olive") })
        assertTrue(inspected.detection.disposition != Disposition.Ambiguous)
        val resources = inspected.layout.resources.filter { it.kind == ResourceKind.Video }
        assertTrue(resources.isNotEmpty())
        assertEquals(1, resources.map { it.extents.map { extent -> extent.source to extent.range } }.distinct().size)
        assertEquals(video.size.toULong(), resources.first().extents.single().range.length)
        assertTrue(resources.any { it.sharedWith.isNotEmpty() })
    }

    @Test
    fun defaultExtractionAndRawSplitCopyVideoOnceAndExplicitAliasesRejectBeforeWriting(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val bytes = LegacyFixtures.fusion(video)
        val source = input(bytes)
        val inspected = value(core.inspect(ReadRequest(source, context)))
        val raw = MemoryOutputTransaction(context, "fusion-default-extract")
        value(core.extract(ExtractRequest(source, emptyList(), inspected.snapshot, output = raw, context = context)))
        assertEquals(listOf(Bytes(video)), raw.committedAssets().values.toList())
        val split = MemoryOutputTransaction(context, "fusion-raw-split")
        val result = value(core.split(SplitRequest(source, mode = SplitMode.Raw, output = split, context = context)))
        assertEquals(1, result.output.assets.count { it.role == AssetRole.MotionVideo })
        assertEquals(Bytes(video), split.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        val aliases = inspected.layout.resources.filter { it.kind == ResourceKind.Video }.map { it.id }
        assertTrue(aliases.size > 1)
        val reject = CountingTransaction(MemoryOutputTransaction(context, "fusion-explicit-aliases"))
        val error = assertIs<CoreResult.Failure>(core.extract(ExtractRequest(source, aliases, inspected.snapshot, output = reject, context = context))).error
        assertEquals(IssueCode("INVALID_ARGUMENT"), error.code)
        assertTrue(error.details.isNotEmpty())
        assertEquals(0, reject.createCalls); assertEquals(0, reject.commitCalls)
    }

    @Test
    fun cleanPreservesOrdinarySefAndXmpAndDeletesOnlyConfirmedOverlayFields(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val bytes = LegacyFixtures.fusion(video, ordinarySef = true, ordinaryXmp = "<p:Copyright xmlns:p='urn:ordinary'>kept</p:Copyright>")
        val transaction = MemoryOutputTransaction(context, "fusion-clean")
        val result = value(core.split(SplitRequest(input(bytes), output = transaction, context = context)))
        val imageAsset = result.output.assets.single { it.role == AssetRole.PrimaryImage }
        val image = transaction.committedAssets().getValue(imageAsset.id).toByteArray()
        assertEquals(Bytes(video), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        assertEquals(listOf(SamsungFixtures.ordinary.bytes.toList()), SamsungFixtures.directory(image).map { it.raw.toList() })
        val reader = BinaryReader(source(image, "fusion-clean-image"), context)
        val jpeg = value(JpegParser.parse(reader))
        val xmp = value(XmpReader.readJpeg(reader, jpeg))
        assertEquals("kept", value(xmp.scalar("urn:ordinary", "Copyright")))
        assertNull(value(xmp.scalar("https://github.com/LengxiQwQ/live-photo-box", "Protocol")))
        assertNull(value(xmp.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhoto")))
        for (field in listOf("MotionPhotoOwner", "OLivePhotoVersion", "VideoLength", "MotionPhotoPrimaryPresentationTimestampUs")) assertNull(value(xmp.scalar("http://ns.oplus.com/photos/1.0/camera/", field)))
        for (field in listOf("VMotionPhotoVersion", "VMotionPhotoSource", "VMediaKitVersion")) assertNull(value(xmp.scalar("http://ns.vivo.com/photos/1.0/camera/", field)))
        assertEquals(Disposition.NonLive, value(core.detect(ReadRequest(input(image), context))).disposition)
        assertTrue(result.preservation.records.any { it.assetId == imageAsset.id && it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
        val strict = CountingTransaction(MemoryOutputTransaction(context, "fusion-clean-strict"))
        assertIs<CoreResult.Failure>(core.split(SplitRequest(input(bytes), policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = strict, context = context)))
        assertEquals(0, strict.commitCalls)
    }

    @Test
    fun vendorLengthAndDuplicateAssertionsCannotSelectSefByPriority(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val conflict = LegacyFixtures.fusion(video, vendorLength = video.size - 1)
        assertEquals(Disposition.Ambiguous, value(core.detect(ReadRequest(input(conflict), context))).disposition)
        val duplicateVivo = "<v:VMotionPhotoVersion xmlns:v='http://ns.vivo.com/photos/1.0/camera/'>2</v:VMotionPhotoVersion>"
        for ((index, bytes) in listOf(conflict, LegacyFixtures.fusion(video, ordinaryXmp = duplicateVivo)).withIndex()) {
            val transaction = CountingTransaction(MemoryOutputTransaction(context, "fusion-conflict-$index"))
            assertIs<CoreResult.Failure>(core.convert(ConvertRequest(input(bytes), ProtocolSelector(ProtocolId("google.motionphoto.v2"), ProfileId("jpeg")), output = transaction, context = context)))
            assertEquals(0, transaction.commitCalls)
            assertTrue(transaction.delegate.committedAssets().isEmpty())
        }
    }

    @Test
    fun authorTextWithoutConfirmedSefDoesNotCreateFusionIdentity(): Unit = runImmediate {
        val xml = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:l='https://github.com/LengxiQwQ/live-photo-box' l:Protocol='MotionPhotoFusion'/></rdf:RDF>"
        val bytes = GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(xml))
        assertTrue(value(core.detect(ReadRequest(input(bytes), context))).matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Legacy })
        val noAuthor = LegacyFixtures.fusion(marker = false)
        assertTrue(value(core.detect(ReadRequest(input(noAuthor), context))).matches.none { it.target.protocol == target.protocol })
    }

    @Test
    fun convertFromFusionWritesSingleTargetAndKeepsExactVideo(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val transaction = MemoryOutputTransaction(context, "fusion-convert-google")
        value(core.convert(ConvertRequest(input(LegacyFixtures.fusion(video)), ProtocolSelector(ProtocolId("google.motionphoto.v2"), ProfileId("jpeg")), output = transaction, context = context)))
        val carrier = transaction.committedAssets().values.single().toByteArray()
        val inspected = value(core.inspect(ReadRequest(input(carrier), context)))
        assertEquals(ProtocolId("google.motionphoto.v2"), inspected.detection.primaryProtocol?.protocol)
        assertTrue(inspected.detection.matches.none { it.target.protocol in setOf(target.protocol, ProtocolId("oplus.olive"), ProtocolId("vivo.motionphoto"), ProtocolId("samsung.motionphoto")) })
        val raw = MemoryOutputTransaction(context, "fusion-converted-raw")
        value(core.extract(ExtractRequest(input(carrier), emptyList(), inspected.snapshot, output = raw, context = context)))
        assertEquals(Bytes(video), raw.committedAssets().values.single())
    }

    @Test
    fun legacyCreationAndConvertToRemainHiddenAndUnavailable(): Unit = runImmediate {
        val caps = core.getProtocolCapabilities(target)
        for (operation in listOf(Operation.Create, Operation.ConvertTo)) {
            val capability = caps.operations.single { it.operation == operation }
            assertEquals(Implementation.Unsupported, capability.implementation)
            assertEquals(Exposure.Hidden, capability.exposure)
            assertEquals(Lifecycle.Legacy, capability.lifecycle)
        }
        assertTrue(caps.operations.none { Verification.DeviceTested in it.verification })
        val transaction = CountingTransaction(MemoryOutputTransaction(context, "fusion-hidden-create"))
        assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "image"), source(GoogleFixtures.video().bytes, "video"), target, output = transaction, context = context)))
        assertEquals(0, transaction.createCalls); assertEquals(0, transaction.commitCalls)
    }

    private class CountingTransaction(val delegate: MemoryOutputTransaction) : OutputTransaction by delegate {
        var createCalls = 0
        var commitCalls = 0
        override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> { createCalls++; return delegate.create(spec) }
        override suspend fun commit(): CoreResult<Receipt> { commitCalls++; return delegate.commit() }
    }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
