package livephoto.core.vivo

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

internal data class GainMapFacts(val range: ByteRange, val jpeg: JpegStructure, val media: MediaFacts)

internal suspend fun verifyGainMap(reader: BinaryReader, range: ByteRange, budget: ParseBudget): GainMapFacts {
    val view = BinaryReader(RangeSource(reader, range), reader.context)
    val jpeg = JpegParser.parse(view, budget).orThrow()
    if (jpeg.primary.length != range.length || jpeg.trailing.length != 0uL) fail("AUXILIARY_RESOURCE_INVALID", "GainMap extent is not exactly one complete JPEG")
    val facts = jpegFacts(view, jpeg)
    if (facts.issues.any { it.severity == Severity.Error }) fail("AUXILIARY_RESOURCE_INVALID", "GainMap JPEG frame is corrupted")
    if (facts.width == null || facts.height == null) fail("UNSUPPORTED_CONTAINER", "GainMap JPEG frame is outside the implemented scope")
    reader.validateIdentity().orThrow()
    return GainMapFacts(range, jpeg, facts)
}

/** Reads only the ordinary Primary/GainMap graph left after a verified clean operation. */
internal fun ordinaryGainMap(xmp: XmpCollection, jpeg: JpegStructure, source: SourceIdentity, budget: ParseBudget): ByteRange? {
    val directories = xmp.packets.flatMap { it.properties(CONTAINER_URI, "Directory") }
    if (directories.size != 1) return null
    val directory = directories.single().element ?: return null
    val seq = directory.elements(RDF_URI, "Seq").singleOrNull() ?: return null
    val children = seq.children.filterIsInstance<XmlElement>()
    if (children.size != 2 || children.any { it.name.expanded != ExpandedName(RDF_URI, "li") }) return null
    fun item(child: XmlElement): XmlElement = child.elements(CONTAINER_URI, "Item").singleOrNull() ?: child
    fun value(element: XmlElement, field: String): String? {
        val values = listOfNotNull(element.attribute(ITEM_URI, field)) + element.elements(ITEM_URI, field).map { leaf ->
            if (leaf.attributes.isNotEmpty() || leaf.children.any { it is XmlElement }) fail("CAPABILITY_UNSUPPORTED", "Qualified auxiliary directory fields are not implemented")
            leaf.children.mapNotNull { (it as? XmlText)?.text ?: (it as? XmlCData)?.text }.joinToString("")
        }
        if (values.distinct().size > 1) fail("CONFLICTING_METADATA", "Auxiliary directory fields conflict")
        return values.firstOrNull()
    }
    val primary = item(children[0]); val auxiliary = item(children[1])
    if (value(primary, "Semantic") != "Primary" || value(auxiliary, "Semantic") != "GainMap") return null
    for (node in listOf(directory, seq, children[0], children[1], primary, auxiliary)) {
        budget.item()
        if (node.attributes.any { it.name.expanded.uri == RDF_URI && it.name.expanded.local != "parseType" } || node.children.any { it is XmlText && it.text.any { character -> character !in " \t\r\n" } || it is XmlCData && it.text.any { character -> character !in " \t\r\n" } }) fail("CAPABILITY_UNSUPPORTED", "Auxiliary resource wrappers are not inline RDF authority")
    }
    if (children.any { it.attribute(RDF_URI, "parseType") != "Resource" }) fail("CAPABILITY_UNSUPPORTED", "Auxiliary directory items must be inline resources")
    if (value(primary, "Mime") != "image/jpeg" || value(primary, "Length") != null || value(primary, "Padding") != null || value(auxiliary, "Mime") != "image/jpeg" || value(auxiliary, "Padding") != null) fail("AUXILIARY_RESOURCE_INVALID", "Ordinary auxiliary graph is outside the verified Primary/GainMap schema")
    val length = value(auxiliary, "Length")?.let(::unsigned) ?: fail("AUXILIARY_RESOURCE_INVALID", "GainMap length is missing")
    if (length == 0uL || length != jpeg.trailing.length) fail("AUXILIARY_RESOURCE_INVALID", "GainMap length does not match the complete image suffix")
    return checkedRange(jpeg.primary.endExclusive, length, source.size)
}
