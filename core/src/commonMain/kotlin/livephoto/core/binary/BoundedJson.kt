package livephoto.core.binary

import livephoto.core.*

internal data class ParsedJsonObject(val value: Value.ObjectValue, val consumedBytes: Int)

/** Manual UTF-8 JSON grammar. Prefix parsing never decodes the binary bytes following the root. */
internal object BoundedJson {
    fun parseObject(bytes: Bytes, budget: ParseBudget): CoreResult<Value.ObjectValue> = attemptNow {
        val parser = JsonParser(bytes, budget)
        val value = parser.root()
        parser.whitespace()
        if (parser.position != bytes.size) malformedJson("JSON has trailing data")
        budget.poll()
        value
    }
    fun parseObjectPrefix(bytes: Bytes, budget: ParseBudget): CoreResult<ParsedJsonObject> = attemptNow {
        val parser = JsonParser(bytes, budget)
        val value = parser.root()
        budget.poll()
        ParsedJsonObject(value, parser.position)
    }
}

internal fun decodeUtf8Strict(bytes: Bytes, budget: ParseBudget): String {
    budget.retain(32uL)
    val result = StringBuilder()
    var position = 0
    while (position < bytes.size) {
        budget.poll()
        val point = utf8Point(bytes, position)
        appendPoint(result, point.code, budget)
        position += point.length
    }
    budget.poll()
    return result.toString()
}

private class JsonParser(private val bytes: Bytes, private val budget: ParseBudget) {
    var position: Int = 0
    init { budget.retain(bytes.size.toULong()) }
    fun root(): Value.ObjectValue {
        whitespace()
        return value(0u) as? Value.ObjectValue ?: malformedJson("JSON root must be an object")
    }
    fun whitespace() { while (position < bytes.size && isJsonWhitespace(byte())) { budget.poll(); position++ } }
    private fun byte(): Int = if (position < bytes.size) bytes[position].toInt() and 255 else -1
    private fun take(expected: Int) { if (byte() != expected) malformedJson("Unexpected JSON token"); position++ }
    private fun value(depth: UInt): Value {
        budget.item(depth); budget.retain(96uL); whitespace()
        return when (byte()) {
            0x7b -> objectValue(depth)
            0x5b -> arrayValue(depth)
            0x22 -> Value.Text(string())
            0x74 -> { keyword("true"); Value.BooleanValue(true) }
            0x66 -> { keyword("false"); Value.BooleanValue(false) }
            0x6e -> { keyword("null"); Value.Null }
            0x2d, in 0x30..0x39 -> number()
            else -> malformedJson("JSON value is missing or malformed")
        }
    }
    private fun objectValue(depth: UInt): Value.ObjectValue {
        take(0x7b); whitespace()
        val fields = linkedMapOf<String, Value>()
        if (byte() == 0x7d) { position++; return Value.ObjectValue(fields.toMap()) }
        while (true) {
            budget.item(depth); budget.retain(96uL)
            if (byte() != 0x22) malformedJson("JSON object key must be a string")
            val key = string()
            if (key in fields) fail("CONFLICTING_METADATA", "JSON object contains a duplicate semantic key")
            whitespace(); take(0x3a)
            fields[key] = value(depth + 1u)
            whitespace()
            if (byte() == 0x7d) { position++; return Value.ObjectValue(fields.toMap()) }
            take(0x2c); whitespace()
        }
    }
    private fun arrayValue(depth: UInt): Value.ArrayValue {
        take(0x5b); whitespace()
        val values = mutableListOf<Value>()
        if (byte() == 0x5d) { position++; return Value.ArrayValue(frozenList(values)) }
        while (true) {
            values.add(value(depth + 1u)); whitespace()
            if (byte() == 0x5d) { position++; return Value.ArrayValue(frozenList(values)) }
            take(0x2c); whitespace()
        }
    }
    private fun string(): String {
        take(0x22); budget.retain(32uL)
        val result = StringBuilder()
        while (position < bytes.size) {
            budget.poll()
            val next = byte()
            if (next == 0x22) { position++; return result.toString() }
            if (next < 0x20) malformedJson("JSON string contains an unescaped control byte")
            if (next == 0x5c) {
                position++
                val escaped = byte(); position++
                val code = when (escaped) {
                    0x22,0x5c,0x2f -> escaped
                    0x62 -> 8; 0x66 -> 12; 0x6e -> 10; 0x72 -> 13; 0x74 -> 9
                    0x75 -> {
                        val high = hexUnit()
                        when (high) {
                            in 0xd800..0xdbff -> {
                                take(0x5c); take(0x75)
                                val low = hexUnit()
                                if (low !in 0xdc00..0xdfff) malformedJson("JSON surrogate pair is malformed")
                                0x10000 + ((high - 0xd800) shl 10) + low - 0xdc00
                            }
                            in 0xdc00..0xdfff -> malformedJson("JSON string has an unpaired low surrogate")
                            else -> high
                        }
                    }
                    else -> malformedJson("JSON string escape is invalid or truncated")
                }
                appendPoint(result, code, budget)
            } else {
                val point = utf8Point(bytes, position)
                appendPoint(result, point.code, budget); position += point.length
            }
        }
        malformedJson("JSON string is unterminated")
    }
    private fun hexUnit(): Int {
        var result = 0
        repeat(4) {
            val digit = when (val next = byte()) { in 0x30..0x39 -> next - 0x30; in 0x41..0x46 -> next - 0x41 + 10; in 0x61..0x66 -> next - 0x61 + 10; else -> malformedJson("JSON Unicode escape is truncated or malformed") }
            result = (result shl 4) or digit; position++
        }
        return result
    }
    private fun keyword(expected: String) { for (char in expected) { budget.poll(); take(char.code) } }
    private fun number(): Value.Number {
        val start = position
        if (byte() == 0x2d) position++
        if (byte() == 0x30) position++ else {
            if (byte() !in 0x31..0x39) malformedJson("JSON number integer part is malformed")
            digits()
        }
        if (byte() == 0x2e) { position++; if (byte() !in 0x30..0x39) malformedJson("JSON number fraction is empty"); digits() }
        if (byte() == 0x65 || byte() == 0x45) {
            position++; if (byte() == 0x2b || byte() == 0x2d) position++
            if (byte() !in 0x30..0x39) malformedJson("JSON number exponent is empty")
            digits()
        }
        val count = position - start
        budget.retain(checkedMultiply(count.toULong(), 2uL))
        val text = buildString(count) { for (index in start until position) { budget.poll(); append((bytes[index].toInt() and 255).toChar()) } }
        return Value.Number(text)
    }
    private fun digits() { while (byte() in 0x30..0x39) { budget.poll(); position++ } }
}

