package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

internal const val CAMERA_URI: String = "http://ns.google.com/photos/1.0/camera/"
internal const val CONTAINER_URI: String = "http://ns.google.com/photos/1.0/container/"
internal const val ITEM_URI: String = "http://ns.google.com/photos/1.0/container/item/"
internal val V1_FIELDS: Set<String> = setOf("MicroVideo", "MicroVideoVersion", "MicroVideoOffset", "MicroVideoPresentationTimestampUs")
internal val V2_FIELDS: Set<String> = setOf("MotionPhoto", "MotionPhotoVersion", "MotionPhotoPresentationTimestampUs")

/** Parsed bindings are distinct from a validated media resource and never authorize writes. */
internal data class GoogleBinding(
    val protocol: ProtocolId,
    val video: ByteRange? = null,
    val padding: ByteRange? = null,
    val items: List<GoogleItem> = emptyList(),
    val key: KeyPhotoResult = KeyPhotoResult(),
    val issues: List<Issue> = emptyList(),
) {
    val selector: ProtocolSelector get() = ProtocolSelector(protocol, ProfileId("jpeg"))
    val structurallyValid: Boolean get() = video != null && issues.none { it.severity == Severity.Error }
}

internal data class GoogleItem(val semantic: String, val mime: String, val range: ByteRange, val length: ULong)

internal object GoogleJpegReader {
    fun read(xmp: XmpCollection, jpeg: JpegStructure, source: SourceIdentity, budget: ParseBudget): CoreResult<List<GoogleBinding>> = attemptNow {
        val bindings = mutableListOf<GoogleBinding>()
        for ((protocol, fields) in listOf(ProtocolIds.GoogleV1 to V1_FIELDS, ProtocolIds.GoogleV2 to V2_FIELDS)) {
            budget.poll()
            val hasFields = fields.any { field -> xmp.packets.any { it.properties(CAMERA_URI, field).isNotEmpty() } }
            val hasDirectory = protocol == ProtocolIds.GoogleV2 && xmp.packets.any { packet -> packet.properties(CONTAINER_URI, "Directory").any { property ->
                property.element?.elements(RDF_URI, "Seq")?.any { sequence -> sequence.elements(RDF_URI, "li").any { child ->
                    val item = child.elements(CONTAINER_URI, "Item").singleOrNull() ?: child
                    item.attribute(ITEM_URI, "Semantic") == "MotionPhoto" || item.elements(ITEM_URI, "Semantic").any { node -> node.children.mapNotNull { (it as? XmlText)?.text ?: (it as? XmlCData)?.text }.joinToString("") == "MotionPhoto" }
                } } == true
            } }
            if (!hasFields && !hasDirectory) continue
            val flag = if (protocol == ProtocolIds.GoogleV1) "MicroVideo" else "MotionPhoto"
            val flagValue = try { xmp.scalar(CAMERA_URI, flag).orThrow()?.let { raw ->
                val digits = raw.removePrefix("-")
                if (digits.isEmpty() || digits.any { it !in '0'..'9' }) null else raw.toLongOrNull()
            } } catch (_: CoreFault) { null }
            if (flagValue != null && (if (protocol == ProtocolIds.GoogleV2) flagValue != 1L else flagValue == 0L)) continue
            budget.item()
            budget.retain(192uL)
            val binding = try {
                if (protocol == ProtocolIds.GoogleV1) v1(xmp, jpeg, source) else v2(xmp, jpeg, source, budget)
            } catch (fault: CoreFault) {
                // This branch performs metadata interpretation only, never source IO. Operational
                // failures still propagate; malformed bindings remain visible as candidates.
                if (fault.error.code.value in setOf("CANCELLED", "RESOURCE_LIMIT_EXCEEDED", "SOURCE_CHANGED", "IO_READ_FAILED")) throw fault
                GoogleBinding(protocol, issues = listOf(Issue(fault.error.code, if (fault.error.code.value == "CAPABILITY_UNSUPPORTED") Severity.Warning else Severity.Error, Layer.Protocol,
                    fault.error.location ?: Location(source = source.id))))
            }
            bindings += binding
        }
        frozenList(bindings)
    }

    private fun scalar(xmp: XmpCollection, field: String, required: Boolean = true): String? {
        for (property in xmp.packets.flatMap { it.properties(CAMERA_URI, field) }) {
            if (property.element?.attributes?.isNotEmpty() == true) fail("CAPABILITY_UNSUPPORTED", "Qualified Google literals require schema-specific interpretation")
        }
        val value = xmp.scalar(CAMERA_URI, field).orThrow()
        if (required && value == null) fail("MISSING_REQUIRED_XMP", "Required Google property $field is missing")
        return value
    }

