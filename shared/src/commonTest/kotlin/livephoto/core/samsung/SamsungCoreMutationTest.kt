package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.BinaryReader
import livephoto.core.google.GoogleFixtures
import livephoto.core.jpeg.JpegParser
import livephoto.core.memory.*
import livephoto.core.xmp.XmpReader
import kotlin.test.*

class SamsungCoreMutationTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolId("samsung.motionphoto"), ProfileId("jpeg-sef-mpv3"))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun input(bytes: ByteArray) = SourceSet.Single(source(bytes, "samsung-carrier"))

    @Test
    fun createUsesCanonicalFooterIndependentRecordOracleAndByteExactVideoRoundTrip(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val copyright = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:p='urn:ordinary' p:Copyright='kept'/></rdf:RDF>"
        val opaque = GoogleFixtures.segment(0xee, GoogleFixtures.bytes(1, 0xff, 0xd9, 2))
        val image = GoogleFixtures.jpeg(opaque + GoogleFixtures.xmpSegment(copyright))
        val transaction = MemoryOutputTransaction(context, "samsung-create")
        val result = value(core.create(CreateRequest(source(image, "samsung-cover"), source(video, "samsung-video"), target, output = transaction, context = context)))
        val carrier = transaction.committedAssets().values.single().toByteArray()
        assertEquals(TransactionState.Committed, result.output.receipt.state)
        for (name in listOf("MotionPhoto_Data", "MotionPhoto_Version")) assertTrue(result.preservation.changes.any { it.selector == "samsung:sef:$name" && it.after != null && it.requested })
        val records = SamsungFixtures.directory(carrier)
        assertEquals(2, records.size)
        assertEquals(36u, SamsungFixtures.read32(carrier, carrier.size - 8))
        val motion = records.single { it.type == 0x0a30 }
        assertEquals(0, motion.prefix)
        assertEquals("MotionPhoto_Data", motion.name)
        assertEquals(24 + video.size, motion.size)
        assertContentEquals(video, motion.payload)
        assertContentEquals("mpv3".encodeToByteArray(), records.single { it.type == 0x0a31 && it.name == "MotionPhoto_Version" }.payload)
        assertTrue(contains(carrier, opaque))
        val source = input(carrier)
        val inspected = value(core.inspect(ReadRequest(source, context)))
        assertEquals(target, inspected.detection.primaryProtocol)
        assertEquals(Verdict.Valid, value(core.validateProtocol(ValidationRequest(source, target = target, context = context))).verdict)
        val movie = inspected.layout.resources.first { it.kind == ResourceKind.Video }
        assertEquals(ByteRange((motion.start + 24).toULong(), video.size.toULong()), movie.extents.single().range)
        val extraction = MemoryOutputTransaction(context, "samsung-create-extract")
        value(core.extract(ExtractRequest(source, listOf(movie.id), inspected.snapshot, output = extraction, context = context)))
        assertEquals(Bytes(video), extraction.committedAssets().values.single())
        val reader = BinaryReader(source.source, context)
        val packet = value(XmpReader.readJpeg(reader, value(JpegParser.parse(reader))))
        assertEquals("kept", value(packet.scalar("urn:ordinary", "Copyright")))
        assertEquals(Coverage.Partial, value(core.analyze(AnalyzeRequest(source, context = context))).validation.coverage)
    }

    @Test
    fun cleanKeepsOrdinarySefRecordAndRebuildsCanonicalIndexWithoutLiveRecords(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val ordinary = "<p:Copyright xmlns:p='urn:ordinary'>kept &amp; retained</p:Copyright>"
        val icc = GoogleFixtures.segment(0xe2, "ICC_PROFILE\u0000".encodeToByteArray() + GoogleFixtures.bytes(1, 1, 5, 6))
        val secondOrdinary = SamsungFixtures.Record(0x2222, "Other_Private_Data", GoogleFixtures.bytes(17, 0, 0, 0, 5))
        val ordered = SamsungFixtures.photo(video, ordinaryRecord = true, xmp = true, extraSegments = icc, extraXmp = ordinary, extraRecords = listOf(secondOrdinary))
        val reversedIndex = ordered.bytes.copyOf()
        val third = ordered.tableStart + 12 + 24
        val fourth = third + 12
        ordered.bytes.copyOfRange(third, third + 12).copyInto(reversedIndex, fourth)
        ordered.bytes.copyOfRange(fourth, fourth + 12).copyInto(reversedIndex, third)
        val fixture = ordered.copy(bytes = reversedIndex)
        val transaction = MemoryOutputTransaction(context, "samsung-clean-ordinary-sef")
        val result = value(core.split(SplitRequest(input(fixture.bytes), output = transaction, context = context)))
        val image = transaction.committedAssets().getValue(result.output.assets.single { it.role == AssetRole.PrimaryImage }.id).toByteArray()
        assertEquals(Bytes(video), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        val records = SamsungFixtures.directory(image)
        assertEquals(2, records.size)
        assertEquals(36u, SamsungFixtures.read32(image, image.size - 8))
        assertContentEquals(SamsungFixtures.ordinary.bytes, records.single { it.type == 0x1234 }.raw)
        assertContentEquals(secondOrdinary.bytes, records.single { it.type == 0x2222 }.raw)
        assertTrue(records.none { it.type == 0x0a30 || it.type == 0x0a31 })
        assertTrue(contains(image, icc))
        val clean = input(image)
        assertEquals(Disposition.NonLive, value(core.detect(ReadRequest(clean, context))).disposition)
        val reader = BinaryReader(clean.source, context)
        val packet = value(XmpReader.readJpeg(reader, value(JpegParser.parse(reader))))
        assertEquals("kept & retained", value(packet.scalar("urn:ordinary", "Copyright")))
        assertNull(value(packet.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhoto")))
        assertTrue(result.preservation.changes.none { it.selector.contains("urn:ordinary") })
        val again = MemoryOutputTransaction(context, "samsung-clean-ordinary-again")
        value(core.split(SplitRequest(clean, output = again, context = context)))
        assertEquals(Bytes(image), again.committedAssets().values.single())
        assertTrue(result.preservation.records.any { it.assetId == result.output.assets.single { asset -> asset.role == AssetRole.PrimaryImage }.id && it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
        for ((index, policy) in listOf(MutationPolicy(preservation = PreservationPolicy.Strict), MutationPolicy(requiredGuarantees = listOf(Guarantee.MetadataPreserving))).withIndex()) {
            val strict = CountingTransaction(MemoryOutputTransaction(context, "samsung-ordinary-required-$index"))
            assertIs<CoreResult.Failure>(core.split(SplitRequest(input(fixture.bytes), policy = policy, output = strict, context = context)))
            assertEquals(0, strict.commitCalls)
            assertTrue(strict.delegate.committedAssets().isEmpty())
        }
    }

    @Test
    fun cleanWithoutOrdinarySefOutputsStandaloneJpegAndExactVideo(): Unit = runImmediate {
        val fixture = SamsungFixtures.photo()
        val transaction = MemoryOutputTransaction(context, "samsung-clean-plain")
        val result = value(core.split(SplitRequest(input(fixture.bytes), output = transaction, context = context)))
        assertEquals(2, result.output.assets.size)
        for (name in listOf("MotionPhoto_Data", "MotionPhoto_Version")) assertTrue(result.preservation.changes.any { it.selector == "samsung:sef:$name" && it.before != null && it.after == null && it.requested })
        assertEquals(Bytes(GoogleFixtures.jpeg()), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.PrimaryImage }.id])
        assertEquals(Bytes(GoogleFixtures.video().bytes), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        val disabled = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:g='http://ns.google.com/photos/1.0/camera/' g:MotionPhoto='0' g:MotionPhotoVersion='99' g:MotionPhotoPresentationTimestampUs='123'/></rdf:RDF>"
        val ordinary = SamsungFixtures.photo(extraSegments = GoogleFixtures.xmpSegment(disabled))
        val preserved = MemoryOutputTransaction(context, "samsung-inactive-google-fields")
        val clean = value(core.split(SplitRequest(input(ordinary.bytes), output = preserved, context = context)))
        val image = preserved.committedAssets().getValue(clean.output.assets.single { it.role == AssetRole.PrimaryImage }.id).toByteArray()
        val reader = BinaryReader(source(image, "inactive-fields"), context)
        val packet = value(XmpReader.readJpeg(reader, value(JpegParser.parse(reader))))
        assertEquals("0", value(packet.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhoto")))
        assertEquals("99", value(packet.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhotoVersion")))
        assertEquals("123", value(packet.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhotoPresentationTimestampUs")))
    }

    @Test
    fun rawSplitKeepsOriginalProtocolJpegAndExportsPureVideo(): Unit = runImmediate {
        val fixture = SamsungFixtures.photo(xmp = true)
        val transaction = MemoryOutputTransaction(context, "samsung-raw-split")
        val result = value(core.split(SplitRequest(input(fixture.bytes), mode = SplitMode.Raw, output = transaction, context = context)))
        val image = transaction.committedAssets().getValue(result.output.assets.single { it.role == AssetRole.PrimaryImage }.id)
        assertEquals(Bytes(fixture.bytes.copyOfRange(0, fixture.jpegEnd)), image)
        assertEquals(Bytes(GoogleFixtures.video().bytes), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
    }

    @Test
    fun existingOrdinarySefCarrierCreateIsExplicitlyUnsupportedBeforePublication(): Unit = runImmediate {
        val ordinaryCarrier = GoogleFixtures.jpeg() + SamsungFixtures.trailer(listOf(SamsungFixtures.ordinary))
        val transaction = MemoryOutputTransaction(context, "samsung-create-existing-sef")
        val failure = assertIs<CoreResult.Failure>(core.create(CreateRequest(source(ordinaryCarrier, "ordinary-cover"), source(GoogleFixtures.video().bytes, "video"), target, output = transaction, context = context)))
        assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), failure.error.code)
        assertTrue(transaction.committedAssets().isEmpty())
    }

    @Test
    fun heicWriterAndUnknownProfilesCannotPublishJpegUnderTheirNames(): Unit = runImmediate {
        for (profile in listOf("heic-sef-mpv2", "unknown")) {
            val transaction = MemoryOutputTransaction(context, "samsung-unsupported-$profile")
            assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "cover"), source(GoogleFixtures.video().bytes, "video"), target.copy(profile = ProfileId(profile)), output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    @Test
    fun outputBudgetFailureNeverPublishesPartialMotionRecordOrFooter(): Unit = runImmediate {
        val low = Context(Limits(2_000_000uL, 32uL, maxMetadataBytes = 1_000_000uL))
        val transaction = MemoryOutputTransaction(low, "samsung-low-budget")
        val failure = assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "cover"), source(GoogleFixtures.video().bytes, "video"), target, output = transaction, context = low)))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), failure.error.code)
        assertTrue(transaction.committedAssets().isEmpty())
    }

    private fun contains(bytes: ByteArray, pattern: ByteArray) = (0..bytes.size - pattern.size).any { start -> pattern.indices.all { bytes[start + it] == pattern[it] } }
    private class CountingTransaction(val delegate: MemoryOutputTransaction) : OutputTransaction by delegate {
        var commitCalls = 0
        override suspend fun commit(): CoreResult<Receipt> { commitCalls++; return delegate.commit() }
    }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
