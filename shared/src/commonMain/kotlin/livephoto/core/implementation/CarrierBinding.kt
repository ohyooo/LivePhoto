package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.xml.ExpandedName

/** Protocol handlers supply scoped ownership and resource facts; this model never authorizes edits. */
internal data class CarrierBinding(
    val protocol: ProtocolId,
    val video: ByteRange? = null,
    val padding: ByteRange? = null,
    val items: List<CarrierItem> = emptyList(),
    val key: KeyPhotoResult = KeyPhotoResult(),
    val issues: List<Issue> = emptyList(),
    val profile: ProfileId = ProfileId("jpeg"),
    val trailer: ByteRange? = null,
    val ownedProperties: Set<ExpandedName> = emptySet(),
    val compatibleBaseOf: ProtocolId? = null,
) {
    val selector: ProtocolSelector get() = ProtocolSelector(protocol, profile)
    val structurallyValid: Boolean get() = video != null && issues.none { it.severity == Severity.Error }
}

internal data class CarrierItem(val semantic: String, val mime: String, val range: ByteRange, val length: ULong)
