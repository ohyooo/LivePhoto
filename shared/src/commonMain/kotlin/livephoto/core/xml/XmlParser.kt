package livephoto.core.xml

import livephoto.core.*
import livephoto.core.binary.*

internal const val XML_NAMESPACE: String = "http://www.w3.org/XML/1998/namespace"
internal const val XMLNS_NAMESPACE: String = "http://www.w3.org/2000/xmlns/"

/** A bounded UTF-8 XML 1.0 parser. DTDs and all user-defined/external entities are rejected. */
internal object XmlParser {
    fun parse(bytes: Bytes, context: Context): CoreResult<XmlDocument> = parseWithBudget(bytes, context, ParseBudget(context), false)

    /** Carrier parsers reserve input bytes before reading, and share item/depth accounting. */
    fun parseReserved(bytes: Bytes, context: Context, budget: ParseBudget): CoreResult<XmlDocument> = parseWithBudget(bytes, context, budget, true)

    private fun parseWithBudget(bytes: Bytes, context: Context, budget: ParseBudget, reserved: Boolean): CoreResult<XmlDocument> = attemptNow {
        if (!reserved) budget.retain(bytes.size.toULong())
        val text = try { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) }
        catch (_: Exception) { fail("MALFORMED_XMP", "XML is not valid UTF-8") }
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        validateXmlCharacters(normalized, context)
        Parser(normalized, budget).parse().also { checkCancelled(context) }
    }
}

private class Parser(private val text: String, private val budget: ParseBudget) {
    private var position = 0
    private var nextPoll = 0
    private fun at(token: String): Boolean = text.startsWith(token, position)
    private fun expect(token: String) {
        if (!at(token)) malformed("Expected XML delimiter")
        position += token.length
    }
    private fun whitespace(): Boolean {
        val start = position
        while (position < text.length && text[position] in " \t\n\r") { poll(); position++ }
        return start != position
    }
    private fun poll() {
        if (position >= nextPoll) { budget.poll(); nextPoll = position + minOf(4096, text.length - position) }
    }
    private fun delimiter(token: String): Int {
        var index = position
        while (index <= text.length - token.length) {
            if (index and 4095 == 0) budget.poll()
            if (text.startsWith(token, index)) return index
            index++
        }
        return -1
    }

    fun parse(): XmlDocument {
        val nodes = mutableListOf<XmlNode>()
        var root: XmlElement? = null
        if (at("<?xml") && position + 5 < text.length && text[position + 5] in " \t\n\r") declaration()
        while (position < text.length) {
            val node = when {
                at("<!--") -> comment()
                at("<?") -> instruction()
                at("<!") -> malformed("DTD and declarations are forbidden")
                at("<") -> {
                    if (root != null) malformed("XML has more than one document element")
                    element(NamespaceScope(null, mapOf("xml" to XML_NAMESPACE)), 0u).also { root = it }
                }
                else -> {
                    val value = content(allowEntities = false)
                    if (value.text.any { it !in " \t\n\r" }) malformed("Non-whitespace outside the XML root")
                    value
                }
            }
            nodes += node
        }
        return XmlDocument(root ?: malformed("XML document has no root element"), frozenList(nodes))
    }

    private fun declaration() {
        expect("<?xml")
        if (!whitespace()) malformed("XML declaration requires whitespace")
        val fields = mutableListOf<Pair<String, String>>()
        while (!at("?>")) {
            val key = name()
            if (fields.size >= 3 || key !in setOf("version", "encoding", "standalone")) malformed("Unsupported XML declaration field")
            whitespace(); expect("="); whitespace()
            val value = quoted(allowEntities = false)
            fields += key to value
            if (!whitespace() && !at("?>")) malformed("XML declaration fields need separation")
        }
        expect("?>")
        if (fields.firstOrNull() != ("version" to "1.0") || fields.map { it.first }.distinct().size != fields.size) malformed("Unsupported XML declaration")
        if (fields.any { it.first !in setOf("version", "encoding", "standalone") }) malformed("Unknown XML declaration field")
        val order = fields.map { listOf("version", "encoding", "standalone").indexOf(it.first) }
        if (order != order.sorted()) malformed("XML declaration fields are out of order")
        val encoding = fields.firstOrNull { it.first == "encoding" }?.second
        if (encoding != null && !encoding.equals("UTF-8", ignoreCase = true)) malformed("Only UTF-8 XML is supported")
        val standalone = fields.firstOrNull { it.first == "standalone" }?.second
        if (standalone != null && standalone !in setOf("yes", "no")) malformed("Invalid XML standalone declaration")
    }

