package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.memory.*
import livephoto.core.samsung.SamsungFixtures
import kotlin.test.*

/** Independently encoded SEF source, not a captured Samsung device fixture. */
class AppleSamsungExifConvertTest {
    private val context = Context(Limits(16_000_000uL, 16_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4"))
    private fun source(bytes: ByteArray) = MemoryBinarySource(Bytes(bytes), SourceId("samsung-exif-source"))
    private fun segment(endian: Endian): ByteArray {
        val bytes = AppleOrdinaryExifTest().image(endian)
        val length = ((bytes[4].toInt() and 255) shl 8) or (bytes[5].toInt() and 255)
        return bytes.copyOfRange(2, 4 + length)
    }
    private fun photo(endian: Endian = Endian.Big, xmp: Boolean = true, ordinary: Boolean = false, legacy: Boolean = false) =
        SamsungFixtures.photo(xmp = xmp, ordinaryRecord = ordinary, legacy = legacy, extraSegments = segment(endian)).bytes

    @Test fun standardSefWithOrWithoutCompatibleGoogleBaseRetainsOrdinaryExifAndPureVideo(): Unit = runImmediate {
        for (endian in Endian.entries) for (xmp in listOf(false, true)) {
            val input = source(photo(endian, xmp))
            val session = SourceSession.open(SourceSet.Single(input), context, ParseBudget(context)).orThrow()
            assertEquals(ProtocolIds.Samsung, session.inspection.detection.primaryProtocol?.protocol)
            val beforeHash = sha256Range(session.reader, ByteRange(0uL, input.size().orThrow())).orThrow()
            val tx = MemoryOutputTransaction(context, "samsung-exif-convert")
            val req = ConvertRequest(SourceSet.Single(input), target, edits = if (xmp) null else EditSpec(keyPosition = CoverPosition.FrameIndex(0uL)), output = tx, context = context)
            core.plan(req).orThrow(); assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val result = core.convert(req).orThrow()
            val image = SourceSession.open(SourceSet.Single(result.output.assets[0].readableSource!!), context, ParseBudget(context)).orThrow()
            assertEquals(codingDigest(session), codingDigest(image))
            val before = session.exifComments.single().document; val after = image.exifComments.single().document
            assertEquals(endian, after.endian)
            for (field in before.ifds.flatMap { it.entries }.filter { it.tag != 0x8769u.toUShort() }) {
                val current = after.ifds.flatMap { it.entries }.single { it.tag == field.tag }
                assertEquals(field.value, current.value); assertEquals(field.type, current.type); assertEquals(field.count, current.count)
                if (field.valueRange!!.length > 4uL) assertEquals(field.valueRange.offset - before.range.offset, current.valueRange!!.offset - after.range.offset)
            }
            val movie = BinaryReader(result.output.assets[1].readableSource!!, context)
            val afterMedia = BmffVideoProbe(movie, allowTimedMetadata = true).probe(ByteRange(0uL, movie.identity().orThrow().size)).orThrow()
            RemuxVerification.verify(session.reader, session.videos.getValue(ProtocolIds.Samsung), movie, afterMedia.copy(tracks = afterMedia.tracks.filter { it.handler != "meta" }))
            assertEquals(Verdict.Valid, result.validation.verdict); assertEquals(0, result.keyPhoto?.position?.compareTo(Time.Zero))
            assertTrue(result.execution.none { it.remuxed || it.transcoded })
            assertTrue(result.preservation.records.any { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
            assertEquals(beforeHash, sha256Range(session.reader, ByteRange(0uL, input.size().orThrow())).orThrow())
        }
    }

    @Test fun ordinarySefLegacyFootersAndUnknownKeyNeverBorrowTheMinimalSourceProof(): Unit = runImmediate {
        for ((bytes, edits) in listOf(photo(ordinary = true) to null, photo(legacy = true) to null, photo(xmp = false) to null)) {
            val tx = MemoryOutputTransaction(context, "samsung-exif-reject")
            assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(source(bytes)), target, edits = edits, output = tx, context = context)))
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
    }

    @Test fun strictDoesNotUpgradeSourceAssociationUnknown(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "samsung-exif-strict")
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(source(photo())), target,
            policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context))).error.code)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }

    @Test fun changedCleanupExifFailsBeforeWritingAnySamsungConversionAsset(): Unit = runImmediate {
        val input = source(photo()); val session = SourceSession.open(SourceSet.Single(input), context, ParseBudget(context)).orThrow()
        val tx = MemoryOutputTransaction(context, "samsung-exif-clean-tamper")
        val req = ConvertRequest(SourceSet.Single(input), target, output = tx, context = context)
        val clean = ConvertOperations.prepare(req, session, ParseBudget(context))!!
        val reader = BinaryReader(clean.first, context)
        val payload = JpegParser.parse(reader).orThrow().segments.single { it.payloadKind == AppPayloadKind.Exif }.payload!!
        val altered = FixedPatchSource.create(reader, listOf(FixedPatch(ByteRange(payload.offset + 6uL + 90uL, 1uL), Bytes(byteArrayOf(83))))).orThrow()
        assertEquals(IssueCode("POSTCONDITION_FAILED"), assertIs<CoreResult.Failure>(AppleAssemblyOperations.plan(req, session, altered to clean.second)).error.code)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }
}