    private fun v1(xmp: XmpCollection, jpeg: JpegStructure, source: SourceIdentity): GoogleBinding {
        if (unsigned(scalar(xmp, "MicroVideo")!!) != 1uL || unsigned(scalar(xmp, "MicroVideoVersion")!!) != 1uL) fail("MALFORMED_XMP", "MicroVideo flag and version must be 1")
        val length = unsigned(scalar(xmp, "MicroVideoOffset")!!)
        if (length == 0uL || length >= source.size) fail("MOTION_VIDEO_LENGTH_MISMATCH", "MicroVideo offset must identify a nonempty suffix smaller than its carrier")
        val start = source.size - length
        if (start < jpeg.primary.endExclusive) fail("OFFSET_OUT_OF_BOUNDS", "Motion video overlaps the primary JPEG")
        val gap = ByteRange(jpeg.primary.endExclusive, start - jpeg.primary.endExclusive)
        val key = safeKey(xmp, "MicroVideoPresentationTimestampUs", source.id)
        return GoogleBinding(ProtocolIds.GoogleV1, ByteRange(start, length), gap.takeIf { it.length != 0uL }, key = key, issues = key.issues)
    }

    private fun v2(xmp: XmpCollection, jpeg: JpegStructure, source: SourceIdentity, budget: ParseBudget): GoogleBinding {
        if (unsigned(scalar(xmp, "MotionPhoto")!!) != 1uL || unsigned(scalar(xmp, "MotionPhotoVersion")!!) != 1uL) fail("MALFORMED_XMP", "MotionPhoto flag and version must be 1")
        val directories = xmp.packets.flatMap { it.properties(CONTAINER_URI, "Directory") }
        if (directories.isEmpty()) fail("MISSING_REQUIRED_XMP", "MotionPhoto directory is missing")
        if (directories.size != 1) fail("CONFLICTING_METADATA", "MotionPhoto needs one authoritative directory")
        val directory = directories.single().element ?: fail("MALFORMED_XMP", "Directory must be an RDF sequence")
        wrapper(directory, emptySet())
        val sequence = directory.elements(RDF_URI, "Seq").singleOrNull() ?: fail("MALFORMED_XMP", "Directory needs exactly one RDF sequence")
        wrapper(sequence, emptySet())
        if (directory.children.filterIsInstance<XmlElement>().size != 1) fail("CAPABILITY_UNSUPPORTED", "Directory has additional resource representations")
        val children = sequence.children.filterIsInstance<XmlElement>()
        if (children.size < 2 || children.any { it.name.expanded != ExpandedName(RDF_URI, "li") }) fail("MALFORMED_XMP", "Directory needs Primary and MotionPhoto RDF items")
        var cursor = jpeg.primary.endExclusive
        var padding: ByteRange? = null
        val items = mutableListOf<GoogleItem>()
        val compatibility = mutableListOf<Issue>()
        for ((index, child) in children.withIndex()) {
            budget.item()
            budget.retain(128uL)
            if (child.attribute(RDF_URI, "parseType") != "Resource") fail("CAPABILITY_UNSUPPORTED", "Directory item requires explicit RDF resource semantics")
            wrapper(child, setOf("parseType"))
            val nested = child.elements(CONTAINER_URI, "Item")
            val item = if (nested.isEmpty()) child else nested.singleOrNull() ?: fail("CONFLICTING_METADATA", "Directory item has multiple Container items")
            if (item !== child) wrapper(item, emptySet())
            if (nested.isNotEmpty() && child.children.filterIsInstance<XmlElement>().size != 1) fail("CAPABILITY_UNSUPPORTED", "Directory item has unknown sibling resources")
            val semantic = itemValue(item, "Semantic") ?: fail("MISSING_REQUIRED_XMP", "Directory semantic is missing")
            val mime = itemValue(item, "Mime") ?: fail("MISSING_REQUIRED_XMP", "Directory MIME is missing")
            val length = itemValue(item, "Length")?.let(::unsigned)
            val itemPadding = itemValue(item, "Padding")?.let(::unsigned)
            if (index == 0) {
                if (semantic != "Primary" || mime != "image/jpeg" || (length != null && length != 0uL)) fail("MALFORMED_XMP", "First directory item must be implicit-length Primary JPEG")
                val paddingLength = itemPadding ?: 0uL
                padding = checkedRange(cursor, paddingLength, source.size).takeIf { it.length != 0uL }
                cursor = checkedAdd(cursor, paddingLength)
                items += GoogleItem(semantic, mime, jpeg.primary, 0uL)
            } else {
                if (itemPadding != null) {
                    if (itemPadding != 0uL) fail("MALFORMED_XMP", "Only Primary may declare nonzero Padding")
                    compatibility += Issue(IssueCode("MALFORMED_XMP"), Severity.Warning, Layer.Compatibility,
                        Location(source = source.id, selector = "{$ITEM_URI}Padding"))
                    compatibility += Issue(IssueCode("MALFORMED_XMP"), Severity.Error, Layer.Protocol,
                        Location(source = source.id, selector = "{$ITEM_URI}Padding"))
                }
                if (semantic == "Primary" || (semantic == "MotionPhoto") != (index == children.lastIndex)) fail("MALFORMED_XMP", "MotionPhoto must be last and Primary must be unique")
                val bytes = length ?: fail("MISSING_REQUIRED_XMP", "Secondary directory length is missing")
                if (bytes == 0uL) {
                    if (semantic == "MotionPhoto") fail("MOTION_VIDEO_LENGTH_MISMATCH", "MotionPhoto video resource must be nonempty")
                    fail("CAPABILITY_UNSUPPORTED", "Zero-length shared auxiliary items need an explicit shared-resource binding")
                }
                if (index == children.lastIndex && mime !in setOf("video/mp4", "video/quicktime")) fail("MALFORMED_XMP", "MotionPhoto MIME must identify a video container")
                val range = checkedRange(cursor, bytes, source.size)
                items += GoogleItem(semantic, mime, range, bytes)
                cursor = range.endExclusive
            }
        }
        if (cursor != source.size) fail("MOTION_VIDEO_LENGTH_MISMATCH", "Directory resources do not end at carrier EOF")
        val motion = items.last()
        if (motion.range.offset != source.size - motion.length) fail("MOTION_VIDEO_LENGTH_MISMATCH", "Forward directory layout conflicts with EOF video binding")
        val key = safeKey(xmp, "MotionPhotoPresentationTimestampUs", source.id)
        return GoogleBinding(ProtocolIds.GoogleV2, motion.range, padding, frozenList(items), key, frozenList(compatibility + key.issues))
    }

