package livephoto.core.apple

import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures

/** Independent finite HEIF + formal Apple Exif framing, not camera or decoder evidence. */
internal object AppleHeifFixtures {
    fun image(identifier: String = AppleFixtures.ID, idat: Boolean = false, bias: Int = 6, linked: Boolean = true,
              tag: Int = 0x11, multipleExif: Boolean = false, unknownProperty: Boolean = false): ByteArray {
        fun u(value: UInt, width: Int) = unsignedBytes(value.toULong(), width, Endian.Big).toByteArray()
        fun full(type: String, payload: ByteArray, version: Int = 0) = GoogleFixtures.box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)
        val jpeg = AppleFixtures.image(identifier, tag)
        val appLength = ((jpeg[4].toInt() and 255) shl 8) or (jpeg[5].toInt() and 255)
        val exif = u(bias.toUInt(), 4) + ByteArray(bias) + jpeg.copyOfRange(12, 4 + appLength)
        val sample = GoogleFixtures.video(hevc = true).samples.first()
        val data = sample + if (multipleExif) exif.copyOfRange(0, 2) + byteArrayOf(1, 2, 3) + exif.copyOfRange(2, exif.size) else exif
        val ftyp = GoogleFixtures.box("ftyp", "heic".encodeToByteArray() + u(0u, 4) + "heicmif1".encodeToByteArray())
        fun meta(start: UInt): ByteArray {
            val info = full("iinf", u(2u, 2) + full("infe", u(1u, 2) + u(0u, 2) + "hvc1primary\u0000".encodeToByteArray(), 2) +
                full("infe", u(2u, 2) + u(0u, 2) + "Exifmetadata\u0000".encodeToByteArray(), 2))
            val method = if (idat) u(1u, 2) else byteArrayOf()
            val primary = u(1u, 2) + method + u(0u, 2) + u(1u, 2) + u(start, 4) + u(sample.size.toUInt(), 4)
            val exifStart = start + sample.size.toUInt()
            val extents = if (multipleExif) u(2u, 2) + u(exifStart, 4) + u(2u, 4) + u(exifStart + 5u, 4) + u((exif.size - 2).toUInt(), 4)
                else u(1u, 2) + u(exifStart, 4) + u(exif.size.toUInt(), 4)
            val iloc = full("iloc", byteArrayOf(0x44, 0) + u(2u, 2) + primary + u(2u, 2) + method + u(0u, 2) + extents, if (idat) 1 else 0)
            val properties = GoogleFixtures.box("ipco", full("ispe", u(1u, 4) + u(1u, 4)) +
                GoogleFixtures.box("hvcC", GoogleFixtures.video(hevc = true).configuration) + if (unknownProperty) GoogleFixtures.box("priv", byteArrayOf(9)) else byteArrayOf())
            val associations = if (unknownProperty) byteArrayOf(3, 0x81.toByte(), 0x82.toByte(), 0x83.toByte()) else byteArrayOf(2, 0x81.toByte(), 0x82.toByte())
            val iprp = GoogleFixtures.box("iprp", properties + full("ipma", u(1u, 4) + u(1u, 2) + associations))
            val iref = if (linked) full("iref", GoogleFixtures.box("cdsc", u(2u, 2) + u(1u, 2) + u(1u, 2))) else byteArrayOf()
            return full("meta", full("hdlr", u(0u, 4) + "pict".encodeToByteArray() + ByteArray(12) + byteArrayOf(0)) +
                full("pitm", u(1u, 2)) + info + iloc + iprp + iref + if (idat) GoogleFixtures.box("idat", data) else byteArrayOf())
        }
        return if (idat) ftyp + meta(0u) else ftyp + meta((ftyp.size + meta(0u).size + 8).toUInt()) + GoogleFixtures.box("mdat", data)
    }
}
