package livephoto.core.xmp

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*

internal const val RDF_URI: String = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
internal const val XMP_META_URI: String = "adobe:ns:meta/"
internal const val XMP_NOTE_URI: String = "http://ns.adobe.com/xmp/note/"
internal const val XMP_HEADER: String = "http://ns.adobe.com/xap/1.0/\u0000"
internal const val EXTENDED_XMP_HEADER: String = "http://ns.adobe.com/xmp/extension/\u0000"

internal data class XmpProperty(val name: ExpandedName, val attribute: XmlAttribute? = null, val element: XmlElement? = null)

/** RDF properties retain their actual namespace URI and original tree, including unknown nodes. */
internal class XmpPacket(val document: XmlDocument, val descriptions: List<XmlElement>) {
    fun properties(uri: String, local: String): List<XmpProperty> {
        val name = ExpandedName(uri, local)
        return descriptions.flatMap { description ->
            description.attributes.filter { it.name.expanded == name }.map { XmpProperty(name, attribute = it) } +
                description.children.filterIsInstance<XmlElement>().filter { it.name.expanded == name }.map { XmpProperty(name, element = it) }
        }
    }

    fun scalar(uri: String, local: String): CoreResult<String?> = attemptNow {
        val values = properties(uri, local).map(::scalarValue)
        if (values.distinct().size > 1) fail("CONFLICTING_METADATA", "RDF property has conflicting values")
        values.firstOrNull()
    }
}

internal data class ExtendedXmpChunk(val guid: String, val totalLength: UInt, val offset: UInt, val data: ByteRange)
internal data class XmpCollection(
    val packets: List<XmpPacket>,
    val extended: List<ExtendedXmpChunk>,
    /** Physical chunk coverage only: Extended XML/GUID digest has not been assembled or validated. */
    val extendedComplete: Boolean = extended.isEmpty(),
    val issues: List<Issue> = emptyList(),
) {
    val rewriteAllowed: Boolean get() = packets.size == 1 && extended.isEmpty() && packets.none { it.properties(XMP_NOTE_URI, "HasExtendedXMP").isNotEmpty() }
    fun scalar(uri: String, local: String): CoreResult<String?> = attemptNow {
        val values = packets.flatMap { it.properties(uri, local) }.map(::scalarValue)
        if (values.distinct().size > 1) fail("CONFLICTING_METADATA", "XMP packets have conflicting property values")
        values.firstOrNull()
    }
}

internal object XmpReader {
    fun parse(bytes: Bytes, context: Context): CoreResult<XmpPacket> = attemptNow {
        val document = XmlParser.parse(bytes, context).orThrow()
        packet(document)
    }

    private fun packet(document: XmlDocument): XmpPacket {
        val rdf = when (document.root.name.expanded) {
            ExpandedName(RDF_URI, "RDF") -> document.root
            ExpandedName(XMP_META_URI, "xmpmeta"), ExpandedName(XMP_META_URI, "xapmeta") -> {
                val children = document.root.children.filterIsInstance<XmlElement>()
                if (children.size != 1 || children.single().name.expanded != ExpandedName(RDF_URI, "RDF")) fail("MALFORMED_XMP", "XMP envelope must directly contain one RDF root")
                children.single()
            }
            else -> fail("MALFORMED_XMP", "Unknown XMP envelope cannot establish an authoritative RDF root")
        }
        val subjects = rdf.children.filterIsInstance<XmlElement>()
        fun literalContent(nodes: List<XmlNode>): Boolean = nodes.any {
            val text = when (it) { is XmlText -> it.text; is XmlCData -> it.text; else -> "" }
            text.any { character -> character !in " \t\n\r" }
        }
        if (literalContent(rdf.children) || (document.root !== rdf && literalContent(document.root.children))) fail("MALFORMED_XMP", "XMP envelope and RDF root cannot contain literal data")
        if (subjects.any { it.name.expanded != ExpandedName(RDF_URI, "Description") }) {
            fail("CAPABILITY_UNSUPPORTED", "Typed RDF subjects are not supported by this XMP binding")
        }
        for (subject in subjects) {
            if (literalContent(subject.children)) fail("MALFORMED_XMP", "RDF descriptions contain properties rather than literal data")
            if (subject.attribute(RDF_URI, "nodeID") != null || subject.attribute(RDF_URI, "ID") != null || !subject.attribute(RDF_URI, "about").isNullOrEmpty()) {
                fail("CAPABILITY_UNSUPPORTED", "Non-default RDF subject identity requires explicit resource binding")
            }
        }
        return XmpPacket(document, frozenList(subjects))
    }

