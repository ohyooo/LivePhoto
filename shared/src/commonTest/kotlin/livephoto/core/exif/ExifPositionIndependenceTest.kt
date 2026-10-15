package livephoto.core.exif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.jpeg.JpegParser
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.oplus.OplusFixtures
import kotlin.test.*

class ExifPositionIndependenceTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL))
    private fun reader(bytes: ByteArray) = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("exif-position-proof")), context)
    private suspend fun range(reader: BinaryReader): ByteRange {
        val payload = JpegParser.parse(reader).orThrow().segments.single { it.payloadKind == livephoto.core.jpeg.AppPayloadKind.Exif }.payload!!
        return ByteRange(payload.offset + 6uL, payload.length - 6uL)
    }

    @Test fun standardLittleAndBigEndianFieldsGetAnIdentityAndHashBoundProof(): Unit = runImmediate {
        for (little in listOf(true, false)) {
            val reader = reader(GoogleFixtures.jpeg(OplusFixtures.exifSegment(OplusFixtures.marker, little)))
            val range = range(reader)
            val proof = ExifPositionIndependenceProof.prove(reader, range, ParseBudget(context)).orThrow()
            assertEquals(reader.identity().orThrow(), proof.sourceIdentity); assertEquals(range, proof.range)
            assertEquals(sha256Range(reader, range).orThrow(), proof.digest)
        }
    }

    @Test fun makerNoteOffsetSensitiveAndUnknownTagsCannotBorrowANoOpMarkerProof(): Unit = runImmediate {
        val original = GoogleFixtures.jpeg(OplusFixtures.exifSegment(OplusFixtures.marker))
        val first = TiffReader(reader(original)).read(range(reader(original))).orThrow().ifds.first().entries.first().entryRange.offset.toInt()
        for (tag in listOf(0x927c, 0x0111, 0xc001)) {
            val bytes = original.copyOf(); bytes[first] = tag.toByte(); bytes[first + 1] = (tag ushr 8).toByte()
            val reader = reader(bytes); val range = range(reader)
            // Existing AddCanonical is deliberately a no-op for an already matching marker.
            assertTrue(ExifMarkerWriter(reader).rewriteMarker(range, ExifMarkerAction.AddCanonical).orThrow().isNoOp)
            assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(ExifPositionIndependenceProof.prove(reader, range, ParseBudget(context))).error.code.value)
        }
    }

    @Test fun nonzeroUnreferencedTiffBytesCannotReceiveAProofButZeroAlignmentCan(): Unit = runImmediate {
        val segment = OplusFixtures.exifSegment(OplusFixtures.marker)
        for (gap in listOf(byteArrayOf(0, 0), byteArrayOf(0, 7))) {
            val reader = reader(GoogleFixtures.jpeg(GoogleFixtures.segment(0xe1, segment.copyOfRange(4, segment.size) + gap)))
            val proof = ExifPositionIndependenceProof.prove(reader, range(reader), ParseBudget(context))
            if (gap.last() == 0.toByte()) assertIs<CoreResult.Success<*>>(proof)
            else assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(proof).error.code.value)
        }
    }
}
