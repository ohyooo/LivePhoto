package livephoto.core.exif

import livephoto.core.*
import livephoto.core.binary.*

internal enum class ExifMarkerAction { AddCanonical, RemoveOwned }
internal data class ExifCommentChange(val previous: OplusCommentMarker?, val resulting: OplusCommentMarker?)

/** Created only by bounded parsing, conservative preservation checks, and final EXIF readback. */
internal class ExifMarkerPatch private constructor(
    val sourceIdentity: SourceIdentity,
    val originalTiffRange: ByteRange?,
    val originalTiffDigest: Digest?,
    val replacementPayload: Bytes,
    val action: ExifMarkerAction,
    val ownedChangedComment: ExifCommentChange,
    val isNoOp: Boolean,
) {
    companion object {
        internal suspend fun create(reader: BinaryReader, budget: ParseBudget): CoreResult<ExifMarkerPatch> = attempt {
            val identity = reader.identity().orThrow()
            val payload = ExifPatchBuilder(reader, budget).create()
            verify(payload, OplusCommentMarker.Modern, reader.context, budget)
            reader.validateIdentity().orThrow()
            ExifMarkerPatch(identity, null, null, payload, ExifMarkerAction.AddCanonical, ExifCommentChange(null, OplusCommentMarker.Modern), false)
        }

        internal suspend fun rewrite(reader: BinaryReader, budget: ParseBudget, range: ByteRange, action: ExifMarkerAction): CoreResult<ExifMarkerPatch> = attempt {
            val identity = reader.identity().orThrow()
            val built = ExifPatchBuilder(reader, budget).rewrite(range, action)
            val readback = verify(built.payload, built.change.resulting, reader.context, budget)
            if (ordinary(built.document, budget) != ordinary(readback.document, budget)) fail("UNSAFE_METADATA_REWRITE", "Ordinary EXIF fields failed final preservation readback")
            val digest = sha256Range(reader, range).orThrow()
            reader.validateIdentity().orThrow()
            ExifMarkerPatch(identity, range, digest, built.payload, action, built.change, built.noOp)
        }

        private suspend fun verify(payload: Bytes, marker: OplusCommentMarker?, context: Context, budget: ParseBudget): ExifCommentFacts {
            if (payload.size < 6 || !(0 until 6).all { payload[it] == exifHeader[it] }) fail("UNSAFE_METADATA_REWRITE", "Replacement APP payload is not EXIF")
            val source = ExifPayloadSource(payload)
            val facts = ExifUserCommentReader(BinaryReader(source, context), budget).read(ByteRange(6uL, (payload.size - 6).toULong())).orThrow()
            if (marker == null) {
                if (facts.comments.any { it.marker != null }) fail("UNSAFE_METADATA_REWRITE", "Owned EXIF marker remains after removal")
            } else if (facts.comments.singleOrNull()?.marker != marker) fail("UNSAFE_METADATA_REWRITE", "Written EXIF marker failed final readback")
            return facts
        }

        internal suspend fun appendMakerNote(reader: BinaryReader, range: ByteRange, note: Bytes, budget: ParseBudget): CoreResult<Bytes> = attempt {
            val document = TiffReader(reader, budget).read(range).orThrow()
            val payload = ExifPatchBuilder(reader, budget).appendMakerNote(range, note)
            val readback = TiffReader(BinaryReader(ExifPayloadSource(payload), reader.context), budget)
                .read(ByteRange(6uL, (payload.size - 6).toULong())).orThrow()
            if (ordinary(document, budget, 0x927cu.toUShort()) != ordinary(readback, budget, 0x927cu.toUShort()))
                fail("POSTCONDITION_FAILED", "MakerNote append changed ordinary EXIF values", Stage.Verify)
            reader.validateIdentity().orThrow()
            payload
        }

        private data class OrdinaryField(val role: String, val tag: UShort, val type: UShort, val count: UInt, val value: Bytes?, val rawUnknown: Bytes?)
        private fun ordinary(document: TiffDocument, budget: ParseBudget, ownedExifTag: UShort = 0x9286u.toUShort()): List<OrdinaryField> {
            var count = 0uL
            for (ifd in document.ifds) count = checkedAdd(count, ifd.entries.size.toULong())
            budget.retain(checkedMultiply(count, 128uL))
            val root = document.ifds.singleOrNull { it.relativeOffset == document.firstIfdOffset }
            fun target(tag: UShort): UInt? = root?.entries?.singleOrNull { it.tag == tag }?.value?.let { readUnsigned(it, document.endian).toUInt() }
            val exif = target(0x8769u.toUShort()); val gps = target(0x8825u.toUShort())
            return document.ifds.flatMap { ifd ->
                val role = when (ifd.relativeOffset) { document.firstIfdOffset -> "primary"; exif -> "exif"; gps -> "gps"; else -> "opaque-${ifd.relativeOffset}" }
                ifd.entries.filter { !(role == "primary" && it.tag == 0x8769u.toUShort()) && !(role == "exif" && it.tag == ownedExifTag) }
                    .map { budget.item(1u); OrdinaryField(role, it.tag, it.type, it.count, it.value, if (it.value == null) it.rawValueField else null) }
            }.sortedWith(compareBy({ it.role }, { it.tag.toUInt() }))
        }
    }
}

