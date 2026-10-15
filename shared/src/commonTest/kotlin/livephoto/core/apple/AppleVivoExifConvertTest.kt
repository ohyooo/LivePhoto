package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.memory.*
import livephoto.core.vivo.VivoFixtures
import kotlin.test.*

/** Literal version-one inline source, not a camera or device compatibility fixture. */
class AppleVivoExifConvertTest {
    private val context = Context(Limits(16_000_000uL, 16_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4"))
    private fun source(bytes: ByteArray) = MemoryBinarySource(Bytes(bytes), SourceId("vivo-exif-source"))
    private fun segment(endian: Endian): ByteArray {
        val image = AppleOrdinaryExifTest().image(endian)
        val length = ((image[4].toInt() and 255) shl 8) or (image[5].toInt() and 255)
        return image.copyOfRange(2, 4 + length)
    }
    private fun photo(endian: Endian = Endian.Big) = VivoFixtures.photo(extraSegments = segment(endian), timestamp = "40000").bytes

    @Test fun bothEndiansKeepExifCodingSamplesAndKnownNonzeroKey(): Unit = runImmediate {
        for (endian in Endian.entries) {
            val input = source(photo(endian)); val session = SourceSession.open(SourceSet.Single(input), context, ParseBudget(context)).orThrow()
            assertEquals(ProtocolIds.VivoModern, session.inspection.detection.primaryProtocol?.protocol)
            val hash = sha256Range(session.reader, ByteRange(0uL, input.size().orThrow())).orThrow()
            val tx = MemoryOutputTransaction(context, "vivo-exif-convert")
            val req = ConvertRequest(SourceSet.Single(input), target, output = tx, context = context)
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
            val movie = BinaryReader(result.output.assets[1].readableSource!!, context)
            val media = BmffVideoProbe(movie, allowTimedMetadata = true).probe(ByteRange(0uL, movie.identity().orThrow().size)).orThrow()
            RemuxVerification.verify(session.reader, session.videos.getValue(ProtocolIds.VivoModern), movie, media.copy(tracks = media.tracks.filter { it.handler != "meta" }))
            assertEquals(Verdict.Valid, result.validation.verdict); assertEquals(0, result.keyPhoto?.position?.compareTo(Time(40, 1000u)))
            assertTrue(result.execution.none { it.remuxed || it.transcoded })
            assertTrue(result.preservation.records.any { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
            assertEquals(hash, sha256Range(session.reader, ByteRange(0uL, input.size().orThrow())).orThrow())
        }
    }

    @Test fun unknownVendorFieldsVersionsAuxiliaryAndStrictCannotBorrowTheSourceProof(): Unit = runImmediate {
        val exif = segment(Endian.Big)
        val cases = listOf(
            VivoFixtures.photo(extraSegments = exif, version = "2").bytes,
            VivoFixtures.photo(extraSegments = exif, source = "2").bytes,
            VivoFixtures.photo(extraSegments = exif, kit = "1.0.0.10").bytes,
            VivoFixtures.photo(extraSegments = exif, extra = "<v:Private>secret</v:Private>").bytes,
            VivoFixtures.photo(extraSegments = exif, extra = "<v:VMotionPhotoFlags>1</v:VMotionPhotoFlags>").bytes,
            VivoFixtures.photo(extraSegments = exif, gainMap = VivoFixtures.gainMap()).bytes)
        for (bytes in cases) {
            val tx = MemoryOutputTransaction(context, "vivo-exif-reject")
            assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(source(bytes)), target, output = tx, context = context)))
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
        val strict = MemoryOutputTransaction(context, "vivo-exif-strict")
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(source(photo())), target,
            policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = strict, context = context))).error.code)
        assertTrue(strict.query().orThrow().assetIds.isEmpty())
    }

    @Test fun alteredCleanExifFailsBeforePublishingAssets(): Unit = runImmediate {
        val input = source(photo()); val session = SourceSession.open(SourceSet.Single(input), context, ParseBudget(context)).orThrow()
        val tx = MemoryOutputTransaction(context, "vivo-clean-tamper")
        val req = ConvertRequest(SourceSet.Single(input), target, output = tx, context = context)
        val clean = ConvertOperations.prepare(req, session, ParseBudget(context))!!
        val reader = BinaryReader(clean.first, context)
        val payload = JpegParser.parse(reader).orThrow().segments.single { it.payloadKind == AppPayloadKind.Exif }.payload!!
        val changed = FixedPatchSource.create(reader, listOf(FixedPatch(ByteRange(payload.offset + 6uL + 90uL, 1uL), Bytes(byteArrayOf(83))))).orThrow()
        assertEquals(IssueCode("POSTCONDITION_FAILED"), assertIs<CoreResult.Failure>(AppleAssemblyOperations.plan(req, session, changed to clean.second)).error.code)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }
}
