package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

internal data class VideoSample(val range: ByteRange, val decodeTime: ULong, val presentationTime: Long, val duration: UInt, val isSync: Boolean, val dependencyByte: UInt? = null)
internal data class VideoEdit(val movieTimescale: UInt, val emptyDuration: ULong, val segmentDuration: ULong, val mediaStart: Long)
internal data class VideoTrack(
    val trackId: UInt, val handler: String, val timescale: UInt, val duration: ULong,
    val codec: VideoCodec?, val sampleEntry: String, val codecConfiguration: Bytes,
    val samples: List<VideoSample>, val audioCodec: AudioCodec? = null,
    val presentationDuration: Time, val edit: VideoEdit? = null,
    val width: UInt? = null, val height: UInt? = null,
    val transform: Bytes, val displayWidthFixed: UInt, val displayHeightFixed: UInt,
    val audioChannels: UInt? = null, val audioSampleRateFixed: UInt? = null, val audioSampleSize: UInt? = null,
    val audioActualSampleRate: UInt? = null, val audioActualChannelCount: UInt? = null,
    val metadataKeys: Map<UInt, TimedMetadataKey> = emptyMap(),
)
internal data class VideoStructure(val container: VideoContainer, val range: ByteRange, val tracks: List<VideoTrack>, val movieTimescale: UInt, val movieDuration: ULong, val decoderValidated: Boolean = false)

/** Unfragmented, single-mdat AVC/HEVC + AAC structural coverage. No decoder/playability claim. */
internal class BmffVideoProbe(private val reader: BinaryReader, private val budget: ParseBudget = ParseBudget(reader.context), private val allowTimedMetadata: Boolean = false) {
    private val boxes = BmffReader(reader, budget)

    suspend fun probe(range: ByteRange): CoreResult<VideoStructure> = attempt {
        val root = boxes.readBoxes(range).orThrow()
        if (root.any { it.type in setOf("moof", "mfra") }) unsupported("Fragmented video is not implemented")
        val ftyp = boxes.readFileType(one(root, "ftyp")).orThrow()
        val brands = setOf(ftyp.majorBrand) + ftyp.compatibleBrands
        val container = when {
            "qt  " in brands -> VideoContainer.Mov
            brands.any { it in setOf("isom", "iso2", "mp41", "mp42", "avc1", "iso4", "iso5", "iso6") } -> VideoContainer.Mp4
            else -> unsupported("Video ftyp brands are not implemented")
        }
        if (brands.any { it in setOf("heic", "heix", "mif1", "msf1", "avif", "avis") }) unsupported("Mixed image/video brand semantics are not implemented")
        val mdats = root.filter { it.type == "mdat" }
        if (mdats.isEmpty()) corrupt("Movie has no mdat")
        if (mdats.size != 1 && !allowTimedMetadata) unsupported("Multiple mdat boxes are not implemented for this profile")
        val moov = children(one(root, "moov"), 1u)
        if (moov.any { it.type == "mvex" }) unsupported("Fragmented movie metadata is not implemented")
        val mvhd = bytes(one(moov, "mvhd"))
        fullVersion(mvhd, setOf(0, 1))
        need(mvhd, if (u8(mvhd, 0) == 0) 100 else 112)
        val movieScale = u32(mvhd, if (u8(mvhd, 0) == 0) 12 else 20)
        if (movieScale == 0u) corrupt("Movie timescale is zero")
        val movieDuration = if (u8(mvhd, 0) == 0) u32(mvhd, 16).toULong() else u64(mvhd, 24)
        if (movieDuration == 0uL) corrupt("Movie duration is zero")
        val tracks = mutableListOf<VideoTrack>()
        for (trak in moov.filter { it.type == "trak" }) tracks.add(track(trak, range, mdats.map { it.payload }, movieScale, movieDuration))
        if (tracks.isEmpty() || tracks.none { it.handler == "vide" }) corrupt("Movie has no video track")
        if (tracks.map { it.trackId }.distinct().size != tracks.size) corrupt("Duplicate track identifiers")
        // Disallow overlapping chunk/sample resources, including across tracks.
        val samples = tracks.flatMap { it.samples }.sortedWith(Comparator { left, right -> checkCancelled(reader.context); left.range.offset.compareTo(right.range.offset) })
        for (index in 1 until samples.size) { checkCancelled(reader.context); if (samples[index - 1].range.endExclusive > samples[index].range.offset) corrupt("Sample byte ranges overlap") }
        reader.validateIdentity().orThrow()
        VideoStructure(container, range, tracks.toList(), movieScale, movieDuration)
    }

