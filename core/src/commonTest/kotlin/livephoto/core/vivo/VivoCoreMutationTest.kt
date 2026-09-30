package livephoto.core.vivo

import livephoto.core.*
import livephoto.core.binary.BinaryReader
import livephoto.core.google.GoogleFixtures
import livephoto.core.jpeg.JpegParser
import livephoto.core.memory.*
import livephoto.core.oplus.OplusFixtures
import livephoto.core.xmp.XmpReader
import kotlin.test.*

class VivoCoreMutationTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolId("vivo.motionphoto"), ProfileId("jpeg"))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun input(bytes: ByteArray) = SourceSet.Single(source(bytes, "vivo-carrier"))

    @Test
    fun createWritesOnlyThreeVendorFieldsAndPreservesOrdinaryUserComment(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val exif = OplusFixtures.exifSegment("ordinary AF AEC comment")
        val copyright = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:p='urn:ordinary' p:Copyright='kept'/></rdf:RDF>"
        val image = GoogleFixtures.jpeg(exif + GoogleFixtures.xmpSegment(copyright))
        val transaction = MemoryOutputTransaction(context, "vivo-create")
        val result = value(core.create(CreateRequest(source(image, "vivo-cover"), source(video, "vivo-video"), target, edits = EditSpec(keyPosition = CoverPosition.FrameIndex(1uL)), output = transaction, context = context)))
        val carrier = transaction.committedAssets().values.single().toByteArray()
        assertTrue(contains(carrier, exif))
        assertEquals(video.toList(), carrier.takeLast(video.size))
        val reader = BinaryReader(source(carrier, "created-vivo"), context)
        val jpeg = value(JpegParser.parse(reader))
        val collection = value(XmpReader.readJpeg(reader, jpeg))
        assertEquals("1", value(collection.scalar(VivoFixtures.uri, "VMotionPhotoVersion")))
        assertEquals("1", value(collection.scalar(VivoFixtures.uri, "VMotionPhotoSource")))
        assertEquals("1.0.0.9", value(collection.scalar(VivoFixtures.uri, "VMediaKitVersion")))
        assertEquals("40000", value(collection.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhotoPresentationTimestampUs")))
        assertEquals("kept", value(collection.scalar("urn:ordinary", "Copyright")))
        assertNull(value(collection.scalar(VivoFixtures.hdrUri, "Version")))
        val fields = collection.packets.single().descriptions.flatMap { it.attributes }.filter { it.name.expanded.uri == VivoFixtures.uri }.map { it.name.expanded.local }.toSet()
        assertEquals(setOf("VMotionPhotoVersion", "VMotionPhotoSource", "VMediaKitVersion"), fields)
        assertEquals(fields.map { "{${VivoFixtures.uri}}$it" }.toSet(), result.preservation.changes.filter { it.selector.startsWith("{${VivoFixtures.uri}}") }.map { it.selector }.toSet())
        val source = input(carrier)
        val inspected = value(core.inspect(ReadRequest(source, context)))
        assertEquals(target, inspected.detection.primaryProtocol)
        assertTrue(inspected.layout.resources.none { it.kind == ResourceKind.GainMap })
        assertTrue(value(core.validateProtocol(ValidationRequest(source, target = target, context = context))).verdict != Verdict.Invalid)
        val raw = MemoryOutputTransaction(context, "vivo-created-extract")
        value(core.extract(ExtractRequest(source, listOf(inspected.layout.resources.first { it.kind == ResourceKind.Video }.id), inspected.snapshot, output = raw, context = context)))
        assertEquals(Bytes(video), raw.committedAssets().values.single())
        assertEquals(TransactionState.Committed, result.output.receipt.state)
    }

    @Test
    fun cleanPlainProfileRemovesOwnedFieldsKeepingExifIccAndOrdinaryText(): Unit = runImmediate {
        val exif = OplusFixtures.exifSegment("ordinary live-photo camera comment")
        val icc = GoogleFixtures.segment(0xe2, "ICC_PROFILE\u0000".encodeToByteArray() + GoogleFixtures.bytes(1, 1, 9, 8))
        val extra = "<p:Comment xmlns:p='urn:ordinary'><![CDATA[VCamera VMotionPhotoSource ordinary]]></p:Comment>"
        val fixture = VivoFixtures.photo(extra = extra, extraSegments = exif + icc)
        val transaction = MemoryOutputTransaction(context, "vivo-clean")
        val result = value(core.split(SplitRequest(input(fixture.bytes), output = transaction, context = context)))
        val image = transaction.committedAssets().getValue(result.output.assets.single { it.role == AssetRole.PrimaryImage }.id).toByteArray()
        assertEquals(Bytes(fixture.video), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        assertTrue(contains(image, exif)); assertTrue(contains(image, icc))
        val reader = BinaryReader(source(image, "vivo-clean-image"), context)
        val jpeg = value(JpegParser.parse(reader))
        assertEquals(0uL, jpeg.trailing.length)
        val packet = value(XmpReader.readJpeg(reader, jpeg))
        assertEquals("VCamera VMotionPhotoSource ordinary", value(packet.scalar("urn:ordinary", "Comment")))
        for (field in listOf("VMotionPhotoVersion", "VMotionPhotoSource", "VMediaKitVersion")) assertNull(value(packet.scalar(VivoFixtures.uri, field)))
        assertNull(value(packet.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhoto")))
        assertEquals(Disposition.NonLive, value(core.detect(ReadRequest(input(image), context))).disposition)
        assertTrue(result.preservation.changes.none { it.selector.contains("urn:ordinary") || it.selector == "exif:UserComment" })
        val again = MemoryOutputTransaction(context, "vivo-clean-again")
        value(core.split(SplitRequest(input(image), output = again, context = context)))
        assertEquals(Bytes(image), again.committedAssets().values.single())
    }

    @Test
    fun rawSplitRetainsNativeProtocolJpegAndExactMotionVideo(): Unit = runImmediate {
        val fixture = VivoFixtures.photo()
        val transaction = MemoryOutputTransaction(context, "vivo-raw-split")
        val result = value(core.split(SplitRequest(input(fixture.bytes), mode = SplitMode.Raw, output = transaction, context = context)))
        assertEquals(Bytes(fixture.bytes.copyOfRange(0, fixture.jpegEnd)), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.PrimaryImage }.id])
        assertEquals(Bytes(fixture.video), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
    }

    @Test
    fun planBindsTwoInputsAndHonorsSourceBudgetWithoutCreatingOutput(): Unit = runImmediate {
        val image = source(GoogleFixtures.jpeg(), "vivo-plan-image")
        val video = source(GoogleFixtures.video().bytes, "vivo-plan-video")
        val transaction = MemoryOutputTransaction(context, "vivo-plan")
        val request = CreateRequest(image, video, target, output = transaction, context = context)
        val plan = value(core.plan(request))
        assertEquals(setOf(SourceId("vivo-plan-image"), SourceId("vivo-plan-video")), plan.snapshot.identities.map { it.id }.toSet())
        assertTrue(value(transaction.query()).assetIds.isEmpty())
        val limited = context.copy(limits = context.limits.copy(maxSources = 1u))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(core.create(request.copy(context = limited))).error.code)
        assertTrue(transaction.committedAssets().isEmpty())
    }

    @Test
    fun unsupportedTargetAndAlreadyLiveCoverCannotBePublishedAsModernVivo(): Unit = runImmediate {
        for ((index, pair) in listOf(
            target.copy(profile = ProfileId("heic")) to GoogleFixtures.jpeg(),
            target.copy(profile = ProfileId("unknown")) to GoogleFixtures.jpeg(),
            target to VivoFixtures.photo().bytes,
        ).withIndex()) {
            val transaction = MemoryOutputTransaction(context, "vivo-unsupported-$index")
            assertIs<CoreResult.Failure>(core.create(CreateRequest(source(pair.second, "cover"), source(GoogleFixtures.video().bytes, "video"), pair.first, output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    @Test
    fun opaqueMpfDependencyRefusesMutationWithoutDiscardingGainMap(): Unit = runImmediate {
        val mpf = GoogleFixtures.segment(0xe2, "MPF\u0000".encodeToByteArray() + GoogleFixtures.bytes(1, 2, 3, 4))
        val fixture = VivoFixtures.photo(gainMap = VivoFixtures.gainMap(), extraSegments = mpf)
        val transaction = CountingTransaction(MemoryOutputTransaction(context, "vivo-mpf-gate"))
        assertIs<CoreResult.Failure>(core.split(SplitRequest(input(fixture.bytes), output = transaction, context = context)))
        assertEquals(0, transaction.commitCalls)
        assertTrue(transaction.delegate.committedAssets().isEmpty())
    }

    @Test
    fun outputBudgetFailureDoesNotPublishCarrierPrefix(): Unit = runImmediate {
        val low = Context(Limits(2_000_000uL, 32uL, maxMetadataBytes = 1_000_000uL))
        val transaction = MemoryOutputTransaction(low, "vivo-low-budget")
        val error = assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "cover"), source(GoogleFixtures.video().bytes, "video"), target, output = transaction, context = low))).error
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), error.code)
        assertTrue(transaction.committedAssets().isEmpty())
    }

    private class CountingTransaction(val delegate: MemoryOutputTransaction) : OutputTransaction by delegate {
        var commitCalls = 0
        override suspend fun commit(): CoreResult<Receipt> { commitCalls++; return delegate.commit() }
    }
    private fun contains(bytes: ByteArray, pattern: ByteArray) = (0..bytes.size - pattern.size).any { start -> pattern.indices.all { bytes[start + it] == pattern[it] } }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