internal class ExifMarkerWriter(private val reader: BinaryReader, private val budget: ParseBudget = ParseBudget(reader.context)) {
    suspend fun createMarkerAppPayload(): CoreResult<ExifMarkerPatch> = ExifMarkerPatch.create(reader, budget)
    suspend fun rewriteMarker(tiffRange: ByteRange, action: ExifMarkerAction): CoreResult<ExifMarkerPatch> = ExifMarkerPatch.rewrite(reader, budget, tiffRange, action)
}

/** Bounded proof for unchanged TIFF bytes containing only standardized position-independent fields. */
internal class ExifPositionIndependenceProof private constructor(val sourceIdentity: SourceIdentity, val range: ByteRange, val digest: Digest) {
    companion object {
        suspend fun prove(reader: BinaryReader, range: ByteRange, budget: ParseBudget): CoreResult<ExifPositionIndependenceProof> = attempt {
            val identity = reader.identity().orThrow()
            ExifPatchBuilder(reader, budget).provePositionIndependence(range)
            val digest = sha256Range(reader, range).orThrow()
            reader.validateIdentity().orThrow()
            ExifPositionIndependenceProof(identity, range, digest)
        }
    }
}

private val exifHeader = Bytes(byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0))
private const val MAX_TIFF_LENGTH = 65527
private data class BuiltPatch(val payload: Bytes, val change: ExifCommentChange, val noOp: Boolean, val document: TiffDocument)

/** Append tables without moving any preexisting value or changing its TIFF offset base. */
private class ExifPatchBuilder(private val reader: BinaryReader, private val budget: ParseBudget) {
    /** Add only a new MakerNote; existing standardized values keep their TIFF base and offsets. */
    suspend fun appendMakerNote(range: ByteRange, note: Bytes): Bytes {
        provePositionIndependence(range) // Reject private fields, thumbnails, aliases and nonzero slack.
        val document = TiffReader(reader, budget).read(range).orThrow()
        val root = document.ifds.single { it.relativeOffset == document.firstIfdOffset }
        val pointer = root.entries.singleOrNull { it.tag == 0x8769u.toUShort() }
        if (pointer != null && (pointer.type != 4u.toUShort() || pointer.count != 1u)) unsafe("MakerNote requires a formal ExifIFD pointer")
        val exif = pointer?.value?.let { value -> document.ifds.singleOrNull { it.relativeOffset == readUnsigned(value, document.endian).toUInt() } }
        if (pointer != null && exif == null) unsafe("Unresolved ExifIFD cannot own a new MakerNote")
        if (root.entries.size >= 65535 || (exif?.entries?.size ?: 0) >= 65535) unsafe("IFD cannot accept a new field")
        val newRoot = checkedAdd(range.length, range.length and 1uL)
        val rootLength = if (pointer == null) checkedAdd(6uL, checkedMultiply((root.entries.size + 1).toULong(), 12uL)) else 0uL
        val newExif = checkedAdd(newRoot, rootLength)
        val retained = exif?.entries.orEmpty()
        val noteOffset = checkedAdd(newExif, checkedAdd(6uL, checkedMultiply((retained.size + 1).toULong(), 12uL)))
        val length = checkedAdd(noteOffset, note.size.toULong())
        if (note.size <= 4 || length > MAX_TIFF_LENGTH.toULong()) unsafe("MakerNote append exceeds the supported APP1 allocation")
        budget.retain(checkedMultiply(checkedAdd(length, 6uL), 4uL))
        val original = reader.readExactly(range.offset, checkedInt(range.length).toUInt()).orThrow()
        val output = ByteArray(checkedInt(length))
        original.copyInto(output, 0)
        if (pointer == null) {
            put(output, 4, newRoot, 4, document.endian)
            put(output, checkedInt(newRoot), (root.entries.size + 1).toULong(), 2, document.endian)
            val entries = (root.entries.map { it.tag.toUInt() to it } + (0x8769u to null)).sortedBy { it.first }
            for ((index, entry) in entries.withIndex()) {
                val position = checkedInt(newRoot) + 2 + index * 12
                if (entry.second == null) field(output, position, 0x8769u, 4u, 1u, newExif, document.endian)
                else copyEntry(original, output, position, entry.second!!, range)
            }
        } else put(output, checkedInt(pointer.entryRange.offset - range.offset) + 8, newExif, 4, document.endian)
        put(output, checkedInt(newExif), (retained.size + 1).toULong(), 2, document.endian)
        val entries = (retained.map { it.tag.toUInt() to it } + (0x927cu to null)).sortedBy { it.first }
        for ((index, entry) in entries.withIndex()) {
            val position = checkedInt(newExif) + 2 + index * 12
            if (entry.second == null) field(output, position, 0x927cu, 7u, note.size.toUInt(), noteOffset, document.endian)
            else copyEntry(original, output, position, entry.second!!, range)
        }
        note.copyInto(output, checkedInt(noteOffset))
        return payload(output)
    }