    private fun wrapper(element: XmlElement, allowedRdf: Set<String>) {
        if (element.attributes.any { it.name.expanded.uri == RDF_URI && it.name.expanded.local !in allowedRdf }) fail("CAPABILITY_UNSUPPORTED", "RDF directory identity or references are not inline item authority")
        if (element.children.any { node -> when (node) { is XmlText -> node.text.any { it !in " \t\r\n" }; is XmlCData -> node.text.any { it !in " \t\r\n" }; else -> false } }) fail("MALFORMED_XMP", "Directory wrappers cannot contain literal data")
    }

    private fun safeKey(xmp: XmpCollection, field: String, source: SourceId): KeyPhotoResult = try {
        key(xmp, field, source)
    } catch (fault: CoreFault) {
        if (fault.error.code.value in setOf("CANCELLED", "RESOURCE_LIMIT_EXCEEDED")) throw fault
        val selector = "{$CAMERA_URI}$field"
        val fields = xmp.packets.flatMap { it.properties(CAMERA_URI, field) }.mapNotNull { property ->
            val raw = property.attribute?.value ?: property.element?.children?.mapNotNull { (it as? XmlText)?.text ?: (it as? XmlCData)?.text }?.joinToString("")
            raw?.let { RawKeyField(selector, Value.Text(it), "microseconds", Location(source = source, selector = selector)) }
        }
        KeyPhotoResult(rawFields = frozenList(fields), issues = listOf(Issue(IssueCode("INVALID_PRESENTATION_TIMESTAMP"), Severity.Error, Layer.Protocol, Location(source = source, selector = selector))))
    }

    private fun itemValue(item: XmlElement, field: String): String? {
        val values = mutableListOf<String>()
        item.attribute(ITEM_URI, field)?.let(values::add)
        for (element in item.elements(ITEM_URI, field)) {
            if (element.attributes.isNotEmpty() || element.children.any { it is XmlElement }) fail("CAPABILITY_UNSUPPORTED", "Qualified directory fields require explicit interpretation")
            values += element.children.mapNotNull { when (it) { is XmlText -> it.text; is XmlCData -> it.text; else -> null } }.joinToString("")
        }
        if (values.distinct().size > 1) fail("CONFLICTING_METADATA", "Directory field $field conflicts")
        return values.firstOrNull()
    }

    private fun key(xmp: XmpCollection, field: String, source: SourceId): KeyPhotoResult {
        val raw = scalar(xmp, field, false) ?: return KeyPhotoResult()
        val digits = raw.removePrefix("-")
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) fail("MALFORMED_XMP", "Presentation timestamp must be signed decimal microseconds")
        val value = raw.toLongOrNull() ?: fail("INTEGER_OVERFLOW", "Presentation timestamp exceeds signed 64-bit range")
        if (value < -1) fail("INVALID_PRESENTATION_TIMESTAMP", "Presentation timestamp must be -1 or nonnegative")
        val selector = "{$CAMERA_URI}$field"
        return KeyPhotoResult(position = if (value == -1L) null else Time(value, 1_000_000u),
            source = KeySource.ProtocolField, rawFields = listOf(RawKeyField(selector, Value.Text(raw), "microseconds", Location(source = source, selector = selector))))
    }
}

internal fun unsigned(raw: String): ULong {
    if (raw.isEmpty() || raw.any { it !in '0'..'9' }) fail("MALFORMED_XMP", "Unsigned directory values require ASCII decimal digits")
    return raw.toULongOrNull() ?: fail("INTEGER_OVERFLOW", "Unsigned metadata exceeds 64-bit range")
}
