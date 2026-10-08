package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.heif.*
import livephoto.core.implementation.ExtentSource

internal data class AppleHeifImage(val identifier: AppleImageIdentifier, val graph: HeifItemGraph, val media: MediaFacts)

/** Finite read-only profile: direct hvc1 primary and one uniquely associated, contiguous Exif item. */
internal object AppleHeifImageReader {
    suspend fun read(reader: BinaryReader, budget: ParseBudget): CoreResult<AppleHeifImage?> = attempt {
        val roots = BmffReader(reader, budget).readBoxes(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
        if (roots.none { it.type == "meta" }) return@attempt null
        val graph = HeifItemGraphReader.read(reader, roots, budget).orThrow()
        val exifInfos = graph.infos.filter { it.type == "Exif" }
        if (exifInfos.isEmpty()) return@attempt null
        val metadata = HeifMetadataReader.read(reader, graph, budget).orThrow()
        val found = mutableListOf<Pair<HeifMetadataItem, AppleImageIdentifier>>()
        for (item in metadata.items.filter { it.tiff != null }) {
            val location = graph.locations.items.single { it.id == item.id }
            val view = ExtentSource.create(reader, location.extents.map { it.data }, budget).orThrow()
            val id = AppleImageReader.readDocuments(BinaryReader(view, reader.context), listOf(item.tiff!!), budget).orThrow()
            if (id != null) found += item to id
        }
        if (found.isEmpty()) return@attempt null
        if (found.size != 1 || exifInfos.size != 1) fail("CONFLICTING_METADATA", "HEIF Apple image has multiple Exif authorities", Stage.Inspect)
        val (item, id) = found.single()
        if (metadata.issues.isNotEmpty() || item.describes != listOf(graph.primary) || graph.infos.size != 2 ||
            graph.unknownMeta.isNotEmpty() || graph.unknownPropertyContainers.isNotEmpty() ||
            graph.properties.any { it.type !in setOf("ispe", "hvcC") } ||
            graph.references.any { it.type != "cdsc" || it.from != item.id || it.to != listOf(graph.primary) } ||
            roots.any { it.type !in setOf("ftyp", "meta", "mdat") })
            fail("CAPABILITY_UNSUPPORTED", "HEIF Apple CID needs a uniquely owned finite primary/Exif graph", Stage.Inspect)
        val location = graph.locations.items.single { it.id == item.id }
        val extent = location.extents.singleOrNull()?.data
            ?: fail("CAPABILITY_UNSUPPORTED", "HEIF Apple CID physical locations require a contiguous Exif item", Stage.Inspect)
        checkedRange(id.range.offset, id.range.length, extent.length)
        checkedRange(id.makerNote.offset, id.makerNote.length, extent.length)
        val primary = HeifCodedItemProbe.primary(reader, graph, budget).orThrow()
        val physical = id.copy(range = ByteRange(checkedAdd(extent.offset, id.range.offset), id.range.length),
            makerNote = ByteRange(checkedAdd(extent.offset, id.makerNote.offset), id.makerNote.length))
        reader.validateIdentity().orThrow()
        AppleHeifImage(physical, graph, MediaFacts(imageFormat = ImageFormat.Heic, mime = "image/heic",
            width = primary.declaredWidth, height = primary.declaredHeight, coverage = Coverage.Partial))
    }
}