    suspend fun provePositionIndependence(range: ByteRange) {
        if (range.length > MAX_TIFF_LENGTH.toULong()) unsafe("EXIF exceeds one APP1 payload")
        val document = TiffReader(reader, budget).read(range).orThrow()
        val root = document.ifds.singleOrNull { it.relativeOffset == document.firstIfdOffset }
            ?: unsafe("Position independence requires a parsed primary IFD")
        safe(document, root)
        // Unknown nonzero slack could contain private dependencies even without a MakerNote tag.
        // Classify every byte as header, parsed table/value, or zero alignment; never guess it away.
        var extents = checkedAdd(1uL, document.ifds.size.toULong())
        for (ifd in document.ifds) extents = checkedAdd(extents, ifd.entries.size.toULong())
        budget.retain(checkedMultiply(extents, 96uL))
        val covered = (listOf(ByteRange(range.offset, 8uL)) + document.ifds.flatMap { ifd ->
            listOf(ByteRange(checkedAdd(range.offset, ifd.relativeOffset.toULong()), checkedAdd(6uL, checkedMultiply(ifd.entries.size.toULong(), 12uL)))) +
                ifd.entries.map { it.valueRange ?: unsafe("Unclassified EXIF value") }
        }).sortedBy { it.offset }
        suspend fun zeroGap(start: ULong, end: ULong) {
            var offset = start
            while (offset < end) {
                budget.poll()
                val bytes = reader.readBuffer(offset, minOf(4096uL, end - offset).toUInt()).orThrow()
                if (bytes.size == 0) fail("UNEXPECTED_EOF", "EXIF proof gap was truncated")
                if ((0 until bytes.size).any { bytes[it] != 0.toByte() }) unsafe("Unreferenced nonzero EXIF bytes may contain unknown dependencies")
                offset = checkedAdd(offset, bytes.size.toULong())
            }
        }
        var cursor = range.offset
        for (extent in covered) {
            budget.poll()
            if (extent.offset > cursor) zeroGap(cursor, extent.offset)
            cursor = maxOf(cursor, extent.endExclusive)
        }
        zeroGap(cursor, range.endExclusive)
    }

    fun create(): Bytes {
        val marker = markerBytes()
        val length = 44 + marker.size
        budget.retain(checkedMultiply((length + 6).toULong(), 4uL))
        val tiff = ByteArray(length)
        tiff[0] = 0x49; tiff[1] = 0x49
        put(tiff, 2, 42uL, 2, Endian.Little); put(tiff, 4, 8uL, 4, Endian.Little)
        put(tiff, 8, 1uL, 2, Endian.Little)
        field(tiff, 10, 0x8769u, 4u, 1u, 26uL, Endian.Little)
        put(tiff, 26, 1uL, 2, Endian.Little)
        field(tiff, 28, 0x9286u, 7u, marker.size.toUInt(), 44uL, Endian.Little)
        marker.copyInto(tiff, 44)
        return payload(tiff)
    }

