package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

internal const val APPLE_CID: String = "com.apple.quicktime.content.identifier"
internal const val APPLE_STILL_TIME: String = "com.apple.quicktime.still-image-time"
internal data class AppleVideoIdentifier(val value: String, val range: ByteRange, val meta: BmffBox)

internal object AppleVideoReader {
    suspend fun read(reader: BinaryReader, budget: ParseBudget): CoreResult<AppleVideoIdentifier?> = attempt {
        val parser = BmffReader(reader, budget)
        val roots = parser.readBoxes(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
        val movies = roots.filter { it.type == "moov" }
        if (movies.isEmpty()) return@attempt null
        if (movies.size != 1) fail("CONFLICTING_METADATA", "Multiple movie metadata authorities")
        val children = parser.readBoxes(movies.single().payload, 1u).orThrow()
        val metaBoxes = children.filter { it.type == "meta" }
        if (metaBoxes.isEmpty()) return@attempt null
        if (metaBoxes.size != 1) fail("CONFLICTING_METADATA", "Multiple movie-level metadata containers")
        val meta = metaBoxes.single()
        if (meta.payload.length < 8uL) fail("CORRUPTED_CONTAINER", "Movie metadata container is truncated")
        if (reader.readU32(meta.payload.offset).orThrow() == 0u) fail("CAPABILITY_UNSUPPORTED", "This Apple reader implements plain QuickTime meta, not ISO FullBox meta")
        val fields = parser.readBoxes(meta.payload, 2u).orThrow()
        val keyBoxes = fields.filter { it.type == "keys" }
        if (keyBoxes.isEmpty()) return@attempt null
        if (keyBoxes.size != 1) fail("CONFLICTING_METADATA", "Movie metadata has duplicate keys tables")
        val keys = keyBoxes.single()
        if (keys.payload.length < 8uL || reader.readU32(keys.payload.offset).orThrow() != 0u) fail("CORRUPTED_CONTAINER", "Movie keys header is malformed")
        val count = reader.readU32(keys.payload.offset + 4uL).orThrow().toULong()
        var position = keys.payload.offset + 8uL
        var contentIndex: UInt? = null
        val names = mutableSetOf<String>()
        for (index in 0 until checkedInt(count)) {
            budget.item(3u); budget.retain(96uL)
            checkedRange(position, 8uL, keys.payload.endExclusive)
            val length = reader.readU32(position).orThrow().toULong()
            if (length < 8uL) fail("CORRUPTED_CONTAINER", "Movie metadata key length is invalid")
            checkedRange(position, length, keys.payload.endExclusive)
            budget.retain(length)
            val bytes = reader.readExactly(position + 4uL, checkedInt(length - 4uL).toUInt()).orThrow()
            val namespace = fourCc(bytes, 0)
            val name = decodeUtf8Strict(bytes.slice(4), budget)
            if (!names.add("$namespace:$name")) fail("CONFLICTING_METADATA", "Movie metadata key declarations are duplicated")
            if (namespace == "mdta" && name == APPLE_CID) contentIndex = (index + 1).toUInt()
            position += length
        }
        if (position != keys.payload.endExclusive) fail("CORRUPTED_CONTAINER", "Movie metadata key count leaves trailing bytes")
        val cidIndex = contentIndex ?: return@attempt null
        val handler = fields.singleOrNull { it.type == "hdlr" } ?: fail("CONFLICTING_METADATA", "Apple movie metadata requires one handler")
        if (handler.payload.length < 24uL) fail("CORRUPTED_CONTAINER", "Movie metadata handler is truncated")
        val h = reader.readBuffer(handler.payload.offset, 24u).orThrow()
        if (readUnsigned(h.slice(0, 8), Endian.Big) != 0uL || readUnsigned(h.slice(16, 24), Endian.Big) != 0uL ||
            !(fourCc(h, 8) == "mdta" && readUnsigned(h.slice(12, 16), Endian.Big) == 0uL || fourCc(h, 8) == "mdir" && fourCc(h, 12) == "appl"))
            fail("UNKNOWN_PROTOCOL_VARIANT", "Apple movie metadata handler ownership is unconfirmed")
        val ilst = fields.singleOrNull { it.type == "ilst" } ?: fail("CONFLICTING_METADATA", "Movie identifier needs one ilst")
        val indices = mutableSetOf<UInt>()
        var identifier: AppleVideoIdentifier? = null
        for (item in parser.readBoxes(ilst.payload, 3u).orThrow()) {
            val index = boxTypeIndex(item.type)
            if (index == 0u || index.toULong() > count || !indices.add(index)) fail("CONFLICTING_METADATA", "Movie item indices are duplicated or out of range")
            if (index != cidIndex) continue
            val valueBox = parser.readBoxes(item.payload, 4u).orThrow().singleOrNull()?.takeIf { it.type == "data" }
                ?: fail("CONFLICTING_METADATA", "Movie identifier must contain one unshadowed data box")
            if (valueBox.payload.length < 9uL || reader.readU32(valueBox.payload.offset).orThrow() != 1u)
                fail("INVALID_PAIR_IDENTIFIER", "Movie identifier is not a UTF-8 value")
            val range = ByteRange(valueBox.payload.offset + 8uL, valueBox.payload.length - 8uL)
            budget.retain(range.length)
            val raw = reader.readExactly(range.offset, checkedInt(range.length).toUInt()).orThrow()
            val value = decodeUtf8Strict(raw, budget).trimEnd('\u0000')
            if (!appleUuid(value)) fail("INVALID_PAIR_IDENTIFIER", "Movie identifier is outside the confirmed UUID profile")
            identifier = AppleVideoIdentifier(value, range, meta)
        }
        if (identifier == null) fail("INVALID_PAIR_IDENTIFIER", "Movie identifier declaration has no value")
        // Traverse only structural containers. Arbitrary payload strings never establish authority.
        suspend fun shadows(boxes: List<BmffBox>, depth: UInt) {
            for (box in boxes) {
                budget.item(depth)
                if (box.range == meta.range) continue
                if (box.type in setOf("meta", "keys", "ilst")) fail("CONFLICTING_METADATA", "A second parsed metadata hierarchy shadows movie-level authority")
                if (box.type in setOf("moov", "trak", "udta", "mdia", "minf", "stbl", "edts", "gmhd")) shadows(parser.readBoxes(box.payload, depth + 1u).orThrow(), depth + 1u)
            }
        }
        shadows(roots, 0u)
        reader.validateIdentity().orThrow()
        identifier
    }

    suspend fun key(reader: BinaryReader, video: VideoStructure, budget: ParseBudget): CoreResult<KeyPhotoResult> = attempt {
        val matches = mutableListOf<Pair<Time, RawKeyField>>()
        val parser = BmffReader(reader, budget)
        for (track in video.tracks.filter { it.handler == "meta" }) {
            val keyIds = track.metadataKeys.filterValues { it.namespace == "mdta" && it.name == APPLE_STILL_TIME }
            for ((id, key) in keyIds) {
                if (key.typeNamespace != 0u || key.type != 65u) fail("CAPABILITY_UNSUPPORTED", "Still-image marker is not a declared signed 8-bit value")
                for (sample in track.samples) {
                    val items = parser.readBoxes(sample.range, 1u).orThrow()
                    if (items.any { boxTypeIndex(it.type) !in track.metadataKeys }) fail("CORRUPTED_CONTAINER", "Timed sample refers to an undeclared metadata key")
                    val marker = items.filter { boxTypeIndex(it.type) == id }
                    if (marker.size > 1) fail("CONFLICTING_METADATA", "Metadata sample has duplicate still-image markers")
                    val value = marker.singleOrNull() ?: continue
                    if (value.payload.length != 1uL) fail("CORRUPTED_CONTAINER", "Still-image marker payload must be one signed byte")
                    val time = Time(sample.presentationTime, track.timescale)
                    val videoEnd = video.tracks.filter { it.handler == "vide" }.maxOf { it.presentationDuration }
                    if (time < Time.Zero || time >= track.presentationDuration || time >= videoEnd) fail("INVALID_PRESENTATION_TIMESTAMP", "Still-image sample lies outside the presented movie")
                    val raw = reader.readBuffer(value.payload.offset, 1u).orThrow()[0].toInt()
                    matches += time to RawKeyField(APPLE_STILL_TIME, Value.Number(raw.toString()), "signed-int8-marker-not-time", Location(source = reader.identity().orThrow().id, range = sample.range, track = TrackId(track.trackId.toString())))
                }
            }
        }
        if (matches.size > 1) fail("CONFLICTING_METADATA", "More than one still-image-time sample is authoritative")
        val found = matches.singleOrNull()
        if (found == null) KeyPhotoResult(issues = listOf(Issue(IssueCode("INVALID_PRESENTATION_TIMESTAMP"), Severity.Warning, Layer.Protocol, Location(selector = APPLE_STILL_TIME))))
        else KeyPhotoResult(found.first, source = KeySource.TimedMetadataSample, rawFields = listOf(found.second))
    }
}
