package livephoto.core.apple

import livephoto.core.google.GoogleFixtures
import livephoto.core.google.GoogleFixtures.box
import livephoto.core.google.GoogleFixtures.fullBox
import livephoto.core.google.GoogleFixtures.u32

/** Independent synthetic metadata, not a captured Apple-device compatibility fixture. */
internal object AppleFixtures {
    const val ID = "00112233-4455-6677-8899-aabbccddeeff"
    fun image(id: String = ID, tag: Int = 0x11, ordinaryNote: Boolean = false): ByteArray {
        val text = (id + "\u0000").encodeToByteArray()
        val note = "Apple iOS\u0000".encodeToByteArray() + byteArrayOf(0, 1, 77, 77, 0, if (ordinaryNote) 2 else 1) +
            byteArrayOf((tag shr 8).toByte(), tag.toByte(), 0, 2) + u32(text.size.toUInt()) + u32(if (ordinaryNote) 40u else 28u) +
            (if (ordinaryNote) byteArrayOf(0, 1, 0, 4) + u32(1u) + u32(42u) else byteArrayOf()) + text
        // Big-endian TIFF: IFD0 -> ExifIFD -> opaque Apple MakerNote.
        val tiff = byteArrayOf(77, 77, 0, 42) + u32(8u) + byteArrayOf(0, 1, 0x87.toByte(), 0x69, 0, 4) +
            u32(1u) + u32(26u) + u32(0u) + byteArrayOf(0, 1, 0x92.toByte(), 0x7c, 0, 7) +
            u32(note.size.toUInt()) + u32(44u) + u32(0u) + note
        return GoogleFixtures.jpeg(GoogleFixtures.segment(0xe1, "Exif\u0000\u0000".encodeToByteArray() + tiff))
    }

    fun movie(id: String = ID, marker: Boolean = true, duplicateMarker: Boolean = false, payload: Byte = -1,
        timescale: UInt = 1000u, delay: UInt = 40u, composition: UInt = 0u, ordinaryKey: Boolean = true, singleMdat: Boolean = false,
        extraMetadataMedia: ByteArray = byteArrayOf()): ByteArray {
        val base = parts(GoogleFixtures.video().bytes)
        val ftyp = box("ftyp", "qt  ".encodeToByteArray() + u32(0u) + "qt  ".encodeToByteArray())
        val mdat = base.single { type(it) == "mdat" }
        val originalMoov = base.single { type(it) == "moov" }
        val originalTrack = parts(originalMoov.copyOfRange(8, originalMoov.size)).single { type(it) == "trak" }
        fun relocate(bytes: ByteArray): ByteArray {
            val kind = type(bytes)
            return if (kind == "stco") fullBox("stco", u32(1u) + u32((ftyp.size + 8).toUInt()))
            else if (kind == "mdhd") bytes.copyOf().also { u32(timescale).copyInto(it, 20) }
            else if (kind in setOf("trak", "mdia", "minf", "stbl")) box(kind, parts(bytes.copyOfRange(8, bytes.size)).fold(byteArrayOf()) { acc, child -> acc + relocate(child) })
            else bytes
        }
        val value = box("data", u32(1u) + u32(0u) + id.encodeToByteArray())
        val declaration = "mdta$APPLE_CID".encodeToByteArray()
        val meta = box("meta", fullBox("hdlr", u32(0u) + "mdta".encodeToByteArray() + ByteArray(12)) +
            fullBox("keys", u32(1u) + u32((declaration.size + 4).toUInt()) + declaration) + box("ilst", atom(1u, value)))
        val timedKey = atom(1u, box("keyd", "mdta$APPLE_STILL_TIME".encodeToByteArray()) + box("dtyp", u32(0u) + u32(65u))) +
            if (ordinaryKey) atom(2u, box("keyd", "mdtaordinary:synthetic".encodeToByteArray()) + box("dtyp", u32(0u) + u32(65u))) else byteArrayOf()
        val sample = atom(if (marker) 1u else 2u, byteArrayOf(payload)) + if (duplicateMarker) atom(1u, byteArrayOf(payload)) else byteArrayOf()
        val header = ByteArray(8).also { it[7] = 1 }
        val stbl = box("stbl", fullBox("stsd", u32(1u) + box("mebx", header + box("keys", timedKey))) +
            fullBox("stts", u32(1u) + u32(1u) + u32(40u)) + fullBox("stsc", u32(1u) + u32(1u) + u32(1u) + u32(1u)) +
            fullBox("stsz", u32(sample.size.toUInt()) + u32(1u)) + fullBox("stco", u32(1u) + u32((ftyp.size + mdat.size + if (singleMdat) 0 else 8).toUInt())) +
            if (composition == 0u) byteArrayOf() else fullBox("ctts", u32(1u) + u32(1u) + u32(composition)))
        val minf = box("minf", box("gmhd", fullBox("gmin", ByteArray(12))) + box("dinf", fullBox("dref", u32(1u) + box("alis", u32(1u)))) + stbl)
        val tkhd = ByteArray(84).also { u32(2u).copyInto(it, 12); u32(delay + 40u).copyInto(it, 20) }
        val mdhd = ByteArray(24).also { u32(timescale).copyInto(it, 12); u32(40u).copyInto(it, 16) }
        val edits = box("edts", fullBox("elst", u32(2u) + u32(delay) + u32(UInt.MAX_VALUE) + u32(0x10000u) + u32(40u) + u32(0u) + u32(0x10000u)))
        val track = box("trak", box("tkhd", tkhd) + edits + box("mdia", box("mdhd", mdhd) + fullBox("hdlr", u32(0u) + "meta".encodeToByteArray() + ByteArray(12)) + minf + extraMetadataMedia))
        val mvhd = parts(originalMoov.copyOfRange(8, originalMoov.size)).single { type(it) == "mvhd" }.also { u32(timescale).copyInto(it, 20) }
        val media = if (singleMdat) box("mdat", mdat.copyOfRange(8, mdat.size) + sample) else mdat + box("mdat", sample)
        return ftyp + media + box("moov", mvhd + relocate(originalTrack) + track + meta)
    }

    private fun atom(id: UInt, payload: ByteArray) = u32((payload.size + 8).toUInt()) + u32(id) + payload
    private fun type(bytes: ByteArray) = bytes.copyOfRange(4, 8).decodeToString()
    private fun parts(bytes: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            var length = 0
            repeat(4) { length = (length shl 8) or (bytes[offset + it].toInt() and 255) }
            result += bytes.copyOfRange(offset, offset + length)
            offset += length
        }
        return result
    }
}
