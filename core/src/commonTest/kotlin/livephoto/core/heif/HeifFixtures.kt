package livephoto.core.heif

import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures

/** Independent finite HEIF encodings, not device fixtures and not a production writer oracle. */
internal object HeifFixtures {
    fun plain(metaLast: Boolean = false, extended: Boolean = false, multiple: Boolean = false, idat: Boolean = false,
              baseWidth: Int = 0, offsetWidth: Int = 4, unknownProperty: Boolean = false, hidden: Boolean = false,
              extraSampleNal: ByteArray = byteArrayOf(), extraConfigArray: ByteArray = byteArrayOf()): ByteArray {
        fun integer(value: ULong, width: Int) = if (width == 0) byteArrayOf() else unsignedBytes(value, width, Endian.Big).toByteArray()
        fun box(type: String, payload: ByteArray, large: Boolean = false): ByteArray = if (!large) GoogleFixtures.box(type, payload)
            else GoogleFixtures.u32(1u) + type.encodeToByteArray() + integer(payload.size.toULong() + 16uL, 8) + payload
        fun full(type: String, payload: ByteArray, version: Int = 0, flags: Int = 0, large: Boolean = false) = box(type, byteArrayOf(version.toByte(), 0, 0, flags.toByte()) + payload, large)
        val sample = GoogleFixtures.video(hevc = true).samples.first() + extraSampleNal
        val configuration = GoogleFixtures.video(hevc = true).configuration.let { bytes ->
            if (extraConfigArray.isEmpty()) bytes else bytes.copyOf().also { it[22] = (it[22].toInt() + 1).toByte() } + extraConfigArray
        }
        val data = if (multiple) sample.copyOfRange(0, 2) + byteArrayOf(0xa5.toByte(), 0x5a, 0xff.toByte()) + sample.copyOfRange(2, sample.size) else sample
        val ftyp = box("ftyp", "heic".encodeToByteArray() + GoogleFixtures.u32(0u) + "heicmif1".encodeToByteArray())
        fun meta(dataStart: ULong): ByteArray {
            val base = if (!idat && baseWidth != 0) dataStart else 0uL
            val method = if (idat) 1 else 0
            val start = if (idat) 0uL else dataStart - base
            var location = byteArrayOf(((offsetWidth shl 4) or 4).toByte(), (baseWidth shl 4).toByte()) + integer(1uL, 2) + integer(1uL, 2)
            if (idat) location += integer(method.toULong(), 2)
            location += integer(0uL, 2) + integer(base, baseWidth) + integer(if (multiple) 2uL else 1uL, 2)
            location += integer(start, offsetWidth) + integer(if (multiple) 2uL else sample.size.toULong(), 4)
            if (multiple) location += integer(start + 5uL, offsetWidth) + integer((sample.size - 2).toULong(), 4)
            val handler = full("hdlr", integer(0uL, 4) + "pict".encodeToByteArray() + ByteArray(12) + byteArrayOf(0))
            val info = full("iinf", integer(1uL, 2) + full("infe", integer(1uL, 2) + integer(0uL, 2) + "hvc1Primary\u0000".encodeToByteArray(), 2, if (hidden) 1 else 0))
            val properties = box("ipco", full("ispe", integer(1uL, 4) + integer(1uL, 4)) + box("hvcC", configuration) + if (unknownProperty) box("priv", byteArrayOf(4, 5, 6)) else byteArrayOf())
            val iprp = box("iprp", properties + full("ipma", integer(1uL, 4) + byteArrayOf(0, 1, 2, 0x81.toByte(), 0x82.toByte())))
            return full("meta", handler + full("pitm", integer(1uL, 2)) + info + full("iloc", location, if (idat) 1 else 0) + iprp +
                if (idat) box("idat", data) else byteArrayOf(), large = extended)
        }
        if (idat) return ftyp + meta(0uL)
        val media = box("mdat", data, extended)
        val start = ftyp.size.toULong() + (if (metaLast) 0uL else meta(0uL).size.toULong()) + if (extended) 16uL else 8uL
        return if (metaLast) ftyp + media + meta(start) else ftyp + meta(start) + media
    }
}
