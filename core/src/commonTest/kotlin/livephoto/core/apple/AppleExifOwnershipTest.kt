package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

/** Synthetic overlapping EXIF ranges, not captured-device compatibility evidence. */
class AppleExifOwnershipTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private enum class Allocation { Disjoint, WholeNote, PartialCid }

    private fun image(allocation: Allocation, endian: Endian): ByteArray {
        fun u16(value: ULong) = unsignedBytes(value, 2, endian).toByteArray()
        fun u32(value: ULong) = unsignedBytes(value, 4, endian).toByteArray()
        val original = AppleFixtures.image()
        // Independent fixed layout: TIFF IFD0=8, ExifIFD=38, MakerNote=56.
        val note = original.copyOfRange(56, 56 + 65)
        val ordinary = "normal\u0000".encodeToByteArray()
        val count = when (allocation) { Allocation.Disjoint -> 7uL; Allocation.WholeNote -> 65uL; Allocation.PartialCid -> 5uL }
        val offset = when (allocation) { Allocation.Disjoint -> 121uL; Allocation.WholeNote -> 56uL; Allocation.PartialCid -> 84uL }
        val order = if (endian == Endian.Big) byteArrayOf(77, 77) else byteArrayOf(73, 73)
        val tiff = order + u16(42uL) + u32(8uL) + u16(2uL) +
            u16(0x010euL) + u16(if (allocation == Allocation.WholeNote) 7uL else 2uL) + u32(count) + u32(offset) +
            u16(0x8769uL) + u16(4uL) + u32(1uL) + u32(38uL) + u32(0uL) +
            u16(1uL) + u16(0x927cuL) + u16(7uL) + u32(65uL) + u32(56uL) + u32(0uL) + note + ordinary
        return GoogleFixtures.jpeg(GoogleFixtures.segment(0xe1, "Exif\u0000\u0000".encodeToByteArray() + tiff))
    }

    private fun pair(jpeg: ByteArray, heic: Boolean): SourceSet.Pair = SourceSet.Pair(
        MemoryBinarySource(Bytes(if (heic) AppleHeifFixtures.image(exifJpeg = jpeg) else jpeg), SourceId("ownership-image")),
        MemoryBinarySource(Bytes(AppleFixtures.movie()), SourceId("ownership-movie")))

    @Test fun ordinaryDisjointExifValuesKeepBothJpegAndHeicPairReading(): Unit = runImmediate {
        for (endian in Endian.entries) for (heic in listOf(false, true)) {
            val source = pair(image(Allocation.Disjoint, endian), heic)
            val before = source.image.identity().orThrow()
            val inspected = core.inspect(ReadRequest(source, context)).orThrow()
            assertEquals(true, inspected.pairing?.matches)
            assertEquals(AppleFixtures.ID, inspected.pairing?.imageIdentifier)
            assertEquals(before, source.image.identity().orThrow())
        }
    }

    @Test fun aWholeMakerNoteAliasCannotEstablishAnAuthoritativePair(): Unit = runImmediate {
        for (endian in Endian.entries) for (heic in listOf(false, true)) {
            assertEquals(IssueCode("CONFLICTING_METADATA"), assertIs<CoreResult.Failure>(
                core.inspect(ReadRequest(pair(image(Allocation.WholeNote, endian), heic), context))).error.code)
        }
    }

    @Test fun partialCidSharingIsRejectedRatherThanComparedOnlyByEqualRanges(): Unit = runImmediate {
        for (endian in Endian.entries) for (heic in listOf(false, true)) {
            assertEquals(IssueCode("CONFLICTING_METADATA"), assertIs<CoreResult.Failure>(
                core.inspect(ReadRequest(pair(image(Allocation.PartialCid, endian), heic), context))).error.code)
        }
    }

    @Test fun repairCannotUseOverlappingCidEvidenceAndNeverStagesOutput(): Unit = runImmediate {
        for (heic in listOf(false, true)) {
            val source = pair(image(Allocation.PartialCid, Endian.Big), heic)
            val before = source.image.identity().orThrow()
            val output = MemoryOutputTransaction(context, "overlapping-cid-$heic")
            val request = RepairRequest(source, mode = RepairMode.ExplicitRePair, dryRun = false,
                output = output, context = context)
            assertEquals(IssueCode("CONFLICTING_METADATA"), assertIs<CoreResult.Failure>(core.repair(request)).error.code)
            assertTrue(output.query().orThrow().assetIds.isEmpty())
            assertEquals(before, source.image.identity().orThrow())
        }
    }
}