    private suspend fun track(trak: BmffBox, videoRange: ByteRange, mdats: List<ByteRange>, movieScale: UInt, movieDuration: ULong): VideoTrack {
        val children = children(trak, 2u)
        val tkhd = bytes(one(children, "tkhd"))
        fullVersion(tkhd, setOf(0, 1), allowFlags = true)
        need(tkhd, if (u8(tkhd, 0) == 0) 84 else 96)
        val id = u32(tkhd, if (u8(tkhd, 0) == 0) 12 else 20)
        if (id == 0u) corrupt("Track identifier is zero")
        val trackDuration = if (u8(tkhd, 0) == 0) u32(tkhd, 20).toULong() else u64(tkhd, 28)
        if (trackDuration == 0uL || trackDuration > movieDuration) corrupt("Track duration is zero or exceeds movie duration")
        val matrixPosition = if (u8(tkhd, 0) == 0) 40 else 52
        val transform = tkhd.slice(matrixPosition, matrixPosition + 36)
        val displayWidth = u32(tkhd, matrixPosition + 36)
        val displayHeight = u32(tkhd, matrixPosition + 40)
        val mdia = children(one(children, "mdia"), 3u)
        val mdhd = bytes(one(mdia, "mdhd"))
        fullVersion(mdhd, setOf(0, 1))
        val version = u8(mdhd, 0)
        need(mdhd, if (version == 0) 24 else 36)
        val timescale = u32(mdhd, if (version == 0) 12 else 20)
        val duration = if (version == 0) u32(mdhd, 16).toULong() else u64(mdhd, 24)
        if (timescale == 0u) corrupt("Track timescale is zero")
        if (duration > Long.MAX_VALUE.toULong()) fail("INTEGER_OVERFLOW", "Track duration exceeds the exact signed timeline domain")
        val hdlr = bytes(one(mdia, "hdlr"))
        fullVersion(hdlr, setOf(0)); need(hdlr, 24)
        val handler = fourCc(hdlr, 8)
        val metadata = allowTimedMetadata && handler == "meta"
        if (handler !in setOf("vide", "soun") && !metadata) unsupported("Track handler $handler is not implemented")
        val minf = children(one(mdia, "minf"), 4u)
        val mediaHeader = if (metadata) bytes(one(children(one(minf, "gmhd"), 5u), "gmin")) else bytes(one(minf, if (handler == "vide") "vmhd" else "smhd"))
        if (handler == "vide") {
            exact(mediaHeader, 12uL)
            if (u32(mediaHeader, 0) != 1u) unsupported("Video media header version/flags are not implemented")
        } else { fullVersion(mediaHeader, setOf(0)); exact(mediaHeader, if (metadata) 16uL else 8uL) }
        val edit = optional(children, "edts")?.let { parseEdit(it, movieScale) }
        val emptyTicks = if (edit == null) 0uL else convertTicks(edit.emptyDuration, movieScale, timescale)
        val presentationDuration = if (edit == null) Time(duration.toLong(), timescale) else {
            val ticks = checkedAdd(edit.emptyDuration, edit.segmentDuration)
            if (ticks > Long.MAX_VALUE.toULong()) fail("INTEGER_OVERFLOW", "Edit duration exceeds the exact signed timeline domain")
            Time(ticks.toLong(), movieScale)
        }
        if (edit != null) {
            if (trackDuration != checkedAdd(edit.emptyDuration, edit.segmentDuration)) corrupt("Edit duration disagrees with track header")
        } else {
            val trackProduct = checkedMultiply(trackDuration, timescale.toULong())
            val mediaProduct = checkedMultiply(duration, movieScale.toULong())
            val difference = if (trackProduct > mediaProduct) trackProduct - mediaProduct else mediaProduct - trackProduct
            if (difference > timescale.toULong()) corrupt("Track/movie duration disagrees with media duration")
        }
        val dinf = children(one(minf, "dinf"), 5u)
        val drefBox = one(dinf, "dref")
        val dref = bytes(drefBox)
        fullVersion(dref, setOf(0)); need(dref, 8)
        if (u32(dref, 4) != 1u) unsupported("Multiple or external data references are not implemented")
        val references = boxes.readBoxes(ByteRange(checkedAdd(drefBox.payload.offset, 8uL), drefBox.payload.length - 8uL), 6u).orThrow()
        val reference = references.singleOrNull() ?: corrupt("Data reference count disagrees with entries")
        val refBytes = bytes(reference)
        if (reference.type != "url " && !(allowTimedMetadata && reference.type == "alis") || refBytes.size != 4 || u8(refBytes, 0) != 0 || u8(refBytes, 1) != 0 || u8(refBytes, 2) != 0 || u8(refBytes, 3) != 1) unsupported("External data references are not implemented")
        val stbl = children(one(minf, "stbl"), 5u)
        if (stbl.any { it.type in setOf("stz2", "senc", "saiz", "saio") }) unsupported("Compact/encrypted sample tables are not implemented")
        val stsdBox = one(stbl, "stsd")
        val stsd = bytes(stsdBox)
        fullVersion(stsd, setOf(0)); need(stsd, 8)
        if (u32(stsd, 4) != 1u) unsupported("Multiple sample descriptions are not implemented")
        val descriptions = boxes.readBoxes(ByteRange(checkedAdd(stsdBox.payload.offset, 8uL), stsdBox.payload.length - 8uL), 6u).orThrow()
        val entry = descriptions.singleOrNull() ?: corrupt("Sample description count disagrees with entries")
        val entryBytes = bytes(entry)
        need(entryBytes, if (handler == "vide") 78 else if (metadata) 8 else 28)
        if (u16(entryBytes, 6) != 1) unsupported("Sample entry does not use the local data reference")
        val codec: VideoCodec?
        val audio: AudioCodec?
        val config: Bytes
        var nalWidth = 0
        var aacConfiguration: AacConfiguration? = null
        var metadataKeys: Map<UInt, TimedMetadataKey> = emptyMap()
        if (handler == "vide") {
            if (u16(entryBytes, 24) == 0 || u16(entryBytes, 26) == 0) corrupt("Video dimensions are zero")
            codec = when (entry.type) { "avc1", "avc3" -> VideoCodec.Avc; "hvc1", "hev1" -> VideoCodec.Hevc; else -> unsupported("Video sample entry ${entry.type} is not implemented") }
            audio = null
            val extra = boxes.readBoxes(ByteRange(checkedAdd(entry.payload.offset, 78uL), entry.payload.length - 78uL), 7u).orThrow()
            config = bytes(one(extra, if (codec == VideoCodec.Avc) "avcC" else "hvcC"))
            nalWidth = if (codec == VideoCodec.Avc) avcConfig(config, budget, entry.type == "avc3") else hevcConfig(config, budget, entry.type == "hev1")
        } else if (metadata) {
            if (entry.type != "mebx") unsupported("Only boxed timed metadata samples are implemented")
            codec = null; audio = null
            config = entryBytes.slice(8)
            metadataKeys = TimedMetadata.readKeys(reader, ByteRange(entry.payload.offset + 8uL, entry.payload.length - 8uL), budget).orThrow()
        } else {
            codec = null; audio = AudioCodec.Aac
            if (entry.type != "mp4a") unsupported("Audio sample entry ${entry.type} is not implemented")
            if (u16(entryBytes, 8) != 0) unsupported("QuickTime extended audio sample entries are not implemented")
            if (u16(entryBytes, 16) == 0 || u16(entryBytes, 18) == 0) corrupt("Audio sample entry has zero channel/size")
            if (u32(entryBytes, 24) == 0u) unsupported("Zero audio sample-entry rate requires an unimplemented rate binding")
            val extra = boxes.readBoxes(ByteRange(checkedAdd(entry.payload.offset, 28uL), entry.payload.length - 28uL), 7u).orThrow()
            config = bytes(one(extra, "esds"))
            aacConfiguration = validateEsds(config, budget)
            if (aacConfiguration.actualSampleRate > 65535u) unsupported("AAC sample rates above 16.16 sample-entry capacity require an unimplemented rate binding")
            if (u32(entryBytes, 24) != (aacConfiguration.actualSampleRate shl 16) || u16(entryBytes, 16).toUInt() != aacConfiguration.channelCount) corrupt("AAC AudioSpecificConfig rate/channels disagree with the sample entry")
        }
        val stsz = bytes(one(stbl, "stsz")); fullVersion(stsz, setOf(0)); need(stsz, 12)
        val count = u32(stsz, 8).toULong()
        if (count == 0uL) corrupt("Track has no samples")
        budget.retain(checkedMultiply(count, 160uL))
        val n = checkedInt(count)
        val fixedSize = u32(stsz, 4)
        exact(stsz, checkedAdd(12uL, if (fixedSize == 0u) checkedMultiply(count, 4uL) else 0uL))
        val sizes = IntArray(n)
        for (i in 0 until n) { budget.item(6u); sizes[i] = (if (fixedSize == 0u) u32(stsz, 12 + i * 4) else fixedSize).toInt(); if (sizes[i] == 0) corrupt("Empty media sample") }
        val durations = timeTable(bytes(one(stbl, "stts")), n, false)
        val cttsBox = optional(stbl, "ctts")
        val composition = if (cttsBox == null) LongArray(n) else timeTable(bytes(cttsBox), n, true)
        val offsetBoxes = stbl.filter { it.type == "stco" || it.type == "co64" }
        if (offsetBoxes.size != 1) corrupt("Exactly one stco/co64 is required")
        val chunkBox = offsetBoxes.single(); val chunkBytes = bytes(chunkBox)
        fullVersion(chunkBytes, setOf(0)); need(chunkBytes, 8)
        val chunkCount = u32(chunkBytes, 4).toULong()
        val chunkWidth = if (chunkBox.type == "co64") 8 else 4
        exact(chunkBytes, checkedAdd(8uL, checkedMultiply(chunkCount, chunkWidth.toULong())))
        if (chunkCount == 0uL || chunkCount > count) corrupt("Invalid chunk count")
        val chunks = checkedInt(chunkCount)
        val stsc = bytes(one(stbl, "stsc")); fullVersion(stsc, setOf(0)); need(stsc, 8)
        val mappings = u32(stsc, 4).toULong()
        exact(stsc, checkedAdd(8uL, checkedMultiply(mappings, 12uL)))
        if (mappings == 0uL || mappings > chunkCount) corrupt("Invalid sample-to-chunk entry count")
        val mapCount = checkedInt(mappings)
        var prior = 0u
        for (i in 0 until mapCount) {
            checkCancelled(reader.context)
            val start = u32(stsc, 8 + i * 12)
            if ((i == 0 && start != 1u) || start <= prior || start.toULong() > chunkCount || u32(stsc, 12 + i * 12) == 0u || u32(stsc, 16 + i * 12) != 1u) corrupt("Invalid sample-to-chunk mapping")
            prior = start
        }
        val sync = BooleanArray(n) { true }
        val dependency = optional(stbl, "sdtp")?.let { val table = bytes(it); fullVersion(table, setOf(0)); exact(table, checkedAdd(4uL, count)); table }
        optional(stbl, "stss")?.let {
            sync.fill(false)
            val table = bytes(it); fullVersion(table, setOf(0)); need(table, 8)
            val syncCount = u32(table, 4).toULong(); exact(table, checkedAdd(8uL, checkedMultiply(syncCount, 4uL)))
            if (syncCount > count) corrupt("Sync sample count exceeds sample count")
            var last = 0u
            for (i in 0 until checkedInt(syncCount)) { checkCancelled(reader.context); val index = u32(table, 8 + i * 4); if (index <= last || index.toULong() > count) corrupt("Invalid sync sample index"); sync[index.toInt() - 1] = true; last = index }
        }
        val samples = mutableListOf<VideoSample>()
        var sampleIndex = 0
        var mapIndex = 0
        var dts = 0uL
        var mediaPresentationEnd = Long.MIN_VALUE
        for (chunk in 0 until chunks) {
            checkCancelled(reader.context)
            if (mapIndex + 1 < mapCount && (chunk + 1).toUInt() == u32(stsc, 8 + (mapIndex + 1) * 12)) mapIndex++
            val inChunk = u32(stsc, 12 + mapIndex * 12).toULong()
            if (inChunk > (n - sampleIndex).toULong()) corrupt("Chunk contains too many samples")
            val relative = if (chunkWidth == 8) u64(chunkBytes, 8 + chunk * 8) else u32(chunkBytes, 8 + chunk * 4).toULong()
            var cursor = checkedAdd(videoRange.offset, relative)
            for (ignored in 0 until checkedInt(inChunk)) {
                checkCancelled(reader.context)
                val size = sizes[sampleIndex].toUInt().toULong()
                checkedRange(cursor, size, videoRange.endExclusive)
                if (mdats.none { cursor >= it.offset && cursor <= it.endExclusive && size <= it.endExclusive - cursor }) corrupt("Sample points outside a complete mdat payload")
                if (dts > Long.MAX_VALUE.toULong()) fail("INTEGER_OVERFLOW", "Decode time cannot be represented as signed presentation time")
                val offset = composition[sampleIndex]
                val base = dts.toLong()
                if (offset > 0 && base > Long.MAX_VALUE - offset) fail("INTEGER_OVERFLOW", "Presentation time overflows")
                val mediaPts = base + offset
                val mediaStart = edit?.mediaStart ?: 0L
                if (mediaPts < Long.MIN_VALUE + mediaStart) fail("INTEGER_OVERFLOW", "Edited presentation time underflows")
                val shifted = mediaPts - mediaStart
                if (emptyTicks > Long.MAX_VALUE.toULong() || shifted > Long.MAX_VALUE - emptyTicks.toLong()) fail("INTEGER_OVERFLOW", "Edited presentation time overflows")
                val pts = shifted + emptyTicks.toLong()
                val sampleRange = ByteRange(cursor, size)
                if (codec != null) validateNalSample(sampleRange, nalWidth, codec)
                val delta = durations[sampleIndex].toUInt()
                if (mediaPts > Long.MAX_VALUE - delta.toLong()) fail("INTEGER_OVERFLOW", "Sample presentation end overflows")
                mediaPresentationEnd = maxOf(mediaPresentationEnd, mediaPts + delta.toLong())
                val dependencyByte = dependency?.let { u8(it, 4 + sampleIndex).toUInt() }
                if (dependencyByte != null && ((dependencyByte shr 4) and 3u) == 3u) corrupt("Sample dependency value is reserved")
                samples.add(VideoSample(sampleRange, dts, pts, delta, sync[sampleIndex], dependencyByte))
                dts = checkedAdd(dts, delta.toULong()); cursor = checkedAdd(cursor, size); sampleIndex++
            }
        }
        if (sampleIndex != n || dts != duration) corrupt("Sample counts/duration disagree with media header")
        if (edit != null) {
            // elst media_time is in the composition domain, whereas mdhd duration is
            // the sum of decoding deltas. Positive ctts (e.g. B-frame delay) can put
            // the final presented sample beyond mdhd duration. Bound the edit using
            // verified sample PTS endpoints, never just duration - mediaStart.
            if (edit.mediaStart >= mediaPresentationEnd) corrupt("Edit begins after the last presented sample")
            val requested = checkedMultiply(edit.segmentDuration, timescale.toULong())
            val available = checkedMultiply((mediaPresentationEnd - edit.mediaStart).toULong(), movieScale.toULong())
            // Movie-duration fields can round the final boundary by one movie tick.
            if (requested > available && requested - available > timescale.toULong()) corrupt("Edit exceeds the sample presentation duration")
        }
        return VideoTrack(id, handler, timescale, duration, codec, entry.type, config, samples.toList(), audio, presentationDuration, edit,
            if (handler == "vide") u16(entryBytes, 24).toUInt() else null,
            if (handler == "vide") u16(entryBytes, 26).toUInt() else null,
            transform, displayWidth, displayHeight,
            if (handler == "soun") u16(entryBytes, 16).toUInt() else null,
            if (handler == "soun") u32(entryBytes, 24) else null,
            if (handler == "soun") u16(entryBytes, 18).toUInt() else null,
            aacConfiguration?.actualSampleRate, aacConfiguration?.channelCount, metadataKeys)
    }

