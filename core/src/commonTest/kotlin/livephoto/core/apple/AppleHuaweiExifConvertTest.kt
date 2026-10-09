package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.huawei.HuaweiFixtures
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.memory.*
import kotlin.test.*

/** Literal basic60 trailer, no inferred timestamp units or device compatibility claim. */
class AppleHuaweiExifConvertTest {
    private val context = Context(Limits(16_000_000uL, 16_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4"))
    private fun source(bytes: ByteArray) = MemoryBinarySource(Bytes(bytes), SourceId("huawei-exif-source"))
    private fun photo(endian: Endian = Endian.Big) = HuaweiFixtures.photo(jpeg = AppleOrdinaryExifTest().image(endian)).bytes
    private val edits = EditSpec(keyPosition = CoverPosition.FrameIndex(1uL))

    @Test fun bothEndiansKeepExifCodingAndPureVideoWithAnExplicitSourceDomainKey(): Unit = runImmediate {
        for (endian in Endian.entries) {
            val input = source(photo(endian)); val session = SourceSession.open(SourceSet.Single(input), context, ParseBudget(context)).orThrow()
            assertEquals(ProtocolIds.Huawei, session.inspection.detection.primaryProtocol?.protocol)
            assertNull(session.inspection.keyPhoto.position)
            val hash = sha256Range(session.reader, ByteRange(0uL, input.size().orThrow())).orThrow()
            val tx = MemoryOutputTransaction(context, "huawei-exif-convert")
            val req = ConvertRequest(SourceSet.Single(input), target, edits = edits, output = tx, context = context)
            core.plan(req).orThrow(); assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val result = core.convert(req).orThrow()
            val image = SourceSession.open(SourceSet.Single(result.output.assets[0].readableSource!!), context, ParseBudget(context)).orThrow()
            assertEquals(codingDigest(session), codingDigest(image))
            val before = session.exifComments.single().document; val after = image.exifComments.single().document
            assertEquals(endian, after.endian)
            for (field in before.ifds.flatMap { it.entries }.filter { it.tag != 0x8769u.toUShort() }) {
                val copied = after.ifds.flatMap { it.entries }.single { it.tag == field.tag }
                assertEquals(field.type, copied.type); assertEquals(field.count, copied.count); assertEquals(field.value, copied.value)
                if (field.valueRange!!.length > 4uL) assertEquals(field.valueRange.offset - before.range.offset, copied.valueRange!!.offset - after.range.offset)
            }
            val reader = BinaryReader(result.output.assets[1].readableSource!!, context)
            val video = BmffVideoProbe(reader, allowTimedMetadata = true).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
            RemuxVerification.verify(session.reader, session.videos.getValue(ProtocolIds.Huawei), reader, video.copy(tracks = video.tracks.filter { it.handler != "meta" }))
            assertEquals(Verdict.Valid, result.validation.verdict); assertEquals(0, result.keyPhoto?.position?.compareTo(Time(40, 1000u)))
            assertTrue(result.execution.none { it.remuxed || it.transcoded })
            assertTrue(result.preservation.records.any { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
            assertEquals(hash, sha256Range(session.reader, ByteRange(0uL, input.size().orThrow())).orThrow())
        }
    }

    @Test fun unknownTimestampStrictAndHonorOrGappedSourcesStayBlocked(): Unit = runImmediate {
        val cases = listOf(
            ConvertRequest(SourceSet.Single(source(photo())), target, output = MemoryOutputTransaction(context, "huawei-missing-key"), context = context),
            ConvertRequest(SourceSet.Single(source(photo())), target, edits = edits, policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = MemoryOutputTransaction(context, "huawei-strict"), context = context),
            ConvertRequest(SourceSet.Single(source(HuaweiFixtures.photo(jpeg = AppleOrdinaryExifTest().image(Endian.Big), prefix = "v1_f1").bytes)), target, edits = edits, output = MemoryOutputTransaction(context, "honor-reject"), context = context),
            ConvertRequest(SourceSet.Single(source(HuaweiFixtures.photo(jpeg = AppleOrdinaryExifTest().image(Endian.Big), extra = byteArrayOf(7)).bytes)), target, edits = edits, output = MemoryOutputTransaction(context, "huawei-gap-reject"), context = context))
        for (req in cases) {
            assertIs<CoreResult.Failure>(core.convert(req)); assertTrue(req.output.query().orThrow().assetIds.isEmpty())
        }
    }

    @Test fun modifiedCleanExifDoesNotGetAuthorityFromTheRemovedTrailer(): Unit = runImmediate {
        val input = source(photo()); val session = SourceSession.open(SourceSet.Single(input), context, ParseBudget(context)).orThrow()
        val tx = MemoryOutputTransaction(context, "huawei-clean-tamper")
        val req = ConvertRequest(SourceSet.Single(input), target, edits = edits, output = tx, context = context)
        val clean = ConvertOperations.prepare(req, session, ParseBudget(context))!!
        val reader = BinaryReader(clean.first, context)
        val payload = JpegParser.parse(reader).orThrow().segments.single { it.payloadKind == AppPayloadKind.Exif }.payload!!
        val changed = FixedPatchSource.create(reader, listOf(FixedPatch(ByteRange(payload.offset + 6uL + 90uL, 1uL), Bytes(byteArrayOf(83))))).orThrow()
        assertEquals(IssueCode("POSTCONDITION_FAILED"), assertIs<CoreResult.Failure>(AppleAssemblyOperations.plan(req, session, changed to clean.second)).error.code)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }
}
