package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.exif.*
import livephoto.core.heif.*
import livephoto.core.implementation.*

/** No item/index relocation: only a proven, disjoint CID-only MakerNote entry/value is retired. */
internal object AppleHeifClean {
    private data class Image(val graph: HeifItemGraph, val tiff: TiffDocument, val extent: ByteRange, val logical: BinaryReader)
    private suspend fun image(reader: BinaryReader, budget: ParseBudget): Image {
        val roots = BmffReader(reader, budget).readBoxes(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
        if (roots.any { it.extendsToParentEnd || it.type !in setOf("ftyp", "meta", "mdat") }) unsafe("Unknown HEIF root dependency")
        val ftyp = roots.singleOrNull { it.type == "ftyp" } ?: unsafe("One HEIF ftyp is required")
        val brands = BmffReader(reader, budget).readFileType(ftyp).orThrow()
        if ((brands.compatibleBrands + brands.majorBrand).none { it in setOf("heic", "heix") }) unsafe("Known HEIC brands are required")
        val graph = HeifItemGraphReader.read(reader, roots, budget).orThrow()
        val exif = graph.infos.singleOrNull { it.type == "Exif" } ?: unsafe("One Exif item is required")
        if (graph.infos.size != 2 || graph.locations.items.size != 2 || graph.infos.any { it.hidden || it.protection != 0u } ||
            graph.unknownMeta.isNotEmpty() || graph.unknownPropertyContainers.isNotEmpty() ||
            graph.references.size != 1 || graph.references.single().let { it.type != "cdsc" || it.from != exif.id || it.to != listOf(graph.primary) })
            unsafe("HEIF cleanup requires one uniquely owned primary/Exif graph")
        HeifCodedCarrier.classifiedPrimary(reader, graph, budget, Stage.Plan).orThrow()
        val extent = graph.locations.items.single { it.id == exif.id }.extents.singleOrNull()?.data ?: unsafe("Exif must be physically contiguous")
        val facts = HeifMetadataReader.read(reader, graph, budget).orThrow()
        if (facts.issues.isNotEmpty() || facts.items.size != 1) unsafe("All Exif dependencies must be parsed")
        val tiff = facts.items.single().tiff ?: unsafe("Exif needs a bounded TIFF")
        val logical = BinaryReader(ExtentSource.create(reader, listOf(extent), budget).orThrow(), reader.context)
        return Image(graph, tiff, extent, logical)
    }

    suspend fun prepare(session: SourceSession, budget: ParseBudget): CoreResult<BinarySource> = attempt {
        val reader = session.applePair?.imageReader ?: unsafe("A complete Apple HEIF pair is required")
        val image = image(reader, budget)
        val id = AppleImageReader.readDocuments(image.logical, listOf(image.tiff), budget).orThrow() ?: unsafe("Formal Apple CID is absent")
        val note = id.makerNote
        val end = if (id.range.offset == note.offset + 32uL && image.logical.readU32(note.offset + 28uL).orThrow() == 0u) 32uL else 28uL
        if (image.logical.readU16(note.offset + 14uL).orThrow() != 1.toUShort() || id.range.offset != note.offset + end || id.range.endExclusive != note.endExclusive)
            unsafe("Only a contiguous CID-only Apple MakerNote can be retired")
        if (overlap(note, ByteRange(image.tiff.range.offset, 8uL))) unsafe("MakerNote aliases TIFF header")
        for (ifd in image.tiff.ifds) {
            budget.item()
            if (overlap(note, ByteRange(image.tiff.range.offset + ifd.relativeOffset.toULong(), 6uL + ifd.entries.size.toULong() * 12uL))) unsafe("MakerNote aliases IFD table")
            for (entry in ifd.entries) {
                budget.item()
                val range = entry.valueRange ?: unsafe("Unknown TIFF value bounds")
                if (range != note && overlap(note, range) || range == note && !entry.isOpaqueMakerNote) unsafe("MakerNote aliases ordinary Exif")
            }
        }
        val physical = ByteRange(checkedAdd(image.extent.offset, note.offset), note.length)
        checkedRange(note.offset, note.length, image.extent.length)
        if (image.graph.locations.items.filter { it.id == image.graph.primary }.flatMap { it.extents }.any { overlap(physical, it.data) })
            unsafe("MakerNote aliases coded primary item")
        val fixed = FixedPatchSource.create(reader, listOf(FixedPatch(ByteRange(physical.offset + 14uL, physical.length - 14uL)))).orThrow()
        val patched = BinaryReader(fixed, reader.context)
        validateRetired(patched, budget).orThrow()
        val staged = SourceSession.open(SourceSet.Single(fixed), reader.context, budget).orThrow()
        if (staged.bindings.isNotEmpty()) fail("POSTCONDITION_FAILED", "Clean HEIF retains a live binding", Stage.Verify)
        // Complete item bytes/configuration and all container/Exif bytes outside the one patch remain in place.
        for (extent in image.graph.locations.items.single { it.id == image.graph.primary }.extents) {
            if (sha256Range(reader, extent.data).orThrow() != sha256Range(patched, extent.data).orThrow())
                fail("POSTCONDITION_FAILED", "HEIF cleanup changed primary coding", Stage.Verify)
        }
        session.recheck()
        fixed
    }

    /** A narrow already-clean profile for SPL-01; unknown or merely absent CID is not sufficient. */
    suspend fun validateRetired(reader: BinaryReader, budget: ParseBudget): CoreResult<Unit> = attempt {
        val image = image(reader, budget)
        val root = image.tiff.ifds.single { it.relativeOffset == image.tiff.firstIfdOffset }
        val pointers = root.entries.filter { it.tag == 0x8769u.toUShort() }
        val pointer = pointers.singleOrNull()?.takeIf { it.type == 4u.toUShort() && it.count == 1u }?.value
            ?: unsafe("Formal ExifIFD pointer is required")
        val offset = readUnsigned(pointer, image.tiff.endian).toUInt()
        val notes = image.tiff.ifds.flatMap { ifd -> ifd.entries.filter { it.isOpaqueMakerNote }.map { ifd to it } }
        val (ifd, entry) = notes.singleOrNull() ?: unsafe("One retired Apple MakerNote is required")
        if (ifd.relativeOffset != offset || entry.type != 7u.toUShort()) unsafe("Retired MakerNote has no formal owner")
        val bytes = entry.value ?: unsafe("Retired MakerNote is not bounded")
        val header = "Apple iOS\u0000".encodeToByteArray() + byteArrayOf(0, 1, 'M'.code.toByte(), 'M'.code.toByte())
        if (bytes.size < 28 || !header.indices.all { bytes[it] == header[it] } || (14 until bytes.size).any { bytes[it] != 0.toByte() })
            unsafe("MakerNote is not a completely retired CID-only envelope")
        if (AppleImageReader.readDocuments(image.logical, listOf(image.tiff), budget).orThrow() != null) unsafe("Retired note retains CID")
        reader.validateIdentity().orThrow()
    }

    private fun overlap(a: ByteRange, b: ByteRange) = a.offset < b.endExclusive && b.offset < a.endExclusive
    private fun unsafe(message: String): Nothing = fail("UNSAFE_METADATA_REWRITE", message, Stage.Plan)
}
