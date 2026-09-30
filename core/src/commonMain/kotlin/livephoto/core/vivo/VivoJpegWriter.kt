package livephoto.core.vivo

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

internal object VivoJpegWriter {
    fun createPlan(session: SourceSession, videoLength: ULong, timestamp: Long, strip: Boolean, context: Context, budget: ParseBudget): CoreResult<JpegRewritePlan> = attemptNow {
        val jpeg = session.jpeg ?: fail("UNSUPPORTED_CONTAINER", "vivo creation requires JPEG")
        if (session.bindings.isNotEmpty()) fail(if (strip) "CAPABILITY_UNSUPPORTED" else "SOURCE_ALREADY_LIVE", "vivo create does not replace existing live bindings")
        reserve(session, budget)
        val base = GoogleJpegWriter.createPlan(session, ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg")), videoLength,
            "video/mp4", timestamp, false, context, motionPadding = 0uL).orThrow()
        val patch = base.patches.single()
        val packet = XmpReader.parse(patch.replacement.slice(4 + XMP_HEADER.length), context).orThrow()
        val updates = mapOf(ExpandedName(VIVO_URI, "VMotionPhotoVersion") to "1", ExpandedName(VIVO_URI, "VMotionPhotoSource") to "1", ExpandedName(VIVO_URI, "VMediaKitVersion") to "1.0.0.9")
        val xml = XmpWriter.merge(packet, updates, context).orThrow()
        val reread = XmpReader.parse(xml, context).orThrow()
        for ((name, expected) in updates) if (reread.scalar(name.uri, name.local).orThrow() != expected) fail("POSTCONDITION_FAILED", "vivo scalar failed final readback")
        GoogleJpegWriter.patch(jpeg, xml)
    }

    fun cleanPlan(session: SourceSession, context: Context, budget: ParseBudget): CoreResult<JpegRewritePlan> = attemptNow {
        val jpeg = session.jpeg ?: fail("UNSUPPORTED_CONTAINER", "vivo cleanup requires JPEG")
        if (session.xmp!!.packets.any { it.properties(VIVO_URI, "VMotionPhotoFlags").isNotEmpty() }) fail("CAPABILITY_UNSUPPORTED", "Unknown vivo Flags ownership cannot authorize cleanup")
        val binding = session.bindings.singleOrNull { it.protocol == ProtocolIds.VivoModern }
        if (binding != null && (!binding.structurallyValid || binding.issues.any { it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT") } || binding.protocol !in session.videos)) fail("UNSAFE_METADATA_REWRITE", "vivo cleanup requires a verified resource graph")
        if (session.gainMaps.isEmpty()) return@attemptNow GoogleJpegWriter.cleanPlan(session, context).orThrow()
        if (session.gainMaps.size != 1 || session.gainMaps.single().jpeg.let { it.hasMpf || it.hasExif || it.hasExtendedXmp }) fail("UNSAFE_METADATA_REWRITE", "GainMap dependencies require a verified relocation implementation")
        if (binding == null) {
            if (session.bindings.isNotEmpty()) fail("CAPABILITY_UNSUPPORTED", "Auxiliary cleanup is scoped to vivo")
            return@attemptNow JpegRewrite.plan(jpeg, emptyList()).orThrow()
        }
        if (binding.items.size != 3 || binding.items[1].semantic != "GainMap" || binding.items[1].range != session.gainMaps.single().range) fail("AUXILIARY_RESOURCE_INVALID", "vivo auxiliary resource does not match its verified directory")
        if (!session.xmp.rewriteAllowed) fail("UNSAFE_METADATA_REWRITE", "vivo cleanup needs a unique complete XMP packet")
        reserve(session, budget)
        val packet = session.xmp.packets.single()
        val directory = packet.properties(CONTAINER_URI, "Directory").single().element!!
        val replacement = withoutMotion(directory)
        fun transform(element: XmlElement): XmlElement = if (element === directory) replacement else element.copy(children = element.children.map { if (it is XmlElement) transform(it) else it })
        val root = transform(packet.document.root)
        val xml = XmlWriter.write(XmlDocument(root, packet.document.nodes.map { if (it === packet.document.root) root else it }), context).orThrow()
        val fields = session.bindings.flatMap { it.ownedProperties }.filter { it != ExpandedName(CONTAINER_URI, "Directory") }.toSet()
        val clean = XmpWriter.merge(XmpReader.parse(xml, context).orThrow(), fields.associateWith { null }, context).orThrow()
        GoogleJpegWriter.patch(jpeg, clean)
    }

    /** Retains every ordinary node/qualifier; only the proven inline MotionPhoto item is removed. */
    fun withoutMotion(directory: XmlElement): XmlElement {
        val seq = directory.elements(RDF_URI, "Seq").singleOrNull() ?: fail("UNSAFE_METADATA_REWRITE", "Motion directory has no unique sequence")
        val children = seq.children.filterIsInstance<XmlElement>()
        if (children.size != 3) fail("UNSAFE_METADATA_REWRITE", "Auxiliary cleanup requires exactly Primary/GainMap/MotionPhoto")
        val child = children.last()
        val item = child.elements(CONTAINER_URI, "Item").singleOrNull() ?: child
        val fields = setOf("Semantic", "Mime", "Length", "Padding").map { ExpandedName(ITEM_URI, it) }.toSet()
        fun guard(element: XmlElement, attributes: Set<ExpandedName>, elements: Set<ExpandedName>) {
            if (element.attributes.any { it.name.expanded !in attributes } || element.children.any { node ->
                when (node) { is XmlElement -> node.name.expanded !in elements; is XmlText -> node.text.any { it !in " \t\r\n" }; else -> true }
            }) fail("UNSAFE_METADATA_REWRITE", "Motion item has private metadata that cannot be removed")
        }
        if (item !== child) guard(child, setOf(ExpandedName(RDF_URI, "parseType")), setOf(ExpandedName(CONTAINER_URI, "Item")))
        guard(item, fields + if (item === child) setOf(ExpandedName(RDF_URI, "parseType")) else emptySet(), fields)
        for (leaf in item.children.filterIsInstance<XmlElement>()) if (leaf.attributes.isNotEmpty() || leaf.children.any { it !is XmlText && it !is XmlCData }) fail("UNSAFE_METADATA_REWRITE", "Qualified motion field cannot be deleted")
        val semantic = item.attribute(ITEM_URI, "Semantic") ?: item.elements(ITEM_URI, "Semantic").singleOrNull()?.children?.mapNotNull { (it as? XmlText)?.text ?: (it as? XmlCData)?.text }?.joinToString("")
        if (semantic != "MotionPhoto") fail("UNSAFE_METADATA_REWRITE", "Last directory item is not owned MotionPhoto")
        val replacement = seq.copy(children = seq.children.filterNot { it === child })
        return directory.copy(children = directory.children.map { if (it === seq) replacement else it })
    }

    private fun reserve(session: SourceSession, budget: ParseBudget) {
        var length = 0uL
        for (segment in session.jpeg!!.segments) if (segment.payloadKind == AppPayloadKind.Xmp) length = checkedAdd(length, segment.payload?.length ?: 0uL)
        budget.retain(checkedMultiply(minOf(65_533uL, checkedAdd(checkedMultiply(length, 6uL), 4096uL)), 16uL))
    }
}
