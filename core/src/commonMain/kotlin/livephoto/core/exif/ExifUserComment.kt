package livephoto.core.exif

import livephoto.core.*
import livephoto.core.binary.*

internal enum class UserCommentEncoding { Ascii, Unicode, Unknown }
internal enum class OplusCommentMarker(val text: String) { Modern("oplus_10485792"), Legacy("oplus_8388608") }
internal data class ExifUserComment(
    val ifdOffset: UInt, val entry: TiffEntry, val encoding: UserCommentEncoding,
    val text: String?, val marker: OplusCommentMarker?,
)
internal data class ExifCommentFacts(val document: TiffDocument, val comments: List<ExifUserComment>)

/** Reads only the standardized ExifIFD reached from the primary IFD0's Exif pointer. */
internal class ExifUserCommentReader(private val reader: BinaryReader, private val budget: ParseBudget = ParseBudget(reader.context)) {
    suspend fun read(tiffRange: ByteRange): CoreResult<ExifCommentFacts> = attempt {
        val document = TiffReader(reader, budget).read(tiffRange).orThrow()
        comments(document, budget)
    }

    internal fun comments(document: TiffDocument, sharedBudget: ParseBudget = budget): ExifCommentFacts {
        val root = document.ifds.singleOrNull { it.relativeOffset == document.firstIfdOffset }
            ?: return ExifCommentFacts(document, emptyList())
        val pointers = root.entries.filter { it.tag == 0x8769u.toUShort() }
        if (pointers.size > 1) fail("CONFLICTING_METADATA", "Primary TIFF IFD has duplicate ExifIFD pointers")
        val pointer = pointers.singleOrNull() ?: return ExifCommentFacts(document, emptyList())
        val target = pointer.value?.let { readUnsigned(it, document.endian).toUInt() }
            ?: fail("CORRUPTED_CONTAINER", "Primary ExifIFD pointer value is unavailable")
        if (target == 0u) return ExifCommentFacts(document, emptyList())
        val exif = document.ifds.singleOrNull { it.relativeOffset == target }
            ?: fail("CORRUPTED_CONTAINER", "Primary ExifIFD pointer is unresolved")
        val commentEntries = exif.entries.filter { it.tag == 0x9286u.toUShort() }
        if (commentEntries.size > 1) fail("CONFLICTING_METADATA", "Primary ExifIFD contains duplicate UserComment fields")
        val comments = mutableListOf<ExifUserComment>()
        for (entry in commentEntries) {
            sharedBudget.item(1u)
            val value = entry.value
            // UserComment is UNDEFINED; nonstandard field types are preserved without interpretation.
            if (entry.type != 7u.toUShort() || value == null || value.size < 8) {
                comments.add(ExifUserComment(exif.relativeOffset, entry, UserCommentEncoding.Unknown, null, null)); continue
            }
            sharedBudget.retain(checkedMultiply(value.size.toULong(), 2uL))
            val encoding = when {
                header(value, "ASCII\u0000\u0000\u0000") -> UserCommentEncoding.Ascii
                header(value, "UNICODE\u0000") -> UserCommentEncoding.Unicode
                else -> UserCommentEncoding.Unknown
            }
            val text = when (encoding) {
                UserCommentEncoding.Ascii -> ascii(value)
                UserCommentEncoding.Unicode -> unicode(value)
                UserCommentEncoding.Unknown -> null
            }
            val marker = OplusCommentMarker.entries.singleOrNull { it.text == text }
            comments.add(ExifUserComment(exif.relativeOffset, entry, encoding, text, marker))
        }
        return ExifCommentFacts(document, comments.toList())
    }

    private fun header(bytes: Bytes, expected: String): Boolean = expected.indices.all { bytes[it].toInt() and 255 == expected[it].code }
    private fun ascii(bytes: Bytes): String? {
        checkCancelled(reader.context)
        var end = bytes.size
        var iterations = 0
        while (end > 8 && bytes[end - 1] == 0.toByte()) { if (iterations++ and 4095 == 0) checkCancelled(reader.context); end-- }
        return buildString(end - 8) {
            for (index in 8 until end) {
                if (iterations++ and 4095 == 0) checkCancelled(reader.context)
                val char = bytes[index].toInt() and 255
                if (char > 127 || char == 0) return null
                append(char.toChar())
            }
            checkCancelled(reader.context)
        }
    }

    private fun unicode(bytes: Bytes): String? {
        checkCancelled(reader.context)
        if ((bytes.size - 8) % 2 != 0) return null
        var start = 8
        val endian: Endian
        if (bytes.size >= 10) {
            val first = bytes[8].toInt() and 255; val second = bytes[9].toInt() and 255
            if (first == 0xfe && second == 0xff) { endian = Endian.Big; start = 10 }
            else if (first == 0xff && second == 0xfe) { endian = Endian.Little; start = 10 }
            else return null // Preserve bytes when Unicode byte order is not explicitly identified.
        } else return null
        var end = bytes.size
        var iterations = 0
        while (end >= start + 2 && bytes[end - 1] == 0.toByte() && bytes[end - 2] == 0.toByte()) { if (iterations++ and 4095 == 0) checkCancelled(reader.context); end -= 2 }
        val result = StringBuilder((end - start) / 2)
        var index = start
        while (index < end) {
            if (iterations++ and 4095 == 0) checkCancelled(reader.context)
            val unit = readUnsigned(bytes.slice(index, index + 2), endian).toInt()
            if (unit == 0) return null
            if (unit in 0xd800..0xdbff) {
                if (index + 4 > end) return null
                val low = readUnsigned(bytes.slice(index + 2, index + 4), endian).toInt()
                if (low !in 0xdc00..0xdfff) return null
                result.append(unit.toChar()); result.append(low.toChar()); index += 4
            } else {
                if (unit in 0xdc00..0xdfff) return null
                result.append(unit.toChar()); index += 2
            }
        }
        checkCancelled(reader.context)
        return result.toString()
    }
}
