package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.exif.*
import livephoto.core.implementation.ExtentSource
import livephoto.core.xmp.*

internal data class HeifMetadataItem(val id: UInt, val describes: List<UInt>, val tiff: TiffDocument? = null,
    val xmp: XmpPacket? = null)
internal data class HeifMetadataFacts(val items: List<HeifMetadataItem>, val issues: List<Issue>)

/** Read-only logical metadata items. TIFF ranges refer to the borrowed extent view, not file offsets. */
internal object HeifMetadataReader {
    suspend fun read(reader: BinaryReader, graph: HeifItemGraph, budget: ParseBudget): CoreResult<HeifMetadataFacts> = attempt {
        if (reader.identity().orThrow() != graph.locations.identity) fail("SOURCE_CHANGED", "HEIF metadata graph belongs to another source")
        val items = mutableListOf<HeifMetadataItem>()
        val issues = mutableListOf<Issue>()
        suspend fun matches(range: ByteRange?, text: String): Boolean {
            if (range == null || range.length != text.length.toULong()) return false
            return reader.readBuffer(range.offset, text.length.toUInt()).orThrow() == Bytes(text.encodeToByteArray())
        }
        for (info in graph.infos) {
            budget.item()
            val exif = info.type == "Exif"
            val xmp = info.type == "mime" && matches(info.contentType, "application/rdf+xml")
            if (!exif && !xmp) continue
            val location = Location(source = graph.locations.identity.id, range = info.box.range, selector = "heif:item:${info.id}")
            val parsed = attempt {
                if (info.protection != 0u) fail("CAPABILITY_UNSUPPORTED", "Protected HEIF metadata requires an unavailable decryptor")
                if (xmp && info.contentEncoding?.length?.let { it != 0uL } == true)
                    fail("CAPABILITY_UNSUPPORTED", "Encoded HEIF XMP metadata is not implemented")
                val item = graph.locations.items.singleOrNull { it.id == info.id }
                    ?: fail("CAPABILITY_UNSUPPORTED", "HEIF metadata has no implemented item location")
                val source = ExtentSource.create(reader, item.extents.map { it.data }, budget).orThrow()
                val logical = BinaryReader(source, reader.context)
                val size = logical.identity().orThrow().size
                val targets = graph.references.filter { it.type == "cdsc" && it.from == info.id }.flatMap { it.to }
                budget.retain(checkedMultiply(targets.size.toULong(), 16uL))
                if (exif) {
                    if (size < 12uL) fail("CORRUPTED_CONTAINER", "HEIF Exif TIFF header is truncated")
                    // ISO HEIF: big-endian offset relative to the byte after this four-byte field.
                    val start = checkedAdd(4uL, logical.readU32(0uL, Endian.Big).orThrow().toULong())
                    if (start > size || size - start < 8uL) fail("OFFSET_OUT_OF_BOUNDS", "HEIF Exif TIFF offset exceeds its logical item")
                    val tiff = TiffReader(logical, budget).read(ByteRange(start, size - start)).orThrow()
                    HeifMetadataItem(info.id, frozenList(targets), tiff = tiff)
                } else {
                    budget.retain(size)
                    val packet = XmpReader.parseReserved(logical.readExactly(0uL, checkedInt(size).toUInt()).orThrow(), reader.context, budget).orThrow()
                    HeifMetadataItem(info.id, frozenList(targets), xmp = packet)
                }
            }
            when (parsed) {
                is CoreResult.Success -> { budget.retain(96uL); items += parsed.value }
                is CoreResult.Failure -> {
                    if (parsed.error.code.value in setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "UNEXPECTED_EOF", "RESOURCE_LIMIT_EXCEEDED")) throw CoreFault(parsed.error)
                    budget.retain(128uL)
                    issues += Issue(parsed.error.code, if (parsed.error.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER")) Severity.Warning else Severity.Error,
                        Layer.Structure, location)
                }
            }
        }
        reader.validateIdentity().orThrow()
        HeifMetadataFacts(frozenList(items), frozenList(issues))
    }
}
