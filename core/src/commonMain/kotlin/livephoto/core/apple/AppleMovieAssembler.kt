package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** Offset-preserving append assembly: retire only the old moov, copy its fields into a new moov. */
internal class AppleMoviePlan internal constructor(
    val reader: BinaryReader, val media: VideoStructure, val originalMovie: BmffBox,
    val movie: Bytes, val timedMedia: Bytes, val identifier: String, val key: Time,
) {
    val byteLength: ULong = checkedAdd(media.range.length, checkedAdd(movie.size.toULong(), timedMedia.size.toULong()))
    suspend fun write(writer: BinaryWriter) {
        copyRange(reader, writer, ByteRange(0uL, originalMovie.range.offset), reader.context).orThrow()
        val header = reader.readExactly(originalMovie.range.offset, originalMovie.headerLength.toUInt()).orThrow().toByteArray()
        "free".encodeToByteArray().copyInto(header, 4)
        writer.writeAll(Bytes(header)).orThrow()
        var remaining = originalMovie.payload.length
        while (remaining > 0uL) {
            val count = minOf(65_536uL, remaining).toInt()
            writer.writeAll(Bytes(ByteArray(count))).orThrow(); remaining -= count.toULong()
        }
        copyRange(reader, writer, ByteRange(originalMovie.range.endExclusive, media.range.endExclusive - originalMovie.range.endExclusive), reader.context).orThrow()
        writer.writeAll(movie).orThrow(); writer.writeAll(timedMedia).orThrow()
        reader.validateIdentity().orThrow()
    }

    suspend fun verify(output: BinaryReader) {
        if (output.identity().orThrow().size != byteLength) fail("POSTCONDITION_FAILED", "Apple movie size differs from its plan", Stage.Verify)
        val parsed = BmffVideoProbe(output, allowTimedMetadata = true).probe(ByteRange(0uL, byteLength)).orThrow()
        if (parsed.container != media.container || parsed.movieTimescale != media.movieTimescale || parsed.movieDuration != media.movieDuration ||
            parsed.tracks.filter { it.handler != "meta" } != media.tracks || parsed.tracks.count { it.handler == "meta" } != 1)
            fail("POSTCONDITION_FAILED", "Apple assembly changed retained track/configuration/sample/timeline semantics", Stage.Verify)
        for (range in listOf(ByteRange(0uL, originalMovie.range.offset), ByteRange(originalMovie.range.endExclusive, media.range.endExclusive - originalMovie.range.endExclusive)))
            if (sha256Range(reader, range).orThrow() != sha256Range(output, range).orThrow()) fail("POSTCONDITION_FAILED", "Apple assembly changed original media/container bytes", Stage.Verify)
        val expected = reader.readExactly(originalMovie.range.offset, originalMovie.headerLength.toUInt()).orThrow().toByteArray()
        "free".encodeToByteArray().copyInto(expected, 4)
        if (output.readExactly(originalMovie.range.offset, originalMovie.headerLength.toUInt()).orThrow() != Bytes(expected))
            fail("POSTCONDITION_FAILED", "Old movie header retirement differs from the plan", Stage.Verify)
        var cursor = originalMovie.payload.offset
        while (cursor < originalMovie.payload.endExclusive) {
            val bytes = output.readBuffer(cursor, minOf(65_536uL, originalMovie.payload.endExclusive - cursor).toUInt()).orThrow()
            if (bytes.toByteArray().any { it != 0.toByte() }) fail("POSTCONDITION_FAILED", "Old movie authority was not retired", Stage.Verify)
            cursor += bytes.size.toULong()
        }
        if (output.readExactly(media.range.length, movie.size.toUInt()).orThrow() != movie ||
            output.readExactly(media.range.length + movie.size.toULong(), timedMedia.size.toUInt()).orThrow() != timedMedia)
            fail("POSTCONDITION_FAILED", "Apple appended metadata differs from the exact assembly plan", Stage.Verify)
        if (AppleVideoReader.read(output, ParseBudget(output.context)).orThrow()?.value != identifier ||
            AppleVideoReader.key(output, parsed, ParseBudget(output.context)).orThrow().position?.compareTo(key) != 0)
            fail("POSTCONDITION_FAILED", "Apple CID or metadata-sample PTS failed independent readback", Stage.Verify)
        reader.validateIdentity().orThrow(); output.validateIdentity().orThrow()
    }
}

