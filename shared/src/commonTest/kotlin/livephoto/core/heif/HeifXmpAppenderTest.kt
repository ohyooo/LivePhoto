package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.memory.*
import kotlin.test.*

class HeifXmpAppenderTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val xml = Bytes("<r:RDF xmlns:r='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><r:Description xmlns:p='urn:owned' p:value='owned-xmp'/></r:RDF>".encodeToByteArray())
    private fun reader(bytes: ByteArray, id: String = "heif-xmp-source", ctx: Context = context) = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId(id)), ctx)
    private suspend fun written(input: BinaryReader, plan: HeifXmpAppender, token: String): Bytes {
        val tx = MemoryOutputTransaction(context, token)
        val handle = tx.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/heic")).orThrow()
        plan.write(input, BinaryWriter(handle.sink, context)).orThrow(); handle.sink.close().orThrow(); tx.prepare().orThrow()
        val source = tx.openStaged(handle.id).orThrow()
        val output = BinaryReader(source, context)
        plan.verify(input, output).orThrow()
        val bytes = output.readExactly(0uL, source.size().orThrow().toUInt()).orThrow()
        source.close(); tx.abort().orThrow() // Internal proof is not a public HEIF writer/publication claim.
        return bytes
    }
    @Test fun appendOneLinkedHiddenXmpPreservesCodedItemAcrossFiftyLayoutsAndIntegerWidths(): Unit = runImmediate {
        var count = 0
        for ((base, offset) in listOf(0 to 4, 0 to 8, 4 to 0, 4 to 4, 8 to 0, 8 to 8))
            for ((metaLast, extended, idat) in listOf(Triple(false, false, false), Triple(true, false, false), Triple(false, true, false), Triple(true, true, false), Triple(false, false, true)))
                for (multiple in listOf(false, true)) {
                    if (multiple && offset == 0) continue
                    val bytes = HeifFixtures.plain(metaLast, extended, multiple, idat, base, offset)
                    val input = reader(bytes)
                    val before = sha256Range(input, ByteRange(0uL, bytes.size.toULong())).orThrow()
                    val plan = HeifXmpAppender.prepare(input, xml).orThrow()
                    val output = written(input, plan, "heif-xmp-layout-${count++}")
                    assertEquals(plan.byteLength, output.size.toULong())
                    assertEquals(before, sha256Range(input, ByteRange(0uL, bytes.size.toULong())).orThrow())
                    val after = reader(output.toByteArray(), "heif-xmp-written")
                    val roots = BmffReader(after).readBoxes(ByteRange(0uL, output.size.toULong())).orThrow()
                    val graph = HeifItemGraphReader.read(after, roots).orThrow()
                    assertEquals(listOf(1u, 2u), graph.infos.map { it.id })
                    assertTrue(graph.infos.last().hidden)
                    assertEquals("mime", graph.infos.last().type)
                    assertEquals(listOf(1u), graph.references.single().to)
                    val facts = HeifMetadataReader.read(after, graph, ParseBudget(context)).orThrow()
                    assertEquals("owned-xmp", facts.items.single().xmp!!.scalar("urn:owned", "value").orThrow())
                    val raw = MemoryOutputTransaction(context, "heif-owned-xmp-raw-$count")
                    DefaultLivePhotoCore().extract(ExtractRequest(SourceSet.Single(after.source), listOf(ResourceId("heif:item:2")), output = raw, context = context)).orThrow()
                    assertEquals(xml, raw.committedAssets().values.single())
                }
        assertEquals(50, count)
    }
    @Test fun tamperedUnrequestedMetadataConfigurationExtentsAndOwnedXmpCannotBorrowTheProof(): Unit = runImmediate {
        val input = reader(HeifFixtures.plain(multiple = true))
        val plan = HeifXmpAppender.prepare(input, xml).orThrow()
        val bytes = written(input, plan, "heif-xmp-tamper").toByteArray()
        val output = reader(bytes, "heif-xmp-inspect")
        val roots = BmffReader(output).readBoxes(ByteRange(0uL, bytes.size.toULong())).orThrow()
        val graph = HeifItemGraphReader.read(output, roots).orThrow()
        val points = listOf(graph.infos.first().name.offset, graph.properties.single { it.type == "hvcC" }.payload.offset + 2uL,
            graph.locations.items.first().extents.last().data.offset, graph.locations.items.last().extents.single().data.offset + 110uL,
            graph.references.single().box.payload.offset + 4uL)
        for ((index, point) in points.withIndex()) {
            val damaged = bytes.copyOf().also { it[point.toInt()] = (it[point.toInt()].toInt() xor 1).toByte() }
            assertIs<CoreResult.Failure>(plan.verify(input, reader(damaged, "heif-xmp-damage-$index")))
        }
    }
    @Test fun unknownDependenciesMalformedXmpAndBudgetFailuresNeverAuthorizeAWrite(): Unit = runImmediate {
        for (bytes in listOf(HeifFixtures.plain(unknownProperty = true), HeifFixtures.plain(hidden = true)))
            assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(HeifXmpAppender.prepare(reader(bytes), xml)).error.code)
        assertEquals(IssueCode("MALFORMED_XMP"), assertIs<CoreResult.Failure>(HeifXmpAppender.prepare(reader(HeifFixtures.plain()), Bytes("<broken>".encodeToByteArray()))).error.code)
        val bytes = HeifFixtures.plain()
        val limited = context.copy(limits = context.limits.copy(maxOutputBytes = bytes.size.toULong() + xml.size.toULong()))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(HeifXmpAppender.prepare(reader(bytes, ctx = limited), xml)).error.code)
        val metadataLimit = context.copy(limits = context.limits.copy(maxMetadataBytes = 100uL))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(HeifXmpAppender.prepare(reader(bytes, ctx = metadataLimit), xml)).error.code)
        val cancelled = context.copy(cancellation = Cancellation { true })
        assertEquals(IssueCode("CANCELLED"), assertIs<CoreResult.Failure>(HeifXmpAppender.prepare(reader(bytes, ctx = cancelled), xml)).error.code)
    }
    @Test fun identityBoundPlansCannotBeReusedOrAppliedAsUnclassifiedRepeatedMetadataInsertion(): Unit = runImmediate {
        val input = reader(HeifFixtures.plain(idat = true))
        val plan = HeifXmpAppender.prepare(input, xml).orThrow()
        val bytes = written(input, plan, "heif-xmp-once")
        val after = reader(bytes.toByteArray(), "heif-xmp-next")
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(plan.verify(after, reader(bytes.toByteArray(), "heif-xmp-wrong-original"))).error.code)
        assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(HeifXmpAppender.prepare(after, xml)).error.code)
        val changedSize = bytes.toByteArray() + byteArrayOf(0)
        assertEquals(IssueCode("POSTCONDITION_FAILED"), assertIs<CoreResult.Failure>(plan.verify(input, reader(changedSize, "heif-xmp-wrong-size"))).error.code)
    }
    @Test fun itemIdsVersionsExtendedTablesAndCoincidentInsertionPointsRemainExplicit(): Unit = runImmediate {
        var count = 0
        for (primary in listOf(1u, 7u, 70_000u)) for (reference in listOf(null, 0, 1)) for (last in listOf(null, "iinf", "iloc", "iref")) {
            val bytes = HeifFixtures.plain(primaryId = primary, iinfVersion = 1, ilocVersion = 2, emptyReferenceVersion = reference,
                lastTable = last, extendedTables = true, idat = true)
            val input = reader(bytes)
            val result = HeifXmpAppender.prepare(input, xml)
            if (primary > 0xffffu && reference == 0) {
                assertEquals(IssueCode("VALUE_NOT_REPRESENTABLE"), assertIs<CoreResult.Failure>(result).error.code)
                continue
            }
            val plan = result.orThrow()
            assertEquals(if (primary == 1u) 2u else 1u, plan.itemId)
            val written = written(input, plan, "heif-xmp-wide-tables-${count++}")
            val output = reader(written.toByteArray(), "heif-xmp-wide-result")
            val roots = BmffReader(output).readBoxes(ByteRange(0uL, written.size.toULong())).orThrow()
            val graph = HeifItemGraphReader.read(output, roots).orThrow()
            assertEquals(primary, graph.primary)
            assertEquals(plan.itemId, graph.references.single().from)
            assertEquals(listOf(primary), graph.references.single().to)
        }
        assertEquals(32, count)
    }
}
