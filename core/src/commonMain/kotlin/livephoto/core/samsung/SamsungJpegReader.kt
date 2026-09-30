package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*

/** SEF owns the pure media range. Google directory suffix lengths remain independent evidence. */
internal object SamsungJpegReader {
    fun bind(directory: SefDirectory, google: List<CarrierBinding>, jpeg: JpegStructure): CarrierBinding? {
        val motion = directory.records.singleOrNull { it.type == 0x0a30.toUShort() } ?: return null
        val video = directory.pureVideoRange
        val base = google.singleOrNull { it.protocol == ProtocolIds.GoogleV2 }
        val issues = mutableListOf<Issue>()
        if (directory.version != 107u || directory.versionRecord == null) issues += Issue(IssueCode("UNKNOWN_PROTOCOL_VARIANT"), Severity.Warning, Layer.Protocol,
            Location(source = directory.sourceIdentity.id, range = directory.table))
        if (video == null || video.offset < jpeg.primary.endExclusive) issues += Issue(IssueCode("SEF_DIRECTORY_INVALID"), Severity.Error, Layer.Protocol)
        if (directory.legacyDialect) issues += Issue(IssueCode("SEF_DIRECTORY_INVALID"), Severity.Error, Layer.Protocol,
            Location(source = directory.sourceIdentity.id, range = directory.footer), observed = Value.Text("legacy-footer-inclusive"))
        val expectedHeader = ByteRange(motion.range.offset, 24uL)
        if (motion.range.offset != jpeg.primary.endExclusive) issues += Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Protocol,
            Location(source = directory.sourceIdentity.id, range = motion.range), observed = Value.Text("records-before-motion"))
        if (base != null) {
            issues += base.issues.map { issue ->
                if (issue.code.value == "MALFORMED_XMP" && issue.location?.selector == "{$ITEM_URI}Padding") issue.copy(severity = Severity.Warning, layer = Layer.Compatibility) else issue
            }
            if (base.video?.offset != video?.offset || base.video?.endExclusive != directory.footer.endExclusive || base.padding != expectedHeader)
                issues += Issue(IssueCode("CONFLICTING_METADATA"), Severity.Error, Layer.Protocol, Location(source = directory.sourceIdentity.id, range = base.video))
        }
        return CarrierBinding(ProtocolIds.Samsung, video, expectedHeader, base?.items ?: emptyList(), base?.key ?: KeyPhotoResult(),
            issues, ProfileId("jpeg-sef-mpv3"), ownedProperties = base?.ownedProperties.orEmpty().filter { it != ExpandedName(CONTAINER_URI, "Directory") }.toSet() +
                if (base != null && base.items.size <= 2) setOf(ExpandedName(CONTAINER_URI, "Directory")) else emptySet())
    }
}
