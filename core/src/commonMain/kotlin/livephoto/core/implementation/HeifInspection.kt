package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.heif.*

internal data class HeifInspectionFragment(val regions: List<Region>, val resources: List<Resource>,
    val metadata: List<MetadataEntry>, val relationships: List<Relationship>)

/** Item bytes are never mislabeled as independent image carriers; string payloads are not logged. */
internal fun inspectHeifItems(identity: SourceIdentity, graph: HeifItemGraph, budget: ParseBudget): HeifInspectionFragment {
    if (identity != graph.locations.identity) fail("SOURCE_CHANGED", "HEIF inspection graph belongs to a different source")
    val regions = mutableListOf<Region>()
    val resources = mutableListOf<Resource>()
    val metadata = mutableListOf<MetadataEntry>()
    val relationships = mutableListOf<Relationship>()
    fun itemId(id: UInt) = ResourceId("heif:item:$id")
    for (item in graph.locations.items) {
        budget.item(); budget.retain(96uL)
        val id = itemId(item.id)
        val extents = item.extents.mapIndexed { index, extent ->
            budget.item(); budget.retain(96uL)
            Region(ResourceId("${id.value}:extent:$index"), identity.id, extent.data, ResourceKind.Unknown)
        }
        val shared = mutableListOf<ResourceId>()
        for (other in graph.locations.items) if (other.id != item.id) {
            var overlap = false
            for (right in other.extents) for (left in item.extents) {
                budget.item()
                if (left.data.offset < right.data.endExclusive && right.data.offset < left.data.endExclusive) overlap = true
            }
            if (overlap) { budget.retain(32uL); shared += itemId(other.id) }
        }
        regions += extents; resources += Resource(id, ResourceKind.Unknown, frozenList(extents), false, frozenList(shared))
    }
    for (item in graph.infos) {
        budget.item(); budget.retain(256uL)
        val location = Location(source = identity.id, range = item.box.range, selector = "heif:item:${item.id}")
        metadata += MetadataEntry("heif:item:${item.id}:type", value = Value.Text(item.type), owner = Ownership.StandardImage, location = location, origin = FactOrigin.Parsed)
        if (item.id == graph.primary) {
            budget.item(); budget.retain(128uL)
            metadata += MetadataEntry("heif:primary-item-id", value = Value.Number(item.id.toString()), owner = Ownership.StandardImage, location = location, origin = FactOrigin.Parsed)
        }
    }
    val resourceIds = resources.map { it.id }.toSet()
    for (reference in graph.references) {
        budget.item(); budget.retain(256uL)
        val selector = "heif:reference:${reference.type}:${reference.from}"
        val values = reference.to.map { id -> budget.item(); budget.retain(32uL); Value.Number(id.toString()) }
        metadata += MetadataEntry(selector, value = Value.ArrayValue(frozenList(values)), owner = Ownership.StandardImage,
            location = Location(source = identity.id, range = reference.box.range, selector = selector), origin = FactOrigin.Parsed)
        val kind = when (reference.type) { "auxl", "thmb" -> RelationshipKind.AuxiliaryOf; "cdsc" -> RelationshipKind.Describes; else -> null }
        if (kind != null && itemId(reference.from) in resourceIds) for (destination in reference.to) if (itemId(destination) in resourceIds) {
            budget.item(); budget.retain(128uL)
            relationships += Relationship(kind, itemId(reference.from), itemId(destination), mapOf("heifReferenceType" to Value.Text(reference.type)))
        }
    }
    return HeifInspectionFragment(frozenList(regions), frozenList(resources), frozenList(metadata), frozenList(relationships))
}
