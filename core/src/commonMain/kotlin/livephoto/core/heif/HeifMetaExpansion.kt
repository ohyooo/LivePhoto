package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.FixedPatch
import livephoto.core.implementation.FixedPatchSource

/** Internal relocation proof for adding an explicitly owned free box, not a public HEIC writer. */
internal class HeifMetaExpansion private constructor(
    private val identity: SourceIdentity, private val graph: HeifItemGraph, private val image: HeifCodedItemFacts,
    private val insertion: ULong, private val padding: UInt, private val patches: List<FixedPatch>, val byteLength: ULong,
) {
    private val added: ULong get() = padding.toULong() + 8uL
    private fun destination(offset: ULong): ULong = if (offset < insertion) offset else checkedAdd(offset, added)
    private fun header(): Bytes = Bytes(unsignedBytes(added, 4, Endian.Big).toByteArray() + "free".encodeToByteArray())

    suspend fun write(reader: BinaryReader, writer: BinaryWriter): CoreResult<Unit> = attempt {
        if (reader.identity().orThrow() != identity) fail("SOURCE_CHANGED", "HEIF expansion belongs to a different source", Stage.WriteProtocol)
        writer.budget.checkCapacity(byteLength)
        val view = BinaryReader(FixedPatchSource.create(reader, patches).orThrow(), reader.context)
        copyRange(view, writer, ByteRange(0uL, insertion), reader.context).orThrow()
        writer.writeAll(header()).orThrow()
        val zero = Bytes(ByteArray(minOf(padding.toULong(), 65_536uL).toInt()))
        var remaining = padding.toULong()
        while (remaining != 0uL) {
            val count = minOf(remaining, zero.size.toULong()).toInt()
            writer.writeAll(zero.slice(0, count)).orThrow(); remaining -= count.toULong()
        }
        copyRange(view, writer, ByteRange(insertion, identity.size - insertion), reader.context).orThrow()
        reader.validateIdentity().orThrow()
    }

    suspend fun verify(original: BinaryReader, output: BinaryReader): CoreResult<Unit> = attempt<Unit> {
        if (original.identity().orThrow() != identity) fail("SOURCE_CHANGED", "HEIF expansion verification source changed", Stage.Verify)
        if (output.identity().orThrow().size != byteLength) fail("POSTCONDITION_FAILED", "HEIF expanded output length is wrong", Stage.Verify)
        val budget = ParseBudget(output.context)
        val parser = BmffReader(output, budget)
        val roots = parser.readBoxes(ByteRange(0uL, byteLength)).orThrow()
        val after = HeifItemGraphReader.read(output, roots, budget).orThrow()
        val coded = HeifCodedItemProbe.primary(output, after, budget).orThrow()
        if (after.primary != graph.primary || after.infos.map { Triple(it.id, it.type, it.protection) } != graph.infos.map { Triple(it.id, it.type, it.protection) } ||
            after.associations != graph.associations || after.references.isNotEmpty() || after.properties.map { it.type } != graph.properties.map { it.type } ||
            coded.declaredWidth != image.declaredWidth || coded.declaredHeight != image.declaredHeight || coded.configurationDigest != image.configurationDigest ||
            coded.nalWidth != image.nalWidth || coded.nalUnits != image.nalUnits || coded.nalTypes != image.nalTypes) fail("POSTCONDITION_FAILED", "HEIF expansion changed its retained graph/configuration", Stage.Verify)
        for ((beforeItem, afterItem) in graph.locations.items.zip(after.locations.items)) {
            if (beforeItem.id != afterItem.id || beforeItem.construction != afterItem.construction || beforeItem.extents.size != afterItem.extents.size)
                fail("POSTCONDITION_FAILED", "HEIF expansion changed item construction", Stage.Verify)
            for ((before, current) in beforeItem.extents.zip(afterItem.extents))
                if (current.data != ByteRange(destination(before.data.offset), before.data.length) || current.index.value != before.index.value)
                    fail("POSTCONDITION_FAILED", "HEIF expansion failed to relocate a retained extent", Stage.Verify)
        }
        if (after.locations.items.size != graph.locations.items.size) fail("POSTCONDITION_FAILED", "HEIF expansion changed its item count", Stage.Verify)
        val owned = after.unknownMeta.singleOrNull { it.range == ByteRange(insertion, added) && it.type == "free" }
            ?: fail("POSTCONDITION_FAILED", "HEIF expansion has no exact inserted padding box", Stage.Verify)
        if (after.unknownMeta.filter { it.range != owned.range }.map { it.type } != graph.unknownMeta.map { it.type })
            fail("POSTCONDITION_FAILED", "HEIF expansion changed unrelated meta children", Stage.Verify)
        if (output.readBuffer(insertion, 8u).orThrow() != header()) fail("POSTCONDITION_FAILED", "HEIF padding header is wrong", Stage.Verify)
        var zeroPosition = owned.payload.offset
        while (zeroPosition < owned.payload.endExclusive) {
            val count = minOf(65_536uL, owned.payload.endExclusive - zeroPosition).toUInt()
            val bytes = output.readBuffer(zeroPosition, count).orThrow()
            if ((0 until bytes.size).any { bytes[it] != 0.toByte() }) fail("POSTCONDITION_FAILED", "HEIF inserted padding contains unexpected data", Stage.Verify)
            zeroPosition += count.toULong()
        }
        suspend fun unchanged(start: ULong, end: ULong) {
            if (start == end) return
            budget.item()
            if (start < insertion && end > insertion) { unchanged(start, insertion); unchanged(insertion, end); return }
            val range = ByteRange(start, end - start)
            if (sha256Range(original, range).orThrow() != sha256Range(output, ByteRange(destination(start), range.length)).orThrow())
                fail("POSTCONDITION_FAILED", "HEIF expansion changed unrequested source bytes", Stage.Verify)
        }
        var cursor = 0uL
        for (patch in patches.sortedBy { it.range.offset }) {
            unchanged(cursor, patch.range.offset)
            if (output.readBuffer(destination(patch.range.offset), patch.range.length.toUInt()).orThrow() != patch.value)
                fail("POSTCONDITION_FAILED", "HEIF relocated field does not match its plan", Stage.Verify)
            cursor = patch.range.endExclusive
        }
        unchanged(cursor, identity.size)
        original.validateIdentity().orThrow(); output.validateIdentity().orThrow()
    }

    companion object {
        suspend fun prepare(reader: BinaryReader, padding: UInt, budget: ParseBudget = ParseBudget(reader.context)): CoreResult<HeifMetaExpansion> = attempt {
            val identity = reader.identity().orThrow()
            val parser = BmffReader(reader, budget)
            val roots = parser.readBoxes(ByteRange(0uL, identity.size)).orThrow()
            if (roots.any { it.extendsToParentEnd || it.type !in setOf("ftyp", "meta", "mdat", "free") })
                fail("CAPABILITY_UNSUPPORTED", "HEIF expansion refuses unknown root dependencies or implicit box sizes", Stage.Plan)
            val ftyp = roots.singleOrNull { it.type == "ftyp" } ?: fail("CORRUPTED_CONTAINER", "HEIF expansion requires one ftyp")
            val brands = parser.readFileType(ftyp).orThrow().let { it.compatibleBrands + it.majorBrand }
            if (brands.none { it in setOf("heic", "heix") }) fail("CAPABILITY_UNSUPPORTED", "HEIF expansion only implements known HEIC brands", Stage.Plan)
            val graph = HeifItemGraphReader.read(reader, roots, budget).orThrow()
            if (graph.infos.size != 1 || graph.infos.single().hidden || graph.infos.single().protection != 0u || graph.locations.items.size != 1 ||
                graph.references.isNotEmpty() || graph.unknownMeta.any { it.type != "free" } || graph.unknownPropertyContainers.isNotEmpty() ||
                graph.properties.any { it.type !in setOf("ispe", "hvcC") })
                fail("UNSAFE_METADATA_REWRITE", "HEIF expansion requires a completely classified single coded item profile", Stage.Plan)
            val image = HeifCodedItemProbe.primary(reader, graph, budget).orThrow()
            if (image.nalTypes.any { it !in 0..9 && it !in 16..21 && it !in 32..34 })
                fail("UNSAFE_METADATA_REWRITE", "HEIF expansion cannot authorize unknown/reserved/SEI metadata NAL units", Stage.Plan)
            budget.retain(image.configuration.length)
            hevcConfig(reader.readExactly(image.configuration.offset, checkedInt(image.configuration.length).toUInt()).orThrow(), budget,
                allowedArrayTypes = setOf(32, 33, 34))
            val meta = roots.single { it.type == "meta" }
            val added = checkedAdd(padding.toULong(), 8uL)
            if (added > UInt.MAX_VALUE.toULong()) fail("VALUE_NOT_REPRESENTABLE", "HEIF padding requires a wider box header", Stage.Plan)
            val byteLength = checkedAdd(identity.size, added)
            if (byteLength > reader.context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Expanded HEIF exceeds output budget", Stage.Plan)
            val newMetaLength = checkedAdd(meta.range.length, added)
            val sizeField = if (meta.headerLength == 8uL) ByteRange(meta.range.offset, 4uL) else ByteRange(meta.range.offset + 8uL, 8uL)
            if (sizeField.length == 4uL && newMetaLength > UInt.MAX_VALUE.toULong()) fail("VALUE_NOT_REPRESENTABLE", "HEIF meta requires a wider box header", Stage.Plan)
            val insertion = meta.range.endExclusive
            val moves = listOf(HeifMovedRange(ByteRange(0uL, insertion), 0uL)) +
                if (insertion < identity.size) listOf(HeifMovedRange(ByteRange(insertion, identity.size - insertion), insertion + added)) else emptyList()
            val offsets = graph.locations.relocation(reader, moves, byteLength).orThrow()
            val patches = offsets.map { FixedPatch(it.original, it.after) } + FixedPatch(sizeField, unsignedBytes(newMetaLength, sizeField.length.toInt(), Endian.Big))
            budget.retain(checkedMultiply(patches.size.toULong(), 128uL))
            // Reuse fixed-patch overlap/length/bounds checks before authorizing a streaming write.
            FixedPatchSource.create(reader, patches).orThrow()
            reader.validateIdentity().orThrow()
            HeifMetaExpansion(identity, graph, image, insertion, padding, frozenList(patches), byteLength)
        }
    }
}
