package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class NalFramingTest {
    private val context = Context(Limits(1_000_000uL, 1_000_000uL))
    private suspend fun read(bytes: Bytes, width: Int, codec: VideoCodec): CoreResult<NalFraming> {
        val reader = BinaryReader(MemoryBinarySource(bytes, SourceId("nal-framing")), context)
        return validateNalFraming(reader, ByteRange(0uL, bytes.size.toULong()), width, codec, ParseBudget(context), 1u)
    }
    @Test fun lengthsAndHeadersAreCheckedForEachCodecAndLengthWidthWithoutDecodeClaims(): Unit = runImmediate {
        for (codec in listOf(VideoCodec.Avc, VideoCodec.Hevc)) for (width in 1..4) {
            val header = if (codec == VideoCodec.Hevc) byteArrayOf(0x26, 1) else byteArrayOf(5)
            val unit = unsignedBytes(header.size.toULong(), width, Endian.Big).toByteArray() + header
            assertEquals(2uL, read(Bytes(unit + unit), width, codec).orThrow().units)
        }
    }
    @Test fun truncatedNonVclAndInvalidHeadersAreStructuredFailures(): Unit = runImmediate {
        for (payload in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 0, 0, 1, 0x26), byteArrayOf(0, 0, 0, 2, 0x80.toByte(), 1),
            byteArrayOf(0, 0, 0, 2, 0x26, 0), byteArrayOf(0, 0, 0, 2, 0x40, 1)))
            assertIs<CoreResult.Failure>(read(Bytes(payload), 4, VideoCodec.Hevc))
        assertEquals(IssueCode("INVALID_ARGUMENT"), assertIs<CoreResult.Failure>(read(Bytes(byteArrayOf(1)), 0, VideoCodec.Hevc)).error.code)
        assertEquals(IssueCode("CORRUPTED_CONTAINER"), assertIs<CoreResult.Failure>(read(Bytes(byteArrayOf(1, 7)), 1, VideoCodec.Avc)).error.code)
    }
}
