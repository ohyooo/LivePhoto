package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

internal data class BmffBox(
    val type: String,
    val range: ByteRange,
    val headerLength: ULong,
    val payload: ByteRange,
    val extendsToParentEnd: Boolean,
    val userType: Bytes? = null,
)

internal data class BmffFileType(val majorBrand: String, val minorVersion: UInt, val compatibleBrands: List<String>)

/** Generic structural reader. Unknown payloads stay source ranges and are never recursively guessed. */
internal class BmffReader(private val reader: BinaryReader) {
    private val budget = ParseBudget(reader.context)

    /** Caller supplies the exact parent payload and schema depth, including any profile-specific prefix. */
    suspend fun readBoxes(parent: ByteRange, depth: UInt = 0u): CoreResult<List<BmffBox>> = attempt {
        checkedRange(parent.offset, parent.length, reader.identity().orThrow().size)
        val end = checkedAdd(parent.offset, parent.length)
        val boxes = mutableListOf<BmffBox>()
        var offset = parent.offset
        while (offset < end) {
            budget.item(depth)
            budget.retain(96uL)
            checkedRange(offset, 8uL, end)
            val header = reader.readBuffer(offset, 8u).orThrow()
            val size32 = readUnsigned(header.slice(0, 4), Endian.Big)
            val type = fourCc(header, 4)
            var headerLength = 8uL
            val length = when (size32) {
                0uL -> end - offset
                1uL -> {
                    checkedRange(offset, 16uL, end)
                    headerLength = 16uL
                    readUnsigned(reader.readBuffer(checkedAdd(offset, 8uL), 8u).orThrow(), Endian.Big)
                }
                else -> size32
            }
            var userType: Bytes? = null
            if (type == "uuid") {
                val newHeaderLength = checkedAdd(headerLength, 16uL)
                if (length < newHeaderLength) fail("CORRUPTED_CONTAINER", "BMFF UUID box is shorter than its header")
                checkedRange(offset, newHeaderLength, end)
                userType = reader.readBuffer(checkedAdd(offset, headerLength), 16u).orThrow()
                headerLength = newHeaderLength
            }
            if (length < headerLength) fail("CORRUPTED_CONTAINER", "BMFF box size is smaller than its header")
            checkedRange(offset, length, end)
            boxes.add(BmffBox(type, ByteRange(offset, length), headerLength,
                ByteRange(checkedAdd(offset, headerLength), length - headerLength), size32 == 0uL, userType))
            offset = checkedAdd(offset, length) // length >= 8: guaranteed positive advancement.
        }
        reader.validateIdentity().orThrow()
        boxes.toList()
    }

    suspend fun readFileType(box: BmffBox): CoreResult<BmffFileType> = attempt {
        if (box.type != "ftyp") fail("INVALID_ARGUMENT", "File type parsing requires an ftyp box")
        checkedRange(box.payload.offset, box.payload.length, reader.identity().orThrow().size)
        if (box.payload.length < 8uL || (box.payload.length - 8uL) % 4uL != 0uL) fail("CORRUPTED_CONTAINER", "Invalid BMFF ftyp payload length")
        val count = (box.payload.length - 8uL) / 4uL
        // Reserve all retained brand strings/model overhead before building the list.
        budget.retain(checkedAdd(32uL, checkedMultiply(count, 16uL)))
        checkedInt(count)
        val header = reader.readBuffer(box.payload.offset, 8u).orThrow()
        val brands = mutableListOf<String>()
        var cursor = checkedAdd(box.payload.offset, 8uL)
        var remaining = count
        while (remaining != 0uL) {
            val chunkBrands = minOf(remaining, 16_384uL)
            val chunk = reader.readBuffer(cursor, (chunkBrands * 4uL).toUInt()).orThrow()
            for (index in 0 until chunkBrands.toInt()) {
                budget.item()
                brands.add(fourCc(chunk, index * 4))
            }
            cursor = checkedAdd(cursor, chunkBrands * 4uL)
            remaining -= chunkBrands
        }
        reader.validateIdentity().orThrow()
        BmffFileType(fourCc(header, 0), readUnsigned(header.slice(4, 8), Endian.Big).toUInt(), brands.toList())
    }
}

internal fun fourCc(bytes: Bytes, offset: Int): String = buildString(4) {
    for (index in offset until offset + 4) append((bytes[index].toInt() and 0xff).toChar())
}