    private fun element(inherited: NamespaceScope, depth: UInt): XmlElement {
        budget.item(depth)
        expect("<")
        val rawName = name()
        val rawAttributes = mutableListOf<Pair<String, String>>()
        val rawAttributeNames = mutableSetOf<String>()
        while (true) {
            val separated = whitespace()
            if (at("/>") || at(">")) break
            if (!separated) malformed("XML attributes need whitespace separation")
            val key = name()
            if (!rawAttributeNames.add(key)) malformed("Duplicate XML attribute")
            whitespace(); expect("="); whitespace()
            rawAttributes += key to quoted()
            budget.item(depth)
        }
        val declarations = linkedMapOf<String, String>()
        for ((raw, value) in rawAttributes) {
            if (raw == "xmlns" || raw.startsWith("xmlns:")) {
                val prefix = if (raw == "xmlns") "" else raw.substring(6)
                if (prefix == "xmlns" || value == XMLNS_NAMESPACE || (prefix == "xml") != (value == XML_NAMESPACE)) malformed("Reserved XML namespace binding")
                if (prefix.isNotEmpty() && value.isEmpty()) malformed("A namespace prefix cannot bind an empty URI")
                declarations[prefix] = value
            }
        }
        val scope = if (declarations.isEmpty()) inherited else NamespaceScope(inherited, declarations)
        val resolvedName = resolve(rawName, scope, false)
        val attributes = rawAttributes.filterNot { it.first == "xmlns" || it.first.startsWith("xmlns:") }
            .map { (raw, value) -> XmlAttribute(resolve(raw, scope, true), value) }
        if (attributes.map { it.name.expanded }.distinct().size != attributes.size) malformed("Duplicate expanded XML attribute name")
        val children = mutableListOf<XmlNode>()
        if (at("/>")) position += 2
        else {
            expect(">")
            while (true) {
                if (position >= text.length) malformed("Unclosed XML element")
                if (at("</")) {
                    position += 2
                    if (name() != rawName) malformed("Mismatched XML closing name")
                    whitespace(); expect(">")
                    break
                }
                children += when {
                    at("<!--") -> comment()
                    at("<![CDATA[") -> cdata()
                    at("<?") -> instruction()
                    at("<!") -> malformed("DTD and declarations are forbidden")
                    at("<") -> element(scope, depth + 1u)
                    else -> content()
                }
            }
        }
        return XmlElement(resolvedName, frozenList(attributes), declarations.toMap(), frozenList(children))
    }

    private fun resolve(raw: String, scope: NamespaceScope, attribute: Boolean): XmlName {
        val components = raw.split(':')
        if (components.size > 2 || components.any { it.isEmpty() }) malformed("Invalid XML qualified name")
        val prefix = if (components.size == 2) components[0] else ""
        if (prefix == "xmlns") malformed("The xmlns prefix is reserved")
        val uri = if (prefix.isNotEmpty()) scope[prefix] ?: malformed("Unbound XML namespace prefix")
        else if (attribute) "" else scope[""] ?: ""
        return XmlName(raw, ExpandedName(uri, components.last()))
    }

    private fun name(): String {
        val start = position
        if (position >= text.length || !nameStart(codePointAt(text, position).first)) malformed("Invalid XML name")
        position += codePointAt(text, position).second
        while (position < text.length) {
            poll()
            val (point, width) = codePointAt(text, position)
            if (!nameCharacter(point)) break
            position += width
        }
        val value = text.substring(start, position)
        val components = value.split(':')
        if (components.size > 2 || components.any { it.isEmpty() }) malformed("Invalid qualified XML name")
        if (components.any { !nameStart(codePointAt(it, 0).first) }) malformed("Invalid namespace prefix or local name")
        return value
    }

    private fun quoted(allowEntities: Boolean = true): String {
        if (position >= text.length || text[position] !in "\"'") malformed("Expected quoted XML value")
        val quote = text[position++]
        val value = StringBuilder()
        while (position < text.length && text[position] != quote) {
            poll()
            when (val character = text[position++]) {
                '<' -> malformed("Literal less-than is forbidden in XML attributes")
                '&' -> if (allowEntities) value.append(entity()) else malformed("Entity references are not permitted here")
                '\t', '\n', '\r' -> value.append(' ')
                else -> value.append(character)
            }
        }
        if (position >= text.length) malformed("Unterminated XML attribute")
        position++
        return value.toString()
    }

    private fun content(allowEntities: Boolean = true): XmlText {
        budget.item()
        val output = StringBuilder()
        while (position < text.length && text[position] != '<') {
            poll()
            if (at("]]>")) malformed("CDATA delimiter in ordinary XML text")
            val character = text[position++]
            if (character == '&') {
                if (!allowEntities) malformed("Entity references are not permitted outside the root")
                output.append(entity())
            } else output.append(character)
        }
        return XmlText(output.toString())
    }

