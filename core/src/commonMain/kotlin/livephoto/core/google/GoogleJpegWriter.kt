package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

/** Produces staging bytes only; publication belongs exclusively to the Core orchestrator. */
internal object GoogleJpegWriter {
    fun createPlan(session: SourceSession, target: ProtocolSelector, videoLength: ULong, mime: String, timestamp: Long, strip: Boolean, context: Context): CoreResult<JpegRewritePlan> = attemptNow {
        val jpeg = session.jpeg ?: fail("UNSUPPORTED_CONTAINER", "Google JPEG creation requires JPEG image content")
        val xmp = session.xmp!!
        if (session.bindings.isNotEmpty() && !strip) fail("SOURCE_ALREADY_LIVE", "Image contains source protocol bindings")
        if (strip && session.bindings.isNotEmpty() && (session.bindings.size != 1 || !session.bindings.single().structurallyValid || session.bindings.single().protocol !in session.videos)) fail("UNSAFE_METADATA_REWRITE", "Source stripping needs one fully verified source binding")
        if (jpeg.trailing.length != 0uL && (!strip || session.bindings.size != 1 || !session.bindings.single().structurallyValid ||
            session.bindings.single().video?.offset != jpeg.primary.endExclusive || session.bindings.single().protocol !in session.videos || session.bindings.single().items.size > 2)) {
            fail("UNSAFE_METADATA_REWRITE", "Create cannot discard an unverified or unowned carrier suffix")
        }
        if (xmp.extended.isNotEmpty() || xmp.packets.size > 1) fail("UNSAFE_METADATA_REWRITE", "Extended or duplicate XMP cannot be reconciled implicitly")
        if (target.protocol !in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2) || target.profile != null && target.profile != ProfileId("jpeg")) fail("UNSUPPORTED_PROTOCOL", "Requested Google profile is not implemented")
        var packet = xmp.packets.singleOrNull()
        if (packet != null && (target.protocol == ProtocolIds.GoogleV2 || strip && session.bindings.any { it.protocol == ProtocolIds.GoogleV2 })) packet = removeDirectory(packet, context)
        val targetFields = if (target.protocol == ProtocolIds.GoogleV1) V1_FIELDS else V2_FIELDS
        val sourceFields = if (strip) session.bindings.flatMap { if (it.protocol == ProtocolIds.GoogleV1) V1_FIELDS else V2_FIELDS }.toSet() else emptySet()
        val updates = (targetFields + sourceFields).associate { ExpandedName(CAMERA_URI, it) to null as String? }.toMutableMap()
        if (target.protocol == ProtocolIds.GoogleV1) {
            updates[ExpandedName(CAMERA_URI, "MicroVideo")] = "1"
            updates[ExpandedName(CAMERA_URI, "MicroVideoVersion")] = "1"
            updates[ExpandedName(CAMERA_URI, "MicroVideoOffset")] = videoLength.toString()
            updates[ExpandedName(CAMERA_URI, "MicroVideoPresentationTimestampUs")] = timestamp.toString()
        } else {
            updates[ExpandedName(CAMERA_URI, "MotionPhoto")] = "1"
            updates[ExpandedName(CAMERA_URI, "MotionPhotoVersion")] = "1"
            updates[ExpandedName(CAMERA_URI, "MotionPhotoPresentationTimestampUs")] = timestamp.toString()
        }
        var bytes = if (packet == null) XmpWriter.create(updates.filterValues { it != null }.mapValues { it.value!! }, context).orThrow()
            else XmpWriter.merge(packet, updates, context).orThrow()
        if (target.protocol == ProtocolIds.GoogleV2) bytes = addDirectory(XmpReader.parse(bytes, context).orThrow(), videoLength, mime, context)
        patch(jpeg, bytes)
    }

    fun cleanPlan(session: SourceSession, context: Context): CoreResult<JpegRewritePlan> = attemptNow {
        val jpeg = session.jpeg ?: fail("UNSUPPORTED_CONTAINER", "Clean JPEG requires JPEG content")
        val xmp = session.xmp!!
        if (session.bindings.isEmpty()) {
            if (jpeg.trailing.length != 0uL) fail("UNSAFE_METADATA_REWRITE", "Unowned suffix cannot be silently removed by clean split")
            return@attemptNow JpegRewrite.plan(jpeg, emptyList()).orThrow()
        }
        if (session.bindings.any { !it.structurallyValid }) fail("UNSAFE_METADATA_REWRITE", "Broken or conflicting bindings cannot authorize clean deletion")
        if (session.bindings.any { it.padding?.length != null && it.padding.length != 0uL || it.items.size > 2 }) fail("GAINMAP_PRESERVATION_UNAVAILABLE", "Auxiliary resources and unknown padding require a verified clean relocation plan")
        if (!xmp.rewriteAllowed) fail("UNSAFE_METADATA_REWRITE", "Clean requires one complete ordinary XMP packet")
        val packet = if (session.bindings.any { it.protocol == ProtocolIds.GoogleV2 }) removeDirectory(xmp.packets.single(), context) else xmp.packets.single()
        val fields = session.bindings.flatMap { if (it.protocol == ProtocolIds.GoogleV1) V1_FIELDS else V2_FIELDS }.toSet()
        val bytes = XmpWriter.merge(packet, fields.associate { ExpandedName(CAMERA_URI, it) to null }, context).orThrow()
        patch(jpeg, bytes)
    }

    private fun patch(jpeg: JpegStructure, xml: Bytes): JpegRewritePlan {
        val header = XMP_HEADER.encodeToByteArray()
        val length = checkedAdd(header.size.toULong(), xml.size.toULong())
        if (length > 65_533uL) fail("VALUE_NOT_REPRESENTABLE", "Canonical XMP does not fit one JPEG APP1 segment")
        val payload = ByteArray(checkedInt(length))
        header.copyInto(payload); xml.copyInto(payload, header.size)
        val app = JpegRewrite.appSegment(0xe1, Bytes(payload)).orThrow()
        val original = jpeg.segments.filter { it.payloadKind == AppPayloadKind.Xmp }
        if (original.size > 1) fail("UNSAFE_METADATA_REWRITE", "Multiple XMP packets need explicit authority")
        val range = original.singleOrNull()?.range ?: ByteRange(jpeg.segments.firstOrNull { it.marker == 0xda }?.range?.offset ?: fail("CORRUPTED_CONTAINER", "JPEG has no scan header"), 0uL)
        return JpegRewrite.plan(jpeg, listOf(JpegPatch(range, app))).orThrow()
    }

    private fun removeDirectory(packet: XmpPacket, context: Context): XmpPacket {
        val directories = packet.properties(CONTAINER_URI, "Directory")
        if (directories.isEmpty()) return packet
        if (directories.size != 1) fail("UNSAFE_METADATA_REWRITE", "Multiple resource directories cannot be stripped")
        val directory = directories.single().element ?: fail("UNSAFE_METADATA_REWRITE", "Directory authority is not an inline sequence")
        val sequence = directory.elements(RDF_URI, "Seq").singleOrNull() ?: fail("UNSAFE_METADATA_REWRITE", "Directory authority is not an inline sequence")
        fun guard(element: XmlElement, attributeNames: Set<ExpandedName>, childNames: Set<ExpandedName>) {
            if (element.attributes.any { it.name.expanded !in attributeNames } || element.children.any { node ->
                when (node) { is XmlElement -> node.name.expanded !in childNames; is XmlText -> node.text.any { it !in " \t\r\n" }; else -> true }
            }) fail("UNSAFE_METADATA_REWRITE", "Directory has private metadata that cannot be removed implicitly")
        }
        guard(directory, emptySet(), setOf(ExpandedName(RDF_URI, "Seq")))
        guard(sequence, emptySet(), setOf(ExpandedName(RDF_URI, "li")))
        val semantics = sequence.elements(RDF_URI, "li").map { child ->
            val item = child.elements(CONTAINER_URI, "Item").singleOrNull() ?: child
            val fields = setOf("Semantic", "Mime", "Length", "Padding").map { ExpandedName(ITEM_URI, it) }.toSet()
            if (item !== child) guard(child, setOf(ExpandedName(RDF_URI, "parseType")), setOf(ExpandedName(CONTAINER_URI, "Item")))
            guard(item, fields + if (item === child) setOf(ExpandedName(RDF_URI, "parseType")) else emptySet(), fields)
            for (leaf in item.children.filterIsInstance<XmlElement>()) {
                if (leaf.attributes.isNotEmpty() || leaf.children.any { it !is XmlText && it !is XmlCData }) fail("UNSAFE_METADATA_REWRITE", "Directory field has private qualifiers or nested metadata")
            }
            item.attribute(ITEM_URI, "Semantic") ?: item.elements(ITEM_URI, "Semantic").singleOrNull()?.children?.filterIsInstance<XmlText>()?.joinToString("") { it.text }
        }
        if (semantics.any { it !in setOf("Primary", "MotionPhoto") }) fail("GAINMAP_PRESERVATION_UNAVAILABLE", "Ordinary and unknown directory resources must remain intact")
        // A plain image may own an ordinary Primary-only directory; it is not a live binding.
        if ("MotionPhoto" !in semantics) fail("UNSAFE_METADATA_REWRITE", "An ordinary image directory cannot be silently replaced")
        fun transform(element: XmlElement): XmlElement = if (element in packet.descriptions) element.copy(children = element.children.filterNot { it === directory })
            else element.copy(children = element.children.map { if (it is XmlElement) transform(it) else it })
        val root = transform(packet.document.root)
        val document = XmlDocument(root, packet.document.nodes.map { if (it === packet.document.root) root else it })
        return XmpReader.parse(XmlWriter.write(document, context).orThrow(), context).orThrow()
    }

    private fun addDirectory(packet: XmpPacket, length: ULong, mime: String, context: Context): Bytes {
        fun name(prefix: String, uri: String, local: String): XmlName = XmlName("$prefix:$local", ExpandedName(uri, local))
        fun item(semantic: String, type: String, bytes: ULong?): XmlElement {
            val attributes = mutableListOf(XmlAttribute(name("i", ITEM_URI, "Semantic"), semantic), XmlAttribute(name("i", ITEM_URI, "Mime"), type))
            if (bytes != null) attributes += XmlAttribute(name("i", ITEM_URI, "Length"), bytes.toString())
            val content = XmlElement(name("c", CONTAINER_URI, "Item"), attributes, mapOf("c" to CONTAINER_URI, "i" to ITEM_URI), emptyList())
            return XmlElement(name("r", RDF_URI, "li"), listOf(XmlAttribute(name("r", RDF_URI, "parseType"), "Resource")), emptyMap(), listOf(content))
        }
        val sequence = XmlElement(name("r", RDF_URI, "Seq"), emptyList(), mapOf("r" to RDF_URI), listOf(item("Primary", "image/jpeg", null), item("MotionPhoto", mime, length)))
        val directory = XmlElement(name("c", CONTAINER_URI, "Directory"), emptyList(), mapOf("c" to CONTAINER_URI), listOf(sequence))
        val first = packet.descriptions.first()
        fun transform(element: XmlElement): XmlElement = if (element === first) element.copy(children = element.children + directory)
            else element.copy(children = element.children.map { if (it is XmlElement) transform(it) else it })
        val root = transform(packet.document.root)
        return XmlWriter.write(XmlDocument(root, packet.document.nodes.map { if (it === packet.document.root) root else it }), context).orThrow()
    }
}
