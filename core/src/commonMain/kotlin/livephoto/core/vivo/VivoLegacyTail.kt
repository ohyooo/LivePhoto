package livephoto.core.vivo

import livephoto.core.*
import livephoto.core.binary.*

internal data class VivoLegacyMetadata(
    val sourceIdentity: SourceIdentity,
    val id: String,
    val range: ByteRange,
    val jsonRange: ByteRange,
    val identifierRange: ByteRange,
    val rawTail: Bytes,
    val json: Value.ObjectValue,
    val rawKeyFields: List<RawKeyField>,
    val key: KeyPhotoResult,
    val issues: List<Issue>,
)

/** The caller supplies the complete JPEG tail or UUID payload; no whole-source search is performed. */
internal object VivoLegacyTail {
    suspend fun read(
        reader: BinaryReader,
        parent: ByteRange,
        budget: ParseBudget = ParseBudget(reader.context),
    ): CoreResult<VivoLegacyMetadata?> = attempt {
        val identity = reader.identity().orThrow()
        checkedRange(parent.offset, parent.length, identity.size)
        if (parent.length < 4uL) { reader.validateIdentity().orThrow(); return@attempt null }
        val prefix = reader.readBuffer(parent.offset, 4u).orThrow()
        if (!matches(prefix, 0, "vivo")) { reader.validateIdentity().orThrow(); return@attempt null }
        budget.item(); budget.retain(512uL)
        val count = checkedInt(parent.length)
        budget.retain(parent.length)
        val raw = reader.readExactly(parent.offset, count.toUInt()).orThrow()
        // Bytes.slice is an immutable view; the parser separately reserves its retained model budget.
        val parsed = BoundedJson.parseObjectPrefix(raw.slice(4), budget).orThrow()
        var jsonEnd = 4 + parsed.consumedBytes
        // Only JSON whitespace may follow the root before its exact binary framing.
        // Check framing before advancing: the length's first byte can itself equal whitespace.
        while (!framingStarts(raw, jsonEnd)) {
            budget.poll()
            if (jsonEnd >= raw.size || !isJsonWhitespace(raw[jsonEnd].toInt() and 255))
                fail("CORRUPTED_CONTAINER", "Vivo JSON has no exact length and cameralbum! framing")
            jsonEnd++
        }
        val recordStart = jsonEnd + 15 // BE32 JSON length + eleven-byte camera marker.
        val recordLength = u32(raw, recordStart)
        if (recordLength < 19uL || checkedAdd(recordStart.toULong(), recordLength) != parent.length)
            fail("CORRUPTED_CONTAINER", "Vivo identifier record does not cover the complete bounded tail")
        val idLength = recordLength - 19uL
        if (idLength == 0uL) fail("INVALID_PAIR_IDENTIFIER", "Vivo legacy identifier is empty")
        val idStart = recordStart + 4
        val idEnd = checkedInt(checkedAdd(idStart.toULong(), idLength))
        if (!matches(raw, idEnd, 0xff, 0xff, 0xff, 0xff) ||
            !matches(raw, idEnd + 4, 0x1b, 0x2a, 0x39, 0x48, 0x57, 0x66, 0x75, 0x84, 0x93, 0xa2, 0xb3))
            fail("CORRUPTED_CONTAINER", "Vivo legacy identifier sentinel or terminal signature is invalid")
        val id = decodeUtf8Strict(raw.slice(idStart, idEnd), budget)
        val jsonId = (parsed.value.entries["com.android.camera.livephoto"] as? Value.Text)?.value
        if (jsonId.isNullOrEmpty() || jsonId != id)
            fail("INVALID_PAIR_IDENTIFIER", "Vivo JSON semantic identifier and UTF-8 trailer identifier disagree")
        val jsonRange = ByteRange(checkedAdd(parent.offset, 4uL), (jsonEnd - 4).toULong())
        val identifierRange = ByteRange(checkedAdd(parent.offset, idStart.toULong()), idLength)
        val fields = mutableListOf<RawKeyField>()
        val issues = mutableListOf<Issue>()
        for (keyName in listOf("com.android.camera.imageTime", "com.vivo.gallery.livePhoto.newCoverTime")) {
            parsed.value.entries[keyName]?.let { rawValue ->
                budget.item(); budget.retain(256uL)
                val location = Location(source = identity.id, range = jsonRange, selector = keyName)
                fields.add(RawKeyField(keyName, rawValue, "unknown", location))
                issues.add(Issue(IssueCode("TIMESTAMP_SEMANTICS_UNKNOWN"), Severity.Warning, Layer.Protocol, location))
            }
        }
        val key = KeyPhotoResult(position = null, source = KeySource.Unknown, rawFields = frozenList(fields), issues = frozenList(issues))
        reader.validateIdentity().orThrow()
        VivoLegacyMetadata(identity, id, parent, jsonRange, identifierRange, raw, parsed.value, frozenList(fields), key, frozenList(issues))
    }

    private fun framingStarts(bytes: Bytes, offset: Int): Boolean =
        offset <= bytes.size - 19 && u32(bytes, offset) == (offset - 4).toULong() && matches(bytes, offset + 4, "cameralbum!")

    private fun u32(bytes: Bytes, offset: Int): ULong {
        if (offset < 0 || offset > bytes.size - 4) fail("CORRUPTED_CONTAINER", "Vivo tail integer field is truncated")
        var value = 0uL
        repeat(4) { value = (value shl 8) or (bytes[offset + it].toInt() and 255).toULong() }
        return value
    }
    private fun matches(bytes: Bytes, offset: Int, text: String): Boolean =
        offset >= 0 && offset <= bytes.size - text.length && text.indices.all { bytes[offset + it].toInt() and 255 == text[it].code }
    private fun matches(bytes: Bytes, offset: Int, vararg expected: Int): Boolean =
        offset >= 0 && offset <= bytes.size - expected.size && expected.indices.all { bytes[offset + it].toInt() and 255 == expected[it] }
}
