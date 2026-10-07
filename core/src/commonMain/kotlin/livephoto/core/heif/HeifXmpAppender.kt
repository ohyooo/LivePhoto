package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.FixedPatch
import livephoto.core.implementation.FixedPatchSource
import livephoto.core.xmp.XmpReader

private data class HeifInsertion(val offset: ULong, val bytes: Bytes)

/** Finite internal proof: append one owned XMP item and cdsc, retaining every unrequested source byte. */
internal class HeifXmpAppender private constructor(
    private val carrier: HeifCodedCarrier, private val xmp: Bytes, val itemId: UInt,
    private val insertions: List<HeifInsertion>, private val patches: List<FixedPatch>,
    private val metadataStart: ULong, val byteLength: ULong,
) {
    private fun destination(offset: ULong): ULong {
        var result = offset
        for (insertion in insertions) if (insertion.offset <= offset) result = checkedAdd(result, insertion.bytes.size.toULong())
        return result
    }
    suspend fun write(reader: BinaryReader, writer: BinaryWriter): CoreResult<Unit> = attempt {
        if (reader.identity().orThrow() != carrier.identity) fail("SOURCE_CHANGED", "HEIF XMP plan belongs to another source", Stage.WriteProtocol)
        writer.budget.checkCapacity(byteLength)
        val view = BinaryReader(FixedPatchSource.create(reader, patches).orThrow(), reader.context)
        var cursor = 0uL
        for (insertion in insertions) {
            copyRange(view, writer, ByteRange(cursor, insertion.offset - cursor), reader.context).orThrow()
            writer.writeAll(insertion.bytes).orThrow(); cursor = insertion.offset
        }
        copyRange(view, writer, ByteRange(cursor, carrier.identity.size - cursor), reader.context).orThrow()
        writer.writeAll(header("mdat", xmp.size.toULong() + 8uL)).orThrow()
        writer.writeAll(xmp).orThrow()
        reader.validateIdentity().orThrow()
    }

    suspend fun verify(original: BinaryReader, output: BinaryReader): CoreResult<Unit> = attempt<Unit> {
        if (original.identity().orThrow() != carrier.identity) fail("SOURCE_CHANGED", "HEIF XMP verification source changed", Stage.Verify)
        if (output.identity().orThrow().size != byteLength) fail("POSTCONDITION_FAILED", "HEIF XMP output length is wrong", Stage.Verify)
        val budget = ParseBudget(output.context)
        val roots = BmffReader(output, budget).readBoxes(ByteRange(0uL, byteLength)).orThrow()
        val after = HeifItemGraphReader.read(output, roots, budget).orThrow()
        val coded = HeifCodedItemProbe.primary(output, after, budget).orThrow()
        val before = carrier.graph
        if (after.primary != before.primary || after.infos.size != before.infos.size + 1 || after.locations.items.size != before.locations.items.size + 1 ||
            after.associations != before.associations || after.properties.map { it.type } != before.properties.map { it.type } ||
            after.unknownMeta.map { it.type } != before.unknownMeta.map { it.type } || after.unknownPropertyContainers.isNotEmpty() ||
            coded.declaredWidth != carrier.image.declaredWidth || coded.declaredHeight != carrier.image.declaredHeight ||
            coded.configurationDigest != carrier.image.configurationDigest || coded.nalWidth != carrier.image.nalWidth ||
            coded.nalUnits != carrier.image.nalUnits || coded.nalTypes != carrier.image.nalTypes)
            fail("POSTCONDITION_FAILED", "HEIF XMP insertion changed retained graph/configuration", Stage.Verify)
        if (after.references.size != 1 || after.references.single().type != "cdsc" || after.references.single().from != itemId ||
            after.references.single().to != listOf(before.primary)) fail("POSTCONDITION_FAILED", "HEIF XMP association is wrong", Stage.Verify)
        val owned = after.locations.items.singleOrNull { it.id == itemId }
            ?: fail("POSTCONDITION_FAILED", "HEIF XMP item is absent", Stage.Verify)
        if (owned.construction != 0u || owned.extents.size != 1 || owned.extents.single().data != ByteRange(metadataStart, xmp.size.toULong()))
            fail("POSTCONDITION_FAILED", "HEIF XMP item range is wrong", Stage.Verify)
        val info = after.infos.single { it.id == itemId }
        if (info.type != "mime" || info.protection != 0u || !info.hidden)
            fail("POSTCONDITION_FAILED", "HEIF XMP item classification is wrong", Stage.Verify)
        val parsed = HeifMetadataReader.read(output, after, budget).orThrow()
        if (parsed.issues.isNotEmpty() || parsed.items.size != 1 || parsed.items.single().id != itemId ||
            parsed.items.single().describes != listOf(before.primary) || parsed.items.single().xmp == null)
            fail("POSTCONDITION_FAILED", "HEIF owned XMP cannot be independently read", Stage.Verify)
        if (sha256Range(output, owned.extents.single().data).orThrow() != sha256Bytes(xmp))
            fail("POSTCONDITION_FAILED", "HEIF owned XMP bytes changed", Stage.Verify)
        for (item in before.locations.items) {
            val current = after.locations.items.single { it.id == item.id }
            if (current.construction != item.construction || current.extents.size != item.extents.size)
                fail("POSTCONDITION_FAILED", "HEIF retained item construction changed", Stage.Verify)
            for ((left, right) in item.extents.zip(current.extents)) if (right.data != ByteRange(destination(left.data.offset), left.data.length) || right.index.value != left.index.value)
                fail("POSTCONDITION_FAILED", "HEIF retained item extent moved incorrectly", Stage.Verify)
        }
        // Independent byte proof, not a writer -> reader round trip alone. Splits at all insertions/patches.
        val boundaries = (listOf(0uL, carrier.identity.size) + insertions.map { it.offset } +
            patches.flatMap { listOf(it.range.offset, it.range.endExclusive) }).distinct().sorted()
        for ((start, end) in boundaries.zipWithNext()) {
            if (start == end) continue
            budget.item()
            val range = ByteRange(start, end - start)
            val patch = patches.singleOrNull { it.range == range }
            if (patch != null) {
                val value = patch.value ?: fail("POSTCONDITION_FAILED", "HEIF insertion has no planned patch bytes", Stage.Verify)
                if (output.readBuffer(destination(start), value.size.toUInt()).orThrow() != value)
                    fail("POSTCONDITION_FAILED", "HEIF planned table patch changed", Stage.Verify)
            } else if (sha256Range(original, range).orThrow() != sha256Range(output, ByteRange(destination(start), range.length)).orThrow())
                fail("POSTCONDITION_FAILED", "HEIF XMP insertion changed unrequested bytes", Stage.Verify)
        }
        var shift = 0uL
        for (insertion in insertions) {
            if (output.readExactly(insertion.offset + shift, insertion.bytes.size.toUInt()).orThrow() != insertion.bytes)
                fail("POSTCONDITION_FAILED", "HEIF owned table insertion changed", Stage.Verify)
            shift += insertion.bytes.size.toULong()
        }
        if (output.readBuffer(metadataStart - 8uL, 8u).orThrow() != header("mdat", xmp.size.toULong() + 8uL))
            fail("POSTCONDITION_FAILED", "HEIF owned metadata mdat header changed", Stage.Verify)
        original.validateIdentity().orThrow(); output.validateIdentity().orThrow()
    }

    companion object {
        private fun integer(value: ULong, width: Int): ByteArray {
            val fits = when (width) { 0 -> value == 0uL; 2 -> value <= 0xffffuL; 4 -> value <= UInt.MAX_VALUE.toULong(); 8 -> true; else -> false }
            if (!fits) fail("VALUE_NOT_REPRESENTABLE", "HEIF insertion exceeds its existing integer width", Stage.Plan)
            return if (width == 0) byteArrayOf() else unsignedBytes(value, width, Endian.Big).toByteArray()
        }
        private fun header(type: String, length: ULong) = Bytes(integer(length, 4) + type.encodeToByteArray())
        private fun box(type: String, payload: ByteArray) = header(type, payload.size.toULong() + 8uL).toByteArray() + payload
        private fun sha256Bytes(bytes: Bytes): Digest = Sha256().also { it.update(bytes) }.finish()

        suspend fun prepare(reader: BinaryReader, xmp: Bytes, budget: ParseBudget = ParseBudget(reader.context)): CoreResult<HeifXmpAppender> = attempt {
            budget.retain(xmp.size.toULong())
            XmpReader.parseReserved(xmp, reader.context, budget).orThrow()
            val carrier = HeifCodedCarrier.read(reader, budget, Stage.Plan).orThrow()
            val graph = carrier.graph
            val parser = BmffReader(reader, budget)
            val children = parser.readBoxes(ByteRange(carrier.meta.payload.offset + 4uL, carrier.meta.payload.length - 4uL), 1u).orThrow()
            if (children.any { it.extendsToParentEnd } || graph.infos.any { it.box.extendsToParentEnd })
                fail("CAPABILITY_UNSUPPORTED", "Implicit table/metadata child sizes cannot authorize HEIF insertion", Stage.Plan)
            val info = children.single { it.type == "iinf" }
            val iloc = children.single { it.type == "iloc" }
            val iref = children.singleOrNull { it.type == "iref" }
            val itemId = if (graph.primary == 1u) 2u else 1u
            val infoVersion = reader.readBuffer(info.payload.offset, 1u).orThrow()[0].toInt() and 0xff
            val ilocHeader = reader.readBuffer(iloc.payload.offset, 6u).orThrow()
            val version = ilocHeader[0].toInt() and 0xff
            val widths = readUnsigned(ilocHeader.slice(4, 6), Endian.Big).toInt()
            val offsetWidth = widths shr 12 and 15
            val lengthWidth = widths shr 8 and 15
            val baseWidth = widths shr 4 and 15
            val indexWidth = if (version == 0) 0 else widths and 15
            val referenceVersion = iref?.let { reader.readBuffer(it.payload.offset, 1u).orThrow()[0].toInt() and 0xff } ?: if (graph.primary > 0xffffu) 1 else 0
            val referenceWidth = if (referenceVersion == 0) 2 else 4
            val description = box("cdsc", integer(itemId.toULong(), referenceWidth) + integer(1uL, 2) + integer(graph.primary.toULong(), referenceWidth))
            val reference = if (iref == null) box("iref", byteArrayOf(referenceVersion.toByte(), 0, 0, 0) + description) else description
            val newInfo = box("infe", byteArrayOf(2, 0, 0, 1) + integer(itemId.toULong(), 2) + integer(0uL, 2) +
                "mimeXMP\u0000application/rdf+xml\u0000\u0000".encodeToByteArray())
            val entryLength = (if (version == 2) 4 else 2) + (if (version == 0) 0 else 2) + 2 + baseWidth + 2 + indexWidth + offsetWidth + lengthWidth
            val added = checkedAdd(checkedAdd(newInfo.size.toULong(), entryLength.toULong()), reference.size.toULong())
            val metadataStart = checkedAdd(checkedAdd(carrier.identity.size, added), 8uL)
            val byteLength = checkedAdd(metadataStart, xmp.size.toULong())
            if (byteLength > reader.context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "HEIF XMP insertion exceeds output budget", Stage.Plan)
            val base = if (baseWidth != 0) metadataStart else 0uL
            val entry = integer(itemId.toULong(), if (version == 2) 4 else 2) + (if (version == 0) byteArrayOf() else integer(0uL, 2)) +
                integer(0uL, 2) + integer(base, baseWidth) + integer(1uL, 2) + integer(0uL, indexWidth) +
                integer(metadataStart - base, offsetWidth) + integer(xmp.size.toULong(), lengthWidth)
            // Stable ordering when a table is the final meta child: its own entry precedes a new sibling iref.
            val insertions = listOf(HeifInsertion(info.range.endExclusive, Bytes(newInfo)), HeifInsertion(iloc.range.endExclusive, Bytes(entry)),
                HeifInsertion(iref?.range?.endExclusive ?: carrier.meta.range.endExclusive, Bytes(reference))).sortedBy { it.offset }
            val moves = mutableListOf<HeifMovedRange>()
            var cursor = 0uL
            var shift = 0uL
            for (insertion in insertions) {
                if (insertion.offset > cursor) moves += HeifMovedRange(ByteRange(cursor, insertion.offset - cursor), cursor + shift)
                cursor = insertion.offset; shift += insertion.bytes.size.toULong()
            }
            if (cursor < carrier.identity.size) moves += HeifMovedRange(ByteRange(cursor, carrier.identity.size - cursor), cursor + shift)
            val patches = graph.locations.relocation(reader, moves, byteLength).orThrow().map { FixedPatch(it.original, it.after) }.toMutableList()
            fun grow(box: BmffBox, growth: ULong) {
                val field = if (box.headerLength == 8uL) ByteRange(box.range.offset, 4uL) else ByteRange(box.range.offset + 8uL, 8uL)
                patches += FixedPatch(field, Bytes(integer(checkedAdd(box.range.length, growth), field.length.toInt())))
            }
            grow(carrier.meta, added); grow(info, newInfo.size.toULong()); grow(iloc, entry.size.toULong())
            if (iref != null) grow(iref, description.size.toULong())
            patches += FixedPatch(ByteRange(info.payload.offset + 4uL, if (infoVersion == 0) 2uL else 4uL), Bytes(integer(2uL, if (infoVersion == 0) 2 else 4)))
            patches += FixedPatch(ByteRange(iloc.payload.offset + 6uL, if (version == 2) 4uL else 2uL), Bytes(integer(2uL, if (version == 2) 4 else 2)))
            budget.retain(checkedMultiply((patches.size + insertions.size).toULong(), 128uL))
            FixedPatchSource.create(reader, patches).orThrow()
            reader.validateIdentity().orThrow()
            HeifXmpAppender(carrier, xmp, itemId, frozenList(insertions), frozenList(patches), metadataStart, byteLength)
        }
    }
}
