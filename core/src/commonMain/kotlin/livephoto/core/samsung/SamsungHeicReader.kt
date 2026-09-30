package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.*

internal data class SamsungHeicFacts(val binding: CarrierBinding, val video: VideoStructure?, val directory: SefDirectory,
    val primary: ByteRange, val boxes: List<BmffBox>, val pointerMode: String?)

/** Establishes complete box/SEF/media ranges. HEIF image-item decoding and rewriting are not claimed. */
internal object SamsungHeicReader {
    suspend fun read(reader: BinaryReader, budget: ParseBudget): CoreResult<SamsungHeicFacts?> = attempt {
        val identity = reader.identity().orThrow()
        val parser = BmffReader(reader, budget)
        val boxes = parser.readBoxes(ByteRange(0uL, identity.size)).orThrow()
        val mpvds = boxes.filter { it.type == "mpvd" }
        val topSefd = boxes.filter { it.type == "sefd" }
        if (mpvds.isEmpty() && topSefd.isEmpty()) return@attempt null
        val mpvd = mpvds.singleOrNull() ?: fail("AMBIGUOUS_LAYOUT", "Samsung HEIC needs one mpvd box")
        val ftyp = boxes.singleOrNull { it.type == "ftyp" } ?: fail("CORRUPTED_CONTAINER", "HEIC has no unique ftyp")
        val brands = parser.readFileType(ftyp).orThrow().let { it.compatibleBrands + it.majorBrand }
        if (brands.none { it in setOf("heic", "heix", "hevc", "hevx", "heim", "heis") }) fail("UNSUPPORTED_CONTAINER", "Samsung HEIC carrier brand is outside the implemented scope")
        val meta = boxes.singleOrNull { it.type == "meta" } ?: fail("CORRUPTED_CONTAINER", "HEIC has no unique image meta box")
        if (meta.payload.length < 4uL) fail("CORRUPTED_CONTAINER", "HEIF meta fullbox header is truncated")
        val fullbox = reader.readBuffer(meta.payload.offset, 4u).orThrow()
        if (readUnsigned(fullbox, Endian.Big) != 0uL) fail("UNSUPPORTED_CONTAINER", "HEIF meta version/flags are not implemented")
        val metaBoxes = parser.readBoxes(ByteRange(meta.payload.offset + 4uL, meta.payload.length - 4uL), 1u).orThrow()
        if (metaBoxes.count { it.type == "pitm" } != 1 || metaBoxes.count { it.type == "iloc" } != 1 || metaBoxes.count { it.type == "iinf" } != 1) fail("CORRUPTED_CONTAINER", "HEIF primary item tables are absent or ambiguous")
        val children = parser.readBoxes(mpvd.payload, 1u).orThrow()
        val nested = children.filter { it.type == "sefd" }
        val sefd = (topSefd + nested).singleOrNull() ?: fail("AMBIGUOUS_LAYOUT", "Samsung HEIC has no unique sefd")
        if (nested.isNotEmpty() && sefd.range.endExclusive != mpvd.range.endExclusive) fail("SEF_DIRECTORY_INVALID", "Nested sefd must end the mpvd payload")
        val mediaEnd = if (nested.isEmpty()) mpvd.payload.endExclusive else sefd.range.offset
        if (mediaEnd <= mpvd.payload.offset) fail("MOTION_VIDEO_MISSING", "mpvd has no media bytes")
        val mediaRange = ByteRange(mpvd.payload.offset, mediaEnd - mpvd.payload.offset)
        val directory = SefReader.parseInRange(reader, sefd.payload, budget).orThrow() ?: fail("SEF_DIRECTORY_INVALID", "sefd has no trusted SEF footer")
        val motion = directory.records.singleOrNull { it.type == 0x0a30.toUShort() } ?: fail("MOTION_VIDEO_MISSING", "sefd has no unique MotionPhoto_Data")
        if (motion.payloadRange.length != 12uL) fail("UNKNOWN_PROTOCOL_VARIANT", "HEIC MotionPhoto_Data is not a 12-byte mpv2 pointer")
        val pointer = reader.readBuffer(motion.payloadRange.offset, 12u).orThrow()
        if (fourCc(pointer, 0) != "mpv2") fail("UNKNOWN_PROTOCOL_VARIANT", "HEIC MotionPhoto_Data is not mpv2")
        val offset = readUnsigned(pointer.slice(4, 8), Endian.Big)
        val length = readUnsigned(pointer.slice(8, 12), Endian.Big)
        val candidates = linkedMapOf<ByteRange, MutableList<String>>()
        fun candidate(start: ULong, mode: String) {
            if (start <= identity.size && length <= identity.size - start && length != 0uL) {
                val range = ByteRange(start, length)
                // A pointer cannot select arbitrary unrelated media outside its mpvd framing.
                if (range == mediaRange) candidates.getOrPut(range) { mutableListOf() }.add(mode)
            }
        }
        candidate(offset, "absolute")
        if (offset <= ULong.MAX_VALUE - mpvd.range.offset) candidate(mpvd.range.offset + offset, "relative-to-mpvd")
        if (candidates.isEmpty()) fail("SEF_DIRECTORY_INVALID", "mpv2 pointer does not match the complete mpvd media range")
        if (candidates.size > 1) fail("AMBIGUOUS_LAYOUT", "Multiple distinct HEIC pointer layouts are valid")
        val videoResult = BmffVideoProbe(reader, budget).probe(candidates.keys.single())
        val video = when (videoResult) {
            is CoreResult.Success -> videoResult.value
            is CoreResult.Failure -> {
                if (videoResult.error.code.value in setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "RESOURCE_LIMIT_EXCEEDED", "UNEXPECTED_EOF")) throw CoreFault(videoResult.error)
                null
            }
        }
        val mediaIssues = if (videoResult is CoreResult.Failure) listOf(Issue(videoResult.error.code,
            if (videoResult.error.code.value in setOf("UNSUPPORTED_CONTAINER", "CAPABILITY_UNSUPPORTED", "VIDEO_CODEC_NOT_SUPPORTED", "AUDIO_CODEC_NOT_SUPPORTED")) Severity.Warning else Severity.Error,
            Layer.Media, videoResult.error.location)) else emptyList()
        val issues = listOf(Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Structure,
            Location(source = identity.id, range = meta.range), observed = Value.Text("HEIF item references/codec configuration and decode checks are not implemented"))) +
            mediaIssues + (if (directory.version != 107u || "absolute" !in candidates.values.single()) listOf(Issue(IssueCode("UNKNOWN_PROTOCOL_VARIANT"), Severity.Warning, Layer.Protocol)) else emptyList()) +
            if (directory.legacyDialect) listOf(Issue(IssueCode("SEF_DIRECTORY_INVALID"), Severity.Error, Layer.Protocol)) else emptyList()
        val binding = CarrierBinding(ProtocolIds.Samsung, mediaRange, issues = issues, profile = ProfileId("heic-sef-mpv2"))
        val primaryEnd = minOf(mpvd.range.offset, topSefd.firstOrNull()?.range?.offset ?: mpvd.range.offset)
        SamsungHeicFacts(binding, video, directory, ByteRange(0uL, primaryEnd), boxes, candidates.values.single().joinToString("|"))
    }
}