    suspend fun rewrite(range: ByteRange, action: ExifMarkerAction): BuiltPatch {
        if (range.length > MAX_TIFF_LENGTH.toULong()) unsafe("EXIF exceeds one APP1 payload")
        val facts = ExifUserCommentReader(reader, budget).read(range).orThrow()
        val document = facts.document
        val comment = facts.comments.singleOrNull()
        if (action == ExifMarkerAction.AddCanonical && comment != null && comment.marker == null) fail("CONFLICTING_METADATA", "Ordinary or undecodable UserComment requires explicit preservation authorization")
        val previous = comment?.marker
        val desired = if (action == ExifMarkerAction.AddCanonical) OplusCommentMarker.Modern else null
        budget.retain(checkedMultiply(checkedAdd(range.length, 6uL), 4uL))
        val original = reader.readExactly(range.offset, checkedInt(range.length).toUInt()).orThrow()
        if ((action == ExifMarkerAction.RemoveOwned && previous == null) || (action == ExifMarkerAction.AddCanonical && previous == desired)) {
            return BuiltPatch(payload(original.toByteArray()), ExifCommentChange(previous, previous), true, document)
        }
        val root = document.ifds.singleOrNull { it.relativeOffset == document.firstIfdOffset } ?: unsafe("Empty root IFD is not supported for existing EXIF edits")
        safe(document, root)
        val exifPointer = root.entries.singleOrNull { it.tag == 0x8769u.toUShort() }
        val exifOffset = exifPointer?.value?.let { readUnsigned(it, document.endian).toUInt() }
        val exif = exifOffset?.let { offset -> document.ifds.singleOrNull { it.relativeOffset == offset } }
        if (exifPointer != null && exif == null) unsafe("Zero/unresolved ExifIFD pointers cannot be edited")
        val retained = exif?.entries?.filter { it.tag != 0x9286u.toUShort() } ?: emptyList()
        val newCount = retained.size + if (desired == null) 0 else 1
        if (newCount > 65535) unsafe("Replacement ExifIFD has too many entries")
        val rootAppendLength = if (exifPointer == null) checkedAdd(6uL, checkedMultiply((root.entries.size + 1).toULong(), 12uL)) else 0uL
        if (root.entries.size == 65535 && exifPointer == null) unsafe("Root IFD cannot accept an ExifIFD pointer")
        val exifTableLength = checkedAdd(6uL, checkedMultiply(newCount.toULong(), 12uL))
        val alignedEnd = checkedAdd(range.length, range.length and 1uL)
        val newRootOffset = alignedEnd
        val newExifOffset = checkedAdd(newRootOffset, rootAppendLength)
        val marker = if (desired == null) null else markerBytes()
        val newValueOffset = checkedAdd(newExifOffset, exifTableLength)
        val length = checkedAdd(newValueOffset, marker?.size?.toULong() ?: 0uL)
        if (length > MAX_TIFF_LENGTH.toULong()) unsafe("Safe EXIF append exceeds one APP1 payload")
        budget.retain(checkedMultiply(checkedAdd(length, 6uL), 4uL))
        val output = ByteArray(checkedInt(length))
        original.copyInto(output, 0)
        if (comment != null && previous != null) {
            val valueRange = comment.entry.valueRange ?: unsafe("Owned marker range is unresolved")
            exclusive(valueRange, comment.entry, document)
            output.fill(0, checkedInt(valueRange.offset - range.offset), checkedInt(valueRange.endExclusive - range.offset))
        }
        if (exifPointer == null) {
            put(output, 4, newRootOffset, 4, document.endian)
            put(output, checkedInt(newRootOffset), (root.entries.size + 1).toULong(), 2, document.endian)
            val rootEntries = mutableListOf<Pair<UInt, TiffEntry?>>()
            rootEntries.addAll(root.entries.map { it.tag.toUInt() to it })
            rootEntries.add(0x8769u to null)
            for ((index, pair) in rootEntries.sortedBy { it.first }.withIndex()) {
                val position = checkedInt(newRootOffset) + 2 + index * 12
                if (pair.second == null) field(output, position, 0x8769u, 4u, 1u, newExifOffset, document.endian)
                else copyEntry(original, output, position, pair.second!!, range)
            }
        } else put(output, checkedInt(exifPointer.entryRange.offset - range.offset) + 8, newExifOffset, 4, document.endian)
        put(output, checkedInt(newExifOffset), newCount.toULong(), 2, document.endian)
        val newEntries = mutableListOf<Pair<UInt, TiffEntry?>>()
        newEntries.addAll(retained.map { it.tag.toUInt() to it })
        if (desired != null) newEntries.add(0x9286u to null)
        for ((index, pair) in newEntries.sortedBy { it.first }.withIndex()) {
            val position = checkedInt(newExifOffset) + 2 + index * 12
            if (pair.second == null) field(output, position, 0x9286u, 7u, marker!!.size.toUInt(), newValueOffset, document.endian)
            else copyEntry(original, output, position, pair.second!!, range)
        }
        marker?.copyInto(output, checkedInt(newValueOffset))
        return BuiltPatch(payload(output), ExifCommentChange(previous, desired), false, document)
    }

