package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.exif.TiffReader
import livephoto.core.jpeg.*
import livephoto.core.memory.MemoryBinarySource

/** Authorization for a new CID-only EXIF block, never replacement of an existing MakerNote. */
internal class AppleImagePatch private constructor(val sourceIdentity: SourceIdentity, val payload: Bytes) {
    companion object {
        suspend fun create(reader: BinaryReader, identifier: String, budget: ParseBudget): CoreResult<AppleImagePatch> = attempt {
            if (!appleUuid(identifier)) fail("INVALID_PAIR_IDENTIFIER", "Apple output requires a UUID", Stage.Plan)
            val identity = reader.identity().orThrow()
            val jpeg = JpegParser.parse(reader, budget).orThrow()
            if (jpeg.hasExif || jpeg.hasMpf || jpeg.hasExtendedXmp || jpeg.trailing.length != 0uL)
                fail("UNSAFE_METADATA_REWRITE", "New Apple EXIF is restricted to plain JPEG without existing EXIF, MPF, extended XMP or trailing resources", Stage.Plan)
            fun u16(value: ULong) = unsignedBytes(value, 2, Endian.Big).toByteArray()
            fun u32(value: ULong) = unsignedBytes(value, 4, Endian.Big).toByteArray()
            val text = (identifier + "\u0000").encodeToByteArray()
            // Formal Apple header + one tag17 + next-IFD=0 + its disjoint UUID value.
            val note = "Apple iOS\u0000".encodeToByteArray() + byteArrayOf(0, 1, 77, 77) + u16(1uL) +
                u16(17uL) + u16(2uL) + u32(text.size.toULong()) + u32(32uL) + u32(0uL) + text
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
