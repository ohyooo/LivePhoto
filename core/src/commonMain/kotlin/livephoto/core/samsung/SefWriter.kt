package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.*

internal sealed interface SefWritePart {
    data class Literal(val bytes: Bytes) : SefWritePart
    data class Copy(val reader: BinaryReader, val range: ByteRange, val sourceIdentity: SourceIdentity) : SefWritePart
}
internal data class SefWritePlan(val length: ULong, val videoOffset: ULong?, val videoLength: ULong?, val parts: List<SefWritePart>, val ordinaryRecords: List<SefRecord>)

/** Canonical SEF indexing; records/video stay streaming ranges, never whole-file allocations. */
internal object SefWriter {
    suspend fun createPlan(
        videoReader: BinaryReader, videoRange: ByteRange, budget: ParseBudget = ParseBudget(videoReader.context),
        ordinaryReader: BinaryReader? = null, ordinaryDirectory: SefDirectory? = null,
    ): CoreResult<SefWritePlan> = attempt {
        val videoIdentity = videoReader.identity().orThrow()
        checkedRange(videoRange.offset, videoRange.length, videoIdentity.size)
        if (videoRange.length == 0uL) fail("INVALID_ARGUMENT", "Samsung video must be nonempty")
        if (ordinaryReader == null && ordinaryDirectory != null || ordinaryReader != null && ordinaryDirectory == null) fail("INVALID_ARGUMENT", "Ordinary SEF source and directory must be supplied together")
        val ordinary = if (ordinaryReader != null && ordinaryDirectory != null) verifiedOrdinary(ordinaryReader, ordinaryDirectory, budget) else emptyList()
        val records = mutableListOf<RecordParts>()
        val header = recordHeader(0x0a30u, "MotionPhoto_Data", budget)
        val motionSize = checkedAdd(header.size.toULong(), videoRange.length)
        width32(motionSize)
        records.add(RecordParts(0u, 0x0a30u.toUShort(), motionSize,
            listOf(SefWritePart.Literal(header), SefWritePart.Copy(videoReader, videoRange, videoIdentity))))
        val versionHeader = recordHeader(0x0a31u, "MotionPhoto_Version", budget)
        budget.retain(8uL)
        val versionBytes = Bytes("mpv3".encodeToByteArray())
        records.add(RecordParts(0u, 0x0a31u.toUShort(), checkedAdd(versionHeader.size.toULong(), 4uL), listOf(SefWritePart.Literal(versionHeader), SefWritePart.Literal(versionBytes))))
        for (record in ordinary) {
            budget.item(1u); budget.retain(128uL)
            records.add(RecordParts(record.prefix, record.type, record.range.length,
                listOf(SefWritePart.Copy(ordinaryReader!!, record.range, ordinaryDirectory!!.sourceIdentity))))
        }
        val plan = index(records, ordinary, budget)
        videoReader.validateIdentity().orThrow()
        ordinaryReader?.validateIdentity()?.orThrow()
        plan.copy(videoOffset = 24uL, videoLength = videoRange.length)
    }

    suspend fun cleanPlan(reader: BinaryReader, directory: SefDirectory, budget: ParseBudget = ParseBudget(reader.context)): CoreResult<SefWritePlan> = attempt {
        val ordinary = verifiedOrdinary(reader, directory, budget)
        val records = ordinary.map { record ->
            budget.item(1u); budget.retain(128uL)
            RecordParts(record.prefix, record.type, record.range.length, listOf(SefWritePart.Copy(reader, record.range, directory.sourceIdentity)))
        }
        // With no ordinary SEF, the entire proved live-only suffix may be omitted.
        val plan = if (records.isEmpty() && directory.records.any { it.type == 0x0a30u.toUShort() || it.type == 0x0a31u.toUShort() })
            SefWritePlan(0uL, null, null, emptyList(), emptyList()) else index(records, ordinary, budget)
        reader.validateIdentity().orThrow()
        plan
    }

