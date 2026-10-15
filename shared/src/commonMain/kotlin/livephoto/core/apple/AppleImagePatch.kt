package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.exif.*
import livephoto.core.jpeg.*
import livephoto.core.memory.MemoryBinarySource

/** Add a CID-only MakerNote, never replace an existing private block. */
internal class AppleImagePatch private constructor(val sourceIdentity: SourceIdentity, val payload: Bytes,
    val originalTiffRange: ByteRange? = null, val originalTiffDigest: Digest? = null) {
    companion object {
        suspend fun create(reader: BinaryReader, identifier: String, budget: ParseBudget): CoreResult<AppleImagePatch> = attempt {
            if (!appleUuid(identifier)) fail("INVALID_PAIR_IDENTIFIER", "Apple output requires a UUID", Stage.Plan)
            val identity = reader.identity().orThrow()
            val jpeg = JpegParser.parse(reader, budget).orThrow()
            val existing = jpeg.segments.filter { it.payloadKind == AppPayloadKind.Exif }
            if (existing.size > 1 || jpeg.hasMpf || jpeg.hasExtendedXmp || jpeg.trailing.length != 0uL)
                fail("UNSAFE_METADATA_REWRITE", "Apple EXIF needs one safe metadata graph without MPF, extended XMP or trailing resources", Stage.Plan)
            fun u16(value: ULong) = unsignedBytes(value, 2, Endian.Big).toByteArray()
            fun u32(value: ULong) = unsignedBytes(value, 4, Endian.Big).toByteArray()
            val text = (identifier + "\u0000").encodeToByteArray()
            // Formal Apple header + one tag17 + next-IFD=0 + its disjoint UUID value.
            val note = "Apple iOS\u0000".encodeToByteArray() + byteArrayOf(0, 1, 77, 77) + u16(1uL) +
                u16(17uL) + u16(2uL) + u32(text.size.toULong()) + u32(32uL) + u32(0uL) + text
            if (existing.isNotEmpty()) {
                val oldPayload = existing.single().payload!!
                val range = ByteRange(oldPayload.offset + 6uL, oldPayload.length - 6uL)
                val payload = ExifMarkerPatch.appendMakerNote(reader, range, Bytes(note), budget).orThrow()
                val source = MemoryBinarySource(payload, SourceId("apple-existing-exif-proof"))
                val proofReader = BinaryReader(source, reader.context)
                val parsed = TiffReader(proofReader, budget).read(ByteRange(6uL, (payload.size - 6).toULong())).orThrow()
                if (AppleImageReader.readDocuments(proofReader, listOf(parsed), budget).orThrow()?.value != identifier)
                    fail("POSTCONDITION_FAILED", "Appended Apple CID failed independent formal readback", Stage.Verify)
                val digest = sha256Range(reader, range).orThrow()
                reader.validateIdentity().orThrow()
                return@attempt AppleImagePatch(identity, payload, range, digest)
            }
            val tiff = byteArrayOf(77, 77, 0, 42) + u32(8uL) + u16(1uL) + u16(0x8769uL) + u16(4uL) + u32(1uL) + u32(26uL) + u32(0uL) +
                u16(1uL) + u16(0x927cuL) + u16(7uL) + u32(note.size.toULong()) + u32(44uL) + u32(0uL) + note
            val payload = Bytes("Exif\u0000\u0000".encodeToByteArray() + tiff)
            budget.retain(payload.size.toULong())
            val source = MemoryBinarySource(payload, SourceId("apple-new-exif-proof"))
            val parsed = TiffReader(BinaryReader(source, reader.context), budget).read(ByteRange(6uL, tiff.size.toULong())).orThrow()
            if (parsed.ifds.size != 2 || parsed.ifds.sumOf { it.entries.size } != 2 ||
                parsed.ifds.flatMap { it.entries }.singleOrNull { it.isOpaqueMakerNote }?.value != Bytes(note))
                fail("POSTCONDITION_FAILED", "New Apple EXIF failed independent TIFF readback", Stage.Verify)
            reader.validateIdentity().orThrow()
            AppleImagePatch(identity, payload)
        }
    }
}
