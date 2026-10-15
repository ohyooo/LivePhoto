package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.BmffReader
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

class HeifMetaExpansionTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private fun reader(bytes: ByteArray, id: String = "heif-expansion", ctx: Context = context) = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId(id)), ctx)
    private suspend fun expanded(input: BinaryReader, plan: HeifMetaExpansion, token: String): Bytes {
        val output = MemoryOutputTransaction(context, token)
        val handle = output.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/heic")).orThrow()
        plan.write(input, BinaryWriter(handle.sink, context)).orThrow()
        handle.sink.close().orThrow(); output.prepare().orThrow()
        val source = output.openStaged(handle.id).orThrow()
        val bytes = BinaryReader(source, context).readExactly(0uL, source.size().orThrow().toUInt()).orThrow()
        plan.verify(input, BinaryReader(source, context)).orThrow()
        source.close(); output.abort().orThrow() // Internal proof test does not fake a public publication.
        return bytes
    }
    @Test fun metadataExpansionPreservesEveryUnrequestedByteAcrossLayoutsWidthsAndExtents(): Unit = runImmediate {
        var number = 0
        for ((baseWidth, offsetWidth) in listOf(0 to 4, 0 to 8, 4 to 0, 4 to 4, 8 to 0, 8 to 8))
            for ((metaLast, extended, idat) in listOf(Triple(false, false, false), Triple(true, false, false), Triple(false, true, false), Triple(true, true, false), Triple(false, false, true)))
                for (multiple in listOf(false, true)) {
                    if (multiple && offsetWidth == 0) continue
                    val bytes = HeifFixtures.plain(metaLast, extended, multiple, idat, baseWidth, offsetWidth)
                    val input = reader(bytes)
                    val digest = sha256Range(input, ByteRange(0uL, bytes.size.toULong())).orThrow()
                    val plan = HeifMetaExpansion.prepare(input, 8u).orThrow()
                    val result = expanded(input, plan, "heif-expansion-${number++}")
                    assertEquals(bytes.size + 16, result.size)
                    assertEquals(digest, sha256Range(input, ByteRange(0uL, bytes.size.toULong())).orThrow())
                    val after = reader(result.toByteArray(), "heif-expanded")
                    val roots = BmffReader(after).readBoxes(ByteRange(0uL, result.size.toULong())).orThrow()
                    val graph = HeifItemGraphReader.read(after, roots).orThrow()
                    assertEquals(1u, graph.primary)
                    assertEquals(16uL, graph.unknownMeta.single { it.type == "free" }.range.length)
                    assertEquals(if (multiple) 2 else 1, graph.locations.items.single().extents.size)
                    assertEquals(if (idat) 1u else 0u, graph.locations.items.single().construction)
                }
        assertEquals(50, number)
    }
    @Test fun unrelatedAndOwnedPaddingTamperingCannotBorrowTheRelocationProof(): Unit = runImmediate {
        val bytes = HeifFixtures.plain(multiple = true)
        val input = reader(bytes)
        val plan = HeifMetaExpansion.prepare(input, 8u).orThrow()
        val result = expanded(input, plan, "heif-expansion-tamper").toByteArray()
        val outputReader = reader(result, "heif-tamper-facts")
        val roots = BmffReader(outputReader).readBoxes(ByteRange(0uL, result.size.toULong())).orThrow()
        val graph = HeifItemGraphReader.read(outputReader, roots).orThrow()
        val locations = listOf(graph.unknownMeta.single { it.type == "free" }.payload.offset,
            graph.infos.single().name.offset,
            graph.locations.items.single().extents.last().data.offset,
            graph.properties.single { it.type == "hvcC" }.payload.offset + 2uL)
        for ((index, position) in locations.withIndex()) {
            val damaged = result.copyOf().also { it[position.toInt()] = (it[position.toInt()].toInt() xor 1).toByte() }
            assertIs<CoreResult.Failure>(plan.verify(input, reader(damaged, "heif-tamper-$index")))
        }
    }
    @Test fun unknownDependenciesBudgetAndHeaderGrowthAreRejectedDuringPreparation(): Unit = runImmediate {
        for (bytes in listOf(HeifFixtures.plain(unknownProperty = true), HeifFixtures.plain(hidden = true)))
            assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(HeifMetaExpansion.prepare(reader(bytes), 8u)).error.code)
        val plain = HeifFixtures.plain()
        assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), assertIs<CoreResult.Failure>(HeifMetaExpansion.prepare(reader(plain + GoogleFixtures.box("priv", byteArrayOf())), 8u)).error.code)
        assertEquals(IssueCode("VALUE_NOT_REPRESENTABLE"), assertIs<CoreResult.Failure>(HeifMetaExpansion.prepare(reader(plain), UInt.MAX_VALUE)).error.code)
        val limited = context.copy(limits = context.limits.copy(maxOutputBytes = plain.size.toULong() + 15uL))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(HeifMetaExpansion.prepare(reader(plain, ctx = limited), 8u)).error.code)
    }
    @Test fun plansAreIdentityBoundAndRepeatedExpansionKeepsExistingPaddingRaw(): Unit = runImmediate {
        val input = reader(HeifFixtures.plain(idat = true))
        val plan = HeifMetaExpansion.prepare(input, 0u).orThrow()
        val first = expanded(input, plan, "heif-zero-padding")
        val next = reader(first.toByteArray(), "heif-expanded-again")
        val secondPlan = HeifMetaExpansion.prepare(next, 8u).orThrow()
        val second = expanded(next, secondPlan, "heif-repeated-padding")
        assertEquals(first.size + 16, second.size)
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(plan.verify(next, reader(second.toByteArray(), "wrong-source-verification"))).error.code)
    }
    @Test fun readableMetadataNalUnitsAndArraysDoNotAuthorizeRelocatingPrivatePayloads(): Unit = runImmediate {
        val sei = GoogleFixtures.u32(2u) + byteArrayOf(0x4e, 1)
        val reserved = GoogleFixtures.u32(2u) + byteArrayOf(0x2c, 1)
        for (extra in listOf(sei, reserved)) {
            val input = reader(HeifFixtures.plain(extraSampleNal = extra))
            val roots = BmffReader(input).readBoxes(ByteRange(0uL, input.identity().orThrow().size)).orThrow()
            val graph = HeifItemGraphReader.read(input, roots).orThrow()
            assertEquals(2uL, HeifCodedItemProbe.primary(input, graph, ParseBudget(context)).orThrow().nalUnits)
            assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(HeifMetaExpansion.prepare(input, 8u)).error.code)
        }
        val configSei = byteArrayOf(0xa7.toByte(), 0, 1, 0, 2, 0x4e, 1)
        val input = reader(HeifFixtures.plain(extraConfigArray = configSei))
        val roots = BmffReader(input).readBoxes(ByteRange(0uL, input.identity().orThrow().size)).orThrow()
        val graph = HeifItemGraphReader.read(input, roots).orThrow()
        HeifCodedItemProbe.primary(input, graph, ParseBudget(context)).orThrow() // Read remains structurally allowed.
        assertEquals(IssueCode("UNSUPPORTED_CONTAINER"), assertIs<CoreResult.Failure>(HeifMetaExpansion.prepare(input, 8u)).error.code)
    }
}
