package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

/** Closed iTunes UTF-8 text envelope; never a keys table, reference or Live Photo authority.
 * Shape and types follow FFmpeg movenc.c's long-style string metadata. Raw bytes must still
 * be retained by the caller; classification alone does not authorize a remuxer to rewrite it.
 */
internal object OrdinaryMovieText {
    private val names = setOf("\u00a9nam", "\u00a9ART", "\u00a9cmt", "\u00a9too", "cprt")

    suspend fun matches(reader: BinaryReader, boxes: BmffReader, udta: BmffBox, depth: UInt,
        budget: ParseBudget): Boolean {
        if (udta.type != "udta" || udta.extendsToParentEnd) return false
        val meta = boxes.readBoxes(udta.payload, depth + 1u).orThrow().singleOrNull()
        if (meta?.type != "meta" || meta.extendsToParentEnd || meta.payload.length < 4uL ||
            reader.readU32(meta.payload.offset).orThrow() != 0u) return false
        val nodes = boxes.readBoxes(ByteRange(meta.payload.offset + 4uL, meta.payload.length - 4uL), depth + 2u).orThrow()
        val handler = nodes.singleOrNull { it.type == "hdlr" }
        val items = nodes.singleOrNull { it.type == "ilst" }
        if (nodes.size != 2 || nodes.any { it.extendsToParentEnd } || handler == null || items == null ||
            handler.payload.length != 25uL || reader.readExactly(handler.payload.offset, 25u).orThrow() !=
            Bytes(ByteArray(8) + "mdirappl".encodeToByteArray() + ByteArray(9))) return false
        val entries = boxes.readBoxes(items.payload, depth + 3u).orThrow()
        if (entries.isEmpty() || entries.size > names.size || entries.map { it.type }.distinct().size != entries.size) return false
        for (entry in entries) {
            if (entry.type !in names || entry.extendsToParentEnd) return false
            val data = boxes.readBoxes(entry.payload, depth + 4u).orThrow().singleOrNull()
            if (data?.type != "data" || data.extendsToParentEnd || data.payload.length !in 9uL..65_544uL ||
                reader.readU32(data.payload.offset).orThrow() != 1u ||
                reader.readU32(data.payload.offset + 4uL).orThrow() != 0u) return false
            val length = data.payload.length - 8uL
            budget.retain(length)
            val text = decodeUtf8Strict(reader.readExactly(data.payload.offset + 8uL, length.toUInt()).orThrow(), budget)
            if ('\u0000' in text) return false
        }
        return true
    }
}
