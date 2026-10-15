package livephoto.core.samsung

import livephoto.core.google.GoogleFixtures
import kotlin.test.*

/** Hand-encoded SEF layouts and a separate byte oracle; never calls a product SEF writer. */
internal object SamsungFixtures {
    data class Record(val type: Int, val name: String, val payload: ByteArray, val prefix: Int = 0) {
        val bytes: ByteArray get() = le16(prefix) + le16(type) + le32(name.encodeToByteArray().size.toUInt()) + name.encodeToByteArray() + payload
    }
    data class Photo(val bytes: ByteArray, val jpegEnd: Int, val videoStart: Int, val tableStart: Int, val records: List<Record>)
    data class Entry(val prefix: Int, val type: Int, val name: String, val start: Int, val size: Int, val payload: ByteArray, val raw: ByteArray)
    val ordinary = Record(0x1234, "Ordinary_Private_Data", GoogleFixtures.bytes(7, 0, 0xff, 0xd9, 8))

    fun photo(video: ByteArray = GoogleFixtures.video().bytes, ordinaryRecord: Boolean = false, legacy: Boolean = false, xmp: Boolean = false, extraSegments: ByteArray = byteArrayOf(), extraXmp: String = "", secondaryPadding: String? = null, extraRecords: List<Record> = emptyList(), timestamp: String? = "0"): Photo {
        val records = listOf(Record(0x0a30, "MotionPhoto_Data", video), Record(0x0a31, "MotionPhoto_Version", "mpv3".encodeToByteArray())) + (if (ordinaryRecord) listOf(ordinary) else emptyList()) + extraRecords
        val trailer = trailer(records, legacy)
        val packet = if (!xmp) byteArrayOf() else {
            val padding = secondaryPadding?.let { " item:Padding='$it'" } ?: ""
            val directory = "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='image/jpeg' item:Semantic='Primary' item:Length='0' item:Padding='24'/></rdf:li>" +
                "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='video/mp4' item:Semantic='MotionPhoto' item:Length='${trailer.size - 24}'$padding/></rdf:li>"
            GoogleFixtures.xmpSegment(GoogleFixtures.v2Xml(video.size, timestamp = timestamp, directory = directory, extra = extraXmp))
        }
        val jpeg = GoogleFixtures.jpeg(extraSegments + packet)
        val tableStart = jpeg.size + records.sumOf { it.bytes.size }
        return Photo(jpeg + trailer, jpeg.size, jpeg.size + 24, tableStart, records)
    }

    fun trailer(records: List<Record>, legacy: Boolean = false): ByteArray {
        val encoded = records.map { it.bytes }
        val payload = encoded.fold(byteArrayOf()) { result, bytes -> result + bytes }
        var consumed = 0
        val table = "SEFH".encodeToByteArray() + le32(107u) + le32(records.size.toUInt()) + records.indices.fold(byteArrayOf()) { result, index ->
            val record = records[index]
            val bytes = encoded[index]
            val entry = le16(record.prefix) + le16(record.type) + le32((payload.size - consumed).toUInt()) + le32(bytes.size.toUInt())
            consumed += bytes.size
            result + entry
        }
        return payload + table + le32((table.size + if (legacy) 8 else 0).toUInt()) + "SEFT".encodeToByteArray()
    }

    /** Canonical-only independent checks on writer/clean output, including record/table agreement. */
    fun directory(bytes: ByteArray): List<Entry> {
        assertTrue(bytes.size >= 20)
        assertEquals("SEFT", bytes.copyOfRange(bytes.size - 4, bytes.size).decodeToString())
        val length = read32(bytes, bytes.size - 8)
        assertTrue(length <= (bytes.size - 8).toUInt())
        val table = bytes.size - 8 - length.toInt()
        assertEquals("SEFH", bytes.copyOfRange(table, table + 4).decodeToString())
        assertEquals(107u, read32(bytes, table + 4))
        val count = read32(bytes, table + 8)
        assertEquals(12uL + 12uL * count.toULong(), length.toULong())
        return (0 until count.toInt()).map { index ->
            val position = table + 12 + index * 12
            val back = read32(bytes, position + 4)
            val size = read32(bytes, position + 8)
            assertTrue(back <= table.toUInt() && size <= back && size >= 8u)
            val start = table - back.toInt()
            val nameLength = read32(bytes, start + 4)
            assertTrue(nameLength <= size - 8u)
            val prefix = read16(bytes, position)
            val type = read16(bytes, position + 2)
            assertEquals(prefix, read16(bytes, start)); assertEquals(type, read16(bytes, start + 2))
            Entry(prefix, type, bytes.copyOfRange(start + 8, start + 8 + nameLength.toInt()).decodeToString(), start, size.toInt(),
                bytes.copyOfRange(start + 8 + nameLength.toInt(), start + size.toInt()), bytes.copyOfRange(start, start + size.toInt()))
        }
    }

    fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())
    fun le32(value: UInt): ByteArray = ByteArray(4) { (value shr (it * 8)).toByte() }
    fun read16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    fun read32(bytes: ByteArray, offset: Int): UInt = (0 until 4).fold(0u) { result, index -> result or ((bytes[offset + index].toInt() and 255).toUInt() shl (index * 8)) }
}
