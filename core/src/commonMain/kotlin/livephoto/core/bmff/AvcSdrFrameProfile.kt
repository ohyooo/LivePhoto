package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

/** Closed SPS/VUI proof for derived SDR frames, not a generic AVC decoder or capability claim.
 * H.264 sections 7.3.2.1.1 and E.1/E.2: https://www.itu.int/rec/T-REC-H.264 */
internal object AvcSdrFrameProfile {
    fun verify(nal: Bytes, width: UInt, height: UInt): CoreResult<Unit> = attemptNow {
        if (nal.size < 5) unsupported()
        val bits = bits(nal, 0x67)
        val profile = bits.read(8)
        if (profile !in setOf(66L, 77L, 100L) || bits.read(8) and 3L != 0L) unsupported()
        bits.read(8) // level is additionally constrained by dimensions, references and the bounded decoder.
        bits.ue(31)
        if (profile == 100L) {
            // High syntax explicitly proves 4:2:0 and eight-bit luma/chroma.
            // No separate planes, transform bypass or custom scaling matrices.
            if (bits.ue(3) != 1 || bits.ue(6) != 0 || bits.ue(6) != 0 || bits.bit() || bits.bit()) unsupported()
        }
        bits.ue(12)
        when (bits.ue(2)) { 0 -> bits.ue(12); 2 -> Unit; else -> unsupported() }
        bits.ue(16)
        if (bits.bit()) unsupported() // frame_num gaps
        val macroWidth = bits.ue(63) + 1; val macroHeight = bits.ue(63) + 1
        if (!bits.bit()) unsupported() // field/MBAFF pictures are outside this profile.
        bits.bit() // direct_8x8_inference_flag
        var left = 0; var right = 0; var top = 0; var bottom = 0
        if (bits.bit()) { left = bits.ue(512); right = bits.ue(512); top = bits.ue(512); bottom = bits.ue(512) }
        if (macroWidth * 16 - 2 * (left + right) != width.toInt() || macroHeight * 16 - 2 * (top + bottom) != height.toInt()) unsupported()
        if (!bits.bit() || !bits.bit()) unsupported() // Explicit VUI and pixel aspect ratio required.
        when (bits.read(8).toInt()) {
            1 -> Unit
            255 -> if (bits.read(16) != 1L || bits.read(16) != 1L) unsupported()
            else -> unsupported()
        }
        if (bits.bit() && bits.bit()) unsupported() // no declared overscan crop
        if (!bits.bit()) unsupported() // video_signal_type_present_flag
        bits.read(3)
        if (bits.bit() || !bits.bit()) unsupported() // limited range and explicit colour_description
        if (bits.read(8) != 1L || bits.read(8) != 1L || bits.read(8) != 1L) unsupported() // 709 primaries/trc/matrix
        // H.264 E.2.1 infers location 0 when absent, not a guessed decoder default.
        if (bits.bit() && (bits.ue(5) != 0 || bits.ue(5) != 0)) unsupported()
        if (bits.bit()) {
            if (bits.read(32) == 0L || bits.read(32) == 0L) corrupt()
            bits.bit() // fixed_frame_rate_flag does not replace the container presentation timeline.
        }
        if (bits.bit() || bits.bit()) unsupported() // HRD/low-delay profiles not implemented here.
        if (bits.bit()) unsupported() // pic_struct_present_flag requires auxiliary interpretation.
        if (bits.bit()) {
            bits.bit()
            repeat(6) { bits.ue(16) }
        }
        bits.finish()
    }

    /** High PPS extension can declare scaling matrices independently of the SPS. */
    fun verifyHighPps(nal: Bytes): CoreResult<Unit> = attemptNow {
        val bits = bits(nal, 0x68)
        bits.ue(255); bits.ue(31)
        bits.bit() // CABAC/CAVLC: both are handled by the selected software decoder.
        if (bits.bit() || bits.ue(7) != 0) unsupported() // bottom-field ordering/FMO
        bits.ue(31); bits.ue(31)
        bits.bit()
        if (bits.read(2) == 3L) corrupt()
        bits.se(-26, 25); bits.se(-26, 25); bits.se(-12, 12)
        bits.bit(); bits.bit()
        if (bits.bit()) unsupported() // redundant pictures
        if (bits.moreData()) {
            bits.bit() // transform_8x8_mode_flag
            if (bits.bit()) unsupported() // pic_scaling_matrix_present_flag
            bits.se(-12, 12)
        }
        bits.finish()
    }

