package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

/** Hashes JPEG coding bytes including frame/tables/scan headers and entropy, excluding APP/COM. */
internal suspend fun codingDigest(session: SourceSession): Digest {
    val jpeg = session.jpeg ?: fail("UNSUPPORTED_CONTAINER", "JPEG coding proof needs parsed JPEG")
    val excluded = jpeg.segments.filter { it.marker in 0xe0..0xef || it.marker == 0xfe }.sortedBy { it.range.offset }
    val hash = Sha256()
    suspend fun feed(range: ByteRange) {
        var offset = range.offset
        while (offset < range.endExclusive) {
            checkCancelled(session.reader.context)
            val bytes = session.reader.readBuffer(offset, minOf(65_536uL, range.endExclusive - offset).toUInt()).orThrow()
            if (bytes.size == 0) fail("UNEXPECTED_EOF", "Image preservation proof was truncated")
            hash.update(bytes); offset += bytes.size.toULong()
        }
    }
    var offset = 0uL
    for (segment in excluded) { feed(ByteRange(offset, segment.range.offset - offset)); offset = segment.range.endExclusive }
    feed(ByteRange(offset, jpeg.primary.endExclusive - offset))
    session.recheck()
    return hash.finish()
}

/** Ordinary XMP meaning and each non-XMP APP/COM byte string, without protocol bindings. */
internal suspend fun ordinaryDigest(session: SourceSession, requestedCameraFields: Set<String> = emptySet()): Digest {
    val hash = Sha256()
    fun text(value: String) { val bytes = Bytes(value.encodeToByteArray()); hash.update(unsignedBytes(bytes.size.toULong(), 8, Endian.Big)); hash.update(bytes) }
    for (segment in session.jpeg!!.segments) if ((segment.marker in 0xe0..0xef || segment.marker == 0xfe) && segment.payloadKind != AppPayloadKind.Xmp) {
        text("raw:${segment.marker}")
        hash.update(Bytes(sha256Range(session.reader, segment.range).orThrow().value.encodeToByteArray()))
    }
    fun visit(element: XmlElement, authority: Boolean, depth: UInt, authoritativeTree: Boolean) {
        checkCancelled(session.reader.context)
        if (depth > minOf(256u, session.reader.context.limits.maxDepth)) fail("RESOURCE_LIMIT_EXCEEDED", "Metadata proof depth exceeds limit")
        val structural = authoritativeTree && element.name.expanded in setOf(ExpandedName(RDF_URI, "RDF"), ExpandedName(RDF_URI, "Description"), ExpandedName(XMP_META_URI, "xmpmeta"), ExpandedName(XMP_META_URI, "xapmeta"))
        if (!structural) { text("element"); text(element.name.expanded.uri); text(element.name.expanded.local) }
        for (attribute in element.attributes.sortedWith(compareBy({ it.name.expanded.uri }, { it.name.expanded.local }))) {
            val name = attribute.name.expanded
            if (name == ExpandedName(RDF_URI, "about") && attribute.value.isEmpty()) continue
            if (authority && name.uri == CAMERA_URI && (name.local in requestedCameraFields || session.bindings.any { name.local in if (it.protocol == ProtocolIds.GoogleV1) V1_FIELDS else V2_FIELDS })) continue
            text("attribute"); text(name.uri); text(name.local); text(attribute.value)
        }
        for (node in element.children) when (node) {
            is XmlElement -> {
                val name = node.name.expanded
                if (authority && name.uri == CAMERA_URI && (name.local in requestedCameraFields || session.bindings.any { name.local in if (it.protocol == ProtocolIds.GoogleV1) V1_FIELDS else V2_FIELDS })) continue
                if (authority && name == ExpandedName(CONTAINER_URI, "Directory") && session.bindings.isNotEmpty()) continue
                val directSubject = authoritativeTree && !authority && element.name.expanded == ExpandedName(RDF_URI, "RDF") && node.name.expanded == ExpandedName(RDF_URI, "Description")
                visit(node, directSubject, depth + 1u, directSubject || authoritativeTree && !authority && node.name.expanded == ExpandedName(RDF_URI, "RDF"))
            }
            is XmlText -> if (!structural || node.text.any { it !in " \t\r\n" }) { text("text"); text(node.text) }
            is XmlCData -> { text("text"); text(node.text) }
            is XmlComment -> { text("comment"); text(node.text) }
            is XmlProcessingInstruction -> { text("pi"); text(node.target); text(node.content) }
        }
        if (!structural) text("end")
    }
    for (packet in session.xmp!!.packets) visit(packet.document.root, false, 0u, true)
    session.recheck()
    return hash.finish()
}

internal fun exactRecords(id: AssetId, digest: Digest, role: AssetRole, metadataSafe: Boolean, videoVerified: Boolean, imageVerified: Boolean): List<GuaranteeRecord> = Guarantee.entries.map { guarantee ->
    val outcome = when (guarantee) {
        Guarantee.ExactExtraction -> GuaranteeOutcome.Verified
        Guarantee.ImageDataPreserving -> if (role in setOf(AssetRole.PrimaryImage, AssetRole.Composite)) { if (imageVerified) GuaranteeOutcome.Verified else GuaranteeOutcome.Unknown } else GuaranteeOutcome.NotApplicable
        Guarantee.BitstreamPreserving -> if (role == AssetRole.MotionVideo) { if (videoVerified) GuaranteeOutcome.Verified else GuaranteeOutcome.Unknown } else if (role == AssetRole.Composite && videoVerified) GuaranteeOutcome.Verified else GuaranteeOutcome.NotApplicable
        Guarantee.MetadataPreserving -> if (metadataSafe) GuaranteeOutcome.Verified else GuaranteeOutcome.Unknown
    }
    GuaranteeRecord(id, guarantee, outcome,
        digest, digest, "SHA-256 of the entire selected resource matches staged readback")
}

internal suspend fun opaqueOffsetsPreserved(input: SourceSession, output: SourceSession): Boolean {
    val originalJpeg = input.jpeg ?: return false
    val stagedJpeg = output.jpeg ?: return false
    // An unparsed MakerNote may depend on later contents, not just positions. Only an exact
    // whole-carrier readback proves that these unknown associations remain unchanged.
    if (originalJpeg.hasExif) {
        val originalSize = input.reader.identity().orThrow().size
        val stagedSize = output.reader.identity().orThrow().size
        if (originalSize != stagedSize || sha256Range(input.reader, ByteRange(0uL, originalSize)).orThrow() !=
            sha256Range(output.reader, ByteRange(0uL, stagedSize)).orThrow()) return false
    }
    val original = originalJpeg.segments.filter { it.marker in 0xe0..0xef && it.payloadKind == AppPayloadKind.Unknown }
    val staged = stagedJpeg.segments.filter { it.marker in 0xe0..0xef && it.payloadKind == AppPayloadKind.Unknown }
    return original.map { it.range } == staged.map { it.range }
}
