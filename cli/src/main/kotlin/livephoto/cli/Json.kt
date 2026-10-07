package livephoto.cli

import livephoto.core.*
import livephoto.core.jvm.DirectoryOutputTransaction
import java.lang.reflect.Modifier

/** CLI-only DTO formatting. No protocol interpretation, handles, or generation tokens are serialized. */
internal object Json {
    fun encode(value: Any?, output: DirectoryOutputTransaction? = null): String = when (value) {
        null, Value.Null -> "null"
        is String -> quote(value)
        is Long, is ULong -> quote(value.toString())
        is Boolean, is Number, is UInt -> value.toString()
        is Enum<*> -> quote(value.name)
        is Value.Text -> quote(value.value)
        is Value.Number -> value.decimal
        is Value.BooleanValue -> value.value.toString()
        is Value.ArrayValue -> encode(value.values, output)
        is Value.ObjectValue -> encode(value.entries, output)
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + encode(it.value, output) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it, output) }
        is BinarySource, is GenerationToken -> quote("redacted")
        is Bytes -> encode(mapOf("byteLength" to value.size))
        is Time -> encode(mapOf("value" to value.value, "timescale" to value.timescale))
        is CoverPosition.Timestamp -> encode(mapOf("kind" to "Timestamp", "time" to value.time,
            "selection" to value.selection, "tolerance" to value.tolerance), output)
        is CoverPosition.FrameIndex -> encode(mapOf("kind" to "FrameIndex", "index" to value.index,
            "trackId" to value.trackId), output)
        is ByteRange -> encode(mapOf("offset" to value.offset, "length" to value.length))
        is SourceIdentity -> encode(mapOf("size" to value.size, "digest" to value.digest), output)
        is Snapshot -> encode(mapOf("sources" to value.identities), output)
        is Receipt -> encode(mapOf("state" to value.state, "assetIds" to value.assetIds, "atomicity" to value.atomicity, "durability" to value.durability), output)
        is CoreResult.Success<*> -> encode(mapOf("result" to value.value), output)
        is CoreResult.Failure -> encode(mapOf("error" to value.error), output)
        is OutputAsset -> encode(mapOf("id" to value.id, "role" to value.role, "mime" to value.mime,
            "imageFormat" to value.imageFormat, "videoContainer" to value.videoContainer,
            "byteLength" to value.byteLength, "digest" to value.digest, "path" to output?.assetPath(value.id)?.toString()), output)
        else -> {
            require(value.javaClass.packageName == "livephoto.core") { "Not a Core result DTO" }
            val fields = value.javaClass.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            encode(fields.associate { field -> field.isAccessible = true; field.name to field.get(value) }, output)
        }
    }
    private fun quote(text: String): String = buildString {
        append('"')
        for (char in text) when (char) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (char.code < 32 || char.isSurrogate()) append("\\u${char.code.toString(16).padStart(4, '0')}") else append(char)
        }
        append('"')
    }
}
