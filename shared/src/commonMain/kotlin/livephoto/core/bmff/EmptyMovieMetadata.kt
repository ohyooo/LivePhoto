package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

/** This exact iTunes envelope has neither key declarations nor item values, hence no CID authority. */
internal object EmptyMovieMetadata {
    suspend fun matches(reader: BinaryReader, boxes: BmffReader, udta: BmffBox, depth: UInt): Boolean {
        if (udta.type != "udta") return false
        val meta = boxes.readBoxes(udta.payload, depth + 1u).orThrow().singleOrNull()
        if (meta?.type != "meta" || meta.payload.length < 4uL || reader.readU32(meta.payload.offset).orThrow() != 0u) return false
        val nodes = boxes.readBoxes(ByteRange(meta.payload.offset + 4uL, meta.payload.length - 4uL), depth + 2u).orThrow()
        val handler = nodes.singleOrNull { it.type == "hdlr" }
        val items = nodes.singleOrNull { it.type == "ilst" }
        val expected = Bytes(ByteArray(8) + "mdirappl".encodeToByteArray() + ByteArray(9))
        return nodes.size == 2 && items?.payload?.length == 0uL && handler != null && handler.payload.length == 25uL &&
            reader.readExactly(handler.payload.offset, 25u).orThrow() == expected
    }
}