internal object AppleMovieAssembler {
    suspend fun prepare(reader: BinaryReader, identifier: String, key: Time, budget: ParseBudget): CoreResult<AppleMoviePlan> = attempt {
        if (!appleUuid(identifier)) fail("INVALID_PAIR_IDENTIFIER", "Apple output requires a UUID", Stage.Plan)
        val size = reader.identity().orThrow().size
        val media = BmffVideoProbe(reader, budget).probe(ByteRange(0uL, size)).orThrow()
        if (media.tracks.count { it.handler == "vide" } != 1 || media.tracks.any { it.handler !in setOf("vide", "soun") })
            fail("CAPABILITY_UNSUPPORTED", "Apple assembly requires a classified video/audio-only movie", Stage.Plan)
        RemuxVerification.metadata(reader, media) // No unknown boxes, external references or hidden media.
        if (key < Time.Zero || key >= media.tracks.single { it.handler == "vide" }.presentationDuration)
            fail("INVALID_PRESENTATION_TIMESTAMP", "Apple key must lie inside the presented video", Stage.Plan)
        val multiplied = checkedMultiply(key.value.toULong(), media.movieTimescale.toULong())
        if (multiplied % key.timescale.toULong() != 0uL) fail("CAPABILITY_UNSUPPORTED", "Apple assembler does not round key times into movie ticks", Stage.Plan)
        val delay = multiplied / key.timescale.toULong()
        if (delay >= UInt.MAX_VALUE.toULong() || delay + 1uL > media.movieDuration) fail("CAPABILITY_UNSUPPORTED", "Apple metadata edit exceeds its exact finite tick domain", Stage.Plan)
        val nextId = media.tracks.maxOf { it.trackId }.toULong() + 1uL
        if (nextId >= UInt.MAX_VALUE.toULong()) fail("INTEGER_OVERFLOW", "Apple metadata track identifiers overflow", Stage.Plan)
        val boxes = BmffReader(reader, budget)
        val roots = boxes.readBoxes(media.range).orThrow()
        if (roots.any { reader.readU32(it.range.offset).orThrow() == 0u }) fail("CAPABILITY_UNSUPPORTED", "Size-to-EOF boxes cannot be followed by Apple append assembly", Stage.Plan)
        val originalMovie = roots.single { it.type == "moov" }
        val children = boxes.readBoxes(originalMovie.payload, 1u).orThrow()
        budget.retain(checkedMultiply(originalMovie.range.length + 2048uL, 4uL))
        val originalPayload = reader.readExactly(originalMovie.payload.offset, checkedInt(originalMovie.payload.length).toUInt()).orThrow().toByteArray()
        val mvhd = children.single { it.type == "mvhd" }
        val version = reader.readBuffer(mvhd.payload.offset, 1u).orThrow()[0].toInt()
        val nextOffset = if (version == 0) 96uL else 108uL
        if (mvhd.payload.length != nextOffset + 4uL) fail("CAPABILITY_UNSUPPORTED", "Noncanonical movie header cannot be rewritten", Stage.Plan)
        u32(nextId + 1uL).copyInto(originalPayload, checkedInt(mvhd.payload.offset - originalMovie.payload.offset + nextOffset))
        val sample = atom(1uL, byteArrayOf(0)) // Marker value is NOT its time: PTS comes from the edit mapping.
        val metadata = box("meta", full("hdlr", u32(0uL) + "mdta".encodeToByteArray() + ByteArray(12)) +
            full("keys", u32(1uL) + box("mdta", APPLE_CID.encodeToByteArray())) +
            box("ilst", atom(1uL, box("data", u32(1uL) + u32(0uL) + identifier.encodeToByteArray()))))
        fun track(offset: ULong): ByteArray {
            val header = ByteArray(8).also { it[7] = 1 }
            val stbl = box("stbl", full("stsd", u32(1uL) + box("mebx", header + box("keys", atom(1uL,
                box("keyd", "mdta$APPLE_STILL_TIME".encodeToByteArray()) + box("dtyp", u32(0uL) + u32(65uL)))))) +
                full("stts", u32(1uL) + u32(1uL) + u32(1uL)) + full("stsc", u32(1uL) + u32(1uL) + u32(1uL) + u32(1uL)) +
                full("stsz", u32(sample.size.toULong()) + u32(1uL)) + full("co64", u32(1uL) + unsignedBytes(offset, 8, Endian.Big).toByteArray()))
            val minf = box("minf", box("gmhd", full("gmin", ByteArray(12))) +
                box("dinf", full("dref", u32(1uL) + box("alis", u32(1uL)))) + stbl)
            val tkhd = ByteArray(84).also { u32(nextId).copyInto(it, 12); u32(delay + 1uL).copyInto(it, 20); matrix(it, 40) }
            val mdhd = ByteArray(24).also { u32(media.movieTimescale.toULong()).copyInto(it, 12); u32(1uL).copyInto(it, 16) }
            val edits = box("edts", full("elst", u32(if (delay == 0uL) 1uL else 2uL) +
                (if (delay == 0uL) byteArrayOf() else u32(delay) + u32(UInt.MAX_VALUE.toULong()) + u32(0x10000uL)) +
                u32(1uL) + u32(0uL) + u32(0x10000uL)))
            return box("trak", box("tkhd", tkhd) + edits + box("mdia", box("mdhd", mdhd) +
                full("hdlr", u32(0uL) + "meta".encodeToByteArray() + ByteArray(12)) + minf))
        }
        val prototype = box("moov", originalPayload + track(0uL) + metadata)
        val offset = checkedAdd(size, checkedAdd(prototype.size.toULong(), 8uL))
        val movie = Bytes(box("moov", originalPayload + track(offset) + metadata))
        if (movie.size != prototype.size) fail("POSTCONDITION_FAILED", "Apple movie offset fixup changed its planned length", Stage.Plan)
        val plan = AppleMoviePlan(reader, media, originalMovie, movie, Bytes(box("mdat", sample)), identifier, key)
        if (plan.byteLength > reader.context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Apple output exceeds the configured byte budget", Stage.Plan)
        reader.validateIdentity().orThrow()
        plan
    }

    private fun u32(value: ULong) = unsignedBytes(value, 4, Endian.Big).toByteArray()
    private fun atom(index: ULong, payload: ByteArray): ByteArray = u32(checkedAdd(payload.size.toULong(), 8uL)) + u32(index) + payload
    private fun box(type: String, payload: ByteArray): ByteArray = u32(checkedAdd(payload.size.toULong(), 8uL)) + type.encodeToByteArray() + payload
    private fun full(type: String, payload: ByteArray): ByteArray = box(type, ByteArray(4) + payload)
    private fun matrix(bytes: ByteArray, offset: Int) { u32(0x10000uL).copyInto(bytes, offset); u32(0x10000uL).copyInto(bytes, offset + 16); u32(0x40000000uL).copyInto(bytes, offset + 32) }
}
