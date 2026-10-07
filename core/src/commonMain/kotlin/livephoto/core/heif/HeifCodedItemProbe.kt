package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.ExtentSource

internal data class HeifCodedItemFacts(val item: UInt, val declaredWidth: UInt, val declaredHeight: UInt,
    val configuration: ByteRange, val configurationDigest: Digest, val nalWidth: Int, val nalUnits: ULong, val nalTypes: Set<Int>)

/** Finite hvc1 framing/property checks. Dimensions are declarations, not verified decoded dimensions. */
internal object HeifCodedItemProbe {
    suspend fun primary(reader: BinaryReader, graph: HeifItemGraph, budget: ParseBudget): CoreResult<HeifCodedItemFacts> = attempt {
        if (reader.identity().orThrow() != graph.locations.identity) fail("SOURCE_CHANGED", "HEIF graph belongs to a different source")
        val info = graph.infos.singleOrNull { it.id == graph.primary } ?: fail("CORRUPTED_CONTAINER", "HEIF primary item information is ambiguous or missing")
        if (info.type != "hvc1" || info.protection != 0u || graph.references.any { it.from == info.id && it.type == "dimg" })
            fail("CAPABILITY_UNSUPPORTED", "Only unprotected directly coded HEIF hvc1 primary items have implemented framing checks")
        val associations = graph.associations[info.id] ?: fail("CORRUPTED_CONTAINER", "HEIF primary has no property associations")
        val properties = associations.filter { it.property != 0u }.map { association ->
            if (association.property > graph.properties.size.toUInt()) fail("CORRUPTED_CONTAINER", "HEIF property association is out of bounds")
            graph.properties[association.property.toInt() - 1] to association
        }
        if (properties.any { (property, association) -> association.essential && property.type !in setOf("ispe", "hvcC") })
            fail("CAPABILITY_UNSUPPORTED", "HEIF primary essential display/codec properties are not all interpreted")
        val dimensions = properties.singleOrNull { it.first.type == "ispe" }?.first
            ?: fail("CORRUPTED_CONTAINER", "HEIF coded primary has no unique ispe")
        val configuration = properties.singleOrNull { it.first.type == "hvcC" }?.first
            ?: fail("CORRUPTED_CONTAINER", "HEIF coded primary has no unique hvcC")
        if (dimensions.payload.length != 12uL) fail("CORRUPTED_CONTAINER", "HEIF ispe has an invalid size")
        val fields = reader.readBuffer(dimensions.payload.offset, 12u).orThrow()
        if (readUnsigned(fields.slice(0, 4), Endian.Big) != 0uL) fail("CAPABILITY_UNSUPPORTED", "HEIF ispe version/flags are not implemented")
        val width = readUnsigned(fields.slice(4, 8), Endian.Big).toUInt()
        val height = readUnsigned(fields.slice(8, 12), Endian.Big).toUInt()
        if (width == 0u || height == 0u) fail("CORRUPTED_CONTAINER", "HEIF declared dimensions are zero")
        budget.retain(checkedAdd(configuration.payload.length, 128uL))
        val bytes = reader.readExactly(configuration.payload.offset, checkedInt(configuration.payload.length).toUInt()).orThrow()
        val nalWidth = hevcConfig(bytes, budget)
        val item = graph.locations.items.singleOrNull { it.id == info.id } ?: fail("CORRUPTED_CONTAINER", "HEIF primary locations are ambiguous or missing")
        val view = ExtentSource.create(reader, item.extents.map { it.data }, budget).orThrow()
        val itemReader = BinaryReader(view, reader.context)
        val framing = validateNalFraming(itemReader, ByteRange(0uL, view.size().orThrow()), nalWidth, VideoCodec.Hevc, budget, 4u).orThrow()
        val digest = Sha256().also { it.update(bytes) }.finish()
        reader.validateIdentity().orThrow()
        budget.retain(checkedMultiply(framing.types.size.toULong(), 32uL))
        HeifCodedItemFacts(info.id, width, height, configuration.payload, digest, nalWidth, framing.units, framing.types)
    }
}
