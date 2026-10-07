package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.BmffBox

internal data class HeifInteger(val value: ULong, val field: ByteRange)
internal data class HeifExtent(val index: HeifInteger, val offset: HeifInteger, val length: HeifInteger, val data: ByteRange)
internal data class HeifItemLocation(val id: UInt, val construction: UInt, val base: HeifInteger, val extents: List<HeifExtent>)
internal data class HeifMovedRange(val original: ByteRange, val destination: ULong)
internal data class HeifOffsetPatch(val original: ByteRange, val destination: ByteRange, val before: Bytes, val after: Bytes)

/** Item locations are not an image graph, decoder proof, or authority to rewrite unknown metadata. */
internal class HeifItemLocations private constructor(
    val identity: SourceIdentity, val items: List<HeifItemLocation>, private val idat: ByteRange?,
) {
    /** Fixed-width relocation only. Caller must separately prove all graph dependencies and copied bytes. */
    suspend fun relocation(reader: BinaryReader, moves: List<HeifMovedRange>, outputSize: ULong): CoreResult<List<HeifOffsetPatch>> = attempt {
        if (reader.identity().orThrow() != identity) fail("SOURCE_CHANGED", "HEIF location plan belongs to a different source", Stage.Plan)
        if (outputSize > reader.context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "HEIF relocation exceeds output budget", Stage.Plan)
        val budget = ParseBudget(reader.context)
        for (move in moves) {
            budget.item(); budget.retain(48uL)
            checkedRange(move.original.offset, move.original.length, identity.size)
            checkedRange(move.destination, move.original.length, outputSize)
        }
        fun disjoint(ranges: List<ByteRange>) {
            var end = 0uL
            for (range in ranges.sortedBy { it.offset }) {
                if (range.length == 0uL || range.offset < end) fail("AMBIGUOUS_LAYOUT", "HEIF relocation maps overlap or are empty", Stage.Plan)
                end = range.endExclusive
            }
        }
        disjoint(moves.map { it.original }); disjoint(moves.map { ByteRange(it.destination, it.original.length) })
        fun mapped(range: ByteRange): ByteRange {
            val move = moves.singleOrNull { range.offset >= it.original.offset && range.endExclusive <= it.original.endExclusive }
                ?: fail("CAPABILITY_UNSUPPORTED", "Every HEIF extent and encoded field must be retained in one relocation region", Stage.Plan)
            return checkedRange(checkedAdd(move.destination, range.offset - move.original.offset), range.length, outputSize)
        }
        fun fits(value: ULong, field: ByteRange): Boolean = when (field.length) {
            0uL -> value == 0uL
            4uL -> value <= UInt.MAX_VALUE.toULong()
            8uL -> true
            else -> false
        }
        val patches = mutableListOf<HeifOffsetPatch>()
        fun patch(integer: HeifInteger, value: ULong) {
            if (!fits(value, integer.field)) fail("VALUE_NOT_REPRESENTABLE", "Relocated HEIF offset does not fit its original width", Stage.Plan)
            if (integer.field.length == 0uL) return
            val destination = mapped(integer.field)
            if (value != integer.value) {
                budget.item(); budget.retain(96uL)
                patches.add(HeifOffsetPatch(integer.field, destination,
                    unsignedBytes(integer.value, integer.field.length.toInt(), Endian.Big),
                    unsignedBytes(value, integer.field.length.toInt(), Endian.Big)))
            }
        }
        for (item in items) {
            budget.poll()
            // Even unchanged encoded fields must survive the move: no silently discarded iloc.
            if (item.base.field.length != 0uL) mapped(item.base.field)
            for (extent in item.extents) for (integer in listOf(extent.index, extent.offset, extent.length))
                if (integer.field.length != 0uL) mapped(integer.field)
            val relocated = item.extents.map { mapped(it.data) }
            if (item.construction == 1u) {
                val oldIdat = idat ?: fail("CORRUPTED_CONTAINER", "Missing HEIF idat")
                val newIdat = mapped(oldIdat)
                for ((extent, data) in item.extents.zip(relocated))
                    if (data.offset - newIdat.offset != extent.data.offset - oldIdat.offset)
                        fail("CAPABILITY_UNSUPPORTED", "HEIF idat extents must retain their relative positions", Stage.Plan)
            } else {
                fun usable(base: ULong): Boolean = fits(base, item.base.field) && item.extents.zip(relocated).all { (extent, data) ->
                    data.offset >= base && fits(data.offset - base, extent.offset.field)
                }
                val first = item.extents.first()
                val shiftedBase = relocated.first().offset.takeIf { it >= first.offset.value }?.minus(first.offset.value)
                val base = when {
                    usable(item.base.value) -> item.base.value
                    shiftedBase != null && usable(shiftedBase) -> shiftedBase
                    usable(0uL) -> 0uL
                    else -> fail("VALUE_NOT_REPRESENTABLE", "HEIF relocation requires changing integer widths", Stage.Plan)
                }
                patch(item.base, base)
                for ((extent, data) in item.extents.zip(relocated)) patch(extent.offset, data.offset - base)
            }
        }
        reader.validateIdentity().orThrow()
        frozenList(patches)
    }

    companion object {
        suspend fun read(reader: BinaryReader, iloc: BmffBox, media: List<ByteRange>, idat: ByteRange? = null,
                         budget: ParseBudget = ParseBudget(reader.context)): CoreResult<HeifItemLocations> = attempt {
            if (iloc.type != "iloc") fail("INVALID_ARGUMENT", "HEIF item location reader requires iloc")
            val identity = reader.identity().orThrow()
            checkedRange(iloc.payload.offset, iloc.payload.length, identity.size)
            for (range in media + listOfNotNull(idat)) checkedRange(range.offset, range.length, identity.size)
            val cursor = LocationCursor(reader, iloc.payload)
            val full = cursor.integer(4)
            val version = (full.value shr 24).toUInt()
            if (version > 2u || full.value and 0xffffffuL != 0uL) fail("CAPABILITY_UNSUPPORTED", "Unsupported HEIF iloc version or flags")
            val widths = cursor.integer(2).value.toInt()
            val offsetWidth = (widths shr 12) and 15
            val lengthWidth = (widths shr 8) and 15
            val baseWidth = (widths shr 4) and 15
            val indexWidth = if (version == 0u) 0 else widths and 15
            if (version == 0u && widths and 15 != 0) fail("CORRUPTED_CONTAINER", "HEIF iloc reserved index size is nonzero")
            if (listOf(offsetWidth, baseWidth, indexWidth).any { it !in listOf(0, 4, 8) } || lengthWidth !in listOf(4, 8))
                fail("CAPABILITY_UNSUPPORTED", "HEIF iloc integer width or implicit extent length is unsupported")
            val count = cursor.integer(if (version == 2u) 4 else 2).value
            val items = mutableListOf<HeifItemLocation>()
            val ids = mutableSetOf<UInt>()
            var remaining = count
            while (remaining != 0uL) {
                budget.item(); budget.retain(128uL)
                val id = cursor.integer(if (version == 2u) 4 else 2).value.toUInt()
                if (id == 0u || !ids.add(id)) fail("CORRUPTED_CONTAINER", "HEIF item IDs must be nonzero and unique")
                val method = if (version == 0u) 0u else cursor.integer(2).value.toUInt()
                if (method and 0xfff0u != 0u) fail("CORRUPTED_CONTAINER", "HEIF construction method reserved bits are nonzero")
                if (method > 1u) fail("CAPABILITY_UNSUPPORTED", "HEIF construction method is unsupported")
                if (cursor.integer(2).value != 0uL) fail("CAPABILITY_UNSUPPORTED", "External HEIF data references are unsupported")
                val base = cursor.integer(baseWidth)
                val extentCount = cursor.integer(2).value
                if (extentCount == 0uL) fail("CAPABILITY_UNSUPPORTED", "HEIF item has no explicit extents")
                val extents = mutableListOf<HeifExtent>()
                var left = extentCount
                while (left != 0uL) {
                    budget.item(); budget.retain(192uL)
                    val index = cursor.integer(indexWidth)
                    if (index.value != 0uL) fail("CAPABILITY_UNSUPPORTED", "HEIF extent indices require an unimplemented item construction graph")
                    val offset = cursor.integer(offsetWidth)
                    val length = cursor.integer(lengthWidth)
                    if (length.value == 0uL) fail("CAPABILITY_UNSUPPORTED", "Implicit-to-end HEIF extents are unsupported")
                    val relative = checkedAdd(base.value, offset.value)
                    val origin = if (method == 1u) idat?.offset ?: fail("CORRUPTED_CONTAINER", "HEIF idat construction has no idat payload") else 0uL
                    val range = checkedRange(checkedAdd(origin, relative), length.value, identity.size)
                    val containers = if (method == 1u) listOfNotNull(idat) else media
                    if (containers.count { range.offset >= it.offset && range.endExclusive <= it.endExclusive } != 1)
                        fail("OFFSET_OUT_OF_BOUNDS", "HEIF extent is not contained in exactly one permitted media payload")
                    extents.add(HeifExtent(index, offset, length, range)); left--
                }
                items.add(HeifItemLocation(id, method, base, frozenList(extents))); remaining--
            }
            cursor.finished()
            reader.validateIdentity().orThrow()
            HeifItemLocations(identity, frozenList(items), idat)
        }
    }
}

private class LocationCursor(private val reader: BinaryReader, private val payload: ByteRange) {
    private var position = payload.offset
    suspend fun integer(width: Int): HeifInteger {
        checkedRange(position, width.toULong(), payload.endExclusive)
        val field = ByteRange(position, width.toULong())
        val value = if (width == 0) 0uL else readUnsigned(reader.readBuffer(position, width.toUInt()).orThrow(), Endian.Big)
        position = checkedAdd(position, width.toULong())
        return HeifInteger(value, field)
    }
    fun finished() {
        if (position != payload.endExclusive) fail("CORRUPTED_CONTAINER", "HEIF iloc contains unparsed trailing bytes")
    }
}
