package livephoto.core.bmff

import livephoto.core.*
import kotlin.test.*

/** Synthetic SPS parser contracts; not decoder or device evidence. */
class AvcSdrFrameProfileTest {
    private fun sps(profile: Int = 77, primary: Int = 1, transfer: Int = 1, matrix: Int = 1,
        fullRange: Boolean = false, aspect: Int = 1, chroma: Int = 0,
        chromaFormat: Int = 1, lumaBits: Int = 0, chromaBits: Int = 0, bypass: Boolean = false, scaling: Boolean = false): Bytes {
        val bits = mutableListOf<Int>()
        fun write(value: Int, size: Int) { for (shift in size - 1 downTo 0) bits += (value ushr shift) and 1 }
        fun ue(value: Int) {
            val number = value + 1; val size = 32 - number.countLeadingZeroBits()
            repeat(size - 1) { bits += 0 }; write(number, size)
        }
        write(profile, 8); write(0, 8); write(31, 8)
        ue(0)
        if (profile == 100) { ue(chromaFormat); ue(lumaBits); ue(chromaBits); write(if (bypass) 1 else 0, 1); write(if (scaling) 1 else 0, 1) }
        ue(0); ue(0); ue(0); ue(2); write(0, 1)
        ue(3); ue(3); write(1, 1); write(1, 1); write(0, 1)
        write(1, 1); write(1, 1); write(aspect, 8); write(0, 1)
        write(1, 1); write(5, 3); write(if (fullRange) 1 else 0, 1); write(1, 1)
        write(primary, 8); write(transfer, 8); write(matrix, 8)
        write(1, 1); ue(chroma); ue(chroma)
        repeat(5) { write(0, 1) } // no timing, HRD, pic_struct or bitstream restriction
        write(1, 1)
        while (bits.size % 8 != 0) write(0, 1)
        return nal(0x67, bits)
    }
    private fun nal(header: Byte, bits: List<Int>): Bytes {
        val bytes = mutableListOf(header)
        var zeros = 0
        for (start in bits.indices step 8) {
            var value = 0; repeat(8) { value = (value shl 1) or bits[start + it] }
            if (zeros == 2 && value <= 3) { bytes += 3; zeros = 0 }
            bytes += value.toByte(); zeros = if (value == 0) zeros + 1 else 0
        }
        return Bytes(bytes.toByteArray())
    }
    private fun pps(scaling: Boolean = false, groups: Int = 0, redundant: Boolean = false, chromaOffset: Int = 0): Bytes {
        val bits = mutableListOf<Int>()
        fun bit(value: Int) { bits += value }
        fun ue(value: Int) {
            val number = value + 1; val size = 32 - number.countLeadingZeroBits()
            repeat(size - 1) { bit(0) }; for (shift in size - 1 downTo 0) bit((number ushr shift) and 1)
        }
        ue(0); ue(0); bit(1); bit(0); ue(groups); ue(0); ue(0)
        repeat(3) { bit(0) }; ue(0); ue(0); ue(0)
        bit(1); bit(0); bit(if (redundant) 1 else 0)
        bit(1); bit(if (scaling) 1 else 0)
        ue(if (chromaOffset <= 0) -2 * chromaOffset else 2 * chromaOffset - 1)
        bit(1); while (bits.size % 8 != 0) bit(0)
        return nal(0x68, bits)
    }
    @Test fun highPpsCannotIntroduceUnclassifiedScalingOrRedundantPictures() {
        assertIs<CoreResult.Success<Unit>>(AvcSdrFrameProfile.verifyHighPps(pps()))
        for (input in listOf(pps(scaling = true), pps(groups = 1), pps(redundant = true), pps(chromaOffset = 13)))
            assertEquals("HDR_PRESERVATION_UNAVAILABLE", assertIs<CoreResult.Failure>(AvcSdrFrameProfile.verifyHighPps(input)).error.code.value)
        assertIs<CoreResult.Failure>(AvcSdrFrameProfile.verifyHighPps(Bytes(byteArrayOf(0x68))))
        assertIs<CoreResult.Failure>(AvcSdrFrameProfile.verifyHighPps(Bytes(pps().toByteArray() + byteArrayOf(1))))
    }
    @Test fun explicitEightBitProfilesAreAcceptedButNoBroadAvcClaim() {
        for (profile in listOf(66, 77, 100)) assertIs<CoreResult.Success<Unit>>(AvcSdrFrameProfile.verify(sps(profile), 64u, 64u))
    }
    @Test fun unknownHdrRangeChromaAndAspectAreNeverDefaulted() {
        for (nal in listOf(sps(110), sps(primary = 2), sps(primary = 9), sps(transfer = 16), sps(transfer = 18),
            sps(matrix = 9), sps(fullRange = true), sps(aspect = 0), sps(aspect = 2), sps(chroma = 1)))
            assertEquals("HDR_PRESERVATION_UNAVAILABLE", assertIs<CoreResult.Failure>(AvcSdrFrameProfile.verify(nal, 64u, 64u)).error.code.value)
        assertIs<CoreResult.Failure>(AvcSdrFrameProfile.verify(sps(), 62u, 64u))
    }
    @Test fun highSyntaxNeverDefaultsBitDepthChromaOrScaling() {
        for (nal in listOf(sps(100, chromaFormat = 0), sps(100, chromaFormat = 2), sps(100, chromaFormat = 3),
            sps(100, lumaBits = 2), sps(100, chromaBits = 2), sps(100, bypass = true), sps(100, scaling = true),
            sps(100, fullRange = true), sps(100, transfer = 16)))
            assertEquals("HDR_PRESERVATION_UNAVAILABLE", assertIs<CoreResult.Failure>(AvcSdrFrameProfile.verify(nal, 64u, 64u)).error.code.value)
        val high = sps(100)
        for (length in 0 until high.size) assertIs<CoreResult.Failure>(AvcSdrFrameProfile.verify(high.slice(0, length), 64u, 64u))
    }
    @Test fun truncatedAndMalformedRbspNeverPass() {
        val nal = sps()
        for (length in 0 until nal.size) assertIs<CoreResult.Failure>(AvcSdrFrameProfile.verify(nal.slice(0, length), 64u, 64u))
        assertIs<CoreResult.Failure>(AvcSdrFrameProfile.verify(Bytes(nal.toByteArray() + byteArrayOf(1)), 64u, 64u))
        assertIs<CoreResult.Failure>(AvcSdrFrameProfile.verify(Bytes(byteArrayOf(0x67, 0, 0, 3, 4, 0)), 64u, 64u))
    }
}
