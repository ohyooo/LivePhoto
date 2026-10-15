package livephoto.core.huawei

import livephoto.core.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import livephoto.core.oplus.OplusFixtures
import kotlin.test.*

class HuaweiCoreMutationTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolId("huawei.movingphoto"), ProfileId("basic60"))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun input(bytes: ByteArray) = SourceSet.Single(source(bytes, "huawei-carrier"))

    @Test
    fun createCopiesMediaAndMatchesIndependentSixtyByteOracle(): Unit = runImmediate {
        val exif = OplusFixtures.exifSegment("ordinary HUAWEI comment")
        val image = GoogleFixtures.jpeg(exif)
        val video = GoogleFixtures.video().bytes
        val transaction = MemoryOutputTransaction(context, "huawei-create")
        val result = value(core.create(CreateRequest(source(image, "cover"), source(video, "video"), target, output = transaction, context = context)))
        val carrier = transaction.committedAssets().values.single()
        val expectedTail = HuaweiFixtures.tail("v6_f0", "0:2", "LIVE_${video.size + 20}")
        assertEquals(Bytes(image + video + expectedTail), carrier)
        assertNull(result.keyPhoto?.position)
        assertTrue((result.keyPhoto?.issues.orEmpty() + result.issues).any { it.code == IssueCode("TIMESTAMP_SEMANTICS_UNKNOWN") })
        val inspected = value(core.inspect(ReadRequest(input(carrier.toByteArray()), context)))
        assertEquals(target, inspected.detection.primaryProtocol)
        val movie = inspected.layout.resources.single { it.kind == ResourceKind.Video }
        assertEquals(ByteRange(image.size.toULong(), video.size.toULong()), movie.extents.single().range)
        val raw = MemoryOutputTransaction(context, "huawei-created-raw")
        value(core.extract(ExtractRequest(input(carrier.toByteArray()), listOf(movie.id), inspected.snapshot, output = raw, context = context)))
        assertEquals(Bytes(video), raw.committedAssets().values.single())
        assertTrue(result.preservation.records.any { it.guarantee == Guarantee.BitstreamPreserving && it.outcome == GuaranteeOutcome.Verified })
    }

    @Test
    fun cleanPreservesOrdinaryExifIccAndDisabledCameraMetadataExactly(): Unit = runImmediate {
        val exif = OplusFixtures.exifSegment("ordinary GPS camera text")
        val icc = GoogleFixtures.segment(0xe2, "ICC_PROFILE\u0000".encodeToByteArray() + GoogleFixtures.bytes(1, 1, 5, 6))
        val xml = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:c='http://ns.google.com/photos/1.0/camera/' xmlns:p='urn:ordinary' c:MotionPhoto='0' p:Copyright='kept'/></rdf:RDF>"
        val image = GoogleFixtures.jpeg(exif + icc + GoogleFixtures.xmpSegment(xml))
        val fixture = HuaweiFixtures.photo(jpeg = image)
        val transaction = MemoryOutputTransaction(context, "huawei-clean")
        val result = value(core.split(SplitRequest(input(fixture.bytes), output = transaction, context = context)))
        assertEquals(Bytes(image), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.PrimaryImage }.id])
        assertEquals(Bytes(fixture.video), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        assertTrue(result.preservation.changes.none { it.selector.contains("urn:ordinary") || it.selector.contains("MotionPhoto") || it.selector == "exif:UserComment" })
        assertTrue(result.preservation.changes.isNotEmpty())
        assertEquals(Disposition.NonLive, value(core.detect(ReadRequest(input(image), context))).disposition)
        val again = MemoryOutputTransaction(context, "huawei-clean-again")
        value(core.split(SplitRequest(input(image), output = again, context = context)))
        assertEquals(Bytes(image), again.committedAssets().values.single())
    }

    @Test
    fun rawSplitIsPrimaryAndPureVideoWithoutFooter(): Unit = runImmediate {
        val fixture = HuaweiFixtures.photo()
        val transaction = MemoryOutputTransaction(context, "huawei-raw-split")
        val result = value(core.split(SplitRequest(input(fixture.bytes), mode = SplitMode.Raw, output = transaction, context = context)))
        assertEquals(Bytes(fixture.jpeg), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.PrimaryImage }.id])
        assertEquals(Bytes(fixture.video), transaction.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        assertTrue(result.output.assets.none { it.role == AssetRole.Composite })
    }

    @Test
    fun explicitKeyAndUnknownWriterProfileRejectBeforePublication(): Unit = runImmediate {
        for ((index, pair) in listOf(target to EditSpec(keyPosition = CoverPosition.FrameIndex(1uL)), target.copy(profile = ProfileId("honor-extended")) to null, target.copy(profile = ProfileId("unknown")) to null).withIndex()) {
            val transaction = CountingTransaction(MemoryOutputTransaction(context, "huawei-key-$index"))
            val result = core.create(CreateRequest(source(GoogleFixtures.jpeg(), "cover"), source(GoogleFixtures.video().bytes, "video"), pair.first, edits = pair.second, output = transaction, context = context))
            val failure = assertIs<CoreResult.Failure>(result)
            if (index == 0) assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), failure.error.code)
            assertEquals(0, transaction.commitCalls)
            assertTrue(transaction.delegate.committedAssets().isEmpty())
        }
        val uuid = GoogleFixtures.box("uuid", ByteArray(16) { 0x31 } + "srcDstWh".encodeToByteArray())
        val transaction = CountingTransaction(MemoryOutputTransaction(context, "huawei-unknown-video-create"))
        assertEquals(IssueCode("UNKNOWN_PROTOCOL_VARIANT"), assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "cover"), source(GoogleFixtures.video().bytes + uuid, "video"), target, output = transaction, context = context))).error.code)
        assertEquals(0, transaction.commitCalls)
        assertTrue(transaction.delegate.committedAssets().isEmpty())
    }

    @Test
    fun prefixGapAndHonorExtensionsCannotBeDiscardedByClean(): Unit = runImmediate {
        val extra = GoogleFixtures.bytes(7, 8, 9)
        val uuid = GoogleFixtures.box("uuid", ByteArray(16) { 0x31 } + "srcDstWh".encodeToByteArray())
        for ((index, fixture) in listOf(
            HuaweiFixtures.photo(extra = extra),
            HuaweiFixtures.photo(video = GoogleFixtures.video().bytes + uuid, prefix = "v2_f1"),
            HuaweiFixtures.photo(extra = uuid, prefix = "v1_f1"),
            HuaweiFixtures.photo(video = GoogleFixtures.video().bytes + uuid),
        ).withIndex()) {
            val transaction = CountingTransaction(MemoryOutputTransaction(context, "huawei-unknown-wrapper-$index"))
            assertIs<CoreResult.Failure>(core.split(SplitRequest(input(fixture.bytes), output = transaction, context = context)))
            assertEquals(0, transaction.commitCalls)
            assertTrue(transaction.delegate.committedAssets().isEmpty())
            if (index > 0) {
                val inspection = value(core.inspect(ReadRequest(input(fixture.bytes), context)))
                assertTrue(inspection.detection.matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong })
                assertTrue(inspection.detection.matches.any { it.target.profile == ProfileId(if (index == 3) "basic60" else "honor-extended") })
                assertTrue(inspection.issues.any { it.code == IssueCode("UNKNOWN_PROTOCOL_VARIANT") })
                assertTrue(inspection.layout.resources.none { it.kind == ResourceKind.Video })
            }
        }
    }

    @Test
    fun sourceBudgetAndOutputLimitLeaveTransactionUnpublished(): Unit = runImmediate {
        for ((index, limits) in listOf(context.limits.copy(maxSources = 1u), context.limits.copy(maxOutputBytes = 32uL)).withIndex()) {
            val constrained = context.copy(limits = limits)
            val transaction = CountingTransaction(MemoryOutputTransaction(constrained, "huawei-budget-$index"))
            assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "cover"), source(GoogleFixtures.video().bytes, "video"), target, output = transaction, context = constrained))).error.code)
            assertEquals(0, transaction.commitCalls)
            assertTrue(transaction.delegate.committedAssets().isEmpty())
        }
    }

    private class CountingTransaction(val delegate: MemoryOutputTransaction) : OutputTransaction by delegate {
        var commitCalls = 0
        override suspend fun commit(): CoreResult<Receipt> { commitCalls++; return delegate.commit() }
    }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
