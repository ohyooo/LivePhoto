package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

private fun corrupt(message: String): Nothing = fail("CORRUPTED_CONTAINER", message)
private fun unsupported(message: String): Nothing = fail("UNSUPPORTED_CONTAINER", message)
private class ConfigCursor(val bytes: Bytes) {
    var index = 0
    fun byte(): Int { if (index >= bytes.size) corrupt("Codec configuration is truncated"); return bytes[index++].toInt() and 255 }
    fun word(): Int = (byte() shl 8) or byte()
    fun skip(count: Int) { if (count < 0 || count > bytes.size - index) corrupt("Codec configuration range exceeds its payload"); index += count }
    fun end() { if (index != bytes.size) corrupt("Codec configuration has unexpected trailing bytes") }
}

internal fun avcConfig(bytes: Bytes, budget: ParseBudget? = null, inBand: Boolean = false): Int {
    val cursor = ConfigCursor(bytes)
    if (cursor.byte() != 1) unsupported("AVC configuration version is not implemented")
    val profile = cursor.byte(); cursor.byte(); cursor.byte()
    val length = cursor.byte()
    if (length and 0xfc != 0xfc || length and 3 == 2) corrupt("AVC NAL length field/reserved bits are invalid")
    val width = (length and 3) + 1
    val spsCount = cursor.byte()
    if (spsCount and 0xe0 != 0xe0) corrupt("AVC configuration SPS reserved bits are invalid")
    if (spsCount and 31 == 0) { if (inBand) unsupported("In-band AVC parameter-set discovery is not implemented"); corrupt("AVC configuration has no SPS") }
    fun nal(type: Int) {
        budget?.item(8u)
        val size = cursor.word()
        if (size == 0 || size > bytes.size - cursor.index) corrupt("AVC parameter set is truncated")
        val header = cursor.byte()
        if (header and 128 != 0 || header and 31 != type) corrupt("AVC parameter set NAL type is invalid")
        cursor.skip(size - 1)
    }
    repeat(spsCount and 31) { nal(7) }
    val ppsCount = cursor.byte()
    if (ppsCount == 0) { if (inBand) unsupported("In-band AVC parameter-set discovery is not implemented"); corrupt("AVC configuration has no PPS") }
    repeat(ppsCount) { nal(8) }
    if (cursor.index < bytes.size) {
        if (profile !in setOf(100,110,122,144,44,83,86,118,128,138,139,134,135)) corrupt("Unexpected AVC configuration extension")
        if (cursor.byte() and 0xfc != 0xfc || cursor.byte() and 0xf8 != 0xf8 || cursor.byte() and 0xf8 != 0xf8) corrupt("AVC extension reserved bits are invalid")
        repeat(cursor.byte()) { nal(13) }
    }
    cursor.end()
    return width
}

internal fun hevcConfig(bytes: Bytes, budget: ParseBudget? = null, inBand: Boolean = false): Int {
    val cursor = ConfigCursor(bytes)
    if (cursor.byte() != 1) unsupported("HEVC configuration version is not implemented")
    cursor.skip(12)
    val spatial = cursor.word()
    if (spatial and 0xf000 != 0xf000) corrupt("HEVC spatial segmentation reserved bits are invalid")
    if (cursor.byte() and 0xfc != 0xfc || cursor.byte() and 0xfc != 0xfc || cursor.byte() and 0xf8 != 0xf8 || cursor.byte() and 0xf8 != 0xf8) corrupt("HEVC configuration reserved bits are invalid")
    cursor.skip(2)
    val width = (cursor.byte() and 3) + 1
    val arrays = cursor.byte()
    val types = mutableSetOf<Int>()
    val complete = mutableSetOf<Int>()
    repeat(arrays) {
        budget?.item(8u)
        val descriptor = cursor.byte()
        if (descriptor and 64 != 0) corrupt("HEVC NAL array reserved bit is invalid")
        val type = descriptor and 63
        if (descriptor and 128 != 0) complete.add(type)
        if (!types.add(type)) corrupt("Duplicate HEVC NAL array")
        val count = cursor.word()
        if (count == 0) corrupt("HEVC parameter array is empty")
        repeat(count) {
            budget?.item(8u)
            val size = cursor.word()
            if (size < 2 || size > bytes.size - cursor.index) corrupt("HEVC parameter set is truncated")
            val first = cursor.byte(); val second = cursor.byte()
            if (first and 128 != 0 || (first shr 1) and 63 != type || second and 7 == 0) corrupt("HEVC parameter set NAL header is invalid")
            cursor.skip(size - 2)
        }
    }
    cursor.end()
    if (!types.containsAll(setOf(32,33,34))) { if (inBand) unsupported("In-band HEVC parameter-set discovery is not implemented"); corrupt("HEVC configuration has no VPS/SPS/PPS") }
    if (!inBand && !complete.containsAll(setOf(32,33,34))) unsupported("Incomplete HEVC configuration arrays are not implemented")
    return width
}

