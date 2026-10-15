package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.memory.*
import kotlin.test.*

class SamsungRepairTest {
    private val context = Context(Limits(4_000_000uL, 4_000_000uL))
    private val core = DefaultLivePhotoCore()
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("samsung-repair")))
    private fun <T> value(result: CoreResult<T>): T = when (result) { is CoreResult.Success -> result.value; is CoreResult.Failure -> fail(result.error.toString()) }

    @Test fun repairsOnlyTheFourByteFooterLengthAndIsIdempotent(): Unit = runImmediate {
        for (xmp in listOf(false, true)) for (ordinary in listOf(false, true)) {
            val fixture = SamsungFixtures.photo(xmp = xmp, ordinaryRecord = ordinary, legacy = true)
            val source = input(fixture.bytes)
            val preview = value(core.repair(RepairRequest(source, context = context)))
            assertEquals(1, preview.proposedChanges.size)
            assertTrue(preview.changesApplied.isEmpty())
            val output = MemoryOutputTransaction(context, "samsung-footer-$xmp-$ordinary")
            val request = RepairRequest(source, dryRun = false, output = output, context = context)
            assertEquals(1, value(core.plan(request)).predictedPreservation.changes.size)
            assertTrue(value(output.query()).assetIds.isEmpty())
            val result = value(core.repair(request))
            assertEquals(1, result.changesApplied.size)
            val actual = output.committedAssets().values.single().toByteArray()
            val expected = fixture.bytes.copyOf()
            SamsungFixtures.le32((12 + 12 * fixture.records.size).toUInt()).copyInto(expected, expected.size - 8)
            assertContentEquals(expected, actual)
            assertEquals(fixture.records.map { Bytes(it.bytes) }, SamsungFixtures.directory(actual).map { Bytes(it.raw) })
            val againOutput = MemoryOutputTransaction(context, "samsung-footer-noop-$xmp-$ordinary")
            val again = value(core.repair(RepairRequest(input(actual), dryRun = false, output = againOutput, context = context)))
            assertTrue(again.proposedChanges.isEmpty())
            assertTrue(value(againOutput.query()).assetIds.isEmpty())
        }
    }

    @Test fun allowlistAndNonSafeModesDoNotAuthorizeThisPatch(): Unit = runImmediate {
        val source = input(SamsungFixtures.photo(legacy = true).bytes)
        val output = MemoryOutputTransaction(context, "samsung-filtered")
        val result = value(core.repair(RepairRequest(source, allowedIssueCodes = listOf(IssueCode("MOTION_VIDEO_LENGTH_MISMATCH")), dryRun = false, output = output, context = context)))
        assertTrue(result.blocked.isNotEmpty()); assertTrue(result.proposedChanges.isEmpty())
        assertTrue(value(output.query()).assetIds.isEmpty())
        assertIs<CoreResult.Failure>(core.repair(RepairRequest(source, mode = RepairMode.ExplicitRemux, context = context)))
    }

    @Test fun malformedIndexedGraphIsNotRepairedByScanningForMagic(): Unit = runImmediate {
        val fixture = SamsungFixtures.photo(legacy = true)
        val broken = fixture.bytes.copyOf()
        SamsungFixtures.le32(UInt.MAX_VALUE).copyInto(broken, fixture.tableStart + 16)
        val output = MemoryOutputTransaction(context, "samsung-broken-index")
        assertIs<CoreResult.Failure>(core.repair(RepairRequest(input(broken), dryRun = false, output = output, context = context)))
        assertTrue(value(output.query()).assetIds.isEmpty())
    }
}
