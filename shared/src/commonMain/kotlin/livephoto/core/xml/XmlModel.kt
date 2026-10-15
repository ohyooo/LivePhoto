package livephoto.core.xml

import livephoto.core.*
import livephoto.core.binary.*

internal data class ExpandedName(val uri: String, val local: String)
internal data class XmlName(val raw: String, val expanded: ExpandedName)
internal data class XmlAttribute(val name: XmlName, val value: String)
internal sealed interface XmlNode
internal data class XmlElement(
    val name: XmlName,
    val attributes: List<XmlAttribute>,
    val namespaces: Map<String, String>,
    val children: List<XmlNode>,
) : XmlNode {
    fun attribute(uri: String, local: String): String? = attributes.firstOrNull { it.name.expanded == ExpandedName(uri, local) }?.value
    fun elements(uri: String, local: String): List<XmlElement> = children.filterIsInstance<XmlElement>().filter { it.name.expanded == ExpandedName(uri, local) }
}
internal data class XmlText(val text: String) : XmlNode
internal data class XmlCData(val text: String) : XmlNode
internal data class XmlComment(val text: String) : XmlNode
internal data class XmlProcessingInstruction(val target: String, val content: String) : XmlNode
internal data class XmlDocument(val root: XmlElement, val nodes: List<XmlNode>)

/** Serializes a parsed tree while retaining unknown nodes, namespaces, and attribute values. */
internal object XmlWriter {
    fun write(document: XmlDocument, context: Context): CoreResult<Bytes> = attemptNow {
        val output = StringBuilder()
        val budget = ParseBudget(context)
        fun append(text: String) {
            // Count actual UTF-8 bytes before retaining the text in the output buffer.
            budget.retain(utf8Length(text, context))
            output.append(text)
        }
        fun escaped(text: String, attribute: Boolean) {
            var index = 0
            var nextPoll = 0
            while (index < text.length) {
                if (index >= nextPoll) { checkCancelled(context); nextPoll = index + minOf(4096, text.length - index) }
                val character = text[index]
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> if (attribute) append("&quot;") else append("\"")
                    '\t' -> if (attribute) append("&#9;") else append("\t")
                    '\n' -> if (attribute) append("&#10;") else append("\n")
                    '\r' -> append("&#13;")
                    else -> {
                        val width = if (character.code in 0xd800..0xdbff) 2 else 1
                        if (index + width > text.length) fail("MALFORMED_XMP", "Isolated surrogate in XML output")
                        append(text.substring(index, index + width))
                        index += width - 1
                    }
                }
                index++
            }
        }
        fun node(value: XmlNode, depth: UInt) {
            budget.item(if (value is XmlElement) depth else 0u)
            when (value) {
                is XmlText -> escaped(value.text, false)
                is XmlCData -> {
                    if ("]]>" in value.text) fail("MALFORMED_XMP", "CDATA contains its closing delimiter")
                    append("<![CDATA["); append(value.text); append("]]>")
                }
                is XmlComment -> {
                    if ("--" in value.text || value.text.endsWith('-')) fail("MALFORMED_XMP", "Invalid XML comment")
                    append("<!--"); append(value.text); append("-->")
                }
                is XmlProcessingInstruction -> {
                    if ("?>" in value.content) fail("MALFORMED_XMP", "Invalid processing instruction")
                    append("<?"); append(value.target)
                    if (value.content.isNotEmpty()) { append(" "); append(value.content) }
                    append("?>")
                }
                is XmlElement -> {
                    append("<"); append(value.name.raw)
                    for ((prefix, uri) in value.namespaces) {
                        budget.item(depth)
                        append(" "); append(if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"); append("=\"")
                        escaped(uri, true); append("\"")
                    }
                    for (attribute in value.attributes) {
                        budget.item(depth)
                        append(" "); append(attribute.name.raw); append("=\""); escaped(attribute.value, true); append("\"")
                    }
                    if (value.children.isEmpty()) append("/>")
                    else {
                        append(">")
                        for (child in value.children) node(child, depth + 1u)
                        append("</"); append(value.name.raw); append(">")
                    }
                }
            }
        }
        for (value in document.nodes) node(value, 0u)
        val bytes = Bytes(output.toString().encodeToByteArray())
        val reparsed = XmlParser.parse(bytes, context).orThrow()
        checkNames(document.root, reparsed.root)
        checkCancelled(context)
        bytes
    }

    private fun checkNames(expected: XmlElement, actual: XmlElement) {
        if (expected.name != actual.name || expected.attributes != actual.attributes) fail("MALFORMED_XMP", "XML output changed a declared expanded name or value")
        val expectedChildren = expected.children.filterIsInstance<XmlElement>()
        val actualChildren = actual.children.filterIsInstance<XmlElement>()
        if (expectedChildren.size != actualChildren.size) fail("MALFORMED_XMP", "XML output changed the element graph")
        for (index in expectedChildren.indices) checkNames(expectedChildren[index], actualChildren[index])
        val expectedText = expected.children.mapNotNull { when (it) { is XmlText -> it.text; is XmlCData -> it.text; else -> null } }.joinToString("")
        val actualText = actual.children.mapNotNull { when (it) { is XmlText -> it.text; is XmlCData -> it.text; else -> null } }.joinToString("")
        if (expectedText != actualText) fail("MALFORMED_XMP", "XML output changed text semantics")
    }
}

private fun utf8Length(text: String, context: Context): ULong {
    var bytes = 0uL
    var index = 0
    var nextPoll = 0
    while (index < text.length) {
        if (index >= nextPoll) { checkCancelled(context); nextPoll = index + minOf(4096, text.length - index) }
        val point = text[index].code
        val width = when {
            point in 0xd800..0xdbff -> {
                if (index + 1 >= text.length || text[index + 1].code !in 0xdc00..0xdfff) fail("MALFORMED_XMP", "Isolated XML surrogate")
                index++
                4
            }
            point in 0xdc00..0xdfff || point == 0 || point in 1..8 || point in 11..12 || point in 14..31 || point in 0xfffe..0xffff -> fail("MALFORMED_XMP", "Forbidden XML output character")
            point < 0x80 -> 1
            point < 0x800 -> 2
            else -> 3
        }
        bytes = checkedAdd(bytes, width.toULong())
        index++
    }
    return bytes
}
