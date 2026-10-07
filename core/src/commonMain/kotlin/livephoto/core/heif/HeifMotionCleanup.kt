package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.implementation.*

/** Removes only a whole canonical owned motion XMP item, its cdsc child, isolated mdat, and mpvd. */
internal class HeifMotionCleanup private constructor(private val identity: SourceIdentity, private val graph: HeifItemGraph,
    private val image: HeifCodedItemFacts, private val moves: List<HeifMovedRange>, private val patches: List<FixedPatch>, val byteLength: ULong) {
    private fun mapped(range: ByteRange): ByteRange {
        val move = moves.singleOrNull { range.offset >= it.original.offset && range.endExclusive <= it.original.endExclusive }
            ?: fail("POSTCONDITION_FAILED", "HEIF retained range has no complete cleanup mapping", Stage.Verify)
        return ByteRange(move.destination + range.offset - move.original.offset, range.length)
    }
    suspend fun view(reader: BinaryReader, budget: ParseBudget): CoreResult<BinarySource> = attempt {
        if (reader.identity().orThrow() != identity) fail("SOURCE_CHANGED", "HEIF cleanup view belongs to another source", Stage.Plan)
        val patched = BinaryReader(FixedPatchSource.create(reader, patches).orThrow(), reader.context)
        val source = ExtentSource.create(patched, moves.map { it.original }, budget).orThrow()
        verify(reader, BinaryReader(source, reader.context)).orThrow()
        source // Borrowed, immutable view; no intermediate output transaction or public asset.
    }
    suspend fun write(reader: BinaryReader, writer: BinaryWriter): CoreResult<Unit> = attempt {
        if (reader.identity().orThrow() != identity) fail("SOURCE_CHANGED", "HEIF cleanup belongs to another source", Stage.WriteProtocol)
        writer.budget.checkCapacity(byteLength)
        val view = BinaryReader(FixedPatchSource.create(reader, patches).orThrow(), reader.context)
        for (move in moves) copyRange(view, writer, move.original, reader.context).orThrow()
        reader.validateIdentity().orThrow()
    }
    suspend fun verify(original: BinaryReader, output: BinaryReader): CoreResult<Unit> = attempt<Unit> {
        if (original.identity().orThrow() != identity) fail("SOURCE_CHANGED", "HEIF cleanup source changed", Stage.Verify)
        if (output.identity().orThrow().size != byteLength) fail("POSTCONDITION_FAILED", "HEIF cleanup length is wrong", Stage.Verify)
        val after = HeifCodedCarrier.read(output, ParseBudget(output.context), Stage.Validate).orThrow()
        if (after.graph.primary != graph.primary || after.graph.associations != graph.associations || after.graph.properties.map { it.type } != graph.properties.map { it.type } ||
            after.image.declaredWidth != image.declaredWidth || after.image.declaredHeight != image.declaredHeight ||
            after.image.configurationDigest != image.configurationDigest || after.image.nalWidth != image.nalWidth ||
            after.image.nalUnits != image.nalUnits || after.image.nalTypes != image.nalTypes)
            fail("POSTCONDITION_FAILED", "HEIF cleanup changed retained graph/coding", Stage.Verify)
        val beforeItem = graph.locations.items.single { it.id == graph.primary }
        val afterItem = after.graph.locations.items.single()
        if (beforeItem.construction != afterItem.construction || beforeItem.extents.size != afterItem.extents.size)
            fail("POSTCONDITION_FAILED", "HEIF cleanup changed retained item construction", Stage.Verify)
        for ((before, current) in beforeItem.extents.zip(afterItem.extents)) if (current.data != mapped(before.data) || current.index.value != before.index.value)
            fail("POSTCONDITION_FAILED", "HEIF cleanup extent relocation is wrong", Stage.Verify)
        for (move in moves) {
            var cursor = move.original.offset
            for (patch in patches.filter { it.range.offset >= move.original.offset && it.range.endExclusive <= move.original.endExclusive }.sortedBy { it.range.offset }) {
                if (cursor < patch.range.offset) {
                    val range = ByteRange(cursor, patch.range.offset - cursor)
                    if (sha256Range(original, range).orThrow() != sha256Range(output, mapped(range)).orThrow())
                        fail("POSTCONDITION_FAILED", "HEIF cleanup changed unrequested bytes", Stage.Verify)
                }
                val value = patch.value ?: fail("POSTCONDITION_FAILED", "HEIF cleanup has no patch bytes", Stage.Verify)
                if (output.readBuffer(mapped(patch.range).offset, value.size.toUInt()).orThrow() != value)
                    fail("POSTCONDITION_FAILED", "HEIF cleanup table patch differs", Stage.Verify)
                cursor = patch.range.endExclusive
            }
            if (cursor < move.original.endExclusive) {
                val range = ByteRange(cursor, move.original.endExclusive - cursor)
                if (sha256Range(original, range).orThrow() != sha256Range(output, mapped(range)).orThrow())
                    fail("POSTCONDITION_FAILED", "HEIF cleanup changed retained bytes", Stage.Verify)
            }
        }
        original.validateIdentity().orThrow(); output.validateIdentity().orThrow()
    }
    companion object {
        private suspend fun classifyMovie(reader: BinaryReader, range: ByteRange, budget: ParseBudget) {
            val parser = BmffReader(reader, budget)
            val roots = parser.readBoxes(range).orThrow()
            if (roots.any { it.type !in setOf("ftyp", "moov", "mdat", "free", "skip", "wide") })
                fail("UNSAFE_METADATA_REWRITE", "Clean movie has unclassified root metadata", Stage.Plan)
            val schemas = mapOf("moov" to setOf("mvhd", "trak", "free", "udta"), "trak" to setOf("tkhd", "edts", "mdia"),
                "edts" to setOf("elst"), "mdia" to setOf("mdhd", "hdlr", "minf"), "minf" to setOf("vmhd", "smhd", "dinf", "stbl"),
                "dinf" to setOf("dref"), "stbl" to setOf("stsd", "stts", "ctts", "stsc", "stsz", "stco", "co64", "stss", "sdtp", "sgpd", "sbgp"))
            suspend fun visit(box: BmffBox, depth: UInt) {
                val allowed = schemas[box.type] ?: return
                val children = parser.readBoxes(box.payload, depth).orThrow()
                if (children.any { it.type !in allowed } || children.filter { it.type == "udta" }.any { !EmptyMovieMetadata.matches(reader, parser, it, depth) })
                    fail("UNSAFE_METADATA_REWRITE", "Clean movie cannot retain unclassified movie/track or nonempty user metadata", Stage.Plan)
                for (child in children) visit(child, depth + 1u)
            }
            visit(roots.single { it.type == "moov" }, 1u)
        }
        suspend fun prepare(session: SourceSession, budget: ParseBudget): CoreResult<HeifMotionCleanup> = attempt {
            val reader = session.reader
            val identity = reader.identity().orThrow()
            val graph = session.heifItems ?: fail("CAPABILITY_UNSUPPORTED", "HEIF cleanup requires implemented item tables", Stage.Plan)
            val binding = session.bindings.singleOrNull { it.selector == ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic")) }
                ?: fail("CAPABILITY_UNSUPPORTED", "HEIF cleanup requires one Google V2 heic authority", Stage.Plan)
            if (!binding.structurallyValid || binding.protocol !in session.videos || binding.issues.any { it.layer == Layer.Protocol } ||
                graph.infos.size != 2 || graph.locations.items.size != 2 || graph.unknownMeta.any { it.type != "free" } ||
                graph.associations.keys != setOf(graph.primary)) fail("UNSAFE_METADATA_REWRITE", "HEIF cleanup graph or protocol is not completely classified", Stage.Plan)
            val image = HeifCodedCarrier.classifiedPrimary(reader, graph, budget, Stage.Plan).orThrow()
            classifyMovie(reader, binding.video!!, budget)
            val metadata = graph.infos.single { it.id != graph.primary }
            if (metadata.type != "mime" || !metadata.hidden || metadata.protection != 0u || metadata.contentEncoding?.length?.let { it != 0uL } == true ||
                metadata.name.length != 3uL || reader.readBuffer(metadata.name.offset, 3u).orThrow() != Bytes("XMP".encodeToByteArray()) ||
                graph.references.size != 1 || graph.references.single().type != "cdsc" || graph.references.single().from != metadata.id || graph.references.single().to != listOf(graph.primary))
                fail("UNSAFE_METADATA_REWRITE", "HEIF cleanup cannot delete mixed or unclassified metadata", Stage.Plan)
            val item = graph.locations.items.single { it.id == metadata.id }
            if (item.construction != 0u || item.extents.size != 1) fail("CAPABILITY_UNSUPPORTED", "HEIF cleanup requires one isolated owned XMP extent", Stage.Plan)
            val extent = item.extents.single().data
            val timestamp = binding.key.position?.let(::microseconds) ?: -1L
            val canonical = GoogleDirectoryWriter.heic(binding.video.length, timestamp, reader.context)
            if (extent.length != canonical.size.toULong() || sha256Range(reader, extent).orThrow() != Sha256().also { it.update(canonical) }.finish())
                fail("UNSAFE_METADATA_REWRITE", "HEIF cleanup only owns the complete canonical motion packet; ordinary XMP must remain", Stage.Plan)
            val parser = BmffReader(reader, budget)
            val roots = parser.readBoxes(ByteRange(0uL, identity.size)).orThrow()
            if (roots.any { it.extendsToParentEnd || it.type !in setOf("ftyp", "meta", "mdat", "mpvd", "free") })
                fail("CAPABILITY_UNSUPPORTED", "HEIF cleanup refuses unknown root dependencies", Stage.Plan)
            val ftyp = roots.singleOrNull { it.type == "ftyp" } ?: fail("CORRUPTED_CONTAINER", "HEIF cleanup requires one ftyp", Stage.Plan)
            val brands = parser.readFileType(ftyp).orThrow().let { it.compatibleBrands + it.majorBrand }
            if (brands.none { it in setOf("heic", "heix") }) fail("CAPABILITY_UNSUPPORTED", "HEIF cleanup only implements known HEIC brands", Stage.Plan)
            val mpvd = roots.single { it.type == "mpvd" }
            if (mpvd.headerLength != 8uL || mpvd.range.endExclusive != identity.size || mpvd.payload != binding.video)
                fail("UNSAFE_METADATA_REWRITE", "HEIF cleanup has no complete standard owned mpvd", Stage.Plan)
            val mdat = roots.singleOrNull { it.type == "mdat" && it.payload == extent }
                ?: fail("CAPABILITY_UNSUPPORTED", "Shared or partially owned HEIF metadata mdat cannot be deleted", Stage.Plan)
            val meta = roots.single { it.type == "meta" }
            val children = parser.readBoxes(ByteRange(meta.payload.offset + 4uL, meta.payload.length - 4uL), 1u).orThrow()
            if (children.any { it.extendsToParentEnd } || graph.infos.any { it.box.extendsToParentEnd })
                fail("CAPABILITY_UNSUPPORTED", "Implicit HEIF table sizes cannot authorize cleanup", Stage.Plan)
            val info = children.single { it.type == "iinf" }
            val iloc = children.single { it.type == "iloc" }
            val iref = children.single { it.type == "iref" }
            val infoVersion = reader.readBuffer(info.payload.offset, 1u).orThrow()[0].toInt() and 0xff
            val version = reader.readBuffer(iloc.payload.offset, 1u).orThrow()[0].toInt() and 0xff
            val prefix = (if (version == 2) 4uL else 2uL) + (if (version == 0) 0uL else 2uL) + 2uL
            if (item.base.field.offset < prefix) fail("CORRUPTED_CONTAINER", "HEIF item entry underflows its prefix", Stage.Plan)
            val start = item.base.field.offset - prefix
            val entry = ByteRange(start, item.extents.last().length.field.endExclusive - start)
            if (entry.offset < iloc.payload.offset + 6uL + (if (version == 2) 4uL else 2uL) || entry.endExclusive > iloc.payload.endExclusive ||
                readUnsigned(reader.readBuffer(start, if (version == 2) 4u else 2u).orThrow(), Endian.Big) != metadata.id.toULong())
                fail("CORRUPTED_CONTAINER", "HEIF cleanup entry is not the declared owned item", Stage.Plan)
            val reference = graph.references.single().box.range
            val deletions = listOf(metadata.box.range, entry, reference, mdat.range, mpvd.range).sortedBy { it.offset }
            val moves = mutableListOf<HeifMovedRange>()
            var cursor = 0uL; var destination = 0uL
            for (range in deletions) {
                if (range.offset < cursor) fail("AMBIGUOUS_LAYOUT", "HEIF owned cleanup ranges overlap", Stage.Plan)
                if (range.offset > cursor) {
                    val retained = ByteRange(cursor, range.offset - cursor)
                    moves += HeifMovedRange(retained, destination); destination += retained.length
                }
                cursor = range.endExclusive
            }
            if (cursor < identity.size) { moves += HeifMovedRange(ByteRange(cursor, identity.size - cursor), destination); destination += identity.size - cursor }
            if (destination > reader.context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Clean HEIF exceeds output budget", Stage.Plan)
            val patches = graph.locations.relocation(reader, moves, destination, setOf(graph.primary)).orThrow().map { FixedPatch(it.original, it.after) }.toMutableList()
            fun shrink(box: BmffBox, removed: ULong) {
                if (removed > box.payload.length) fail("CORRUPTED_CONTAINER", "HEIF cleanup exceeds its table", Stage.Plan)
                val field = if (box.headerLength == 8uL) ByteRange(box.range.offset, 4uL) else ByteRange(box.range.offset + 8uL, 8uL)
                patches += FixedPatch(field, unsignedBytes(box.range.length - removed, field.length.toInt(), Endian.Big))
            }
            shrink(meta, metadata.box.range.length + entry.length + reference.length); shrink(info, metadata.box.range.length); shrink(iloc, entry.length); shrink(iref, reference.length)
            patches += FixedPatch(ByteRange(info.payload.offset + 4uL, if (infoVersion == 0) 2uL else 4uL), unsignedBytes(1uL, if (infoVersion == 0) 2 else 4, Endian.Big))
            patches += FixedPatch(ByteRange(iloc.payload.offset + 6uL, if (version == 2) 4uL else 2uL), unsignedBytes(1uL, if (version == 2) 4 else 2, Endian.Big))
            budget.retain(checkedMultiply((moves.size + patches.size).toULong(), 128uL))
            FixedPatchSource.create(reader, patches).orThrow(); reader.validateIdentity().orThrow()
            HeifMotionCleanup(identity, graph, image, frozenList(moves), frozenList(patches), destination)
        }
    }
}
