package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

internal data class HeifItemInfo(val id: UInt, val type: String, val hidden: Boolean, val protection: UInt,
    val name: ByteRange, val contentType: ByteRange?, val contentEncoding: ByteRange?, val uriType: ByteRange?, val box: BmffBox)
internal data class HeifAssociation(val property: UInt, val essential: Boolean)
internal data class HeifReference(val type: String, val from: UInt, val to: List<UInt>, val box: BmffBox)
internal data class HeifItemGraph(val primary: UInt, val infos: List<HeifItemInfo>, val locations: HeifItemLocations,
    val properties: List<BmffBox>, val associations: Map<UInt, List<HeifAssociation>>, val references: List<HeifReference>,
    val unknownMeta: List<BmffBox>, val unknownPropertyContainers: List<BmffBox>)

/** Preserves explicit item/property/reference graphs. It does not authorize a decoder or mutation. */
internal object HeifItemGraphReader {
    suspend fun read(reader: BinaryReader, roots: List<BmffBox>, budget: ParseBudget = ParseBudget(reader.context)): CoreResult<HeifItemGraph> = attempt {
        val parser = BmffReader(reader, budget)
        val meta = roots.singleOrNull { it.type == "meta" } ?: fail("CORRUPTED_CONTAINER", "HEIF has no unique image meta")
        val metaCursor = GraphCursor(reader, meta.payload, budget)
        metaCursor.full(setOf(0), 0u)
        val children = parser.readBoxes(metaCursor.remaining(), 1u).orThrow()
        fun required(type: String): BmffBox = children.singleOrNull { it.type == type }
            ?: fail("CORRUPTED_CONTAINER", "HEIF requires one $type box")
        val handler = GraphCursor(reader, required("hdlr").payload, budget)
        handler.full(setOf(0), 0u)
        if (handler.uint(4) != 0uL || handler.cc() != "pict") fail("UNSUPPORTED_CONTAINER", "HEIF meta is not an implemented picture handler")
        repeat(3) { if (handler.uint(4) != 0uL) fail("CAPABILITY_UNSUPPORTED", "HEIF handler reserved fields are unsupported") }
        handler.string(); handler.end()
        val primaryCursor = GraphCursor(reader, required("pitm").payload, budget)
        val primaryVersion = primaryCursor.full(setOf(0, 1), 0u).first
        val primary = primaryCursor.uint(if (primaryVersion == 0) 2 else 4).toUInt()
        primaryCursor.end()
        val infoCursor = GraphCursor(reader, required("iinf").payload, budget)
        val infoVersion = infoCursor.full(setOf(0, 1), 0u).first
        val infoCount = infoCursor.uint(if (infoVersion == 0) 2 else 4)
        val infoBoxes = parser.readBoxes(infoCursor.remaining(), 2u).orThrow()
        if (infoCount != infoBoxes.size.toULong() || infoBoxes.any { it.type != "infe" }) fail("CORRUPTED_CONTAINER", "HEIF iinf entries do not match their count/type")
        val infos = mutableListOf<HeifItemInfo>()
        val ids = mutableSetOf<UInt>()
        for (box in infoBoxes) {
            budget.item(2u); budget.retain(192uL)
            val cursor = GraphCursor(reader, box.payload, budget)
            val (version, flags) = cursor.full(setOf(2, 3), 1u)
            val id = cursor.uint(if (version == 2) 2 else 4).toUInt()
            if (id == 0u || !ids.add(id)) fail("CORRUPTED_CONTAINER", "HEIF item information IDs are zero or duplicate")
            val protection = cursor.uint(2).toUInt()
            val type = cursor.cc()
            val name = cursor.string()
            var contentType: ByteRange? = null
            var encoding: ByteRange? = null
            var uri: ByteRange? = null
            if (type == "mime") { contentType = cursor.string(); if (cursor.remaining().length != 0uL) encoding = cursor.string() }
            if (type == "uri ") uri = cursor.string()
            cursor.end()
            infos.add(HeifItemInfo(id, type, flags and 1u != 0u, protection, name, contentType, encoding, uri, box))
        }
        if (primary == 0u || primary !in ids) fail("CORRUPTED_CONTAINER", "HEIF primary item is absent from item information")
        val idats = children.filter { it.type == "idat" }
        if (idats.size > 1) fail("AMBIGUOUS_LAYOUT", "HEIF has more than one idat")
        val locations = HeifItemLocations.read(reader, required("iloc"), roots.filter { it.type == "mdat" }.map { it.payload }, idats.singleOrNull()?.payload, budget).orThrow()
        if (locations.items.any { it.id !in ids } || locations.items.none { it.id == primary }) fail("CORRUPTED_CONTAINER", "HEIF item locations do not resolve the primary/item information")
        val propertyContainers = parser.readBoxes(required("iprp").payload, 2u).orThrow()
        val ipco = propertyContainers.singleOrNull { it.type == "ipco" } ?: fail("CORRUPTED_CONTAINER", "HEIF has no unique property container")
        val properties = parser.readBoxes(ipco.payload, 3u).orThrow()
        val ipmas = propertyContainers.filter { it.type == "ipma" }
        if (ipmas.size != 1) fail("CAPABILITY_UNSUPPORTED", "HEIF property association envelope is outside the implemented profile")
        val associationCursor = GraphCursor(reader, ipmas.single().payload, budget)
        val (associationVersion, associationFlags) = associationCursor.full(setOf(0, 1), 1u)
        var entryCount = associationCursor.uint(4)
        val associations = linkedMapOf<UInt, List<HeifAssociation>>()
        while (entryCount != 0uL) {
            budget.item(3u); budget.retain(64uL)
            val id = associationCursor.uint(if (associationVersion == 0) 2 else 4).toUInt()
            if (id !in ids || id in associations) fail("CORRUPTED_CONTAINER", "HEIF property association refers to an absent/duplicate item")
            var count = associationCursor.uint(1)
            val values = mutableListOf<HeifAssociation>()
            while (count != 0uL) {
                budget.item(3u); budget.retain(32uL)
                val wide = associationFlags and 1u != 0u
                val encoded = associationCursor.uint(if (wide) 2 else 1).toUInt()
                val essentialBit = if (wide) 0x8000u else 0x80u
                val index = encoded and (essentialBit - 1u)
                if (index > properties.size.toUInt() || index == 0u && encoded and essentialBit != 0u)
                    fail("CORRUPTED_CONTAINER", "HEIF property index is out of bounds")
                values.add(HeifAssociation(index, encoded and essentialBit != 0u)); count--
            }
            associations[id] = frozenList(values); entryCount--
        }
        associationCursor.end()
        val references = mutableListOf<HeifReference>()
        val irefs = children.filter { it.type == "iref" }
        if (irefs.size > 1) fail("AMBIGUOUS_LAYOUT", "HEIF has multiple reference envelopes")
        irefs.singleOrNull()?.let { box ->
            val cursor = GraphCursor(reader, box.payload, budget)
            val version = cursor.full(setOf(0, 1), 0u).first
            val referenceBoxes = parser.readBoxes(cursor.remaining(), 2u).orThrow()
            val authorities = mutableSetOf<Pair<String, UInt>>()
            for (reference in referenceBoxes) {
                budget.item(2u); budget.retain(96uL)
                val entry = GraphCursor(reader, reference.payload, budget)
                val from = entry.uint(if (version == 0) 2 else 4).toUInt()
                if (from !in ids || !authorities.add(reference.type to from)) fail("CORRUPTED_CONTAINER", "HEIF reference has an absent or duplicate source authority")
                var count = entry.uint(2)
                if (count == 0uL) fail("CORRUPTED_CONTAINER", "HEIF reference has no destination items")
                val to = mutableListOf<UInt>()
                while (count != 0uL) {
                    budget.item(2u); budget.retain(16uL)
                    val id = entry.uint(if (version == 0) 2 else 4).toUInt()
                    if (id !in ids) fail("CORRUPTED_CONTAINER", "HEIF reference has an absent destination")
                    to.add(id); count--
                }
                entry.end()
                references.add(HeifReference(reference.type, from, frozenList(to), reference))
            }
        }
        reader.validateIdentity().orThrow()
        HeifItemGraph(primary, frozenList(infos), locations, frozenList(properties), associations.toMap(), frozenList(references),
            frozenList(children.filter { it.type !in setOf("hdlr", "pitm", "iinf", "iloc", "iprp", "iref", "idat") }),
            frozenList(propertyContainers.filter { it.type !in setOf("ipco", "ipma") }))
    }
}

