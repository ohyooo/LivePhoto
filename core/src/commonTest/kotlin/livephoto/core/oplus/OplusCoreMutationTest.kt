package livephoto.core.oplus

import livephoto.core.*
import livephoto.core.binary.BinaryReader
import livephoto.core.binary.readUnsigned
import livephoto.core.exif.TiffDocument
import livephoto.core.exif.TiffReader
import livephoto.core.google.GoogleFixtures
import livephoto.core.jpeg.JpegParser
import livephoto.core.memory.*
import livephoto.core.xmp.XmpReader
import kotlin.test.*

class OplusCoreMutationTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolId("oplus.olive"), ProfileId("jpeg-no-tail"))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun input(bytes: ByteArray) = SourceSet.Single(source(bytes, "oplus-carrier"))

    @Test
    fun createPublishesActualExifMarkerAndExactVideoWithSynchronizedTimes(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val ordinary = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:p='urn:ordinary' p:Copyright='kept'/></rdf:RDF>"
        val opaque = GoogleFixtures.segment(0xee, GoogleFixtures.bytes(3, 0xff, 0xd9, 5))
        val image = GoogleFixtures.jpeg(opaque + GoogleFixtures.xmpSegment(ordinary))
        val transaction = MemoryOutputTransaction(context, "oplus-create")
        val result = value(core.create(CreateRequest(source(image, "oplus-cover"), source(video, "oplus-video"), target, edits = EditSpec(keyPosition = CoverPosition.FrameIndex(1uL)), output = transaction, context = context)))
        val carrier = transaction.committedAssets().values.single().toByteArray()
        assertEquals(TransactionState.Committed, result.output.receipt.state)
        assertEquals(video.toList(), carrier.takeLast(video.size))
        assertTrue(contains(carrier, opaque))
        val reader = BinaryReader(source(carrier, "created-oplus"), context)
        val jpeg = value(JpegParser.parse(reader))
        assertEquals(2uL, jpeg.segments.single { it.payloadKind == livephoto.core.jpeg.AppPayloadKind.Exif }.range.offset)
        val tiff = tiff(reader)
        val comment = tiff.ifds.flatMap { it.entries }.single { it.tag == 0x9286u.toUShort() }
        assertEquals(7u.toUShort(), comment.type)
        assertEquals(Bytes("ASCII\u0000\u0000\u0000${OplusFixtures.marker}\u0000".encodeToByteArray()), comment.value)
        val packet = value(XmpReader.readJpeg(reader, jpeg))
        assertEquals("oplus", value(packet.scalar(OplusFixtures.uri, "MotionPhotoOwner")))
        assertEquals("2", value(packet.scalar(OplusFixtures.uri, "OLivePhotoVersion")))
        assertEquals(video.size.toString(), value(packet.scalar(OplusFixtures.uri, "VideoLength")))
        assertEquals("40000", value(packet.scalar(OplusFixtures.uri, "MotionPhotoPrimaryPresentationTimestampUs")))
        assertEquals("40000", value(packet.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhotoPresentationTimestampUs")))
        assertEquals("kept", value(packet.scalar("urn:ordinary", "Copyright")))
        val source = input(carrier)
        val inspected = value(core.inspect(ReadRequest(source, context)))
        assertEquals(ProtocolId("oplus.olive"), inspected.detection.primaryProtocol?.protocol)
        assertEquals(Verdict.Valid, value(core.validateProtocol(ValidationRequest(source, target = target, context = context))).verdict)
        val extract = MemoryOutputTransaction(context, "oplus-created-raw")
        value(core.extract(ExtractRequest(source, listOf(inspected.layout.resources.first { it.kind == ResourceKind.Video }.id), inspected.snapshot, output = extract, context = context)))
        assertEquals(Bytes(video), extract.committedAssets().values.single())
        assertEquals(Coverage.Partial, value(core.analyze(AnalyzeRequest(source, context = context))).validation.coverage)
    }

    @Test
    fun existingOrdinaryExifCanAddMarkerWithoutLosingEndianDimensionsOrDescription(): Unit = runImmediate {
        for (little in listOf(true, false)) {
            val transaction = MemoryOutputTransaction(context, "oplus-exif-create-$little")
            value(core.create(CreateRequest(source(OplusFixtures.ordinaryImage(littleEndian = little), "existing-exif"), source(GoogleFixtures.video().bytes, "video"), target, output = transaction, context = context)))
            val reader = BinaryReader(source(transaction.committedAssets().values.single().toByteArray(), "created-exif"), context)
            val document = tiff(reader)
            assertEquals(if (little) livephoto.core.binary.Endian.Little else livephoto.core.binary.Endian.Big, document.endian)
            assertOrdinaryExif(document)
            assertEquals(Bytes("ASCII\u0000\u0000\u0000${OplusFixtures.marker}\u0000".encodeToByteArray()), document.ifds.flatMap { it.entries }.single { it.tag == 0x9286u.toUShort() }.value)
        }
    }

    @Test
    fun ordinaryOrMixedUserCommentCannotBeSilentlyOverwrittenBeforeOutputCreation(): Unit = runImmediate {
        for (comment in listOf("ordinary photo comment", "prefix ${OplusFixtures.marker} suffix", "MotionPhoto ordinary")) {
            val transaction = CountingTransaction(MemoryOutputTransaction(context, "oplus-comment-$comment"))
            assertIs<CoreResult.Failure>(core.create(CreateRequest(source(OplusFixtures.ordinaryImage(comment), "cover-comment"), source(GoogleFixtures.video().bytes, "video-comment"), target, output = transaction, context = context)))
            assertEquals(0, transaction.createCalls)
            assertTrue(transaction.delegate.committedAssets().isEmpty())
        }
    }

    @Test
    fun cleanDeletesOnlyOwnedExifMarkerAndVendorGoogleFieldsPreservingOrdinaryExifXmpAndIcc(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val ordinary = "<p:Copyright xmlns:p='urn:ordinary'>keep &amp; retain</p:Copyright><p:Private xmlns:p='urn:ordinary'><![CDATA[oplus MotionPhoto ordinary]]></p:Private>"
        val icc = GoogleFixtures.segment(0xe2, "ICC_PROFILE\u0000".encodeToByteArray() + GoogleFixtures.bytes(1, 1, 7, 8))
        for (little in listOf(true, false)) {
            val base = OplusFixtures.photo(littleEndian = little, extra = ordinary)
            val carrier = base.copyOfRange(0, 2) + icc + base.copyOfRange(2, base.size)
            val transaction = MemoryOutputTransaction(context, "oplus-clean-$little")
            val result = value(core.split(SplitRequest(input(carrier), output = transaction, context = context)))
            assertEquals(2, result.output.assets.size)
            val image = transaction.committedAssets().getValue(result.output.assets.single { it.role == AssetRole.PrimaryImage }.id).toByteArray()
            assertEquals(Bytes(video), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
            assertTrue(contains(image, icc))
            val reader = BinaryReader(source(image, "clean-oplus-image"), context)
            val document = tiff(reader)
            assertOrdinaryExif(document)
            assertTrue(document.ifds.flatMap { it.entries }.none { it.tag == 0x9286u.toUShort() })
            val jpeg = value(JpegParser.parse(reader))
            assertEquals(0uL, jpeg.trailing.length)
            val packet = value(XmpReader.readJpeg(reader, jpeg))
            assertEquals("keep & retain", value(packet.scalar("urn:ordinary", "Copyright")))
            assertEquals("oplus MotionPhoto ordinary", value(packet.scalar("urn:ordinary", "Private")))
            for (field in listOf("MotionPhotoOwner", "OLivePhotoVersion", "VideoLength", "MotionPhotoPrimaryPresentationTimestampUs")) assertNull(value(packet.scalar(OplusFixtures.uri, field)))
            for (field in listOf("MotionPhoto", "MotionPhotoVersion", "MotionPhotoPresentationTimestampUs")) assertNull(value(packet.scalar("http://ns.google.com/photos/1.0/camera/", field)))
            assertEquals(Disposition.NonLive, value(core.detect(ReadRequest(input(image), context))).disposition)
            assertTrue(result.preservation.changes.none { it.selector.contains("urn:ordinary") })
            val second = MemoryOutputTransaction(context, "oplus-clean-again-$little")
            value(core.split(SplitRequest(input(image), output = second, context = context)))
            assertEquals(Bytes(image), second.committedAssets().values.single())
        }
    }

    @Test
    fun rawSplitPreservesProtocolJpegAndSeparatesPureVideoFromUnknownTail(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val tail = GoogleFixtures.bytes(1, 0, 255, 9)
        val carrier = OplusFixtures.photo(video, tail)
        val transaction = MemoryOutputTransaction(context, "oplus-tail-raw-split")
        val result = value(core.split(SplitRequest(input(carrier), mode = SplitMode.Raw, output = transaction, context = context)))
        assertEquals(3, result.output.assets.size)
        assertEquals(Bytes(carrier.copyOfRange(0, carrier.size - video.size - tail.size)), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.PrimaryImage }.id])
        assertEquals(Bytes(video), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        assertEquals(Bytes(tail), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.VendorTrailer }.id])
    }

    @Test
    fun unknownTailAndAuxiliaryDependenciesBlockCleanInsteadOfBeingDiscarded(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val primary = "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='image/jpeg' item:Semantic='Primary' item:Length='0' item:Padding='0'/></rdf:li>"
        val aux = "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='application/x-private' item:Semantic='VendorAux' item:Length='4'/></rdf:li>"
        val motion = "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='video/mp4' item:Semantic='MotionPhoto' item:Length='${video.size}'/></rdf:li>"
        val auxiliaryBase = OplusFixtures.photo(video, directory = primary + aux + motion)
        val auxiliary = auxiliaryBase.copyOfRange(0, auxiliaryBase.size - video.size) + GoogleFixtures.bytes(9, 8, 7, 6) + video
        for ((index, carrier) in listOf(OplusFixtures.photo(video, GoogleFixtures.bytes(9, 8, 7)), auxiliary).withIndex()) {
            val transaction = CountingTransaction(MemoryOutputTransaction(context, "oplus-dependency-$index"))
            assertIs<CoreResult.Failure>(core.split(SplitRequest(input(carrier), output = transaction, context = context)))
            assertEquals(0, transaction.createCalls)
            assertTrue(transaction.delegate.committedAssets().isEmpty())
        }
    }

    @Test
    fun tailBearingAndUnknownTargetsCannotPublishNoTailBytesUnderWrongProfile(): Unit = runImmediate {
        for (profile in listOf("oneplus-tail-bearing", "unknown")) {
            val transaction = CountingTransaction(MemoryOutputTransaction(context, "oplus-unsupported-$profile"))
            assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "cover"), source(GoogleFixtures.video().bytes, "video"), target.copy(profile = ProfileId(profile)), output = transaction, context = context)))
            assertEquals(0, transaction.createCalls)
            assertTrue(transaction.delegate.committedAssets().isEmpty())
        }
    }

    @Test
    fun outputBudgetFailureCannotPublishPartialVendorCarrier(): Unit = runImmediate {
        val low = Context(Limits(2_000_000uL, 32uL, maxMetadataBytes = 1_000_000uL))
        val transaction = MemoryOutputTransaction(low, "oplus-low-budget")
        val failure = assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "cover"), source(GoogleFixtures.video().bytes, "video"), target, output = transaction, context = low)))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), failure.error.code)
        assertTrue(transaction.committedAssets().isEmpty())
        assertTrue(value(transaction.query()).state != TransactionState.Committed)
    }

    private suspend fun tiff(reader: BinaryReader): TiffDocument {
        val jpeg = value(JpegParser.parse(reader))
        val payload = jpeg.segments.mapNotNull { it.payload }.single { range -> range.length >= 6uL && value(reader.readExactly(range.offset, 6u)) == Bytes("Exif\u0000\u0000".encodeToByteArray()) }
        return value(TiffReader(reader).read(ByteRange(payload.offset + 6uL, payload.length - 6uL)))
    }
    private fun assertOrdinaryExif(document: TiffDocument) {
        val entries = document.ifds.flatMap { it.entries }
        for (tag in listOf(0x0100, 0x0101)) assertEquals(1uL, readUnsigned(requireNotNull(entries.single { it.tag == tag.toUShort() }.value), document.endian))
        assertEquals(Bytes((OplusFixtures.description + "\u0000").encodeToByteArray()), entries.single { it.tag == 0x010eu.toUShort() }.value)
    }
    private class CountingTransaction(val delegate: MemoryOutputTransaction) : OutputTransaction by delegate {
        var createCalls = 0
        override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> { createCalls++; return delegate.create(spec) }
    }
    private fun contains(bytes: ByteArray, pattern: ByteArray) = (0..bytes.size - pattern.size).any { start -> pattern.indices.all { bytes[start + it] == pattern[it] } }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