internal data class AacConfiguration(val actualSampleRate: UInt, val channelCount: UInt, val descriptorHintsOffset: Int, val esDescriptorFlags: Int)

internal fun validateEsds(bytes: Bytes, budget: ParseBudget? = null): AacConfiguration {
    val cursor = ConfigCursor(bytes)
    repeat(4) { if (cursor.byte() != 0) unsupported("ESDS FullBox version/flags are not implemented") }
    data class Descriptor(val tag: Int, val end: Int)
    fun descriptor(parentEnd: Int): Descriptor {
        budget?.item(8u)
        if (cursor.index >= parentEnd) corrupt("ESDS descriptor is absent")
        val tag = cursor.byte()
        var size = 0
        var terminated = false
        for (index in 0 until 4) {
            if (cursor.index >= parentEnd) corrupt("ESDS descriptor length is truncated")
            val next = cursor.byte(); size = (size shl 7) or (next and 127)
            if (next and 128 == 0) { terminated = true; break }
        }
        if (!terminated || size > parentEnd - cursor.index) corrupt("ESDS descriptor length exceeds its parent")
        return Descriptor(tag, cursor.index + size)
    }
    val es = descriptor(bytes.size)
    if (es.tag != 3) corrupt("ESDS has no ES descriptor")
    fun within(end: Int, count: Int) { if (count > end - cursor.index) corrupt("ESDS descriptor fields are truncated") }
    within(es.end, 3); cursor.skip(2); val flags = cursor.byte()
    if (flags and 128 != 0) { within(es.end, 2); cursor.skip(2) }
    if (flags and 64 != 0) { within(es.end, 1); val size = cursor.byte(); within(es.end, size); cursor.skip(size) }
    if (flags and 32 != 0) { within(es.end, 2); cursor.skip(2) }
    val decoder = descriptor(es.end)
    if (decoder.tag != 4) corrupt("ESDS has no decoder config")
    within(decoder.end, 13)
    if (cursor.byte() != 0x40) unsupported("Only MPEG-4 AAC audio is implemented")
    val stream = cursor.byte()
    if (stream shr 2 != 5 || stream and 1 != 1 || stream and 2 != 0) unsupported("Audio ESDS stream type is not implemented")
    val descriptorHintsOffset = cursor.index
    cursor.skip(11)
    val specific = descriptor(decoder.end)
    if (specific.tag != 5 || specific.end - cursor.index < 2) corrupt("AAC decoder-specific config is absent")
    val specificBytes = bytes.slice(cursor.index, specific.end)
    var bit = 0
    fun bits(count: Int): Int {
        if (count.toULong() > specificBytes.size.toULong() * 8uL - bit.toULong()) corrupt("AAC AudioSpecificConfig is truncated")
        var value = 0
        repeat(count) { value = (value shl 1) or ((specificBytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1); bit++ }
        return value
    }
    if (bits(5) != 2) unsupported("Only AAC-LC AudioSpecificConfig is implemented")
    val frequency = bits(4)
    val actualSampleRate = if (frequency == 15) bits(24).toUInt().also { if (it == 0u) corrupt("AAC sample rate is zero") }
    else {
        if (frequency > 12) corrupt("AAC frequency index is invalid")
        intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)[frequency].toUInt()
    }
    val channelConfiguration = bits(4)
    if (channelConfiguration !in 1..7) unsupported("AAC channel layout is not implemented")
    val channelCount = if (channelConfiguration == 7) 8u else channelConfiguration.toUInt()
    if (bits(1) != 0 || bits(1) != 0 || bits(1) != 0) unsupported("AAC frame/core-coder/extension flags are not implemented")
    if (specificBytes.size != if (frequency == 15) 5 else 2) unsupported("Additional AAC sync-extension configuration is not implemented")
    cursor.index = specific.end
    if (cursor.index != decoder.end) unsupported("Additional audio decoder descriptors are not implemented")
    val sl = descriptor(es.end)
    if (sl.tag != 6 || sl.end - cursor.index != 1 || cursor.byte() != 2) unsupported("Audio SL configuration is not implemented")
    if (cursor.index != es.end) unsupported("Additional ES descriptors are not implemented")
    cursor.end()
    return AacConfiguration(actualSampleRate, channelCount, descriptorHintsOffset, flags)
}
