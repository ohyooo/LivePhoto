package livephoto.core.bmff

import livephoto.core.google.GoogleFixtures

/** Synthetic headers matching the reported timing relationship; no phone pixels/audio/EXIF. */
internal fun cameraOffsetVideo(composition: ByteArray? = null): ByteArray {
    val edits = GoogleFixtures.u32(2u) + GoogleFixtures.u32(4u) + GoogleFixtures.u32(UInt.MAX_VALUE) + byteArrayOf(0, 1, 0, 0) +
        GoogleFixtures.u32(13331u) + GoogleFixtures.u32(0u) + byteArrayOf(0, 1, 0, 0)
    val bytes = GoogleFixtures.video(hevc = true, co64 = true, editList = GoogleFixtures.fullBox("elst", edits),
        composition = composition, trackDuration = 13335u, sampleDurations = 59991u to 59991u).bytes
    fun field(type: String, offset: Int, value: UInt) {
        val tag = type.encodeToByteArray()
        val position = (4..bytes.size - 4).single { index -> tag.indices.all { bytes[index + it] == tag[it] } } + 4
        GoogleFixtures.u32(value).copyInto(bytes, position + offset)
    }
    field("mvhd", 12, 10000u)
    field("mvhd", 16, 13335u)
    field("mdhd", 12, 90000u)
    field("mdhd", 16, 120015u)
    return bytes
}
