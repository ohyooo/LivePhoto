package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.exif.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*

internal data class AppleCleanPlan(val image: BinarySource, val video: BinarySource, val media: VideoStructure)

/** Deliberately narrow: CID-only MakerNote and dedicated, fully understood movie bindings. */
internal object AppleClean {
    suspend fun prepare(session: SourceSession, budget: ParseBudget): CoreResult<AppleCleanPlan> = attempt {
        val pair = session.applePair ?: unsafe("A complete Apple pair is required")
        val jpeg = session.jpeg ?: unsafe("Only JPEG Apple images have a cleanup model")
        if (jpeg.trailing.length != 0uL || jpeg.hasMpf) unsafe("Auxiliary image dependencies require a dedicated cleanup model")
        val reader = pair.imageReader
        val id = AppleImageReader.read(reader, jpeg, budget).orThrow() ?: unsafe("Apple image identifier is absent")
        val note = id.makerNote
        if (reader.readU16(note.offset + 14uL).orThrow() != 1.toUShort() || id.range.offset != note.offset + 28uL || id.range.endExclusive != note.endExclusive)
            unsafe("Only a contiguous CID-only MakerNote can be cleaned; ordinary/private MakerNote fields are retained by rejecting this rewrite")
        for (segment in jpeg.segments.filter { it.payloadKind == AppPayloadKind.Exif }) {
            val payload = segment.payload!!
            val tiff = TiffReader(reader, budget).read(ByteRange(payload.offset + 6uL, payload.length - 6uL)).orThrow()
            if (overlap(note, ByteRange(tiff.range.offset, 8uL))) unsafe("MakerNote aliases the TIFF header")
            for (ifd in tiff.ifds) {
                budget.item()
                val table = ByteRange(tiff.range.offset + ifd.relativeOffset.toULong(), 6uL + ifd.entries.size.toULong() * 12uL)
                if (overlap(note, table)) unsafe("MakerNote aliases an IFD table")
                for (entry in ifd.entries) {
                    budget.item()
                    val range = entry.valueRange ?: unsafe("Unknown TIFF value bounds cannot authorize cleanup")
                    if (range != note && overlap(note, range)) unsafe("MakerNote bytes are shared with ordinary EXIF")
                    if (range == note && !entry.isOpaqueMakerNote) unsafe("MakerNote bytes are aliased by another field")
                }
            }
        }
        // Retain the Apple MakerNote namespace and enclosing TIFF field; remove only its sole CID entry/value.
        val image = FixedPatchSource.create(reader, listOf(FixedPatch(ByteRange(note.offset + 14uL, note.length - 14uL)))).orThrow()
        val imageReader = BinaryReader(image, reader.context)
        if (AppleImageReader.read(imageReader, JpegParser.parse(imageReader, budget).orThrow(), budget).orThrow() != null) unsafe("Image identifier survived cleanup")
        val imageSession = SourceSession.open(SourceSet.Single(image), reader.context, budget).orThrow()
        if (imageSession.bindings.isNotEmpty()) unsafe("Image retains another protocol binding")
        if (codingDigest(session) != codingDigest(imageSession)) fail("POSTCONDITION_FAILED", "Image coding changed during Apple cleanup", Stage.Verify)

        val videoReader = pair.videoReader
        val movieId = AppleVideoReader.read(videoReader, budget).orThrow() ?: unsafe("Apple movie identifier is absent")
        val parser = BmffReader(videoReader, budget)
        val roots = parser.readBoxes(ByteRange(0uL, videoReader.identity().orThrow().size)).orThrow()
        val movie = roots.single { it.type == "moov" }
        val children = parser.readBoxes(movie.payload, 1u).orThrow()
        if (children.any { it.type !in setOf("mvhd", "trak", "meta", "free") }) unsafe("Unclassified movie-level dependencies prevent track retirement")
        val fields = parser.readBoxes(movieId.meta.payload, 2u).orThrow()
        if (fields.any { it.type !in setOf("hdlr", "keys", "ilst") }) unsafe("Movie metadata includes unclassified ordinary fields")
        val keys = fields.single { it.type == "keys" }
        if (videoReader.readU32(keys.payload.offset + 4uL).orThrow() != 1u) unsafe("Ordinary movie metadata keys require selective table rewriting")
        val items = parser.readBoxes(fields.single { it.type == "ilst" }.payload, 3u).orThrow()
        if (items.size != 1) unsafe("Ordinary movie metadata values cannot be discarded")
        val original = session.videos[ProtocolIds.Apple] ?: unsafe("Movie structure must be verified before cleanup")
        val patches = mutableListOf<FixedPatch>()
        fun retire(box: BmffBox) {
            // A free atom preserves all movie/sample offsets. Its former owned payload is zeroed.
            patches += FixedPatch(ByteRange(box.range.offset + 4uL, 4uL), Bytes("free".encodeToByteArray()))
            patches += FixedPatch(box.payload)
        }
        retire(movieId.meta)
        for (trak in children.filter { it.type == "trak" }) {
            val trackChildren = parser.readBoxes(trak.payload, 2u).orThrow()
            if (trackChildren.any { it.type !in setOf("tkhd", "edts", "mdia") }) unsafe("Track-level references or unknown metadata need a dedicated cleanup model")
            val tkhd = trackChildren.single { it.type == "tkhd" }
            val version = videoReader.readBuffer(tkhd.payload.offset, 1u).orThrow()[0].toInt()
            val trackId = videoReader.readU32(tkhd.payload.offset + if (version == 0) 12uL else 20uL).orThrow()
            val track = original.tracks.single { it.trackId == trackId }
            if (track.handler != "meta") continue
            if (track.metadataKeys.isEmpty() || track.metadataKeys.values.any { it.namespace != "mdta" || it.name != APPLE_STILL_TIME })
                unsafe("Mixed or private timed metadata cannot be removed as an owned track")
            verifyDedicatedTrack(parser, trak)
            for (sample in track.samples) {
                for (item in parser.readBoxes(sample.range, 1u).orThrow()) {
                    if (boxTypeIndex(item.type) !in track.metadataKeys) unsafe("Unclassified timed sample payload")
                }
                patches += FixedPatch(sample.range)
            }
            retire(trak)
        }
        val video = FixedPatchSource.create(videoReader, patches).orThrow()
        val cleanReader = BinaryReader(video, videoReader.context)
        if (AppleVideoReader.read(cleanReader, budget).orThrow() != null) unsafe("Movie identifier survived cleanup")
        val clean = BmffVideoProbe(cleanReader, budget, allowTimedMetadata = true).probe(ByteRange(0uL, cleanReader.identity().orThrow().size)).orThrow()
        if (clean.tracks != original.tracks.filter { it.handler != "meta" }) fail("POSTCONDITION_FAILED", "Apple cleanup changed retained movie tracks", Stage.Verify)
        session.recheck()
        AppleCleanPlan(image, video, clean)
    }