    suspend fun readJpeg(reader: BinaryReader, structure: JpegStructure): CoreResult<XmpCollection> = attempt {
        val packets = mutableListOf<XmpPacket>()
        val extended = mutableListOf<ExtendedXmpChunk>()
        val budget = ParseBudget(reader.context)
        for (segment in structure.segments) {
            val payload = segment.payload ?: continue
            when (segment.payloadKind) {
                AppPayloadKind.Xmp -> {
                    val header = XMP_HEADER.length.toULong()
                    if (payload.length < header) fail("MALFORMED_XMP", "Truncated XMP header")
                    val length = payload.length - header
                    budget.retain(length)
                    budget.retain(32uL)
                    val bytes = reader.readExactly(payload.offset + header, length.toUInt()).orThrow()
                    packets += packet(XmlParser.parseReserved(bytes, reader.context, budget).orThrow())
                }
                AppPayloadKind.ExtendedXmp -> {
                    val header = EXTENDED_XMP_HEADER.length.toULong()
                    if (payload.length < header + 40uL) fail("MALFORMED_XMP", "Truncated Extended XMP chunk header")
                    val metadata = reader.readBuffer(payload.offset + header, 40u).orThrow()
                    val guid = metadata.slice(0, 32).toByteArray().decodeToString()
                    if (!guid.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("MALFORMED_XMP", "Invalid Extended XMP GUID")
                    val total = readUnsigned(metadata.slice(32, 36), Endian.Big).toUInt()
                    val offset = readUnsigned(metadata.slice(36, 40), Endian.Big).toUInt()
                    val data = ByteRange(payload.offset + header + 40uL, payload.length - header - 40uL)
                    if (total == 0u || data.length == 0uL) fail("MALFORMED_XMP", "Extended XMP packet and chunk must be nonempty")
                    if (total.toULong() > reader.context.limits.maxMetadataBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Extended XMP declared length exceeds the metadata budget")
                    if (offset > total || data.length > (total - offset).toULong()) fail("MALFORMED_XMP", "Extended XMP chunk exceeds its declared packet")
                    budget.item()
                    budget.retain(96uL)
                    extended += ExtendedXmpChunk(guid, total, offset, data)
                }
                else -> Unit
            }
        }
        var complete = true
        val groups = extended.groupBy { it.guid.uppercase() }
        for (chunks in groups.values) {
            if (chunks.map { it.totalLength }.distinct().size != 1) fail("CONFLICTING_METADATA", "Extended XMP chunks disagree on total packet length")
            var end = 0uL
            for (chunk in chunks.sortedBy { it.offset }) {
                if (chunk.offset.toULong() < end) fail("CONFLICTING_METADATA", "Extended XMP chunks overlap")
                if (chunk.offset.toULong() != end) complete = false
                end = chunk.offset.toULong() + chunk.data.length
            }
            if (end != chunks.first().totalLength.toULong()) complete = false
        }
        for (packet in packets) {
            val reference = packet.scalar(XMP_NOTE_URI, "HasExtendedXMP").orThrow() ?: continue
            if (reference.length != 32 || !reference.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("MALFORMED_XMP", "Invalid Extended XMP reference GUID")
            if (reference.uppercase() !in groups) fail("MALFORMED_XMP", "Referenced Extended XMP chunks are missing")
        }
        val issues = if (complete) emptyList() else listOf(Issue(IssueCode("MALFORMED_XMP"), Severity.Error, Layer.Structure))
        reader.validateIdentity().orThrow()
        XmpCollection(frozenList(packets), frozenList(extended), complete, issues)
    }
}

/** A literal and an RDF resource are distinct; structured values are never guessed as scalars. */
private fun scalarValue(property: XmpProperty): String {
    property.attribute?.let { return it.value }
    val element = property.element ?: fail("MALFORMED_XMP", "Missing RDF property representation")
    if (element.children.any { it is XmlElement } || element.attribute(RDF_URI, "resource") != null || element.attribute(RDF_URI, "parseType") != null) {
        fail("CAPABILITY_UNSUPPORTED", "RDF property is not a simple literal")
    }
    if (element.attributes.any { it.name.expanded !in setOf(ExpandedName(RDF_URI, "datatype"), ExpandedName(XML_NAMESPACE, "lang")) }) {
        fail("CAPABILITY_UNSUPPORTED", "Unknown RDF literal qualifiers require explicit interpretation")
    }
    return element.children.mapNotNull { when (it) { is XmlText -> it.text; is XmlCData -> it.text; else -> null } }.joinToString("")
}

internal object XmpWriter {
    /** Merge only explicitly named scalar properties; retain every ordinary/unknown node. */
    fun merge(packet: XmpPacket, updates: Map<ExpandedName, String?>, context: Context): CoreResult<Bytes> = attemptNow {
        if (packet.descriptions.isEmpty()) {
            val description = XmlElement(XmlName("rdf:Description", ExpandedName(RDF_URI, "Description")), emptyList(), mapOf("rdf" to RDF_URI), emptyList())
            val oldRoot = packet.document.root
            val root = if (oldRoot.name.expanded == ExpandedName(RDF_URI, "RDF")) oldRoot.copy(children = oldRoot.children + description)
            else oldRoot.copy(children = oldRoot.children.map { if (it is XmlElement && it.name.expanded == ExpandedName(RDF_URI, "RDF")) it.copy(children = it.children + description) else it })
            val document = XmlDocument(root, packet.document.nodes.map { if (it === oldRoot) root else it })
            val initialized = XmpReader.parse(XmlWriter.write(document, context).orThrow(), context).orThrow()
            return@attemptNow merge(initialized, updates, context).orThrow()
        }
        if (packet.scalar(XMP_NOTE_URI, "HasExtendedXMP").orThrow() != null) fail("UNSAFE_METADATA_REWRITE", "Referenced Extended XMP must be reassembled before rewriting")
        if (updates.keys.any { it.uri.isEmpty() || it.uri == RDF_URI || it.uri == XML_NAMESPACE || it.uri == XMLNS_NAMESPACE }) {
            fail("INVALID_ARGUMENT", "Scalar XMP edits require an explicit property namespace")
        }
        for (name in updates.keys) {
            packet.scalar(name.uri, name.local).orThrow()
            if (packet.properties(name.uri, name.local).any { it.element?.attributes?.isNotEmpty() == true }) {
                fail("UNSAFE_METADATA_REWRITE", "Qualified literal properties require a verified qualifier-preserving edit")
            }
        }
        val first = packet.descriptions.first()
        val usedPrefixes = mutableSetOf<String>()
        fun prefixes(element: XmlElement) {
            usedPrefixes += element.namespaces.keys
            element.children.filterIsInstance<XmlElement>().forEach(::prefixes)
        }
        prefixes(packet.document.root)
        val additions = linkedMapOf<String, String>()
        val prefixForUri = linkedMapOf<String, String>()
        var ordinal = 0
        for (name in updates.filterValues { it != null }.keys) {
            if (name.uri !in prefixForUri) {
                var prefix: String
                do { prefix = "lp${ordinal++}" } while (prefix in usedPrefixes)
                usedPrefixes += prefix
                prefixForUri[name.uri] = prefix
                additions[prefix] = name.uri
            }
        }
        fun transformDescription(element: XmlElement): XmlElement {
                val attributes = element.attributes.filterNot { it.name.expanded in updates }.toMutableList()
                val children = element.children.filterNot { it is XmlElement && it.name.expanded in updates }
                if (element === first) for ((name, value) in updates) if (value != null) {
                    attributes += XmlAttribute(XmlName("${prefixForUri.getValue(name.uri)}:${name.local}", name), value)
                }
                return element.copy(attributes = frozenList(attributes), children = frozenList(children), namespaces = element.namespaces + if (element === first) additions else emptyMap())
        }
        fun transform(element: XmlElement): XmlElement {
            if (element.children.any { it === first }) {
                return element.copy(children = frozenList(element.children.map { if (it is XmlElement) transformDescription(it) else it }))
            }
            return element.copy(children = frozenList(element.children.map { if (it is XmlElement) transform(it) else it }))
        }
        val root = transform(packet.document.root)
        val nodes = packet.document.nodes.map { if (it === packet.document.root) root else it }
        val result = XmlWriter.write(XmlDocument(root, frozenList(nodes)), context).orThrow()
        val reparsed = XmpReader.parse(result, context).orThrow()
        for ((name, value) in updates) if (reparsed.scalar(name.uri, name.local).orThrow() != value) fail("POSTCONDITION_FAILED", "XMP scalar merge failed its readback")
        result
    }

    fun merge(collection: XmpCollection, updates: Map<ExpandedName, String?>, context: Context): CoreResult<Bytes> = attemptNow {
        if (!collection.rewriteAllowed) fail("UNSAFE_METADATA_REWRITE", "Duplicate packets or Extended XMP require explicit reconciliation")
        merge(collection.packets.single(), updates, context).orThrow()
    }

    fun create(properties: Map<ExpandedName, String>, context: Context): CoreResult<Bytes> = attemptNow {
        val description = XmlElement(XmlName("rdf:Description", ExpandedName(RDF_URI, "Description")), emptyList(), emptyMap(), emptyList())
        val rdf = XmlElement(XmlName("rdf:RDF", ExpandedName(RDF_URI, "RDF")), emptyList(), mapOf("rdf" to RDF_URI), listOf(description))
        val document = XmlDocument(rdf, listOf(rdf))
        merge(XmpPacket(document, listOf(description)), properties, context).orThrow()
    }
}