private class GraphCursor(private val reader: BinaryReader, private val payload: ByteRange, private val budget: ParseBudget) {
    private var position = payload.offset
    suspend fun uint(width: Int): ULong {
        checkedRange(position, width.toULong(), payload.endExclusive)
        val value = readUnsigned(reader.readBuffer(position, width.toUInt()).orThrow(), Endian.Big)
        position = checkedAdd(position, width.toULong())
        return value
    }
    suspend fun cc(): String {
        checkedRange(position, 4uL, payload.endExclusive)
        val value = fourCc(reader.readBuffer(position, 4u).orThrow(), 0)
        position += 4uL
        return value
    }
    suspend fun full(versions: Set<Int>, allowedFlags: UInt): Pair<Int, UInt> {
        val raw = uint(4)
        val version = (raw shr 24).toInt()
        val flags = (raw and 0xffffffuL).toUInt()
        if (version !in versions || flags and allowedFlags.inv() != 0u) fail("CAPABILITY_UNSUPPORTED", "HEIF FullBox version/flags are not implemented")
        return version to flags
    }
    suspend fun string(): ByteRange {
        val start = position
        while (position < payload.endExclusive) {
            budget.poll()
            val length = minOf(4096uL, payload.endExclusive - position).toUInt()
            val bytes = reader.readBuffer(position, length).orThrow()
            val zero = (0 until bytes.size).firstOrNull { bytes[it] == 0.toByte() }
            val consumed = zero?.plus(1)?.toULong() ?: length.toULong()
            budget.retain(consumed)
            position += consumed
            if (zero != null) return ByteRange(start, position - start - 1uL)
        }
        fail("CORRUPTED_CONTAINER", "HEIF item string is not terminated")
    }
    fun remaining(): ByteRange = ByteRange(position, payload.endExclusive - position)
    fun end() { if (position != payload.endExclusive) fail("CORRUPTED_CONTAINER", "HEIF box has unexpected trailing fields") }
}
