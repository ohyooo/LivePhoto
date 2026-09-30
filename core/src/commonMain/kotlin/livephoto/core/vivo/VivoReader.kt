package livephoto.core.vivo

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

internal const val VIVO_URI: String = "http://ns.vivo.com/photos/1.0/camera/"
internal val VIVO_FIELDS: Set<String> = setOf("VMotionPhotoVersion", "VMotionPhotoSource", "VMediaKitVersion")
private val candidateFields = VIVO_FIELDS + "VMotionPhotoFlags"

/** Native-confirmed modern JPEG profile, retaining safe base extents even when vendor identity is partial. */
internal object VivoReader {
    fun read(xmp: XmpCollection, google: List<CarrierBinding>, jpeg: JpegStructure, identity: SourceIdentity, budget: ParseBudget): CoreResult<CarrierBinding?> = attemptNow {
        budget.poll()
        if (candidateFields.none { field -> xmp.packets.any { it.properties(VIVO_URI, field).isNotEmpty() } }) return@attemptNow null
        budget.item(); budget.retain(384uL)
        val issues = mutableListOf<Issue>()
        val owned = mutableSetOf<ExpandedName>()
        fun issue(code: String, severity: Severity, selector: String? = null, layer: Layer = Layer.Protocol) {
            budget.item(); budget.retain(96uL)
            issues.add(Issue(IssueCode(code), severity, layer, Location(source = identity.id, selector = selector)))
        }
        var version: String? = null
        for (field in VIVO_FIELDS) {
            budget.poll()
            val selector = "{$VIVO_URI}$field"
            val properties = xmp.packets.flatMap { it.properties(VIVO_URI, field) }
            if (properties.isEmpty()) continue
            if (properties.size != 1) { issue("CONFLICTING_METADATA", Severity.Error, selector); continue }
            val property = properties.single()
            val element = property.element
            if (element != null && (element.attributes.isNotEmpty() || element.children.any { it !is XmlText && it !is XmlCData })) {
                issue("CAPABILITY_UNSUPPORTED", Severity.Warning, selector); continue
            }
            val value = property.attribute?.value ?: literal(element!!, budget)
            owned.add(ExpandedName(VIVO_URI, field))
            if (field == "VMotionPhotoVersion") version = value
        }
        if (version == null) {
            // Qualified/conflicting properties already carry a precise issue; absent version is distinct.
            if (xmp.packets.none { it.properties(VIVO_URI, "VMotionPhotoVersion").isNotEmpty() }) issue("MISSING_REQUIRED_XMP", Severity.Error, "{$VIVO_URI}VMotionPhotoVersion")
        } else {
            val parsed = try { unsigned(version) } catch (fault: CoreFault) {
                if (fault.error.code.value in setOf("CANCELLED", "RESOURCE_LIMIT_EXCEEDED")) throw fault
                issue(fault.error.code.value, Severity.Error, "{$VIVO_URI}VMotionPhotoVersion"); null
            }
            if (parsed != null && parsed != 1uL) issue("UNKNOWN_PROTOCOL_VARIANT", Severity.Warning, "{$VIVO_URI}VMotionPhotoVersion")
        }
        val bases = google.filter { it.protocol == ProtocolIds.GoogleV2 }
        val base = bases.singleOrNull()
        if (base == null || base.video == null || base.items.isEmpty()) {
            if (bases.size > 1) issue("CONFLICTING_METADATA", Severity.Error, "{$CONTAINER_URI}Directory")
            else issue("MISSING_REQUIRED_XMP", Severity.Error, "{$CONTAINER_URI}Directory")
            issues.addAll(base?.issues ?: emptyList())
            return@attemptNow CarrierBinding(ProtocolIds.VivoModern, issues = frozenList(issues), profile = ProfileId("jpeg"), ownedProperties = owned.toSet())
        }
        var nativeLayout = false
        try {
            val directories = xmp.packets.flatMap { it.properties(CONTAINER_URI, "Directory") }
            if (directories.size != 1) fail("CONFLICTING_METADATA", "vivo needs exactly one authoritative resource directory")
            val directory = directories.single().element ?: fail("MALFORMED_XMP", "vivo directory must be an inline sequence")
            val sequence = directory.elements(RDF_URI, "Seq").singleOrNull() ?: fail("MALFORMED_XMP", "vivo directory must have one RDF sequence")
            val children = sequence.children.filterIsInstance<XmlElement>()
            if (children.size !in 2..3 || children.size != base.items.size) fail("UNKNOWN_PROTOCOL_VARIANT", "vivo modern requires two or three resources")
            val raw = children.map { child ->
                budget.item(); budget.retain(128uL)
                child.elements(CONTAINER_URI, "Item").singleOrNull() ?: child
            }
            fun value(item: XmlElement, field: String): String? {
                budget.item(); budget.retain(64uL)
                val attributes = item.attributes.filter { it.name.expanded == ExpandedName(ITEM_URI, field) }
                val elements = item.elements(ITEM_URI, field)
                if (attributes.size + elements.size > 1) fail("CONFLICTING_METADATA", "vivo directory field has duplicate representations")
                val element = elements.singleOrNull()
                if (element != null && (element.attributes.isNotEmpty() || element.children.any { it !is XmlText && it !is XmlCData })) fail("CAPABILITY_UNSUPPORTED", "Qualified vivo item fields need explicit interpretation")
                return attributes.singleOrNull()?.value ?: element?.let { literal(it, budget) }
            }
            val primary = raw.first()
            if (value(primary, "Semantic") != "Primary" || value(primary, "Mime") != "image/jpeg" || value(primary, "Length") != null || value(primary, "Padding") != null) fail("MALFORMED_XMP", "vivo Primary must omit both Length and Padding")
            if (base.items.first().range != jpeg.primary || base.padding != null) fail("MOTION_VIDEO_LENGTH_MISMATCH", "vivo Primary must exactly match the complete JPEG boundary")
            val motion = raw.last()
            if (value(motion, "Semantic") != "MotionPhoto" || value(motion, "Mime") != "video/mp4") fail("UNKNOWN_PROTOCOL_VARIANT", "vivo modern motion resource must be MP4")
            val length = value(motion, "Length")?.let(::unsigned) ?: fail("MISSING_REQUIRED_XMP", "vivo motion Length is required")
            val padding = value(motion, "Padding")?.let(::unsigned) ?: fail("MISSING_REQUIRED_XMP", "vivo motion Padding must be explicitly zero")
            if (length == 0uL || length >= identity.size || padding != 0uL || base.video.length != length || base.video.offset != identity.size - length) fail("MOTION_VIDEO_LENGTH_MISMATCH", "vivo motion range/zero-padding disagrees with the verified resource graph")
            val primaryEnd = if (raw.size == 3) {
                val gainmap = raw[1]
                if (value(gainmap, "Semantic") != "GainMap" || value(gainmap, "Mime") != "image/jpeg" || value(gainmap, "Padding") != null) fail("AUXILIARY_RESOURCE_INVALID", "vivo GainMap must be a JPEG without Padding")
                val gainLength = value(gainmap, "Length")?.let(::unsigned) ?: fail("AUXILIARY_RESOURCE_INVALID", "vivo GainMap Length is required")
                if (gainLength == 0uL || gainLength > base.video.offset || base.items[1].range != ByteRange(base.video.offset - gainLength, gainLength)) fail("AUXILIARY_RESOURCE_INVALID", "vivo GainMap range disagrees with the resource graph")
                base.video.offset - gainLength
            } else base.video.offset
            if (primaryEnd != jpeg.primary.endExclusive) fail("MOTION_VIDEO_LENGTH_MISMATCH", "vivo resources do not immediately follow the complete Primary JPEG")
            nativeLayout = true
            if (raw.size == 3) issue("CAPABILITY_UNSUPPORTED", Severity.Warning, "vivo:GainMap:jpeg-verification", Layer.Media)
        } catch (fault: CoreFault) {
            if (fault.error.code.value in setOf("CANCELLED", "RESOURCE_LIMIT_EXCEEDED", "SOURCE_CHANGED", "IO_READ_FAILED")) throw fault
            issue(fault.error.code.value, if (fault.error.code.value in setOf("UNKNOWN_PROTOCOL_VARIANT", "CAPABILITY_UNSUPPORTED")) Severity.Warning else Severity.Error, "{$CONTAINER_URI}Directory")
        }
        val baseIssues = base.issues.filterNot { nativeLayout && it.code.value == "MALFORMED_XMP" && it.layer == Layer.Protocol &&
            it.severity == Severity.Error && it.location?.selector == "{$ITEM_URI}Padding" }
        issues.addAll(baseIssues)
        owned.addAll(base.ownedProperties.filter { it != ExpandedName(CONTAINER_URI, "Directory") || base.items.size == 2 })
        budget.poll()
        CarrierBinding(ProtocolIds.VivoModern, base.video, base.padding, base.items, base.key, frozenList(issues),
            ProfileId("jpeg"), ownedProperties = owned.toSet())
    }

    private fun literal(element: XmlElement, budget: ParseBudget): String {
        var count = 0uL
        for (node in element.children) {
            budget.poll()
            count = checkedAdd(count, when (node) { is XmlText -> node.text.length.toULong(); is XmlCData -> node.text.length.toULong(); else -> 0uL })
        }
        budget.retain(checkedMultiply(count, 2uL))
        checkedInt(count)
        return buildString(count.toInt()) {
            for (node in element.children) { budget.poll(); when (node) { is XmlText -> append(node.text); is XmlCData -> append(node.text); else -> Unit } }
        }
    }
}