    private fun overlap(a: ByteRange, b: ByteRange): Boolean = a.offset < b.endExclusive && b.offset < a.endExclusive
    private suspend fun verifyDedicatedTrack(parser: BmffReader, track: BmffBox) {
        val schemas = mapOf(
            "trak" to setOf("tkhd", "edts", "mdia"), "edts" to setOf("elst"),
            "mdia" to setOf("mdhd", "hdlr", "minf"), "minf" to setOf("gmhd", "hdlr", "dinf", "stbl"),
            "gmhd" to setOf("gmin"), "dinf" to setOf("dref"),
            "stbl" to setOf("stsd", "stts", "ctts", "stsc", "stsz", "stco", "co64", "stss", "sdtp"),
            "stsd" to setOf("mebx"), "mebx" to setOf("keys", "btrt"))
        suspend fun visit(box: BmffBox, depth: UInt) {
            val allowed = schemas[box.type] ?: return
            val prefix = if (box.type in setOf("stsd", "mebx")) 8uL else 0uL
            if (box.payload.length < prefix) unsafe("Truncated dedicated track container")
            val children = parser.readBoxes(ByteRange(box.payload.offset + prefix, box.payload.length - prefix), depth).orThrow()
            if (children.any { it.type !in allowed }) unsafe("Dedicated metadata track contains unclassified nested fields")
            for (child in children) visit(child, depth + 1u)
        }
        visit(track, 2u)
    }
    private fun unsafe(message: String): Nothing = fail("UNSAFE_METADATA_REWRITE", message, Stage.Plan)
}
