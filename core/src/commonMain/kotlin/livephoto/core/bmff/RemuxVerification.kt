package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

/** Conservative profile: ordinary/private metadata and unreferenced media bytes are never discarded. */
internal object RemuxVerification {
    data class Metadata(val digest: Digest, val fields: Map<String, Digest>)

    fun verifyMetadata(before: Metadata, after: Metadata) {
        if (before != after) {
            val changed = (before.fields.keys + after.fields.keys).sorted().firstOrNull { before.fields[it] != after.fields[it] }
            fail("POSTCONDITION_FAILED", "Remux changed classified metadata", Stage.Verify, Location(selector = changed))
        }
    }

    suspend fun metadata(reader: BinaryReader, video: VideoStructure, trimDurationsVerifiedSeparately: Boolean = false, transcodeAvcConfiguration: Boolean = false): Metadata {
        val budget = ParseBudget(reader.context)
        val boxes = BmffReader(reader, budget)
        val records = mutableListOf<Pair<String, Digest>>()
        val containers = mapOf("root" to setOf("ftyp", "moov", "mdat", "free", "wide"),
            "moov" to setOf("mvhd", "trak", "udta"), "trak" to setOf("tkhd", "edts", "mdia"),
            "edts" to setOf("elst"), "mdia" to setOf("mdhd", "hdlr", "minf"),
            "minf" to setOf("vmhd", "smhd", "hdlr", "dinf", "stbl"), "dinf" to setOf("dref"),
            "stbl" to setOf("stsd", "stts", "ctts", "stsc", "stsz", "stco", "co64", "stss", "sdtp", "sgpd", "sbgp"))
        suspend fun visit(parent: ByteRange, type: String, path: String, depth: UInt) {
            val children = boxes.readBoxes(parent, depth).orThrow()
            if (type == "stbl") RemuxRollGroups.validate(reader, boxes, children, depth)
            val counts = mutableMapOf<String, Int>()
            for (box in children) {
                checkCancelled(reader.context)
                if (box.type !in containers.getValue(type))
                    fail("UNSAFE_METADATA_REWRITE", "Remux cannot preserve this unclassified box", Stage.Plan, Location(selector = box.type))
                val index = counts.getOrElse(box.type) { 0 }; counts[box.type] = index + 1
                val key = "$path/${box.type}[$index]"
                when {
                    box.type == "udta" -> {
                        // FFmpeg writes a canonical empty iTunes metadata directory even in bitexact mode.
                        // Accept only this proven-empty envelope, never arbitrary ordinary metadata.
                        val meta = boxes.readBoxes(box.payload, depth + 1u).orThrow().singleOrNull()
                        if (meta?.type != "meta" || meta.payload.length < 4uL || reader.readU32(meta.payload.offset).orThrow() != 0u)
                            fail("UNSAFE_METADATA_REWRITE", "Nonempty or unclassified user metadata cannot be remuxed", Stage.Plan)
                        val nodes = boxes.readBoxes(ByteRange(meta.payload.offset + 4uL, meta.payload.length - 4uL), depth + 2u).orThrow()
                        val handler = nodes.singleOrNull { it.type == "hdlr" }
                        val items = nodes.singleOrNull { it.type == "ilst" }
                        val expectedHandler = Bytes(ByteArray(8) + "mdirappl".encodeToByteArray() + ByteArray(9))
                        if (nodes.size != 2 || items?.payload?.length != 0uL || handler == null || handler.payload.length != 25uL ||
                            reader.readExactly(handler.payload.offset, 25u).orThrow() != expectedHandler)
                            fail("UNSAFE_METADATA_REWRITE", "User metadata envelope is not provably empty", Stage.Plan)
                    }
                    box.type in containers -> visit(box.payload, box.type, key, depth + 1u)
                    box.type == "mdat" -> {
                        val samples = video.tracks.flatMap { it.samples }.map { it.range }.sortedBy { it.offset }
                        var cursor = box.payload.offset
                        for (sample in samples) {
                            if (sample.offset != cursor) fail("UNSAFE_METADATA_REWRITE", "Remux cannot discard unreferenced mdat bytes", Stage.Plan)
                            cursor = sample.endExclusive
                        }
                        if (cursor != box.payload.endExclusive) fail("UNSAFE_METADATA_REWRITE", "Remux cannot discard trailing mdat bytes", Stage.Plan)
                    }
                    box.type in setOf("free", "wide") -> {
                        var offset = box.payload.offset
                        while (offset < box.payload.endExclusive) {
                            val bytes = reader.readBuffer(offset, minOf(65_536uL, box.payload.endExclusive - offset).toUInt()).orThrow()
                            if (bytes.toByteArray().any { it != 0.toByte() }) fail("UNSAFE_METADATA_REWRITE", "Padding contains unclassified data", Stage.Plan)
                            offset += bytes.size.toULong()
                        }
                    }
                    box.type == "ftyp" -> {
                        val fileType = boxes.readFileType(box).orThrow()
                        val neutral = setOf("qt  ", "isom", "iso2", "mp41", "mp42", "avc1", "iso4", "iso5", "iso6")
                        if (fileType.majorBrand !in neutral || fileType.compatibleBrands.any { it !in neutral } || fileType.minorVersion !in setOf(0u, 512u))
                            fail("UNSAFE_METADATA_REWRITE", "Unclassified brand/version semantics cannot be discarded by remux", Stage.Plan)
                    }
                    box.type in setOf("elst", "stts", "ctts", "stsc", "stsz", "stco", "co64", "stss", "sdtp") -> Unit // independently verified sample/timeline semantics
                    box.type == "hdlr" -> {
                        budget.retain(box.payload.length)
                        val bytes = reader.readExactly(box.payload.offset, checkedInt(box.payload.length).toUInt()).orThrow()
                        if (type == "minf") {
                            val canonical = Bytes(ByteArray(4) + "dhlrurl ".encodeToByteArray() + ByteArray(12) + byteArrayOf(11) + "DataHandler".encodeToByteArray())
                            if (video.container != VideoContainer.Mov || bytes != canonical) fail("UNSAFE_METADATA_REWRITE", "Unclassified QuickTime data handler", Stage.Plan)
                        } else {
                            if (bytes.size < 25 || bytes.slice(0, 4) != Bytes(ByteArray(4)) || bytes.slice(12, 24) != Bytes(ByteArray(12)))
                                fail("UNSAFE_METADATA_REWRITE", "Unclassified media handler fields", Stage.Plan)
                            val name = when (bytes.slice(4, 8)) {
                                Bytes(ByteArray(4)) -> {
                                    if (bytes[bytes.size - 1] != 0.toByte()) fail("UNSAFE_METADATA_REWRITE", "Media handler name is not terminated", Stage.Plan)
                                    bytes.slice(24, bytes.size - 1)
                                }
                                Bytes("mhlr".encodeToByteArray()) -> {
                                    val length = bytes[24].toInt() and 255
                                    if (video.container != VideoContainer.Mov || bytes.size != 25 + length) fail("UNSAFE_METADATA_REWRITE", "Unclassified QuickTime handler name", Stage.Plan)
                                    bytes.slice(25)
                                }
                                else -> fail("UNSAFE_METADATA_REWRITE", "Unclassified handler component type", Stage.Plan)
                            }
                            records += key to Sha256().also { it.update(bytes.slice(8, 12)); it.update(name) }.finish()
                        }
                    }
                    box.type == "stsd" -> {
                        // All visual/audio entry fields and known extensions must survive byte-for-byte.
                        val entries = boxes.readBoxes(ByteRange(box.payload.offset + 8uL, box.payload.length - 8uL), depth + 1u).orThrow()
                        for (entry in entries) {
                            if (transcodeAvcConfiguration && entry.type != "avc1") fail("CAPABILITY_UNSUPPORTED", "Only AVC configuration rewrite has a classified transcode profile", Stage.Plan)
                            val prefix = if (entry.type in setOf("avc1", "avc3", "hvc1", "hev1")) 78uL else 28uL
                            if (entry.payload.length < prefix) fail("CORRUPTED_CONTAINER", "Truncated remux sample entry")
                            val extensions = boxes.readBoxes(ByteRange(entry.payload.offset + prefix, entry.payload.length - prefix), depth + 2u).orThrow()
                            if (extensions.any { it.type !in setOf("avcC", "hvcC", "esds", "colr", "pasp", "clap", "fiel", "mdcv", "clli", "btrt") })
                                fail("UNSAFE_METADATA_REWRITE", "Unclassified sample-entry extension cannot be remuxed", Stage.Plan)
                            val header = reader.readExactly(entry.payload.offset, prefix.toUInt()).orThrow().toByteArray()
                            if (prefix == 78uL) {
                                // ISO visual reserved fields versus FFmpeg's canonical QuickTime compressor defaults.
                                // Nondefault QuickTime vendor/quality annotations are not silently removed.
                                val defaults = if (video.container == VideoContainer.Mov) "FFMP".encodeToByteArray() +
                                    unsignedBytes(512uL, 4, Endian.Big).toByteArray() + unsignedBytes(512uL, 4, Endian.Big).toByteArray() else ByteArray(12)
                                if (!header.copyOfRange(12, 24).contentEquals(defaults)) fail("UNSAFE_METADATA_REWRITE", "Nondefault compressor annotations cannot be remuxed", Stage.Plan)
                                header.fill(0, 12, 24)
                            }
                            val hash = Sha256(); hash.update(Bytes(header))
                            for (extension in extensions.sortedBy { it.type }) {
                                if (transcodeAvcConfiguration && extension.type == "avcC") continue // explicitly requested encoded configuration rewrite, not ordinary metadata
                                hash.update(Bytes(extension.type.encodeToByteArray()))
                                hash.update(Bytes(sha256Range(reader, extension.payload).orThrow().value.encodeToByteArray()))
                            }
                            records += "$key/${entry.type}" to hash.finish()
                        }
                    }
                    box.type in setOf("mvhd", "tkhd", "mdhd") -> {
                        budget.retain(box.payload.length)
                        val bytes = reader.readExactly(box.payload.offset, checkedInt(box.payload.length).toUInt()).orThrow().toByteArray()
                        val version = bytes[0].toInt() and 255
                        if (version !in 0..1) fail("CAPABILITY_UNSUPPORTED", "Unsupported remux header version", Stage.Plan)
                        // Only these duration/timescale fields may change representation; exact rational checks follow.
                        val start = if (version == 0) (if (box.type == "tkhd") 20 else 12) else (if (box.type == "tkhd") 28 else 20)
                        val length = if (box.type == "tkhd") (if (version == 0) 4 else 8) else (if (version == 0) 8 else 12)
                        if (box.type == "tkhd" && !trimDurationsVerifiedSeparately) {
                            val duration = readUnsigned(Bytes(bytes.copyOfRange(start, start + length)), Endian.Big)
                            val scale = video.movieTimescale.toULong()
                            var a = duration; var b = scale
                            while (b != 0uL) { val next = a % b; a = b; b = next }
                            records += "$key/presentationDuration" to Sha256().also {
                                it.update(unsignedBytes(duration / a, 8, Endian.Big)); it.update(unsignedBytes(scale / a, 8, Endian.Big))
                            }.finish()
                        }
                        bytes.fill(0, start, start + length)
                        if (box.type == "mdhd" && video.container == VideoContainer.Mov) {
                            val language = if (version == 0) 20 else 32
                            // Canonical unspecified Mac language and ISO-639 "und" mean the same thing.
                            // Other language-code conversions are not guessed or ignored.
                            if ((bytes[language].toInt() and 255) == 0x7f && (bytes[language + 1].toInt() and 255) == 0xff) {
                                bytes[language] = 0x55; bytes[language + 1] = 0xc4.toByte()
                            }
                        }
                        records += key to Sha256().also { it.update(Bytes(bytes)) }.finish()
                    }
                    else -> records += key to sha256Range(reader, box.payload).orThrow()
                }
            }
        }
        visit(video.range, "root", "", 0u)
        val hash = Sha256()
        for ((path, digest) in records.sortedBy { it.first }) {
            val key = Bytes(path.encodeToByteArray())
            hash.update(unsignedBytes(key.size.toULong(), 8, Endian.Big)); hash.update(key)
            hash.update(Bytes(digest.value.encodeToByteArray()))
        }
        reader.validateIdentity().orThrow()
        return Metadata(hash.finish(), records.toMap())
    }

