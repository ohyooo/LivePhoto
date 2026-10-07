package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** Closed, single coded-item profile; neither an SPS/display proof nor generic HEIF support. */
internal class HeifCodedCarrier private constructor(val identity: SourceIdentity, val graph: HeifItemGraph,
    val image: HeifCodedItemFacts, val meta: BmffBox) {
    companion object {
        suspend fun read(reader: BinaryReader, budget: ParseBudget, stage: Stage): CoreResult<HeifCodedCarrier> = attempt {
            val identity = reader.identity().orThrow()
            val parser = BmffReader(reader, budget)
            val roots = parser.readBoxes(ByteRange(0uL, identity.size)).orThrow()
            if (roots.any { it.extendsToParentEnd || it.type !in setOf("ftyp", "meta", "mdat", "free") })
                fail("CAPABILITY_UNSUPPORTED", "HEIF coded carrier refuses unknown root dependencies or implicit sizes", stage)
            val ftyp = roots.singleOrNull { it.type == "ftyp" } ?: fail("CORRUPTED_CONTAINER", "HEIF coded carrier requires one ftyp")
            val brands = parser.readFileType(ftyp).orThrow().let { it.compatibleBrands + it.majorBrand }
            if (brands.none { it in setOf("heic", "heix") }) fail("CAPABILITY_UNSUPPORTED", "Only known HEIC brands are implemented", stage)
            val graph = HeifItemGraphReader.read(reader, roots, budget).orThrow()
            val unsafe = if (stage == Stage.Plan) "UNSAFE_METADATA_REWRITE" else "CAPABILITY_UNSUPPORTED"
            if (graph.infos.size != 1 || graph.infos.single().hidden || graph.infos.single().protection != 0u || graph.locations.items.size != 1 ||
                graph.references.isNotEmpty() || graph.unknownMeta.any { it.type != "free" } || graph.unknownPropertyContainers.isNotEmpty() ||
                graph.properties.any { it.type !in setOf("ispe", "hvcC") })
                fail(unsafe, "HEIF operation requires a completely classified single coded item profile", stage)
            val image = classifiedPrimary(reader, graph, budget, stage).orThrow()
            reader.validateIdentity().orThrow()
            HeifCodedCarrier(identity, graph, image, roots.single { it.type == "meta" })
        }

        /** Caller separately classifies every non-primary item/reference/root before mutation. */
        suspend fun classifiedPrimary(reader: BinaryReader, graph: HeifItemGraph, budget: ParseBudget, stage: Stage): CoreResult<HeifCodedItemFacts> = attempt {
            val unsafe = if (stage == Stage.Plan) "UNSAFE_METADATA_REWRITE" else "CAPABILITY_UNSUPPORTED"
            val info = graph.infos.single { it.id == graph.primary }
            if (info.hidden || info.protection != 0u || graph.unknownPropertyContainers.isNotEmpty() || graph.properties.any { it.type !in setOf("ispe", "hvcC") })
                fail(unsafe, "HEIF primary properties are not completely classified", stage)
            val image = HeifCodedItemProbe.primary(reader, graph, budget).orThrow()
            if (image.nalTypes.any { it !in 0..9 && it !in 16..21 && it !in 32..34 })
                fail(unsafe, "Unknown/reserved/SEI metadata NAL units are outside the finite HEIF profile", stage)
            budget.retain(image.configuration.length)
            hevcConfig(reader.readExactly(image.configuration.offset, checkedInt(image.configuration.length).toUInt()).orThrow(), budget,
                allowedArrayTypes = setOf(32, 33, 34))
            reader.validateIdentity().orThrow()
            image
        }
    }
}
