package livephoto.core.huawei

import livephoto.core.*
import livephoto.core.binary.*

internal enum class HuaweiTailVariant { Basic60, HonorExtended, Unknown }
internal data class HuaweiTailFacts(
    val sourceIdentity: SourceIdentity,
    val tailRange: ByteRange,
    val rawTail: Bytes,
    val liveValue: ULong,
    val candidateVideoRange: ByteRange,
    val videoRange: ByteRange?,
    val gap: ByteRange?,
    val variant: HuaweiTailVariant,
    val prefixNumber: ULong?,
    val historyNumbers: Pair<ULong, ULong>?,
    val rawFrameFields: List<RawKeyField>,
    val key: KeyPhotoResult,
    val issues: List<Issue>,
)

/** Fixed end-of-file layout only. Native's milliseconds interpretation is not adopted as protocol fact. */
internal object HuaweiTail {
    /** Encode only the owned length; never manufacture the two opaque historical fields. */
    fun lengthField(videoLength: ULong, budget: ParseBudget): CoreResult<Bytes> = attemptNow {
        budget.poll()
        if (videoLength == 0uL) fail("INVALID_ARGUMENT", "Huawei motion payload must be nonempty")
        val live = "LIVE_${checkedAdd(videoLength, 20uL)}"
        if (live.length > 20) fail("VALUE_NOT_REPRESENTABLE", "Huawei LIVE_ length exceeds its fixed twenty-byte field")
        budget.item(); budget.retain(80uL)
        Bytes(ByteArray(20) { 0x20 }.also { live.encodeToByteArray().copyInto(it) })
    }