    private fun entity(): String {
        var end = position
        while (end < text.length && end - position <= 32 && text[end] != ';') end++
        if (end - position > 32) fail("RESOURCE_LIMIT_EXCEEDED", "XML entity spelling exceeds the supported safety limit")
        if (end >= text.length) malformed("Unterminated XML entity")
        val token = text.substring(position, end)
        position = end + 1
        return when (token) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val point = when {
                    token.startsWith("#x") -> token.substring(2).takeIf { digits -> digits.isNotEmpty() && digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }?.toIntOrNull(16)
                    token.startsWith('#') -> token.substring(1).takeIf { digits -> digits.isNotEmpty() && digits.all { it in '0'..'9' } }?.toIntOrNull(10)
                    else -> null
                } ?: malformed("User-defined and external XML entities are forbidden")
                if (!validXmlPoint(point)) malformed("Invalid XML character reference")
                if (point <= 0xffff) point.toChar().toString()
                else {
                    val adjusted = point - 0x10000
                    "${(0xd800 + (adjusted shr 10)).toChar()}${(0xdc00 + (adjusted and 0x3ff)).toChar()}"
                }
            }
        }
    }

    private fun comment(): XmlComment {
        budget.item()
        expect("<!--")
        val end = delimiter("-->")
        if (end < 0) malformed("Unterminated XML comment")
        val body = text.substring(position, end)
        if ("--" in body || body.endsWith('-')) malformed("Invalid XML comment body")
        position = end + 3
        return XmlComment(body)
    }

    private fun cdata(): XmlCData {
        budget.item()
        expect("<![CDATA[")
        val end = delimiter("]]>")
        if (end < 0) malformed("Unterminated CDATA")
        val body = text.substring(position, end)
        position = end + 3
        return XmlCData(body)
    }

    private fun instruction(): XmlProcessingInstruction {
        budget.item()
        expect("<?")
        val target = name()
        if (':' in target) malformed("Processing instruction targets must be namespace-free")
        if (target.equals("xml", ignoreCase = true)) malformed("XML processing target is reserved")
        val separated = whitespace()
        if (!separated && !at("?>")) malformed("Invalid XML processing instruction")
        val end = delimiter("?>")
        if (end < 0) malformed("Unterminated XML processing instruction")
        val body = text.substring(position, end)
        position = end + 2
        return XmlProcessingInstruction(target, body)
    }
}

/** Local declarations share their ancestor scope; large inherited prefix tables are never copied. */
private class NamespaceScope(private val parent: NamespaceScope?, private val local: Map<String, String>) {
    operator fun get(prefix: String): String? {
        var current: NamespaceScope? = this
        while (current != null) {
            if (prefix in current.local) return current.local.getValue(prefix)
            current = current.parent
        }
        return null
    }
}

private fun malformed(message: String): Nothing = fail("MALFORMED_XMP", message)

private fun codePointAt(text: String, index: Int): Pair<Int, Int> {
    val first = text[index].code
    if (first in 0xd800..0xdbff) {
        if (index + 1 >= text.length || text[index + 1].code !in 0xdc00..0xdfff) malformed("Isolated XML surrogate")
        return (0x10000 + ((first - 0xd800) shl 10) + text[index + 1].code - 0xdc00) to 2
    }
    if (first in 0xdc00..0xdfff) malformed("Isolated XML surrogate")
    return first to 1
}

private fun validXmlPoint(point: Int): Boolean = point == 9 || point == 10 || point == 13 || point in 0x20..0xd7ff || point in 0xe000..0xfffd || point in 0x10000..0x10ffff
private fun validateXmlCharacters(text: String, context: Context) {
    var position = 0
    var nextPoll = 0
    while (position < text.length) {
        if (position >= nextPoll) { checkCancelled(context); nextPoll = position + minOf(4096, text.length - position) }
        val (point, width) = codePointAt(text, position)
        if (!validXmlPoint(point)) malformed("Forbidden XML character")
        position += width
    }
}
private fun nameStart(point: Int): Boolean = point == ':'.code || point == '_'.code || point in 'A'.code..'Z'.code || point in 'a'.code..'z'.code ||
    point in 0xc0..0xd6 || point in 0xd8..0xf6 || point in 0xf8..0x2ff || point in 0x370..0x37d || point in 0x37f..0x1fff ||
    point in 0x200c..0x200d || point in 0x2070..0x218f || point in 0x2c00..0x2fef || point in 0x3001..0xd7ff ||
    point in 0xf900..0xfdcf || point in 0xfdf0..0xfffd || point in 0x10000..0xeffff
private fun nameCharacter(point: Int): Boolean = nameStart(point) || point == '-'.code || point == '.'.code || point in '0'.code..'9'.code || point == 0xb7 || point in 0x300..0x36f || point in 0x203f..0x2040