    suspend fun write(plan: SefWritePlan, writer: BinaryWriter, context: Context): CoreResult<Unit> = attempt {
        var total = 0uL
        for (part in plan.parts) total = checkedAdd(total, when (part) { is SefWritePart.Literal -> part.bytes.size.toULong(); is SefWritePart.Copy -> part.range.length })
        if (total != plan.length) fail("INVALID_ARGUMENT", "SEF write plan length is inconsistent")
        writer.budget.checkCapacity(total)
        for (part in plan.parts) {
            checkCancelled(context)
            when (part) {
                is SefWritePart.Literal -> writer.writeAll(part.bytes).orThrow()
                is SefWritePart.Copy -> {
                    if (part.reader.identity().orThrow() != part.sourceIdentity) fail("SOURCE_CHANGED", "SEF source changed before staging copy")
                    copyRange(part.reader, writer, part.range, context).orThrow()
                }
            }
        }
        for (part in plan.parts) if (part is SefWritePart.Copy) part.reader.validateIdentity().orThrow()
    }

    private suspend fun verifiedOrdinary(reader: BinaryReader, directory: SefDirectory, budget: ParseBudget): List<SefRecord> {
        if (reader.identity().orThrow() != directory.sourceIdentity) fail("SOURCE_CHANGED", "SEF directory source identity changed")
        val current = if (directory.parent.offset == 0uL && directory.parent.length == directory.sourceIdentity.size)
            SefReader.parse(reader, directory.recordFloor, budget).orThrow()
        else SefReader.parseInRange(reader, directory.parent, budget).orThrow()
        if (current != directory) fail("SOURCE_CHANGED", "SEF directory facts no longer match their source")
        if (directory.version != 107u) fail("UNSUPPORTED_CONTAINER", "Unknown SEFH versions cannot authorize index rebuilding")
        if (directory.gaps.isNotEmpty()) fail("UNSAFE_METADATA_REWRITE", "Unindexed SEF gaps cannot be silently discarded")
        return directory.records.filter { it.type != 0x0a30u.toUShort() && it.type != 0x0a31u.toUShort() }.sortedWith(Comparator { a, b -> checkCancelled(reader.context); a.range.offset.compareTo(b.range.offset) })
    }

    private data class RecordParts(val prefix: UShort, val type: UShort, val size: ULong, val parts: List<SefWritePart>)

    private fun index(records: List<RecordParts>, ordinary: List<SefRecord>, budget: ParseBudget): SefWritePlan {
        val tableLength = checkedAdd(12uL, checkedMultiply(records.size.toULong(), 12uL))
        width32(tableLength)
        budget.retain(checkedMultiply(checkedAdd(tableLength, 8uL), 3uL))
        var payloadLength = 0uL
        for (record in records) { budget.item(1u); width32(record.size); payloadLength = checkedAdd(payloadLength, record.size) }
        // Every backwards directory pointer is 32-bit, including the first record's span.
        width32(payloadLength)
        val table = ByteArray(checkedInt(checkedAdd(tableLength, 8uL)))
        "SEFH".encodeToByteArray().copyInto(table, 0)
        put(table, 4, 107uL, 4); put(table, 8, records.size.toULong(), 4)
        var preceding = 0uL
        val parts = mutableListOf<SefWritePart>()
        for ((index, record) in records.withIndex()) {
            budget.item(1u); budget.retain(96uL)
            val position = 12 + index * 12
            put(table, position, record.prefix.toULong(), 2); put(table, position + 2, record.type.toULong(), 2)
            put(table, position + 4, payloadLength - preceding, 4); put(table, position + 8, record.size, 4)
            parts.addAll(record.parts)
            preceding = checkedAdd(preceding, record.size)
        }
        put(table, checkedInt(tableLength), tableLength, 4)
        "SEFT".encodeToByteArray().copyInto(table, checkedInt(tableLength) + 4)
        parts.add(SefWritePart.Literal(Bytes(table)))
        return SefWritePlan(checkedAdd(payloadLength, checkedAdd(tableLength, 8uL)), null, null, frozenList(parts), frozenList(ordinary))
    }

    private fun recordHeader(type: UInt, name: String, budget: ParseBudget): Bytes {
        budget.retain(checkedMultiply((8 + name.length).toULong(), 3uL))
        val output = ByteArray(8 + name.length)
        put(output, 0, 0uL, 2); put(output, 2, type.toULong(), 2); put(output, 4, name.length.toULong(), 4)
        name.encodeToByteArray().copyInto(output, 8)
        return Bytes(output)
    }

    private fun put(bytes: ByteArray, position: Int, value: ULong, width: Int) { unsignedBytes(value, width, Endian.Little).copyInto(bytes, position) }
    private fun width32(value: ULong) { if (value > UInt.MAX_VALUE.toULong()) fail("VALUE_NOT_REPRESENTABLE", "SEF index field exceeds its 32-bit representation") }
}
