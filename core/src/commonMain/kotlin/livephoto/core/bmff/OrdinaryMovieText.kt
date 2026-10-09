package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

/** Closed iTunes / short-style QuickTime UTF-8 text; never a reference or Live Photo authority.
 * Shape and types follow FFmpeg movenc.c's string metadata. Raw bytes must still
 * be retained by the caller; classification alone does not authorize a remuxer to rewrite it.
 */
internal object OrdinaryMovieText {
    private val names = setOf("\u00a9nam", "\u00a9ART", "\u00a9cmt", "\u00a9too", "cprt")
    private val quickTimeNames = setOf("\u00a9nam", "\u00a9ART", "\u00a9des", "\u00a9cmt", "\u00a9swr", "\u00a9cpy")

    suspend fun matches(reader: BinaryReader, boxes: BmffReader, udta: BmffBox, depth: UInt,
        budget: ParseBudget): Boolean {
        if (udta.type != "udta" || udta.extendsToParentEnd) return false
        val children = boxes.readBoxes(udta.payload, depth + 1u).orThrow()
        if (children.none { it.type == "meta" }) {
            // Confirmed FFmpeg short style: u16 byte length + packed ISO-639 "und" + UTF-8.
            // Do not guess MacRoman, localized variants, nested data boxes or implicit strings.
            if (children.isEmpty() || children.size > quickTimeNames.size ||
                children.map { it.type }.distinct().size != children.size) return false
            for (child in children) {
                if (child.type !in quickTimeNames || child.extendsToParentEnd || child.payload.length !in 5uL..65_539uL) return false
                val length = reader.readU16(child.payload.offset).orThrow().toUInt()
                if (length.toULong() != child.payload.length - 4uL || reader.readU16(child.payload.offset + 2uL).orThrow() != 0x55c4u.toUShort()) return false
                budget.retain(length.toULong())
                if ('\u0000' in decodeUtf8Strict(reader.readExactly(child.payload.offset + 4uL, length).orThrow(), budget)) return false
            }
            return true
        }
        val meta = children.singleOrNull()
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
