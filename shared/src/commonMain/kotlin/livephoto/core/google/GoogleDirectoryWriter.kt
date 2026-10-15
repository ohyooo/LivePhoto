package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

/** Namespace-aware canonical directory; carrier layout and publication are separate responsibilities. */
internal object GoogleDirectoryWriter {
    fun append(packet: XmpPacket, length: ULong, mime: String, context: Context, primaryPadding: ULong,
        motionPadding: ULong?, primaryMime: String = "image/jpeg"): Bytes {
        if (packet.properties(CONTAINER_URI, "Directory").isNotEmpty() || packet.descriptions.isEmpty())
            fail("UNSAFE_METADATA_REWRITE", "Canonical directory needs a description without an existing authority")
        fun name(prefix: String, uri: String, local: String): XmlName = XmlName("$prefix:$local", ExpandedName(uri, local))
        fun item(semantic: String, type: String, bytes: ULong?): XmlElement {
            val attributes = mutableListOf(XmlAttribute(name("i", ITEM_URI, "Semantic"), semantic), XmlAttribute(name("i", ITEM_URI, "Mime"), type))
            if (bytes != null) attributes += XmlAttribute(name("i", ITEM_URI, "Length"), bytes.toString())
            if (semantic == "Primary" && primaryPadding != 0uL) attributes += XmlAttribute(name("i", ITEM_URI, "Padding"), primaryPadding.toString())
            if (semantic == "MotionPhoto" && motionPadding != null) attributes += XmlAttribute(name("i", ITEM_URI, "Padding"), motionPadding.toString())
            val content = XmlElement(name("c", CONTAINER_URI, "Item"), attributes, mapOf("c" to CONTAINER_URI, "i" to ITEM_URI), emptyList())
            return XmlElement(name("r", RDF_URI, "li"), listOf(XmlAttribute(name("r", RDF_URI, "parseType"), "Resource")), emptyMap(), listOf(content))
        }
        val sequence = XmlElement(name("r", RDF_URI, "Seq"), emptyList(), mapOf("r" to RDF_URI), listOf(item("Primary", primaryMime, null), item("MotionPhoto", mime, length)))
        val directory = XmlElement(name("c", CONTAINER_URI, "Directory"), emptyList(), mapOf("c" to CONTAINER_URI), listOf(sequence))
        val first = packet.descriptions.first()
        fun transform(element: XmlElement): XmlElement = if (element === first) element.copy(children = element.children + directory)
            else element.copy(children = element.children.map { if (it is XmlElement) transform(it) else it })
        val root = transform(packet.document.root)
        return XmlWriter.write(XmlDocument(root, packet.document.nodes.map { if (it === packet.document.root) root else it }), context).orThrow()
    }

    fun heic(length: ULong, timestamp: Long, context: Context): Bytes {
        val fields = mapOf(ExpandedName(CAMERA_URI, "MotionPhoto") to "1", ExpandedName(CAMERA_URI, "MotionPhotoVersion") to "1",
            ExpandedName(CAMERA_URI, "MotionPhotoPresentationTimestampUs") to timestamp.toString())
        val packet = XmpReader.parse(XmpWriter.create(fields, context).orThrow(), context).orThrow()
        return append(packet, length, "video/mp4", context, 8uL, null, "image/heic")
    }
}