    suspend fun verify(input: BinaryReader, before: VideoStructure, output: BinaryReader, after: VideoStructure): Digest {
        fun unchanged(value: Boolean, field: String) {
            if (!value) fail("POSTCONDITION_FAILED", "Remux changed unrequested media semantics", Stage.Verify, Location(selector = field))
        }
        if (before.movieDuration > Long.MAX_VALUE.toULong() || after.movieDuration > Long.MAX_VALUE.toULong()) fail("INTEGER_OVERFLOW", "Movie duration exceeds exact remux verification domain")
        unchanged(Time(before.movieDuration.toLong(), before.movieTimescale).compareTo(Time(after.movieDuration.toLong(), after.movieTimescale)) == 0, "movieDuration")
        unchanged(before.tracks.map { it.trackId } == after.tracks.map { it.trackId }, "trackSet/order")
        val digest = Sha256()
        for ((left, right) in before.tracks.zip(after.tracks)) {
            checkCancelled(input.context)
            unchanged(left.handler == right.handler && left.codec == right.codec && left.audioCodec == right.audioCodec && left.sampleEntry == right.sampleEntry, "trackCodec")
            if (left.codecConfiguration != right.codecConfiguration) {
                val offset = (0 until minOf(left.codecConfiguration.size, right.codecConfiguration.size)).firstOrNull {
                    left.codecConfiguration[it] != right.codecConfiguration[it]
                }
                throw CoreFault(CoreError(IssueCode("POSTCONDITION_FAILED"), Stage.Verify, "Remux changed unrequested decoder configuration",
                    Location(selector = "decoderConfiguration"), details = mapOf("trackId" to Value.Number(left.trackId.toString()),
                        "beforeSize" to Value.Number(left.codecConfiguration.size.toString()), "afterSize" to Value.Number(right.codecConfiguration.size.toString())) +
                        (offset?.let { mapOf("firstDifference" to Value.Number(it.toString()),
                            "beforeByte" to Value.Number((left.codecConfiguration[it].toInt() and 255).toString()),
                            "afterByte" to Value.Number((right.codecConfiguration[it].toInt() and 255).toString())) } ?: emptyMap())))
            }
            unchanged(left.width == right.width && left.height == right.height && left.transform == right.transform && left.displayWidthFixed == right.displayWidthFixed && left.displayHeightFixed == right.displayHeightFixed, "displayTransform")
            unchanged(left.audioChannels == right.audioChannels && left.audioSampleRateFixed == right.audioSampleRateFixed && left.audioSampleSize == right.audioSampleSize, "audioConfiguration")
            unchanged(left.presentationDuration.compareTo(right.presentationDuration) == 0, "presentationDuration")
            unchanged(Time(left.duration.toLong(), left.timescale).compareTo(Time(right.duration.toLong(), right.timescale)) == 0, "decodeDuration")
            val le = left.edit; val re = right.edit
            unchanged(Time(le?.emptyDuration?.toLong() ?: 0L, le?.movieTimescale ?: 1u).compareTo(Time(re?.emptyDuration?.toLong() ?: 0L, re?.movieTimescale ?: 1u)) == 0, "editEmptyDuration")
            unchanged(Time(le?.mediaStart ?: 0L, left.timescale).compareTo(Time(re?.mediaStart ?: 0L, right.timescale)) == 0, "editMediaStart")
            unchanged(Time(le?.segmentDuration?.toLong() ?: left.duration.toLong(), le?.movieTimescale ?: left.timescale).compareTo(Time(re?.segmentDuration?.toLong() ?: right.duration.toLong(), re?.movieTimescale ?: right.timescale)) == 0, "editSegmentDuration")
            unchanged(left.samples.size == right.samples.size, "sampleCount")
            digest.update(unsignedBytes(left.trackId.toULong(), 4, Endian.Big))
            for ((a, b) in left.samples.zip(right.samples)) {
                checkCancelled(input.context)
                unchanged(Time(a.decodeTime.toLong(), left.timescale).compareTo(Time(b.decodeTime.toLong(), right.timescale)) == 0 &&
                    Time(a.presentationTime, left.timescale).compareTo(Time(b.presentationTime, right.timescale)) == 0 &&
                    Time(a.duration.toLong(), left.timescale).compareTo(Time(b.duration.toLong(), right.timescale)) == 0, "sampleDtsPtsDuration")
                unchanged(a.isSync == b.isSync && a.dependencyByte == b.dependencyByte, "sampleDependencies")
                val hash = sha256Range(input, a.range).orThrow()
                unchanged(a.range.length == b.range.length && hash == sha256Range(output, b.range).orThrow(), "sampleBytes")
                digest.update(unsignedBytes(a.range.length, 8, Endian.Big)); digest.update(Bytes(hash.value.encodeToByteArray()))
            }
        }
        input.validateIdentity().orThrow(); output.validateIdentity().orThrow()
        return digest.finish()
    }
}