private data class Utf8Point(val code: Int, val length: Int)
private fun utf8Point(bytes: Bytes, offset: Int): Utf8Point {
    val first = bytes[offset].toInt() and 255
    if (first < 128) return Utf8Point(first, 1)
    val length = when (first) { in 0xc2..0xdf -> 2; in 0xe0..0xef -> 3; in 0xf0..0xf4 -> 4; else -> malformedJson("UTF-8 leading byte is invalid") }
    if (length > bytes.size - offset) malformedJson("UTF-8 sequence is truncated")
    var point = first and (0x7f shr length)
    for (index in 1 until length) {
        val next = bytes[offset + index].toInt() and 255
        if (next !in 0x80..0xbf) malformedJson("UTF-8 continuation byte is invalid")
        point = (point shl 6) or (next and 63)
    }
    if (point < when (length) { 2 -> 0x80; 3 -> 0x800; else -> 0x10000 } || point > 0x10ffff || point in 0xd800..0xdfff) malformedJson("UTF-8 scalar is overlong or invalid")
    return Utf8Point(point, length)
}
private fun appendPoint(builder: StringBuilder, code: Int, budget: ParseBudget) {
    budget.retain(if (code > 0xffff) 4uL else 2uL)
    if (code <= 0xffff) builder.append(code.toChar()) else { val point = code - 0x10000; builder.append((0xd800 + (point shr 10)).toChar()); builder.append((0xdc00 + (point and 1023)).toChar()) }
}
private fun malformedJson(message: String): Nothing = fail("CORRUPTED_CONTAINER", message)
internal fun isJsonWhitespace(byte: Int): Boolean = byte == 0x20 || byte == 9 || byte == 10 || byte == 13