    private fun safe(document: TiffDocument, root: TiffIfd) {
        if (document.ifds.size > 3) unsafe("Only primary, Exif, and GPS IFDs are supported by safe edits")
        val tables = document.ifds.map { ByteRange(checkedAdd(document.range.offset, it.relativeOffset.toULong()), checkedAdd(6uL, checkedMultiply(it.entries.size.toULong(), 12uL))) }
        for ((index, table) in tables.withIndex()) {
            budget.item(1u)
            if (overlaps(table, ByteRange(document.range.offset, 8uL))) unsafe("IFD table aliases the TIFF header")
            if (tables.take(index).any { overlaps(table, it) }) unsafe("IFD tables overlap")
        }
        for (ifd in document.ifds) {
            budget.item(1u)
            if (ifd.nextOffset != 0u) unsafe("Thumbnail/next-IFD metadata cannot be rewritten by this subset")
            if (ifd.entries.map { it.tag }.distinct().size != ifd.entries.size) unsafe("Duplicate TIFF fields cannot be rewritten")
            for (entry in ifd.entries) {
                budget.item(1u)
                if (entry.isOpaqueMakerNote || entry.value == null) unsafe("MakerNote or unknown TIFF field types cannot be rewritten")
                if (entry.tag == 0x014au.toUShort() || entry.tag == 0xa005u.toUShort() || entry.tag in offsetSensitiveTags) unsafe("Offset-sensitive metadata is outside the safe EXIF subset")
                if (ifd.relativeOffset == root.relativeOffset && entry.tag !in safeRootTags) unsafe("Unknown primary TIFF tag is outside the safe EXIF subset")
                val isExif = root.entries.any { it.tag == 0x8769u.toUShort() && it.value != null && readUnsigned(it.value, document.endian).toUInt() == ifd.relativeOffset }
                if (isExif && entry.tag !in safeExifTags) unsafe("Unknown ExifIFD tag is outside the safe EXIF subset")
                val isGps = root.entries.any { it.tag == 0x8825u.toUShort() && it.value != null && readUnsigned(it.value, document.endian).toUInt() == ifd.relativeOffset }
                if (ifd.relativeOffset != root.relativeOffset && !isExif && !isGps) unsafe("Unknown IFD semantics are outside the safe EXIF subset")
                if (isGps && entry.tag.toUInt() > 0x1fu) unsafe("Unknown GPS field is outside the safe EXIF subset")
                val value = entry.valueRange ?: unsafe("Unknown value range")
                if (value.length > 4uL && (overlaps(value, ByteRange(document.range.offset, 8uL)) || tables.any { overlaps(value, it) })) unsafe("Out-of-line metadata aliases an IFD table/header")
            }
        }
        // No table or retained value may alias bytes changed in the original header/pointer/value.
        val mutation = root.entries.singleOrNull { it.tag == 0x8769u.toUShort() }?.let { ByteRange(it.entryRange.offset + 8uL, 4uL) }
            ?: ByteRange(document.range.offset + 4uL, 4uL)
        for (ifd in document.ifds) for (entry in ifd.entries) {
            val value = entry.valueRange ?: unsafe("Unknown value range")
            if (entry.tag != 0x8769u.toUShort() && overlaps(value, mutation)) unsafe("Metadata aliases an EXIF pointer to be changed")
        }
    }

