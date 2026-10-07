package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

/** Only the finite AAC-LC one-previous-sample recovery graph; all bytes remain metadata proof inputs. */
internal object RemuxRollGroups {
    suspend fun validate(reader: BinaryReader, boxes: BmffReader, children: List<BmffBox>, depth: UInt) {
        val groups = children.filter { it.type in setOf("sgpd", "sbgp") }
        if (groups.isEmpty()) return
        fun unsupported(): Nothing = fail("UNSAFE_METADATA_REWRITE", "Unclassified sample recovery group cannot be remuxed", Stage.Plan)
        val description = groups.singleOrNull { it.type == "sgpd" } ?: unsupported()
        val mapping = groups.singleOrNull { it.type == "sbgp" } ?: unsupported()
        val stsd = children.singleOrNull { it.type == "stsd" } ?: unsupported()
        if (stsd.payload.length < 8uL || reader.readU32(stsd.payload.offset).orThrow() != 0u || reader.readU32(stsd.payload.offset + 4uL).orThrow() != 1u) unsupported()
        val entry = boxes.readBoxes(ByteRange(stsd.payload.offset + 8uL, stsd.payload.length - 8uL), depth + 1u).orThrow().singleOrNull()
        if (entry?.type != "mp4a") unsupported()
        val expectedDescription = Bytes(byteArrayOf(1, 0, 0, 0) + "roll".encodeToByteArray() +
            unsignedBytes(2uL, 4, Endian.Big).toByteArray() + unsignedBytes(1uL, 4, Endian.Big).toByteArray() + byteArrayOf(-1, -1))
        if (description.payload.length != 18uL || reader.readExactly(description.payload.offset, 18u).orThrow() != expectedDescription) unsupported()
        val stsz = children.singleOrNull { it.type == "stsz" } ?: unsupported()
        if (stsz.payload.length < 12uL) unsupported()
        val sampleCount = reader.readU32(stsz.payload.offset + 8uL).orThrow()
        if (mapping.payload.length !in setOf(20uL, 28uL)) unsupported()
        val raw = reader.readExactly(mapping.payload.offset, mapping.payload.length.toUInt()).orThrow()
        if (raw.slice(0, 8) != Bytes(ByteArray(4) + "roll".encodeToByteArray())) unsupported()
        val runs = readUnsigned(raw.slice(8, 12), Endian.Big)
        if (runs !in 1uL..2uL || mapping.payload.length != 12uL + runs * 8uL) unsupported()
        var count = 0uL
        for (index in 0 until runs.toInt()) {
            checkCancelled(reader.context)
            val start = 12 + index * 8
            val length = readUnsigned(raw.slice(start, start + 4), Endian.Big)
            val group = readUnsigned(raw.slice(start + 4, start + 8), Endian.Big)
            if (length == 0uL || group != (if (runs == 2uL && index == 0) 0uL else 1uL) || runs == 2uL && index == 0 && length != 1uL) unsupported()
            count = checkedAdd(count, length)
        }
        if (count != sampleCount.toULong()) unsupported()
        reader.validateIdentity().orThrow()
    }
}
