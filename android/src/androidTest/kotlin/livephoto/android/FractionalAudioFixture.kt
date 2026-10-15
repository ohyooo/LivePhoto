package livephoto.android

/** Synthetic framing/timing only; no recorded image/audio or private device metadata. */
internal fun fractionalAudioVideo(): ByteArray {
    fun u32(value: Int): ByteArray = ByteArray(4) { (value ushr ((3 - it) * 8)).toByte() }
    fun box(type: String, bytes: ByteArray): ByteArray = u32(bytes.size + 8) + type.encodeToByteArray() + bytes
    fun full(type: String, bytes: ByteArray): ByteArray = box(type, ByteArray(4) + bytes)
    fun put32(bytes: ByteArray, offset: Int, value: Int) { u32(value).copyInto(bytes, offset) }
    fun position(bytes: ByteArray, type: String): Int {
        val tag = type.encodeToByteArray()
        return (4..bytes.size - 4).single { start -> tag.indices.all { bytes[start + it] == tag[it] } } + 4
    }
    val base = cameraOffsetVideo()
    val moov = position(base, "moov")
    val mdat = position(base, "mdat")
    val tkhd = ByteArray(84)
    tkhd[3] = 1
    put32(tkhd, 12, 2); put32(tkhd, 20, 30781)
    tkhd[36] = 1 // Audio volume, 8.8 fixed point.
    put32(tkhd, 40, 0x10000); put32(tkhd, 56, 0x10000); put32(tkhd, 72, 0x40000000)
    val mdhd = ByteArray(24)
    put32(mdhd, 12, 48000); put32(mdhd, 16, 147750)
    val edits = u32(2) + u32(2408) + u32(-1) + byteArrayOf(0, 1, 0, 0) +
        u32(28373) + u32(0) + byteArrayOf(0, 1, 0, 0)
    val header = ByteArray(28)
    header[7] = 1; header[17] = 2; header[19] = 16
    put32(header, 24, 48000 shl 16)
    val descriptor = byteArrayOf(3, 25, 0, 2, 0, 4, 17, 0x40, 0x15) + ByteArray(11) +
        byteArrayOf(5, 2, 0x11, 0x90.toByte(), 6, 1, 2)
    val stbl = box("stbl",
        full("stsd", u32(1) + box("mp4a", header + full("esds", descriptor))) +
        full("stts", u32(1) + u32(133) + u32(1024)) +
        full("stsc", u32(1) + u32(1) + u32(133) + u32(1)) +
        full("stsz", u32(4) + u32(133)) + full("stco", u32(1) + u32(0)))
    val dataReference = box("dinf", full("dref", u32(1) + box("url ", byteArrayOf(0, 0, 0, 1))))
    val handler = full("hdlr", u32(0) + "soun".encodeToByteArray() + ByteArray(12) + byteArrayOf(0))
    val audio = box("trak", box("tkhd", tkhd) + box("edts", full("elst", edits)) +
        box("mdia", box("mdhd", mdhd) + handler + box("minf", full("smhd", ByteArray(4)) + dataReference + stbl)))
    val samples = ByteArray(133 * 4) { byteArrayOf(0x21, 0x10, 4, 0x60)[it % 4] }
    val bytes = base.copyOfRange(0, moov - 8) + box("moov", base.copyOfRange(moov, mdat - 8) + audio) +
        box("mdat", base.copyOfRange(mdat, base.size) + samples)
    put32(bytes, position(bytes, "mvhd") + 16, 30781)
    put32(bytes, position(bytes, "mvhd") + 96, 3)
    put32(bytes, position(bytes, "co64") + 12, mdat + audio.size)
    put32(bytes, position(bytes, "stco") + 8, mdat + audio.size + 13)
    return bytes
}
