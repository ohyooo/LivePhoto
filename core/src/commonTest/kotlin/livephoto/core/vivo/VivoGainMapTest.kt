package livephoto.core.vivo

import livephoto.core.*
import livephoto.core.binary.BinaryReader
import livephoto.core.google.GoogleFixtures
import livephoto.core.jpeg.JpegParser
import livephoto.core.memory.*
import livephoto.core.xml.XmlElement
import livephoto.core.xmp.XmpReader
import kotlin.test.*

class VivoGainMapTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolId("vivo.motionphoto"), ProfileId("jpeg"))
    private fun source(bytes: ByteArray, id: String = "vivo-gainmap") = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun input(bytes: ByteArray) = SourceSet.Single(source(bytes))

    @Test
    fun threeItemGraphProvidesExactAuxiliaryAndMotionResources(): Unit = runImmediate {
        val gain = VivoFixtures.gainMap()
        val fixture = VivoFixtures.photo(gainMap = gain)
        val source = input(fixture.bytes)
        val inspected = value(core.inspect(ReadRequest(source, context)))
        assertEquals(target, inspected.detection.primaryProtocol)
        val primary = inspected.layout.resources.single { it.kind == ResourceKind.PrimaryImage }
        val auxiliary = inspected.layout.resources.first { it.kind == ResourceKind.GainMap }
        val video = inspected.layout.resources.first { it.kind == ResourceKind.Video }
        assertEquals(ByteRange(fixture.jpegEnd.toULong(), gain.size.toULong()), auxiliary.extents.single().range)
        assertEquals(ByteRange((fixture.jpegEnd + gain.size).toULong(), fixture.video.size.toULong()), video.extents.single().range)
        assertTrue(inspected.layout.relationships.any { it.kind == RelationshipKind.AuxiliaryOf && it.from == auxiliary.id && it.to == primary.id })
        assertTrue(value(core.validateProtocol(ValidationRequest(source, target = target, context = context))).verdict != Verdict.Invalid)
        val transaction = MemoryOutputTransaction(context, "vivo-gainmap-raw")
        val result = value(core.extract(ExtractRequest(source, listOf(auxiliary.id, video.id), inspected.snapshot, output = transaction, context = context)))
        assertEquals(Bytes(gain), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.AuxiliaryImage }.id])
        assertEquals(Bytes(fixture.video), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        assertTrue(result.preservation.records.filter { it.guarantee == Guarantee.ExactExtraction }.all { it.outcome == GuaranteeOutcome.Verified })
        val raw = MemoryOutputTransaction(context, "vivo-gainmap-raw-split")
        val split = value(core.split(SplitRequest(source, mode = SplitMode.Raw, output = raw, context = context)))
        assertEquals(3, split.output.assets.size)
        assertEquals(Bytes(fixture.bytes.copyOfRange(0, fixture.jpegEnd)), raw.committedAssets()[split.output.assets.single { it.role == AssetRole.PrimaryImage }.id])
        assertEquals(Bytes(gain), raw.committedAssets()[split.output.assets.single { it.role == AssetRole.AuxiliaryImage }.id])
    }

    @Test
    fun cleanKeepsEncodedGainMapAndOrdinaryDirectoryRelationship(): Unit = runImmediate {
        val gain = VivoFixtures.gainMap()
        val fixture = VivoFixtures.photo(gainMap = gain, extra = "<p:Copyright xmlns:p='urn:ordinary'>kept</p:Copyright>")
        val transaction = MemoryOutputTransaction(context, "vivo-gainmap-clean")
        val result = value(core.split(SplitRequest(input(fixture.bytes), output = transaction, context = context)))
        val image = transaction.committedAssets().getValue(result.output.assets.single { it.role == AssetRole.PrimaryImage }.id).toByteArray()
        assertEquals(Bytes(fixture.video), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        val reader = BinaryReader(source(image, "clean-aux"), context)
        val jpeg = value(JpegParser.parse(reader))
        assertEquals(gain.size.toULong(), jpeg.trailing.length)
        assertEquals(Bytes(gain), value(reader.readExactly(jpeg.trailing.offset, gain.size.toUInt())))
        val xmp = value(XmpReader.readJpeg(reader, jpeg))
        assertEquals("kept", value(xmp.scalar("urn:ordinary", "Copyright")))
        assertEquals("1.0", value(xmp.scalar(VivoFixtures.hdrUri, "Version")))
        for (field in listOf("VMotionPhotoVersion", "VMotionPhotoSource", "VMediaKitVersion")) assertNull(value(xmp.scalar(VivoFixtures.uri, field)))
        assertNull(value(xmp.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhoto")))
        val items = directoryItems(xmp.packets.single().properties("http://ns.google.com/photos/1.0/container/", "Directory").single().element!!)
        assertEquals(listOf("Primary", "GainMap"), items.map { it.attribute(itemUri, "Semantic") })
        assertEquals(gain.size.toString(), items.last().attribute(itemUri, "Length"))
        val clean = value(core.inspect(ReadRequest(input(image), context)))
        assertEquals(Disposition.NonLive, clean.detection.disposition)
        val auxiliary = clean.layout.resources.single { it.kind == ResourceKind.GainMap }
        assertEquals(jpeg.trailing, auxiliary.extents.single().range)
        assertTrue(clean.layout.relationships.any { it.kind == RelationshipKind.AuxiliaryOf && it.from == auxiliary.id && it.to == clean.layout.resources.single { resource -> resource.kind == ResourceKind.PrimaryImage }.id })
        val again = MemoryOutputTransaction(context, "vivo-gainmap-clean-again")
        value(core.split(SplitRequest(input(image), output = again, context = context)))
        assertEquals(Bytes(image), again.committedAssets().values.single())
    }

    @Test
    fun opaqueAuxiliaryMetadataRemainsUnknownAndBlocksRequiredGuarantees(): Unit = runImmediate {
        val unknown = GoogleFixtures.segment(0xee, "private absolute-reference data".encodeToByteArray())
        val gain = GoogleFixtures.jpeg(unknown)
        val fixture = VivoFixtures.photo(gainMap = gain)
        val transaction = MemoryOutputTransaction(context, "vivo-unknown-aux-clean")
        val result = value(core.split(SplitRequest(input(fixture.bytes), output = transaction, context = context)))
        val imageAsset = result.output.assets.single { it.role == AssetRole.PrimaryImage }
        assertEquals(gain.toList(), transaction.committedAssets().getValue(imageAsset.id).toByteArray().takeLast(gain.size))
        assertTrue(result.preservation.records.any { it.assetId == imageAsset.id && it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
        for ((index, policy) in listOf(MutationPolicy(preservation = PreservationPolicy.Strict), MutationPolicy(requiredGuarantees = listOf(Guarantee.MetadataPreserving))).withIndex()) {
            val rejected = CountingTransaction(MemoryOutputTransaction(context, "vivo-unknown-aux-strict-$index"))
            assertIs<CoreResult.Failure>(core.split(SplitRequest(input(fixture.bytes), policy = policy, output = rejected, context = context)))
            assertEquals(0, rejected.commitCalls)
            assertTrue(rejected.delegate.committedAssets().isEmpty())
        }
        // Existing ordinary auxiliary carriers are outside this writer's plain-JPEG create subset.
        val create = CountingTransaction(MemoryOutputTransaction(context, "vivo-existing-aux-create"))
        assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(core.create(CreateRequest(source(VivoFixtures.ordinaryGainMapImage(), "cover"), source(GoogleFixtures.video().bytes, "video"), target, output = create, context = context))).error.code)
        assertEquals(0, create.commitCalls)
    }

    @Test
    fun corruptAndUnsupportedAuxiliaryJpegHaveDifferentValidationCoverage(): Unit = runImmediate {
        val corrupt = GoogleFixtures.bytes(0xff, 0xd8, 0xff, 0xd9)
        val zeroHeight = GoogleFixtures.jpeg().copyOf()
        // Independent SOF0 height position: SOI(2), DQT(69), marker+length(4), precision(1).
        zeroHeight[76] = 0; zeroHeight[77] = 0
        val unsupported = zeroHeight.copyOfRange(0, zeroHeight.size - 2) + GoogleFixtures.segment(0xdc, GoogleFixtures.bytes(0, 1)) + zeroHeight.takeLast(2).toByteArray()
        for ((index, gain) in listOf(corrupt, unsupported).withIndex()) {
            val fixture = VivoFixtures.photo(gainMap = gain)
            val source = input(fixture.bytes)
            val inspected = value(core.inspect(ReadRequest(source, context)))
            assertTrue(inspected.detection.matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong }, "aux index=$index")
            val report = value(core.validateMedia(ValidationRequest(source, target = target, context = context)))
            val issues = report.issues + report.checks.flatMap { it.issues }
            if (index == 0) {
                assertEquals(Verdict.Invalid, report.verdict)
                assertTrue(issues.any { it.severity == Severity.Error })
            } else {
                assertEquals(Verdict.Warning, report.verdict)
                assertTrue(report.coverage != Coverage.Complete)
                assertTrue(issues.any { it.severity == Severity.Warning && it.code == IssueCode("UNSUPPORTED_CONTAINER") })
                val structure = value(core.validateStructure(ValidationRequest(source, target = target, context = context)))
                assertEquals(Coverage.Partial, structure.coverage)
                assertEquals(Verdict.Warning, structure.verdict)
                val auxiliary = inspected.layout.resources.first { it.kind == ResourceKind.GainMap }
                val raw = MemoryOutputTransaction(context, "vivo-unsupported-aux-raw")
                value(core.extract(ExtractRequest(source, listOf(auxiliary.id), inspected.snapshot, output = raw, context = context)))
                assertEquals(Bytes(gain), raw.committedAssets().values.single())
            }
            val clean = CountingTransaction(MemoryOutputTransaction(context, "vivo-invalid-aux-clean-$index"))
            assertIs<CoreResult.Failure>(core.split(SplitRequest(source, output = clean, context = context)))
            assertEquals(0, clean.commitCalls)
        }
    }

    private val itemUri = "http://ns.google.com/photos/1.0/container/item/"
    private fun directoryItems(directory: XmlElement): List<XmlElement> = directory.elements("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Seq").single().elements("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "li").map { it.elements("http://ns.google.com/photos/1.0/container/", "Item").single() }
    private class CountingTransaction(val delegate: MemoryOutputTransaction) : OutputTransaction by delegate {
        var commitCalls = 0
        override suspend fun commit(): CoreResult<Receipt> { commitCalls++; return delegate.commit() }
    }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