    suspend fun read(reader: BinaryReader, jpegEnd: ULong? = null, budget: ParseBudget = ParseBudget(reader.context)): CoreResult<HuaweiTailFacts?> = attempt {
        val identity = reader.identity().orThrow()
        if (jpegEnd != null && jpegEnd > identity.size) fail("OFFSET_OUT_OF_BOUNDS", "Image boundary exceeds Huawei carrier")
        if (identity.size < 20uL) { reader.validateIdentity().orThrow(); return@attempt null }
        val last = reader.readBuffer(identity.size - 20uL, 20u).orThrow()
        if (!starts(last, "LIVE_")) { reader.validateIdentity().orThrow(); return@attempt null }
        if (identity.size < 60uL) fail("VENDOR_TRAILER_INVALID", "LIVE_ field has no complete sixty-byte trailer")
        budget.item(); budget.retain(1024uL)
        val liveToken = token(last, 5, 20) ?: fail("VENDOR_TRAILER_INVALID", "Huawei LIVE_ length has invalid bytes or padding")
        val m = decimal(liveToken) ?: fail("VENDOR_TRAILER_INVALID", "Huawei LIVE_ length is not unsigned decimal")
        if (m <= 20uL) fail("MOTION_VIDEO_LENGTH_MISMATCH", "Huawei motion payload must be nonempty")
        val length = m - 20uL
        val tailStart = identity.size - 60uL
        if (length > tailStart) fail("MOTION_VIDEO_LENGTH_MISMATCH", "Huawei motion range exceeds its carrier")
        val start = tailStart - length
        if (jpegEnd != null && start < jpegEnd) fail("OFFSET_OUT_OF_BOUNDS", "Huawei motion range overlaps the complete primary image")
        val raw = reader.readExactly(tailStart, 60u).orThrow()
        val prefixRaw = token(raw, 0, 20)
        val historyRaw = token(raw, 20, 40)
        val prefixNumber = prefixRaw?.takeIf { it.startsWith("v6_f") }?.substring(4)?.let(::decimal)
        val historyNumbers = historyRaw?.let { value ->
            val colon = value.indexOf(':')
            if (colon <= 0 || colon != value.lastIndexOf(':') || colon == value.lastIndex) null
            else decimal(value.substring(0, colon))?.let { first -> decimal(value.substring(colon + 1))?.let { second -> first to second } }
        }
        val honor = starts(raw, "v1_f") || starts(raw, "v2_f") || containsBounded(raw, "srcDstWh")
        val basic = !honor && prefixRaw != null && prefixRaw.length <= 6 && prefixNumber != null &&
            historyRaw != null && historyRaw.length <= 8 && historyNumbers != null
        val variant = when { honor -> HuaweiTailVariant.HonorExtended; basic -> HuaweiTailVariant.Basic60; else -> HuaweiTailVariant.Unknown }
        val video = ByteRange(start, length)
        val gap = jpegEnd?.let { boundary -> ByteRange(boundary, start - boundary).takeIf { it.length != 0uL } }
        fun location(offset: ULong, size: ULong, selector: String) = Location(source = identity.id, range = ByteRange(checkedAdd(tailStart, offset), size), selector = selector)
        val firstLocation = location(0uL, 20uL, "huawei:tail:first-field")
        val secondLocation = location(20uL, 20uL, "huawei:tail:history-field")
        val fields = listOf(
            RawKeyField("huawei:tail:first-field", Value.Text(prefixRaw ?: asciiRaw(raw.slice(0, 20))), "unknown", firstLocation),
            RawKeyField("huawei:tail:history-field", Value.Text(historyRaw ?: asciiRaw(raw.slice(20, 40))), "unknown", secondLocation),
        )
        val issues = mutableListOf(Issue(IssueCode("TIMESTAMP_SEMANTICS_UNKNOWN"), Severity.Warning, Layer.Protocol, secondLocation))
        if (variant != HuaweiTailVariant.Basic60) issues.add(Issue(IssueCode("UNKNOWN_PROTOCOL_VARIANT"), Severity.Warning, Layer.Protocol, Location(source = identity.id, range = ByteRange(tailStart, 60uL))))
        if (gap != null) issues.add(Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Structure, Location(source = identity.id, range = gap, selector = "huawei:unknown-gap")))
        val key = KeyPhotoResult(position = null, source = KeySource.Unknown, rawFields = frozenList(fields), issues = frozenList(issues.filter { it.code.value == "TIMESTAMP_SEMANTICS_UNKNOWN" }))
        reader.validateIdentity().orThrow()
        HuaweiTailFacts(identity, ByteRange(tailStart, 60uL), raw, m, video, video.takeIf { variant == HuaweiTailVariant.Basic60 }, gap,
            variant, prefixNumber, historyNumbers, frozenList(fields), key, frozenList(issues))
    }

    /** The caller supplies opaque chosen fields explicitly; this helper assigns neither frames nor time units. */
    fun create(videoLength: ULong, prefixFrameField: String, historyField: String, budget: ParseBudget): CoreResult<Bytes> = attemptNow {
        budget.poll()
        if (videoLength == 0uL) fail("INVALID_ARGUMENT", "Huawei motion payload must be nonempty")
        val m = checkedAdd(videoLength, 20uL)
        if (prefixFrameField.length > 6 || historyField.length > 8) fail("VALUE_NOT_REPRESENTABLE", "Huawei chosen fields exceed their fixed widths")
        budget.item(); budget.retain(240uL)
        if (!prefixFrameField.startsWith("v6_f") || decimal(prefixFrameField.substring(4)) == null) fail("INVALID_ARGUMENT", "Huawei basic60 prefix must be an explicit v6_f numeric field")
        val colon = historyField.indexOf(':')
        if (colon <= 0 || colon != historyField.lastIndexOf(':') || colon == historyField.lastIndex ||
            decimal(historyField.substring(0, colon)) == null || decimal(historyField.substring(colon + 1)) == null) fail("INVALID_ARGUMENT", "Huawei history must contain two explicit unsigned raw values")
        val live = "LIVE_$m"
        if (live.length > 20) fail("VALUE_NOT_REPRESENTABLE", "Huawei LIVE_ length exceeds its fixed twenty-byte field")
        val output = ByteArray(60) { 0x20 }
        prefixFrameField.encodeToByteArray().copyInto(output, 0)
        historyField.encodeToByteArray().copyInto(output, 20)
        live.encodeToByteArray().copyInto(output, 40)
        Bytes(output)
    }

    /** ASCII token followed solely by space/NUL padding; internal whitespace/bytes are not silently removed. */
    private fun token(bytes: Bytes, start: Int, end: Int): String? {
        var stop = start
        while (stop < end && bytes[stop] != 0.toByte() && bytes[stop] != 0x20.toByte()) {
            if (bytes[stop].toInt() and 255 !in 0x21..0x7e) return null
            stop++
        }
        for (index in stop until end) if (bytes[index] != 0.toByte() && bytes[index] != 0x20.toByte()) return null
        return buildString(stop - start) { for (index in start until stop) append((bytes[index].toInt() and 255).toChar()) }
    }
    private fun decimal(value: String): ULong? {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        return value.toULongOrNull() ?: fail("INTEGER_OVERFLOW", "Huawei raw decimal field exceeds UInt64")
    }
    private fun starts(bytes: Bytes, value: String): Boolean = bytes.size >= value.length && value.indices.all { bytes[it].toInt() and 255 == value[it].code }
    private fun containsBounded(bytes: Bytes, value: String): Boolean = (0..bytes.size - value.length).any { offset -> value.indices.all { bytes[offset + it].toInt() and 255 == value[it].code } }
    private fun asciiRaw(bytes: Bytes): String = buildString(bytes.size) { for (index in 0 until bytes.size) append((bytes[index].toInt() and 255).toChar()) }
}