    /** Container agreement only; never a substitute for the independent SPS/VUI proof.
     * QuickTime colr has nclc + three u16 indexes, no range bit:
     * https://developer.apple.com/documentation/quicktime-file-format/color_parameter_atom */
    fun verifyContainerColour(bytes: Bytes, container: VideoContainer): CoreResult<Unit> = attemptNow {
        val nclx = bytes.size == 11 && bytes.slice(0, 4) == Bytes("nclx".encodeToByteArray()) && bytes[10] == 0.toByte()
        val nclc = container == VideoContainer.Mov && bytes.size == 10 && bytes.slice(0, 4) == Bytes("nclc".encodeToByteArray())
        if ((!nclx && !nclc) || readUnsigned(bytes.slice(4, 6), Endian.Big) != 1uL ||
            readUnsigned(bytes.slice(6, 8), Endian.Big) != 1uL || readUnsigned(bytes.slice(8, 10), Endian.Big) != 1uL)
            fail("HDR_PRESERVATION_UNAVAILABLE", "Container color properties contradict the finite SPS profile", Stage.Plan)
    }

    private fun bits(nal: Bytes, header: Int): Bits {
        if (nal.size !in 2..4096 || nal[0].toInt() and 255 != header) unsupported()
        val rbsp = ByteArray(nal.size - 1)
        var count = 0; var zeros = 0; var index = 1
        while (index < nal.size) {
            var value = nal[index++].toInt() and 255
            if (zeros == 2) {
                if (value == 3) {
                    if (index >= nal.size || nal[index].toInt() and 255 > 3) corrupt()
                    zeros = 0; value = nal[index++].toInt() and 255
                } else if (value < 3) corrupt()
            }
            rbsp[count++] = value.toByte()
            zeros = if (value == 0) zeros + 1 else 0
        }
        return Bits(rbsp.copyOf(count))
    }
    private class Bits(private val bytes: ByteArray) {
        private var at = 0
        val remaining: Int get() = bytes.size * 8 - at
        fun bit(): Boolean = read(1) != 0L
        fun read(size: Int): Long {
            if (size !in 1..32 || size > remaining) corrupt()
            var value = 0L
            repeat(size) { value = (value shl 1) or ((bytes[at / 8].toInt() ushr (7 - at % 8)) and 1).toLong(); at++ }
            return value
        }
        fun ue(maximum: Int): Int {
            var zeros = 0
            while (!bit()) { if (++zeros > 16) corrupt() }
            val value = (1L shl zeros) - 1 + if (zeros == 0) 0L else read(zeros)
            if (value > maximum) unsupported()
            return value.toInt()
        }
        fun se(minimum: Int, maximum: Int): Int {
            val raw = ue(2 * maxOf(-minimum, maximum))
            val value = if (raw and 1 == 0) -raw / 2 else (raw + 1) / 2
            if (value !in minimum..maximum) unsupported()
            return value
        }
        fun moreData(): Boolean {
            val saved = at
            var stopOnly = bit()
            while (remaining > 0) if (bit()) stopOnly = false
            at = saved
            return !stopOnly
        }
        fun finish() {
            if (!bit()) corrupt()
            while (remaining > 0) if (bit()) corrupt()
        }
    }
    private fun unsupported(): Nothing = fail("HDR_PRESERVATION_UNAVAILABLE", "SPS/VUI is outside the explicit progressive square-pixel eight-bit BT.709 limited-range left-chroma frame profile", Stage.Plan)
    private fun corrupt(): Nothing = fail("CORRUPTED_CONTAINER", "Malformed/truncated SPS/VUI frame profile", Stage.Plan)
}
