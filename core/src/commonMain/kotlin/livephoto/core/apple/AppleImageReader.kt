package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.exif.*
import livephoto.core.jpeg.*

internal data class AppleImageIdentifier(val value: String, val range: ByteRange, val makerNote: ByteRange)

/** Only the MakerNote reached from IFD0's formal ExifIFD can own an Apple image identifier. */
internal object AppleImageReader {
    suspend fun read(reader: BinaryReader, jpeg: JpegStructure, budget: ParseBudget, documents: List<TiffDocument>? = null): CoreResult<AppleImageIdentifier?> = attempt {
        val tiffs = documents ?: jpeg.segments.filter { it.payloadKind == AppPayloadKind.Exif }.map { segment ->
            val payload = segment.payload!!
            TiffReader(reader, budget).read(ByteRange(payload.offset + 6uL, payload.length - 6uL)).orThrow()
        }
        readDocuments(reader, tiffs, budget).orThrow()
    }

    /** TIFF offsets stay in the reader's address space, including a HEIF logical Exif view. */
    suspend fun readDocuments(reader: BinaryReader, tiffs: List<TiffDocument>, budget: ParseBudget): CoreResult<AppleImageIdentifier?> = attempt {
        val found = mutableListOf<AppleImageIdentifier>()
        var totalNotes = 0
        for (tiff in tiffs) {
            val root = tiff.ifds.singleOrNull { it.relativeOffset == tiff.firstIfdOffset } ?: continue
            val pointers = root.entries.filter { it.tag == 0x8769u.toUShort() }
            val exifOffset = pointers.singleOrNull()?.takeIf { it.type == 4u.toUShort() && it.count == 1u }?.value?.let { readUnsigned(it, tiff.endian).toUInt() }
            for (ifd in tiff.ifds) for (entry in ifd.entries.filter { it.isOpaqueMakerNote }) {
                totalNotes++
                val bytes = entry.value ?: continue
                val signature = "Apple iOS\u0000".encodeToByteArray()
                if (bytes.size < 14 || !signature.indices.all { bytes[it] == signature[it] }) continue
                if (ifd.relativeOffset != exifOffset || pointers.size != 1 || entry.type != 7u.toUShort())
                    fail("CONFLICTING_METADATA", "Apple MakerNote is not uniquely owned by the formal ExifIFD")
                if (bytes[10] != 0.toByte() || bytes[11] != 1.toByte() || bytes[12] != 'M'.code.toByte() || bytes[13] != 'M'.code.toByte())
                    fail("UNKNOWN_PROTOCOL_VARIANT", "Apple MakerNote header variant is unconfirmed")
                val note = entry.valueRange ?: fail("CORRUPTED_CONTAINER", "Apple MakerNote has no bounded value")
                checkedRange(0uL, 16uL, note.length)
                val count = reader.readU16(note.offset + 14uL).orThrow().toULong()
                val tableEnd = checkedAdd(16uL, checkedMultiply(count, 12uL))
                checkedRange(0uL, tableEnd, note.length)
                var identifier: AppleImageIdentifier? = null
                for (index in 0 until checkedInt(count)) {
                    budget.item(); budget.retain(64uL)
                    val position = 16 + index * 12
                    if (readUnsigned(bytes.slice(position, position + 2), Endian.Big) != 0x11uL) continue
                    if (identifier != null) fail("CONFLICTING_METADATA", "Duplicate Apple ContentIdentifier entries")
                    val type = readUnsigned(bytes.slice(position + 2, position + 4), Endian.Big)
                    val length = readUnsigned(bytes.slice(position + 4, position + 8), Endian.Big)
                    val offset = readUnsigned(bytes.slice(position + 8, position + 12), Endian.Big)
                    if (type != 2uL || length < 2uL || offset < tableEnd) fail("INVALID_PAIR_IDENTIFIER", "Apple identifier requires a disjoint ASCII value")
                    checkedRange(offset, length, note.length)
                    val raw = bytes.slice(checkedInt(offset), checkedInt(offset + length))
                    val value = decodeUtf8Strict(if (raw[raw.size - 1] == 0.toByte()) raw.slice(0, raw.size - 1) else raw, budget)
                    if (!appleUuid(value)) fail("INVALID_PAIR_IDENTIFIER", "Apple identifier is outside the confirmed UUID profile")
                    identifier = AppleImageIdentifier(value, ByteRange(note.offset + offset, length), note)
                }
                identifier?.let { found += it }
            }
        }
        if (found.size > 1 || found.isNotEmpty() && totalNotes != 1) fail("CONFLICTING_METADATA", "Multiple MakerNote authorities shadow the Apple identifier")
        reader.validateIdentity().orThrow()
        found.singleOrNull()
    }
}

internal fun appleUuid(value: String): Boolean = value.length == 36 && value.indices.all { index ->
    if (index in setOf(8, 13, 18, 23)) value[index] == '-' else value[index] in '0'..'9' || value[index] in 'a'..'f' || value[index] in 'A'..'F'
}
