package livephoto.core.jpeg

import livephoto.core.*
import livephoto.core.binary.*

internal enum class AppPayloadKind { Exif, Xmp, ExtendedXmp, Icc, Mpf, Unknown }
internal data class JpegSegment(val marker: Int, val range: ByteRange, val payload: ByteRange? = null, val payloadKind: AppPayloadKind = AppPayloadKind.Unknown)
internal data class JpegStructure(
    val primary: ByteRange,
    val trailing: ByteRange,
    val segments: List<JpegSegment>,
    val scans: List<ByteRange>,
) {
    val hasMpf: Boolean get() = segments.any { it.payloadKind == AppPayloadKind.Mpf }
    val hasExtendedXmp: Boolean get() = segments.any { it.payloadKind == AppPayloadKind.ExtendedXmp }
    val hasExif: Boolean get() = segments.any { it.payloadKind == AppPayloadKind.Exif }
}

/** Indexes markers and entropy data without materializing the image or appended video. */
internal object JpegParser {
    suspend fun parse(reader: BinaryReader, budget: ParseBudget = ParseBudget(reader.context)): CoreResult<JpegStructure> = attempt {
        val size = reader.identity().orThrow().size
        if (size < 2uL || reader.readBuffer(0uL, 2u).orThrow() != Bytes(byteArrayOf(0xff.toByte(), 0xd8.toByte()))) {
            fail("CORRUPTED_CONTAINER", "JPEG start-of-image marker is missing")
        }
        val scanner = Scanner(reader, size)
        val segments = mutableListOf(JpegSegment(0xd8, ByteRange(0uL, 2uL)))
        val scans = mutableListOf<ByteRange>()
        var position = 2uL
        var entropy = false
        var scanStart = position
        while (position < size) {
            checkCancelled(reader.context)
            var markerStart: ULong
            var code: Int
            val wasEntropy = entropy
            if (entropy) {
                while (true) {
                    if (position >= size) fail("CORRUPTED_CONTAINER", "JPEG entropy data has no terminating marker")
                    val value = scanner.byte(position++)
                    if (value != 0xff) continue
                    markerStart = position - 1uL
                    do {
                        if (position >= size) fail("UNEXPECTED_EOF", "Truncated JPEG marker in entropy data")
                        code = scanner.byte(position++)
                    } while (code == 0xff)
                    if (code == 0 || code in 0xd0..0xd7) continue
                    scans += ByteRange(scanStart, markerStart - scanStart)
                    entropy = false
                    break
                }
            } else {
                markerStart = position
                if (scanner.byte(position++) != 0xff) fail("CORRUPTED_CONTAINER", "Expected JPEG marker prefix")
                do {
                    if (position >= size) fail("UNEXPECTED_EOF", "Truncated JPEG marker")
                    code = scanner.byte(position++)
                } while (code == 0xff)
                if (code == 0) fail("CORRUPTED_CONTAINER", "Byte stuffing is only legal inside entropy data")
            }
            budget.item()
            if (code == 0xd9) {
                segments += JpegSegment(code, ByteRange(markerStart, position - markerStart))
                reader.validateIdentity().orThrow()
                return@attempt JpegStructure(ByteRange(0uL, position), ByteRange(position, size - position), frozenList(segments), frozenList(scans))
            }
            if (code == 0xd8 || code in 0xd0..0xd7) fail("CORRUPTED_CONTAINER", "Unexpected JPEG standalone marker")
            if (code == 0x01) {
                segments += JpegSegment(code, ByteRange(markerStart, position - markerStart))
                if (wasEntropy) { entropy = true; scanStart = position }
                continue
            }
            if (code < 0xc0) fail("CORRUPTED_CONTAINER", "Unknown JPEG marker has no safe length interpretation")
            checkedRange(position, 2uL, size)
            val length = reader.readU16(position).orThrow().toULong()
            if (length < 2uL) fail("CORRUPTED_CONTAINER", "JPEG segment length is smaller than its length field")
            checkedRange(position, length, size)
            val payload = ByteRange(position + 2uL, length - 2uL)
            val kind = if (code in 0xe0..0xef) {
                budget.retain(payload.length)
                classify(reader, code, payload)
            } else {
                if (code == 0xfe) budget.retain(payload.length)
                AppPayloadKind.Unknown
            }
            position += length
            segments += JpegSegment(code, ByteRange(markerStart, position - markerStart), payload, kind)
            if (code == 0xda || (wasEntropy && code == 0xdc)) {
                entropy = true
                scanStart = position
            }
        }
        fail("CORRUPTED_CONTAINER", "JPEG end-of-image marker is missing")
    }

    private suspend fun classify(reader: BinaryReader, marker: Int, payload: ByteRange): AppPayloadKind {
        val prefix = reader.readBuffer(payload.offset, minOf(40uL, payload.length).toUInt()).orThrow()
        return when {
            marker == 0xe1 && prefix.startsWith("Exif\u0000\u0000") -> AppPayloadKind.Exif
            marker == 0xe1 && prefix.startsWith("http://ns.adobe.com/xap/1.0/\u0000") -> AppPayloadKind.Xmp
            marker == 0xe1 && prefix.startsWith("http://ns.adobe.com/xmp/extension/\u0000") -> AppPayloadKind.ExtendedXmp
            marker == 0xe2 && prefix.startsWith("ICC_PROFILE\u0000") -> AppPayloadKind.Icc
            marker == 0xe2 && prefix.startsWith("MPF\u0000") -> AppPayloadKind.Mpf
            else -> AppPayloadKind.Unknown
        }
    }
}

private fun Bytes.startsWith(text: String): Boolean = size >= text.length && text.indices.all { (this[it].toInt() and 0xff) == text[it].code }

private class Scanner(private val reader: BinaryReader, private val size: ULong) {
    private var start: ULong = 0uL
    private var buffer: Bytes = Bytes(byteArrayOf())
    suspend fun byte(offset: ULong): Int {
        if (offset < start || offset - start >= buffer.size.toULong()) {
            start = offset
            buffer = reader.readBuffer(offset, minOf(64uL * 1024uL, size - offset).toUInt()).orThrow()
        }
        return buffer[(offset - start).toInt()].toInt() and 0xff
    }
}
