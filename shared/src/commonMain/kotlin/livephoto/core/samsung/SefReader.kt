package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.*

internal data class SefRecord(val prefix: UShort, val type: UShort, val name: Bytes, val range: ByteRange, val payloadRange: ByteRange)
internal data class SefDirectory(
    val sourceIdentity: SourceIdentity, val parent: ByteRange, val recordFloor: ULong?,
    val table: ByteRange, val footer: ByteRange, val version: UInt, val records: List<SefRecord>,
    val suffixRange: ByteRange, val gaps: List<ByteRange>, val legacyDialect: Boolean,
) {
    val motionRecord: SefRecord? get() = records.singleOrNull { it.type == 0x0a30u.toUShort() }
    val versionRecord: SefRecord? get() = records.singleOrNull { it.type == 0x0a31u.toUShort() }
    /** Pointer records expose their raw payload separately; they are never mistaken for video bytes. */
    val pureVideoRange: ByteRange? get() = motionRecord?.payloadRange?.takeUnless { it.length == 12uL }
}

/** Exact-footer, indexed SEF parsing. This primitive does not validate BMFF or image semantics. */
internal object SefReader {
    suspend fun parse(reader: BinaryReader, jpegEnd: ULong? = null, budget: ParseBudget = ParseBudget(reader.context)): CoreResult<SefDirectory?> = attempt {
        val size = reader.identity().orThrow().size
        if (jpegEnd != null && jpegEnd > size) fail("SEF_DIRECTORY_INVALID", "JPEG boundary exceeds its source")
        parseBounded(reader, ByteRange(0uL, size), jpegEnd, budget)
    }

    suspend fun parseInRange(reader: BinaryReader, parent: ByteRange, budget: ParseBudget = ParseBudget(reader.context)): CoreResult<SefDirectory?> = attempt {
        checkedRange(parent.offset, parent.length, reader.identity().orThrow().size)
        parseBounded(reader, parent, parent.offset, budget)
    }

    private suspend fun parseBounded(reader: BinaryReader, parent: ByteRange, floor: ULong?, budget: ParseBudget): SefDirectory? {
        if (parent.length < 4uL) { reader.validateIdentity().orThrow(); return null }
        val end = parent.endExclusive
        if (!signature(reader.readBuffer(end - 4uL, 4u).orThrow(), "SEFT")) { reader.validateIdentity().orThrow(); return null }
        if (parent.length < 20uL) invalid("SEFT has no complete directory/footer")
        val footer = ByteRange(end - 8uL, 8uL)
        val r = reader.readU32(footer.offset, Endian.Little).orThrow().toULong()
        val candidates = mutableListOf<SefDirectory>()
        for (legacy in listOf(false, true)) {
            checkCancelled(reader.context)
            val tableLength = if (legacy) { if (r < 8uL) continue; r - 8uL } else r
            if (tableLength < 12uL || tableLength > footer.offset - parent.offset) continue
            val start = footer.offset - tableLength
            if (!signature(reader.readBuffer(start, 4u).orThrow(), "SEFH")) continue
            try { candidates.add(candidate(reader, parent, floor, start, tableLength, footer, legacy, budget)) }
            catch (fault: CoreFault) {
                if (fault.error.code.value != "SEF_DIRECTORY_INVALID") throw fault
            }
        }
        if (candidates.isEmpty()) invalid("SEFT footer has no complete indexed SEF graph")
        if (candidates.size != 1) fail("AMBIGUOUS_LAYOUT", "Both canonical and legacy SEF directory layouts are valid")
        reader.validateIdentity().orThrow()
        return candidates.single()
    }

