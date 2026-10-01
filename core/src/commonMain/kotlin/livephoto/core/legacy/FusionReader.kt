package livephoto.core.legacy

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.implementation.*
import livephoto.core.oplus.*
import livephoto.core.vivo.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

internal const val FUSION_URI: String = "https://github.com/LengxiQwQ/live-photo-box"

/** Author-generated overlay evidence; Samsung's checked record graph supplies every media extent. */
internal object FusionReader {
    fun read(xmp: XmpCollection, samsung: CarrierBinding?, oplus: CarrierBinding?, vivo: CarrierBinding?, google: List<CarrierBinding>, budget: ParseBudget): CoreResult<CarrierBinding?> = attemptNow {
        val properties = xmp.packets.flatMap { it.properties(FUSION_URI, "Protocol") }
        if (properties.isEmpty()) return@attemptNow null
        if (properties.size != 1) return@attemptNow CarrierBinding(ProtocolIds.Fusion,
            issues = listOf(Issue(IssueCode("CONFLICTING_METADATA"), Severity.Error, Layer.Protocol, Location(selector = "{$FUSION_URI}Protocol"))))
        if (properties.any { it.element?.attributes?.isNotEmpty() == true }) return@attemptNow null
        val marker = try { xmp.scalar(FUSION_URI, "Protocol").orThrow() } catch (fault: CoreFault) {
            if (fault.error.code.value in setOf("CANCELLED", "RESOURCE_LIMIT_EXCEEDED")) throw fault
            return@attemptNow CarrierBinding(ProtocolIds.Fusion, issues = listOf(Issue(fault.error.code, Severity.Error, Layer.Protocol, fault.error.location)), profile = ProfileId("jpeg"))
        }
        if (marker != "MotionPhotoFusion") return@attemptNow null
        budget.item(); budget.retain(256uL)
        val issues = mutableListOf<Issue>()
        if (samsung?.video == null) issues += Issue(IssueCode("SEF_DIRECTORY_INVALID"), Severity.Error, Layer.Protocol)
        if (samsung != null) issues += samsung.issues
        for (binding in google.filter { it.protocol == ProtocolIds.GoogleV1 }) {
            issues += binding.issues
            if (binding.video != samsung?.video || binding.key.position != null && samsung?.key?.position != null && binding.key.position.compareTo(samsung.key.position) != 0)
                issues += Issue(IssueCode("CONFLICTING_METADATA"), Severity.Error, Layer.Protocol, Location(selector = "{$CAMERA_URI}MicroVideoOffset"))
        }
        issues += oplus?.issues.orEmpty()
        issues += vivo?.issues.orEmpty().map { issue ->
            if (samsung?.structurallyValid == true && issue.code.value == "MALFORMED_XMP" &&
                issue.location?.selector in setOf("{$CONTAINER_URI}Directory", "{$ITEM_URI}Padding")) issue.copy(severity = Severity.Warning, layer = Layer.Compatibility) else issue
        }
        if (oplus?.video != null && samsung?.video != null && oplus.video != samsung.video) issues += Issue(IssueCode("CONFLICTING_METADATA"), Severity.Error, Layer.Protocol)
        val lengthName = ExpandedName(OPLUS_URI, "VideoLength")
        try {
            val lengthProperties = xmp.packets.flatMap { it.properties(lengthName.uri, lengthName.local) }
            if (lengthProperties.any { it.element?.attributes?.isNotEmpty() == true }) fail("CAPABILITY_UNSUPPORTED", "Qualified Fusion pure-video length is not confirmed")
            val raw = xmp.scalar(lengthName.uri, lengthName.local).orThrow()
            if (raw != null && samsung?.video != null && unsigned(raw) != samsung.video.length) issues += Issue(IssueCode("CONFLICTING_METADATA"), Severity.Error, Layer.Protocol, Location(selector = "{$OPLUS_URI}VideoLength"))
        } catch (fault: CoreFault) {
            if (fault.error.code.value in setOf("CANCELLED", "RESOURCE_LIMIT_EXCEEDED")) throw fault
            issues += Issue(fault.error.code, if (fault.error.code.value == "CAPABILITY_UNSUPPORTED") Severity.Warning else Severity.Error, Layer.Protocol, Location(selector = "{$OPLUS_URI}VideoLength"))
        }
        if (oplus == null || vivo == null) issues += Issue(IssueCode("UNKNOWN_PROTOCOL_VARIANT"), Severity.Warning, Layer.Protocol)
        // The author's overlay has its own Samsung layout; it never authorizes a full vivo native profile.
        for ((field, expected) in mapOf("VMotionPhotoVersion" to "1", "VMotionPhotoSource" to "1", "VMediaKitVersion" to "1.0.0.9")) {
            val propertiesForField = xmp.packets.flatMap { it.properties(VIVO_URI, field) }
            if (propertiesForField.size != 1 || propertiesForField.any { it.element?.attributes?.isNotEmpty() == true }) {
                issues += Issue(IssueCode("CONFLICTING_METADATA"), Severity.Error, Layer.Protocol, Location(selector = "{$VIVO_URI}$field"))
                continue
            }
            if (xmp.scalar(VIVO_URI, field).orThrow() != expected)
                issues += Issue(IssueCode("UNKNOWN_PROTOCOL_VARIANT"), Severity.Warning, Layer.Protocol, Location(selector = "{$VIVO_URI}$field"))
        }
        if (oplus?.video != null && samsung?.video != null && oplus.video.length != samsung.video.length)
            issues += Issue(IssueCode("CONFLICTING_METADATA"), Severity.Error, Layer.Protocol)
        val owned = setOf(ExpandedName(FUSION_URI, "Protocol")) + samsung?.ownedProperties.orEmpty() +
            oplus?.takeIf { it.structurallyValid }?.ownedProperties.orEmpty() + vivo?.ownedProperties.orEmpty().filter { name -> name.uri == VIVO_URI && issues.none { it.severity == Severity.Error } }
        CarrierBinding(ProtocolIds.Fusion, samsung?.video, samsung?.padding, samsung?.items ?: emptyList(),
            samsung?.key ?: KeyPhotoResult(), issues, ProfileId("jpeg"), ownedProperties = owned)
    }
}
