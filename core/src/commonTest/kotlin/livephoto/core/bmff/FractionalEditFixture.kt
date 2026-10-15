package livephoto.core.bmff

import livephoto.core.google.GoogleFixtures

/** Independent tiny framing fixture, not a recording or a decoder/phone claim. */
internal fun fractionalAudioVideo(declaredDuration: UInt = 147750u): ByteArray {
    val edits = GoogleFixtures.u32(2u) + GoogleFixtures.u32(2408u) + GoogleFixtures.u32(UInt.MAX_VALUE) + byteArrayOf(0, 1, 0, 0) +
        GoogleFixtures.u32(28373u) + GoogleFixtures.u32(0u) + byteArrayOf(0, 1, 0, 0)
    val bytes = GoogleFixtures.video(hevc = true, aac = true, sampleDurations = 135000u to 135000u, trackDuration = 30000u,
        audioSampleCount = 133u, audioEditList = GoogleFixtures.fullBox("elst", edits),
        audioTrackDuration = 30781u, audioHeaderDuration = declaredDuration).bytes
    fun field(type: String, offset: Int, value: UInt) {
        val tag = type.encodeToByteArray()
        val position = (4..bytes.size - 4).first { index -> tag.indices.all { bytes[index + it] == tag[it] } } + 4
        GoogleFixtures.u32(value).copyInto(bytes, position + offset)
    }
    field("mvhd", 12, 10000u)
    field("mvhd", 16, 30781u)
    field("mdhd", 12, 90000u)
    field("mdhd", 16, 270000u)
    return bytes
}

internal fun fractionalVideo(): ByteArray {
    val edits = GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u) + GoogleFixtures.u32(UInt.MAX_VALUE) + byteArrayOf(0, 1, 0, 0) +
        GoogleFixtures.u32(200u) + GoogleFixtures.u32(0u) + byteArrayOf(0, 1, 0, 0)
    val bytes = GoogleFixtures.video(editList = GoogleFixtures.fullBox("elst", edits), trackDuration = 201u,
        sampleDurations = 60u to 60u).bytes
    fun field(type: String, offset: Int, value: UInt) {
        val tag = type.encodeToByteArray()
        val position = (4..bytes.size - 4).single { index -> tag.indices.all { bytes[index + it] == tag[it] } } + 4
        GoogleFixtures.u32(value).copyInto(bytes, position + offset)
    }
    field("mvhd", 16, 201u)
    field("mdhd", 12, 600u)
    field("mdhd", 16, 120u)
    return bytes
}

/** The later frame has a valid 90 kHz PTS that integer microseconds cannot encode. */
internal fun nonMicrosecondVideo(): ByteArray {
    val bytes = GoogleFixtures.video(trackDuration = 1376u, sampleDurations = 6190u to 6190u).bytes
    fun field(type: String, offset: Int, value: UInt) {
        val tag = type.encodeToByteArray()
        val position = (4..bytes.size - 4).single { index -> tag.indices.all { bytes[index + it] == tag[it] } } + 4
        GoogleFixtures.u32(value).copyInto(bytes, position + offset)
    }
    field("mvhd", 12, 10000u); field("mvhd", 16, 1376u)
    field("mdhd", 12, 90000u); field("mdhd", 16, 12380u)
    return bytes
}