    private suspend fun candidate(reader: BinaryReader, parent: ByteRange, floor: ULong?, start: ULong, length: ULong, footer: ByteRange, legacy: Boolean, budget: ParseBudget): SefDirectory {
        budget.item(); budget.retain(192uL)
        val version = reader.readU32(checkedAdd(start, 4uL), Endian.Little).orThrow()
        val count = reader.readU32(checkedAdd(start, 8uL), Endian.Little).orThrow().toULong()
        if (checkedAdd(12uL, checkedMultiply(count, 12uL)) != length) invalid("SEF table count disagrees with its exact footer boundary")
        budget.retain(checkedMultiply(count, 160uL))
        val records = mutableListOf<SefRecord>()
        val seen = mutableSetOf<Pair<UShort, Bytes>>()
        var hasMotion = false
        var hasVersion = false
        for (index in 0 until checkedInt(count)) {
            budget.item(1u)
            val at = checkedAdd(checkedAdd(start, 12uL), checkedMultiply(index.toULong(), 12uL))
            val entry = reader.readBuffer(at, 12u).orThrow()
            val prefix = readUnsigned(entry.slice(0, 2), Endian.Little).toUShort()
            val type = readUnsigned(entry.slice(2, 4), Endian.Little).toUShort()
            val back = readUnsigned(entry.slice(4, 8), Endian.Little)
            val size = readUnsigned(entry.slice(8, 12), Endian.Little)
            val lower = maxOf(parent.offset, floor ?: parent.offset)
            if (lower > start) invalid("SEF directory lies before the image/parent payload boundary")
            if (back > start - lower || size > back || size < 8uL) invalid("SEF record range exceeds its indexed payload region")
            val position = start - back
            val range = ByteRange(position, size)
            val header = reader.readBuffer(position, 8u).orThrow()
            if (readUnsigned(header.slice(0, 2), Endian.Little).toUShort() != prefix || readUnsigned(header.slice(2, 4), Endian.Little).toUShort() != type) invalid("SEF record prefix/type disagree with the index")
            val nameLength = readUnsigned(header.slice(4, 8), Endian.Little)
            if (nameLength > size - 8uL) invalid("SEF record name exceeds its record")
            budget.retain(nameLength)
            val name = reader.readExactly(checkedAdd(position, 8uL), checkedInt(nameLength).toUInt()).orThrow()
            if (!seen.add(type to name)) invalid("SEF contains duplicate record type/name ownership")
            val payload = ByteRange(checkedAdd(checkedAdd(position, 8uL), nameLength), size - 8uL - nameLength)
            if (type == 0x0a30u.toUShort()) {
                if (hasMotion || prefix != 0u.toUShort() || !signature(name, "MotionPhoto_Data") || payload.length == 0uL) invalid("SEF MotionPhoto_Data header is invalid or duplicated")
                hasMotion = true
                if (payload.length == 12uL && !signature(reader.readBuffer(payload.offset, 4u).orThrow(), "mpv2")) invalid("Twelve-byte motion payload is not an mpv2 pointer")
            }
            if (type == 0x0a31u.toUShort()) {
                if (hasVersion || prefix != 0u.toUShort() || !signature(name, "MotionPhoto_Version") || payload.length != 4uL || !signature(reader.readBuffer(payload.offset, 4u).orThrow(), "mpv3")) invalid("SEF motion version record is invalid or duplicated")
                hasVersion = true
            }
            records.add(SefRecord(prefix, type, name, range, payload))
        }
        val ordered = records.sortedWith(Comparator { a, b -> checkCancelled(reader.context); a.range.offset.compareTo(b.range.offset) })
        val first = floor ?: ordered.firstOrNull()?.range?.offset ?: start
        if (first > start) invalid("SEF records begin after their directory")
        val gaps = mutableListOf<ByteRange>()
        var cursor = first
        for (record in ordered) {
            checkCancelled(reader.context)
            if (record.range.offset < cursor) invalid("SEF records overlap")
            if (record.range.offset > cursor) { budget.retain(32uL); gaps.add(ByteRange(cursor, record.range.offset - cursor)) }
            cursor = record.range.endExclusive
        }
        if (cursor < start) { budget.retain(32uL); gaps.add(ByteRange(cursor, start - cursor)) }
        return SefDirectory(reader.identity().orThrow(), parent, floor, ByteRange(start, length), footer, version,
            frozenList(records), ByteRange(first, parent.endExclusive - first), frozenList(gaps), legacy)
    }

    private fun signature(bytes: Bytes, expected: String): Boolean = bytes.size == expected.length && expected.indices.all { bytes[it].toInt() and 255 == expected[it].code }
    private fun invalid(message: String): Nothing = fail("SEF_DIRECTORY_INVALID", message)
}