    private fun exclusive(value: ByteRange, owned: TiffEntry, document: TiffDocument) {
        if (overlaps(value, ByteRange(document.range.offset, 8uL))) unsafe("Owned marker aliases TIFF header")
        for (ifd in document.ifds) {
            val table = ByteRange(checkedAdd(document.range.offset, ifd.relativeOffset.toULong()), checkedAdd(6uL, checkedMultiply(ifd.entries.size.toULong(), 12uL)))
            if (overlaps(value, table)) unsafe("Owned marker aliases an IFD table")
            for (entry in ifd.entries) if (entry != owned && entry.valueRange?.let { overlaps(value, it) } == true) unsafe("Owned marker aliases ordinary metadata")
        }
    }

    private fun copyEntry(original: Bytes, output: ByteArray, position: Int, entry: TiffEntry, range: ByteRange) {
        val start = checkedInt(entry.entryRange.offset - range.offset)
        original.slice(start, start + 12).copyInto(output, position)
    }
    private fun markerBytes(): Bytes {
        budget.retain(checkedMultiply((8 + OplusCommentMarker.Modern.text.length + 1).toULong(), 4uL))
        return Bytes(("ASCII\u0000\u0000\u0000" + OplusCommentMarker.Modern.text + "\u0000").encodeToByteArray())
    }
    private fun payload(tiff: ByteArray): Bytes { val output = ByteArray(tiff.size + 6); exifHeader.copyInto(output, 0); tiff.copyInto(output, 6); return Bytes(output) }
    private fun field(output: ByteArray, position: Int, tag: UInt, type: UInt, count: UInt, value: ULong, endian: Endian) {
        put(output, position, tag.toULong(), 2, endian); put(output, position + 2, type.toULong(), 2, endian)
        put(output, position + 4, count.toULong(), 4, endian); put(output, position + 8, value, 4, endian)
    }
    private fun put(output: ByteArray, position: Int, value: ULong, width: Int, endian: Endian) { unsignedBytes(value, width, endian).copyInto(output, position) }
    private fun overlaps(a: ByteRange, b: ByteRange): Boolean = a.length != 0uL && b.length != 0uL && a.offset < b.endExclusive && b.offset < a.endExclusive
    private fun unsafe(message: String): Nothing = fail("UNSAFE_METADATA_REWRITE", message)

    private val offsetSensitiveTags = setOf(0x0111u,0x0117u,0x0144u,0x0145u,0x0201u,0x0202u).map { it.toUShort() }.toSet()
    private val safeRootTags = setOf(0x0100u,0x0101u,0x010eu,0x010fu,0x0110u,0x0112u,0x011au,0x011bu,0x0128u,0x0131u,0x0132u,0x013bu,0x8298u,0x8769u,0x8825u).map { it.toUShort() }.toSet()
    private val safeExifTags = setOf(0x829au,0x829du,0x8827u,0x9000u,0x9003u,0x9004u,0x9010u,0x9011u,0x9012u,0x9201u,0x9202u,0x9203u,0x9204u,0x9205u,0x9206u,0x9207u,0x9208u,0x9209u,0x920au,0x9286u,0xa001u,0xa002u,0xa003u,0xa217u,0xa300u,0xa301u,0xa401u,0xa402u,0xa403u,0xa404u,0xa405u,0xa406u,0xa407u,0xa408u,0xa409u,0xa40au,0xa40cu,0xa431u,0xa432u,0xa433u,0xa434u,0xa435u).map { it.toUShort() }.toSet()
}

private class ExifPayloadSource(private val bytes: Bytes): BinarySource {
    private val identity = SourceIdentity(SourceId("internal-exif-readback"), GenerationToken("immutable-payload"), bytes.size.toULong())
    override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(identity)
    override suspend fun size(): CoreResult<ULong> = CoreResult.Success(identity.size)
    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = attemptNow {
        checkedRange(offset, length.toULong(), identity.size)
        bytes.slice(checkedInt(offset), checkedInt(checkedAdd(offset, length.toULong())))
    }
    override suspend fun close() {}
}
