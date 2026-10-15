package livephoto.core.exif

import livephoto.core.*
import livephoto.core.binary.*

/** Entry/value ranges are absolute source ranges. Encoded TIFF pointers stay relative to the TIFF base. */
internal data class TiffEntry(
    val tag: UShort,
    val type: UShort,
    val count: UInt,
    val entryRange: ByteRange,
    val rawValueField: Bytes,
    val valueRange: ByteRange?,
    val value: Bytes?,
) {
    val isOpaqueMakerNote: Boolean get() = tag == 0x927cu.toUShort()
}

internal data class TiffIfd(val relativeOffset: UInt, val entries: List<TiffEntry>, val nextOffset: UInt)
internal data class TiffDocument(
    val range: ByteRange,
    val endian: Endian,
    /** Encoded offset relative to range.offset; entry/value ranges below are absolute. */
    val firstIfdOffset: UInt,
    val ifds: List<TiffIfd>,
)

/** Read-only TIFF 6/EXIF infrastructure. No relocation or private MakerNote interpretation. */
internal class TiffReader(private val reader: BinaryReader, private val budget: ParseBudget = ParseBudget(reader.context)) {
    suspend fun read(range: ByteRange): CoreResult<TiffDocument> = attempt {
        checkedRange(range.offset, range.length, reader.identity().orThrow().size)
        if (range.length < 8uL) fail("CORRUPTED_CONTAINER", "TIFF header is truncated")
        budget.retain(8uL)
        val header = reader.readExactly(range.offset, 8u).orThrow()
        val endian = when {
            header[0] == 0x49.toByte() && header[1] == 0x49.toByte() -> Endian.Little
            header[0] == 0x4d.toByte() && header[1] == 0x4d.toByte() -> Endian.Big
            else -> fail("CORRUPTED_CONTAINER", "TIFF byte order is absent")
        }
        when (readUnsigned(header.slice(2, 4), endian)) {
            42uL -> Unit
            43uL -> fail("UNSUPPORTED_CONTAINER", "BigTIFF is not implemented")
            else -> fail("CORRUPTED_CONTAINER", "TIFF magic is invalid")
        }
        val first = readUnsigned(header.slice(4, 8), endian).toUInt()
        val result = mutableListOf<TiffIfd>()
        val complete = mutableSetOf<UInt>()
        val active = mutableSetOf<UInt>()
        data class Visit(val offset: UInt, val depth: UInt, val exit: Boolean = false)
        val pending = mutableListOf<Visit>()
        if (first != 0u) pending.add(Visit(first, 0u))
        while (pending.isNotEmpty()) {
            checkCancelled(reader.context)
            val visit = pending.removeAt(pending.lastIndex)
            if (visit.exit) { active.remove(visit.offset); complete.add(visit.offset); continue }
            if (visit.offset in active) fail("CORRUPTED_CONTAINER", "TIFF IFD references form a cycle")
            if (visit.offset in complete) continue
            budget.item(visit.depth)
            budget.retain(48uL)
            if (visit.offset < 8u) fail("CORRUPTED_CONTAINER", "TIFF IFD overlaps the TIFF header")
            fun absolute(relative: ULong, length: ULong): ULong {
                checkedRange(relative, length, range.length)
                return checkedAdd(range.offset, relative)
            }
            val base = visit.offset.toULong()
            val count = reader.readU16(absolute(base, 2uL), endian).orThrow().toULong()
            val tableLength = checkedAdd(checkedMultiply(count, 12uL), 6uL)
            absolute(base, tableLength)
            // Reserve entry model/raw-field memory before any table/list allocation.
            budget.retain(checkedMultiply(count, 64uL))
            val entries = mutableListOf<TiffEntry>()
            val links = mutableListOf<UInt>()
            for (index in 0 until checkedInt(count)) {
                budget.item(visit.depth)
                val relative = checkedAdd(checkedAdd(base, 2uL), checkedMultiply(index.toULong(), 12uL))
                val position = absolute(relative, 12uL)
                val bytes = reader.readExactly(position, 12u).orThrow()
                val tag = readUnsigned(bytes.slice(0, 2), endian).toUShort()
                val type = readUnsigned(bytes.slice(2, 4), endian).toUShort()
                val valueCount = readUnsigned(bytes.slice(4, 8), endian).toUInt()
                val raw = bytes.slice(8, 12)
                val width = typeWidth(type)
                var valueRange: ByteRange? = null
                var value: Bytes? = null
                if (width != null) {
                    val length = checkedMultiply(valueCount.toULong(), width)
                    val valueRelative = if (length <= 4uL) checkedAdd(relative, 8uL) else readUnsigned(raw, endian)
                    val valuePosition = absolute(valueRelative, length)
                    valueRange = ByteRange(valuePosition, length)
                    budget.retain(length)
                    val allocation = checkedInt(length)
                    value = reader.readExactly(valuePosition, allocation.toUInt()).orThrow()
                }
                entries.add(TiffEntry(tag, type, valueCount, ByteRange(position, 12uL), raw, valueRange, value))
                // Follow only standardized IFD pointers; unknown tags and MakerNote stay opaque.
                if (tag in pointerTags) {
                    if (type != 4u.toUShort() && type != 13u.toUShort()) fail("CORRUPTED_CONTAINER", "TIFF IFD pointer has an unsupported field type")
                    val pointers = value ?: fail("CORRUPTED_CONTAINER", "TIFF IFD pointer value is unavailable")
                    if (tag != 0x014au.toUShort() && valueCount != 1u) fail("CORRUPTED_CONTAINER", "EXIF IFD pointer must have exactly one value")
                    for (pointerIndex in 0 until checkedInt(valueCount.toULong())) {
                        budget.item(visit.depth)
                        val start = pointerIndex * 4
                        val target = readUnsigned(pointers.slice(start, start + 4), endian).toUInt()
                        if (target != 0u) { budget.retain(24uL); links.add(target) }
                    }
                }
            }
            val nextPosition = absolute(checkedAdd(checkedAdd(base, 2uL), checkedMultiply(count, 12uL)), 4uL)
            val next = reader.readU32(nextPosition, endian).orThrow()
            if (next != 0u) { budget.retain(24uL); links.add(next) }
            result.add(TiffIfd(visit.offset, entries.toList(), next))
            active.add(visit.offset)
            pending.add(Visit(visit.offset, visit.depth, true))
            for (target in links.asReversed()) {
                if (visit.depth == UInt.MAX_VALUE) fail("RESOURCE_LIMIT_EXCEEDED", "IFD depth overflows")
                if (visit.depth >= reader.context.limits.maxDepth) fail("RESOURCE_LIMIT_EXCEEDED", "IFD depth exceeds its budget")
                pending.add(Visit(target, visit.depth + 1u))
            }
        }
        reader.validateIdentity().orThrow()
        TiffDocument(range, endian, first, result.toList())
    }

    private fun typeWidth(type: UShort): ULong? = when (type.toInt()) {
        1, 2, 6, 7 -> 1uL
        3, 8 -> 2uL
        4, 9, 11, 13 -> 4uL
        5, 10, 12 -> 8uL
        else -> null // Preserve raw entry without guessing an unknown type's allocation.
    }

    private val pointerTags = setOf(0x8769u.toUShort(), 0x8825u.toUShort(), 0xa005u.toUShort(), 0x014au.toUShort())
}
