package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

/** Only the finite AAC-LC one-previous-sample recovery graph; all bytes remain metadata proof inputs. */
internal object RemuxRollGroups {
    /** Independently bind the known all-sample recovery assignment to each audio track's sample count. */
    suspend fun canonical(reader: BinaryReader, video: VideoStructure, code: String = "CAPABILITY_UNSUPPORTED") {
        if (video.tracks.none { it.handler == "soun" }) return
        val boxes = BmffReader(reader)
        suspend fun children(box: BmffBox) = boxes.readBoxes(box.payload).orThrow()
        fun one(children: List<BmffBox>, type: String): BmffBox = children.singleOrNull { it.type == type }
            ?: fail(code, "AAC trim requires a unique classified recovery path", Stage.Plan)
        val moov = one(boxes.readBoxes(video.range).orThrow(), "moov")
        val tracks = children(moov).filter { it.type == "trak" }
        if (tracks.size != video.tracks.size) fail(code, "AAC recovery track graph differs", Stage.Plan)
        for ((index, track) in video.tracks.withIndex()) {
            if (track.handler != "soun") continue
            val stbl = children(one(children(one(children(one(children(tracks[index]), "mdia")), "minf")), "stbl"))
            val description = one(stbl, "sgpd")
            val mapping = one(stbl, "sbgp")
            val expectedDescription = Bytes(byteArrayOf(1, 0, 0, 0) + "roll".encodeToByteArray() +
                unsignedBytes(2uL, 4, Endian.Big).toByteArray() + unsignedBytes(1uL, 4, Endian.Big).toByteArray() + byteArrayOf(-1, -1))
            val expectedMapping = Bytes(ByteArray(4) + "roll".encodeToByteArray() + unsignedBytes(1uL, 4, Endian.Big).toByteArray() +
                unsignedBytes(track.samples.size.toULong(), 4, Endian.Big).toByteArray() + unsignedBytes(1uL, 4, Endian.Big).toByteArray())
            if (description.payload.length != expectedDescription.size.toULong() || mapping.payload.length != expectedMapping.size.toULong() ||
                reader.readExactly(description.payload.offset, expectedDescription.size.toUInt()).orThrow() != expectedDescription ||
                reader.readExactly(mapping.payload.offset, expectedMapping.size.toUInt()).orThrow() != expectedMapping)
                fail(code, "AAC trim requires the canonical one-sample recovery assignment, independently bound to every retained sample", Stage.Plan)
        }
        reader.validateIdentity().orThrow()
    }

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
