package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class HeifItemGraphTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private fun u(value: UInt, width: Int) = unsignedBytes(value.toULong(), width, Endian.Big).toByteArray()
    private fun full(type: String, payload: ByteArray, version: Int = 0, flags: Int = 0) = GoogleFixtures.box(type, byteArrayOf(version.toByte(), 0, 0, flags.toByte()) + payload)
    private fun fixture(wide: Boolean = false, primary: UInt = 3u, association: UInt = 1u, destination: UInt = 1u,
                        duplicateInfo: Boolean = false, extra: Boolean = false, ipmaVersion: Int? = null, ipmaFlags: Int? = null): ByteArray {
        val idWidth = if (wide) 4 else 2
        val bias = if (wide) 70_000u else 0u
        fun id(value: UInt) = u(value + bias, idWidth)
        val types = listOf("hvc1", "hvc1", "grid", "hvc1", "Exif", "mime")
        val info = types.mapIndexed { index, type ->
            val number = if (duplicateInfo && index == 5) 1u else (index + 1).toUInt()
            val suffix = if (type == "mime") "application/rdf+xml\u0000identity\u0000".encodeToByteArray() else byteArrayOf()
            full("infe", id(number) + u(if (index == 3) 1u else 0u, 2) + type.encodeToByteArray() + "item-${index + 1}\u0000".encodeToByteArray() + suffix, if (wide) 3 else 2, if (index == 0) 1 else 0)
        }.fold(byteArrayOf()) { a, b -> a + b }
        val props = GoogleFixtures.box("ipco", full("ispe", u(2u, 4) + u(2u, 4)) + GoogleFixtures.box("hvcC", GoogleFixtures.video(hevc = true).configuration) + GoogleFixtures.box("zzzz", byteArrayOf(9, 8, 7)))
        var assocs = u(6u, 4)
        for (index in 1..6) assocs += id(index.toUInt()) + byteArrayOf(1) + u((if (index == 1) association else 1u) or (if (wide) 0x8000u else 0x80u), if (wide) 2 else 1)
        val iprp = GoogleFixtures.box("iprp", props + full("ipma", assocs, ipmaVersion ?: if (wide) 1 else 0, ipmaFlags ?: if (wide) 1 else 0) + if (extra) GoogleFixtures.box("priv", byteArrayOf(7)) else byteArrayOf())
        fun reference(type: String, from: UInt, to: List<UInt>) = GoogleFixtures.box(type, id(from) + u(to.size.toUInt(), 2) + to.fold(byteArrayOf()) { bytes, value -> bytes + id(value) })
        val iref = full("iref", reference("dimg", 3u, listOf(destination, 2u)) + reference("auxl", 4u, listOf(3u)) + reference("cdsc", 5u, listOf(3u)) + reference("cdsc", 6u, listOf(3u)), if (wide) 1 else 0)
        val ftyp = GoogleFixtures.box("ftyp", "heic".encodeToByteArray() + u(0u, 4) + "mif1heic".encodeToByteArray())
        fun meta(dataOffset: UInt): ByteArray {
            var locations = byteArrayOf(0x44, 0) + u(6u, if (wide) 4 else 2)
            val offsets = listOf(0u, 0u, 8u, 16u, 20u, 24u)
            for (index in 1..6) locations += id(index.toUInt()) + (if (wide) u(0u, 2) else byteArrayOf()) + u(0u, 2) + u(1u, 2) + u(dataOffset + offsets[index - 1], 4) + u(4u, 4)
            val hdlr = full("hdlr", u(0u, 4) + "pict".encodeToByteArray() + ByteArray(12) + byteArrayOf(0))
            return full("meta", hdlr + full("pitm", id(primary), if (wide) 1 else 0) + full("iinf", u(6u, if (wide) 4 else 2) + info, if (wide) 1 else 0) + full("iloc", locations, if (wide) 2 else 0) + iprp + iref + if (extra) GoogleFixtures.box("priv", byteArrayOf(1, 2, 3)) else byteArrayOf())
        }
        return ftyp + meta((ftyp.size + meta(0u).size + 8).toUInt()) + GoogleFixtures.box("mdat", ByteArray(32) { it.toByte() })
    }
    private suspend fun read(bytes: ByteArray, ctx: Context = context): CoreResult<HeifItemGraph> = attempt {
        val reader = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("heif-graph")), ctx)
        val roots = BmffReader(reader).readBoxes(ByteRange(0uL, bytes.size.toULong())).orThrow()
        HeifItemGraphReader.read(reader, roots).orThrow()
    }
    @Test fun itemInfosAssociationsAndDerivedAuxiliaryMetadataReferencesAreNotFlattened(): Unit = runImmediate {
        for (wide in listOf(false, true)) {
            val graph = read(fixture(wide)).orThrow()
            val bias = if (wide) 70_000u else 0u
            assertEquals(3u + bias, graph.primary)
            assertEquals(listOf("hvc1", "hvc1", "grid", "hvc1", "Exif", "mime"), graph.infos.map { it.type })
            assertTrue(graph.infos.first().hidden)
            assertEquals(1u, graph.infos[3].protection)
            assertNotNull(graph.infos.last().contentType); assertNotNull(graph.infos.last().contentEncoding)
            assertEquals(HeifAssociation(1u, true), graph.associations.getValue(1u + bias).single())
            assertEquals(listOf("dimg", "auxl", "cdsc", "cdsc"), graph.references.map { it.type })
            assertEquals(listOf(1u + bias, 2u + bias), graph.references.first().to)
            assertEquals(graph.locations.items[0].extents.single().data, graph.locations.items[1].extents.single().data)
            assertEquals(listOf("ispe", "hvcC", "zzzz"), graph.properties.map { it.type })
        }
    }
    @Test fun danglingPrimaryPropertyAndReferenceIdsAndDuplicateInfosAreRejected(): Unit = runImmediate {
        for (bytes in listOf(fixture(primary = 9u), fixture(association = 4u), fixture(destination = 9u), fixture(duplicateInfo = true)))
            assertEquals(IssueCode("CORRUPTED_CONTAINER"), assertIs<CoreResult.Failure>(read(bytes)).error.code)
    }
    @Test fun unknownPrivateContainersAndPropertiesRemainOriginalRangesWithoutWriteAuthorization(): Unit = runImmediate {
        val bytes = fixture(extra = true)
        val graph = read(bytes).orThrow()
        assertEquals("priv", graph.unknownMeta.single().type)
        assertEquals("priv", graph.unknownPropertyContainers.single().type)
        assertEquals(Bytes(byteArrayOf(1, 2, 3)), Bytes(bytes.copyOfRange(graph.unknownMeta.single().payload.offset.toInt(), graph.unknownMeta.single().payload.endExclusive.toInt())))
        assertEquals(Bytes(byteArrayOf(9, 8, 7)), Bytes(bytes.copyOfRange(graph.properties.last().payload.offset.toInt(), graph.properties.last().payload.endExclusive.toInt())))
    }
    @Test fun unimplementedAssociationVersionsFlagsAndBudgetAreExplicitFailures(): Unit = runImmediate {
        for (bytes in listOf(fixture(ipmaVersion = 2), fixture(ipmaFlags = 2)))
            assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), assertIs<CoreResult.Failure>(read(bytes)).error.code)
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(read(fixture(), context.copy(limits = context.limits.copy(maxItems = 2uL)))).error.code)
    }
    @Test fun publicInspectionRetainsDerivedLinksWithoutInventingImageSemantics(): Unit = runImmediate {
        val bytes = fixture()
        val source = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("heif-public-graph")))
        val inspected = DefaultLivePhotoCore().inspect(ReadRequest(source, context)).orThrow()
        assertEquals(Disposition.Unknown, inspected.detection.disposition)
        assertEquals(ImageFormat.HeifOther, inspected.media.single().imageFormat)
        assertEquals(Coverage.Partial, inspected.media.single().coverage)
        assertEquals(6, inspected.layout.resources.size)
        assertTrue(inspected.layout.resources.all { !it.standalone && it.kind == ResourceKind.Unknown })
        assertEquals(Value.ArrayValue(listOf(Value.Number("1"), Value.Number("2"))), inspected.metadata.single { it.selector == "heif:reference:dimg:3" }.value)
        assertEquals(3, inspected.layout.relationships.size)
        assertTrue(inspected.layout.relationships.any { it.kind == RelationshipKind.AuxiliaryOf && it.from == ResourceId("heif:item:4") && it.to == ResourceId("heif:item:3") })
        assertTrue(inspected.layout.relationships.any { it.kind == RelationshipKind.Describes && it.from == ResourceId("heif:item:5") && it.to == ResourceId("heif:item:3") })
        assertTrue(inspected.layout.relationships.none { it.from == ResourceId("heif:item:3") })
    }
}
