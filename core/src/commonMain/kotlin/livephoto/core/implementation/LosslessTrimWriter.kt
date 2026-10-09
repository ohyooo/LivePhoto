package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** Bounded structural sample selection, not a decoder, encoder, or general movie muxer.
 * Rebuilds only classified indexing/duration fields; ordinary leaf payloads remain exact.
 * The caller must independently reread the result before staging/publication. */
internal object LosslessTrimWriter {
    suspend fun write(reader: BinaryReader, spec: TrimSpec, sink: BinarySink): CoreResult<LosslessTrimPlan> = attempt {
        val context = reader.context
        val size = reader.identity().orThrow().size
        if (size > 8_000_000uL) fail("RESOURCE_LIMIT_EXCEEDED", "Structural trim exceeds its finite input budget", Stage.Plan)
        val video = BmffVideoProbe(reader).probe(ByteRange(0uL, size)).orThrow()
        if (video.container != VideoContainer.Mp4 || video.tracks.size != 1 || video.tracks.single().samples.size !in 1..64)
            fail("CAPABILITY_UNSUPPORTED", "Structural trim requires one finite MP4 track", Stage.Plan)
        val plan = planLosslessTrim(reader, video, spec)
        RemuxVerification.metadata(reader, video, trimDurationsVerifiedSeparately = true)
        val expected = plan.expected()
        val boxes = BmffReader(reader)
        val root = boxes.readBoxes(video.range).orThrow()
        val moov = root.single { it.type == "moov" }
        val ftyp = root.single { it.type == "ftyp" }
        // Bound all retained/rebuilt header allocations, including nested concatenations.
        val headerLimit = minOf(65_536uL, context.limits.maxMetadataBytes / 16uL)
        if (moov.range.length + ftyp.range.length + 4096uL > headerLimit)
            fail("RESOURCE_LIMIT_EXCEEDED", "Structural trim header budget is insufficient", Stage.Plan)
        fun number(value: ULong, width: Int = 4) = unsignedBytes(value, width, Endian.Big).toByteArray()
        fun box(type: String, payload: ByteArray) = number(payload.size.toULong() + 8uL) + type.encodeToByteArray() + payload
        fun table(type: String, payload: ByteArray) = box(type, ByteArray(4) + payload)
        suspend fun raw(box: BmffBox) = reader.readExactly(box.payload.offset, box.payload.length.toUInt()).orThrow().toByteArray()
        val samples = plan.samples
        val payloadSize = samples.fold(0uL) { sum, sample -> checkedAdd(sum, sample.range.length) }
        suspend fun rebuild(parent: BmffBox, depth: UInt, chunkOffset: ULong): ByteArray {
            checkCancelled(context)
            var payload = byteArrayOf()
            for (child in boxes.readBoxes(parent.payload, depth).orThrow()) {
                checkCancelled(context)
                val result = when (child.type) {
                    "trak", "mdia", "minf", "stbl" -> rebuild(child, depth + 1u, chunkOffset)
                    "edts" -> byteArrayOf() // Planner proved identity; output has no hidden content.
                    "sgpd", "sbgp" -> fail("CAPABILITY_UNSUPPORTED", "Trim sample-group rebinding is not implemented", Stage.Plan)
                    "mvhd", "tkhd", "mdhd" -> {
                        val bytes = raw(child)
                        val version = bytes[0].toInt() and 255
                        val position = if (child.type == "tkhd") (if (version == 0) 20 else 28) else (if (version == 0) 16 else 24)
                        val duration = if (child.type == "mdhd") plan.durationTicks else expected.movieDuration
                        number(duration, if (version == 0) 4 else 8).copyInto(bytes, position)
                        box(child.type, bytes)
                    }
                    "stts" -> table("stts", number(samples.size.toULong()) + samples.fold(byteArrayOf()) { bytes, sample -> bytes + number(1uL) + number(sample.duration.toULong()) })
                    "ctts" -> table("ctts", number(1uL) + number(samples.size.toULong()) + number(0uL))
                    "stsc" -> table("stsc", number(1uL) + number(1uL) + number(samples.size.toULong()) + number(1uL))
                    "stsz" -> table("stsz", number(0uL) + number(samples.size.toULong()) + samples.fold(byteArrayOf()) { bytes, sample -> bytes + number(sample.range.length) })
                    "stco", "co64" -> table(child.type, number(1uL) + number(chunkOffset, if (child.type == "co64") 8 else 4))
                    "stss" -> {
                        val sync = samples.withIndex().filter { it.value.isSync }
                        table("stss", number(sync.size.toULong()) + sync.fold(byteArrayOf()) { bytes, sample -> bytes + number(sample.index.toULong() + 1uL) })
                    }
                    "sdtp" -> table("sdtp", samples.map { it.dependencyByte!!.toByte() }.toByteArray())
                    else -> box(child.type, raw(child))
                }
                payload += result
                if (payload.size.toULong() > headerLimit) fail("RESOURCE_LIMIT_EXCEEDED", "Rebuilt trim headers exceed their budget", Stage.Plan)
            }
            return box(parent.type, payload)
        }
        val fileType = box("ftyp", raw(ftyp))
        val provisional = rebuild(moov, 1u, 0uL)
        val chunkOffset = fileType.size.toULong() + provisional.size.toULong() + 8uL
        val header = rebuild(moov, 1u, chunkOffset)
        if (header.size != provisional.size) fail("POSTCONDITION_FAILED", "Trim offset relocation changed header size", Stage.Verify)
        val outputSize = checkedAdd(chunkOffset, payloadSize)
        val writer = BinaryWriter(sink, context)
        writer.budget.checkCapacity(outputSize)
        val sourceHash = sha256Range(reader, video.range).orThrow()
        writer.writeAll(Bytes(fileType)).orThrow(); writer.writeAll(Bytes(header)).orThrow()
        writer.writeAll(Bytes(number(payloadSize + 8uL) + "mdat".encodeToByteArray())).orThrow()
        for (sample in samples) copyRange(reader, writer, sample.range, context).orThrow()
        if (sourceHash != sha256Range(reader, video.range).orThrow()) fail("SOURCE_CHANGED", "Trim source changed while selecting samples", Stage.Verify)
        reader.validateIdentity().orThrow()
        plan
    }
}
