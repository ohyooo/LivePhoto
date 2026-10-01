package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

internal data class TimedMetadataKey(val namespace: String, val name: String, val typeNamespace: UInt, val type: UInt)

/** QTFF mebx keys are atoms keyed by local IDs, distinct from movie-level mdta keys tables. */
internal object TimedMetadata {
    suspend fun readKeys(reader: BinaryReader, parent: ByteRange, budget: ParseBudget): CoreResult<Map<UInt, TimedMetadataKey>> = attempt {
        val parser = BmffReader(reader, budget)
        val children = parser.readBoxes(parent, 8u).orThrow()
        val tables = children.filter { it.type == "keys" }
        if (tables.size != 1) fail("CONFLICTING_METADATA", "Timed sample description requires one key table")
        val keys = linkedMapOf<UInt, TimedMetadataKey>()
        for (entry in parser.readBoxes(tables.single().payload, 9u).orThrow()) {
            budget.item(9u)
            val id = boxTypeIndex(entry.type)
            if (id == 0u || id in keys) fail("CONFLICTING_METADATA", "Timed metadata key IDs must be unique and nonzero")
            val fields = parser.readBoxes(entry.payload, 10u).orThrow()
            val declaration = fields.singleOrNull { it.type == "keyd" } ?: fail("CONFLICTING_METADATA", "Timed metadata key must have one declaration")
            val dataType = fields.singleOrNull { it.type == "dtyp" } ?: fail("CAPABILITY_UNSUPPORTED", "Implicit timed metadata value types are not implemented")
            if (declaration.payload.length < 5uL || dataType.payload.length != 8uL) fail("CORRUPTED_CONTAINER", "Timed metadata key fields are malformed")
            if (fields.any { it.type !in setOf("keyd", "dtyp") }) fail("CAPABILITY_UNSUPPORTED", "Qualified timed metadata keys require an explicit reader")
            budget.retain(declaration.payload.length)
            val bytes = reader.readExactly(declaration.payload.offset, checkedInt(declaration.payload.length).toUInt()).orThrow()
            val name = decodeUtf8Strict(bytes.slice(4), budget)
            val key = TimedMetadataKey(fourCc(bytes, 0), name, reader.readU32(dataType.payload.offset).orThrow(), reader.readU32(dataType.payload.offset + 4uL).orThrow())
            if (keys.values.any { it.namespace == key.namespace && it.name == key.name }) fail("CONFLICTING_METADATA", "Duplicate timed metadata semantic keys")
            keys[id] = key
        }
        reader.validateIdentity().orThrow()
        keys.toMap()
    }
}

internal fun boxTypeIndex(type: String): UInt {
    var index = 0u
    for (char in type) index = (index shl 8) or char.code.toUInt()
    return index
}
