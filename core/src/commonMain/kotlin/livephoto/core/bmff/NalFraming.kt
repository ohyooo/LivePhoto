package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

internal data class NalFraming(val units: ULong)

/** Encoded lengths and NAL headers only; no SPS/slice semantics or successful decode claim. */
internal suspend fun validateNalFraming(reader: BinaryReader, range: ByteRange, width: Int, codec: VideoCodec,
    budget: ParseBudget, depth: UInt): CoreResult<NalFraming> = attempt {
    if (width !in 1..4 || codec !in setOf(VideoCodec.Avc, VideoCodec.Hevc)) fail("INVALID_ARGUMENT", "Invalid NAL framing parameters")
    checkedRange(range.offset, range.length, reader.identity().orThrow().size)
    var cursor = range.offset
    var vcl = false
    var units = 0uL
    while (cursor < range.endExclusive) {
        budget.item(depth)
        checkedRange(cursor, width.toULong(), range.endExclusive)
        val length = readUnsigned(reader.readBuffer(cursor, width.toUInt()).orThrow(), Endian.Big)
        cursor = checkedAdd(cursor, width.toULong())
        val headerLength = if (codec == VideoCodec.Hevc) 2u else 1u
        if (length < headerLength.toULong()) fail("CORRUPTED_CONTAINER", "NAL unit is empty or truncated")
        checkedRange(cursor, length, range.endExclusive)
        val header = reader.readBuffer(cursor, headerLength).orThrow()
        val first = header[0].toInt() and 255
        if (first and 128 != 0) fail("CORRUPTED_CONTAINER", "NAL forbidden bit is set")
        val type = if (codec == VideoCodec.Hevc) (first shr 1) and 63 else first and 31
        if (codec == VideoCodec.Hevc) {
            if (header[1].toInt() and 7 == 0) fail("CORRUPTED_CONTAINER", "HEVC temporal_id_plus1 is zero")
            if (type <= 31) vcl = true
        } else {
            if (type == 0 || type >= 24) fail("CORRUPTED_CONTAINER", "Invalid AVC NAL type")
            if (type in 1..5) vcl = true
        }
        cursor = checkedAdd(cursor, length)
        units = checkedAdd(units, 1uL)
    }
    if (!vcl) fail("CORRUPTED_CONTAINER", "Coded resource has no VCL NAL unit")
    reader.validateIdentity().orThrow()
    NalFraming(units)
}