    private fun timeTable(bytes: Bytes, count: Int, composition: Boolean): LongArray {
        fullVersion(bytes, if (composition) setOf(0, 1) else setOf(0)); need(bytes, 8)
        val entries = u32(bytes, 4).toULong()
        exact(bytes, checkedAdd(8uL, checkedMultiply(entries, 8uL)))
        if (entries == 0uL || entries > count.toULong()) corrupt("Invalid time table entry count")
        val values = LongArray(count)
        var index = 0
        for (i in 0 until checkedInt(entries)) {
            checkCancelled(reader.context)
            val runCount = u32(bytes, 8 + i * 8).toULong()
            if (runCount == 0uL || runCount > (count - index).toULong()) corrupt("Time table sample count mismatch")
            val raw = u32(bytes, 12 + i * 8)
            val value = if (composition && u8(bytes, 0) == 1) raw.toInt().toLong() else raw.toLong()
            if (!composition && value == 0L) corrupt("Sample duration is zero")
            repeat(checkedInt(runCount)) { if (index and 4095 == 0) checkCancelled(reader.context); values[index++] = value }
        }
        if (index != count) corrupt("Time table sample count mismatch")
        return values
    }

    private suspend fun parseEdit(edts: BmffBox, movieScale: UInt): VideoEdit {
        val edits = children(edts, 3u)
        if (edits.any { it.type != "elst" }) unsupported("Additional edit metadata is not implemented")
        val table = bytes(one(edits, "elst")); fullVersion(table, setOf(0, 1)); need(table, 8)
        val count = u32(table, 4)
        if (count !in 1u..2u) unsupported("Multiple media edit segments are not implemented")
        val width = if (u8(table, 0) == 0) 12 else 20
        exact(table, checkedAdd(8uL, checkedMultiply(count.toULong(), width.toULong())))
        var empty = 0uL
        var mediaStart = 0L
        var segmentDuration = 0uL
        for (index in 0 until count.toInt()) {
            val base = 8 + index * width
            val duration = if (width == 12) u32(table, base).toULong() else u64(table, base)
            val start = if (width == 12) u32(table, base + 4).toInt().toLong() else u64(table, base + 8).toLong()
            val ratePosition = base + if (width == 12) 8 else 16
            if (u16(table, ratePosition) != 1 || u16(table, ratePosition + 2) != 0) unsupported("Non-unit edit rate is not implemented")
            if (duration == 0uL) unsupported("Zero-duration edit semantics are not implemented")
            if (start == -1L && index == 0 && count == 2u) empty = duration
            else if (start >= 0 && index == count.toInt() - 1) { mediaStart = start; segmentDuration = duration }
            else unsupported("Multiple/negative media edit mapping is not implemented")
        }
        return VideoEdit(movieScale, empty, segmentDuration, mediaStart)
    }

