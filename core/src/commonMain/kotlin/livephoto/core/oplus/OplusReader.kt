package livephoto.core.oplus

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

internal const val OPLUS_URI: String = "http://ns.oplus.com/photos/1.0/camera/"
internal val OPLUS_FIELDS: Set<String> = setOf("MotionPhotoPrimaryPresentationTimestampUs", "MotionPhotoOwner", "OLivePhotoVersion", "VideoLength")
internal const val OPLUS_MARKER: String = "oplus_10485792"

/** D describes the base directory suffix; V alone describes the independently extracted video. */
internal object OplusReader {
    fun read(xmp: XmpCollection, google: List<CarrierBinding>, jpeg: JpegStructure, source: SourceIdentity,
        userComment: String?, budget: ParseBudget): CoreResult<CarrierBinding?> = attemptNow {
        val present = OPLUS_FIELDS.any { field -> xmp.packets.any { it.properties(OPLUS_URI, field).isNotEmpty() } }
        val marker = userComment in setOf(OPLUS_MARKER, "oplus_8388608")
        if (!present && !marker) return@attemptNow null
        budget.item(); budget.retain(256uL)
        val properties = OPLUS_FIELDS.map { ExpandedName(OPLUS_URI, it) }.toSet() + V2_FIELDS.map { ExpandedName(CAMERA_URI, it) }
        try {
            fun scalar(field: String): String {
                for (property in xmp.packets.flatMap { it.properties(OPLUS_URI, field) }) if (property.element?.attributes?.isNotEmpty() == true) fail("CAPABILITY_UNSUPPORTED", "Qualified Oplus fields require explicit interpretation")
                return xmp.scalar(OPLUS_URI, field).orThrow() ?: fail("MISSING_REQUIRED_XMP", "Oplus property $field is missing")
            }
            if (scalar("MotionPhotoOwner") != "oplus") fail("UNKNOWN_PROTOCOL_VARIANT", "Oplus owner is not the confirmed oplus binding")
            if (unsigned(scalar("OLivePhotoVersion")) !in setOf(1uL, 2uL)) fail("UNKNOWN_PROTOCOL_VARIANT", "Oplus version is outside the confirmed reader scope")
            val base = google.singleOrNull { it.protocol == ProtocolIds.GoogleV2 } ?: fail("MISSING_REQUIRED_XMP", "Oplus needs one authoritative Google V2 base directory")
            val suffix = base.video ?: fail("MOTION_VIDEO_MISSING", "Oplus base has no confirmed suffix range")
            val length = unsigned(scalar("VideoLength"))
            if (length == 0uL || length > suffix.length || suffix.length >= source.size) fail("MOTION_VIDEO_LENGTH_MISMATCH", "Oplus requires 0 < V <= D < carrier size")
            val video = checkedRange(source.size - suffix.length, length, source.size)
            if (video.offset < jpeg.primary.endExclusive) fail("OFFSET_OUT_OF_BOUNDS", "Oplus video overlaps its JPEG")
            val tail = ByteRange(video.endExclusive, suffix.length - length).takeIf { it.length != 0uL }
            val issues = base.issues.map { issue ->
                if (issue.code.value == "MALFORMED_XMP" && issue.location?.selector == "{$ITEM_URI}Padding") issue.copy(severity = Severity.Warning, layer = Layer.Compatibility) else issue
            }.toMutableList()
            if (!marker) issues += Issue(IssueCode("MISSING_VENDOR_MARKER"), Severity.Error, Layer.Protocol, Location(source = source.id, selector = "exif:UserComment"))
            val selector = "{$OPLUS_URI}MotionPhotoPrimaryPresentationTimestampUs"
            val propertiesForTime = xmp.packets.flatMap { it.properties(OPLUS_URI, "MotionPhotoPrimaryPresentationTimestampUs") }
            val rawFields = propertiesForTime.mapNotNull { property ->
                val raw = property.attribute?.value ?: property.element?.children?.mapNotNull { (it as? XmlText)?.text ?: (it as? XmlCData)?.text }?.joinToString("")
                raw?.let { RawKeyField(selector, Value.Text(it), "microseconds", Location(source = source.id, selector = selector)) }
            }
            val raw = try {
                if (propertiesForTime.any { it.element?.attributes?.isNotEmpty() == true }) fail("CAPABILITY_UNSUPPORTED", "Qualified Oplus timestamps need explicit interpretation")
                xmp.scalar(OPLUS_URI, "MotionPhotoPrimaryPresentationTimestampUs").orThrow()
            } catch (fault: CoreFault) {
                issues += Issue(fault.error.code, if (fault.error.code.value == "CAPABILITY_UNSUPPORTED") Severity.Warning else Severity.Error, Layer.Protocol, Location(source = source.id, selector = selector))
                null
            }
            val digits = raw?.removePrefix("-")
            val parsed = if (!digits.isNullOrEmpty() && digits.all { it in '0'..'9' }) raw.toLongOrNull() else null
            if (raw != null && (parsed == null || parsed < -1)) issues += Issue(IssueCode("INVALID_PRESENTATION_TIMESTAMP"), Severity.Error, Layer.Protocol, Location(source = source.id, selector = selector))
            // The edit model distinguishes the current cover from the original capture.
            // PrimaryPresentationTimestampUs is retained as a raw original-photo fact;
            // a different value is not a conflict and must not override the cover key.
            val key = KeyPhotoResult(base.key.position, source = base.key.source,
                rawFields = base.key.rawFields + rawFields, issues = issues.filter { it.code.value in setOf("INVALID_PRESENTATION_TIMESTAMP", "CONFLICTING_METADATA") })
            CarrierBinding(ProtocolIds.Oplus, video, base.padding, base.items, key, frozenList(issues),
                ProfileId(if (tail == null) "jpeg-no-tail" else "oneplus-tail-bearing"), tail,
                properties + if (base.items.size <= 2) setOf(ExpandedName(CONTAINER_URI, "Directory")) else emptySet())
        } catch (fault: CoreFault) {
            if (fault.error.code.value in setOf("CANCELLED", "RESOURCE_LIMIT_EXCEEDED")) throw fault
            CarrierBinding(ProtocolIds.Oplus, issues = listOf(Issue(fault.error.code,
                if (fault.error.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT")) Severity.Warning else Severity.Error,
                Layer.Protocol, fault.error.location ?: Location(source = source.id))), profile = ProfileId("jpeg-no-tail"), ownedProperties = properties)
        }
    }
}