    private fun convertTicks(value: ULong, from: UInt, to: UInt): ULong {
        // Cancel scale factors first so an otherwise representable exact conversion does not overflow.
        var a = from.toULong(); var b = to.toULong()
        while (b != 0uL) { val remainder = a % b; a = b; b = remainder }
        val divisor = from.toULong() / a
        if (value % divisor != 0uL) unsupported("Fractional edit mapping is not implemented")
        return checkedMultiply(value / divisor, to.toULong() / a)
    }

    private suspend fun validateNalSample(range: ByteRange, width: Int, codec: VideoCodec) {
        validateNalFraming(reader, range, width, codec, budget, 7u).orThrow()
    }

    private suspend fun children(box: BmffBox, depth: UInt): List<BmffBox> = boxes.readBoxes(box.payload, depth).orThrow()
    private suspend fun bytes(box: BmffBox): Bytes { budget.retain(box.payload.length); return reader.readExactly(box.payload.offset, checkedInt(box.payload.length).toUInt()).orThrow() }
    private fun one(boxes: List<BmffBox>, type: String): BmffBox = optional(boxes, type) ?: corrupt("Missing $type box")
    private fun optional(boxes: List<BmffBox>, type: String): BmffBox? { val matching = boxes.filter { it.type == type }; if (matching.size > 1) corrupt("Duplicate $type box"); return matching.singleOrNull() }
    private fun fullVersion(bytes: Bytes, versions: Set<Int>, allowFlags: Boolean = false) { need(bytes, 4); if (u8(bytes, 0) !in versions) unsupported("FullBox version is not implemented"); if (!allowFlags && (u8(bytes, 1) != 0 || u8(bytes, 2) != 0 || u8(bytes, 3) != 0)) unsupported("FullBox flags are not implemented") }
    private fun need(bytes: Bytes, size: Int) { if (bytes.size < size) corrupt("BMFF payload is truncated") }
    private fun exact(bytes: Bytes, size: ULong) { if (bytes.size.toULong() != size) corrupt("BMFF table length disagrees with its count") }
    private fun u8(bytes: Bytes, offset: Int): Int { need(bytes, offset + 1); return bytes[offset].toInt() and 255 }
    private fun u16(bytes: Bytes, offset: Int): Int { need(bytes, offset + 2); return readUnsigned(bytes.slice(offset, offset + 2), Endian.Big).toInt() }
    private fun u32(bytes: Bytes, offset: Int): UInt { need(bytes, offset + 4); return readUnsigned(bytes.slice(offset, offset + 4), Endian.Big).toUInt() }
    private fun u64(bytes: Bytes, offset: Int): ULong { need(bytes, offset + 8); return readUnsigned(bytes.slice(offset, offset + 8), Endian.Big) }
    private fun corrupt(message: String): Nothing = fail("CORRUPTED_CONTAINER", message)
    private fun unsupported(message: String): Nothing = fail("UNSUPPORTED_CONTAINER", message)
}
