package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class SamsungKeyMetadataTest {
    private val context = Context(Limits(4_000_000uL, 4_000_000uL))
    private val core = DefaultLivePhotoCore()
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("samsung-key")))
    private fun <T> value(result: CoreResult<T>): T = when (result) { is CoreResult.Success -> result.value; is CoreResult.Failure -> fail(result.error.toString()) }

    @Test fun keyChangeKeepsEntireSefSuffixAndRevalidatesBackwardOffsets(): Unit = runImmediate {
        for (padding in listOf(null, "0")) {
            val fixture = SamsungFixtures.photo(xmp = true, secondaryPadding = padding)
            val source = input(fixture.bytes)
            val before = value(SourceSession.open(source, context, ParseBudget(context)))
            val output = MemoryOutputTransaction(context, "samsung-key-$padding")
            val request = SetKeyRequest(source, CoverPosition.FrameIndex(1uL), output = output, context = context)
            value(core.plan(request)); assertTrue(value(output.query()).assetIds.isEmpty())
            val result = value(core.setKeyPhotoPosition(request))
            val bytes = output.committedAssets().values.single().toByteArray()
            val after = value(SourceSession.open(input(bytes), context, ParseBudget(context)))
            assertEquals(Time(40_000, 1_000_000u), result.keyPhoto?.position)
            assertEquals(ProtocolIds.Samsung, after.inspection.detection.primaryProtocol?.protocol)
            assertEquals(codingDigest(before), codingDigest(after))
            assertEquals(value(sha256Range(before.reader, before.jpeg!!.trailing)), value(sha256Range(after.reader, after.jpeg!!.trailing)))
            assertEquals(fixture.records.map { Bytes(it.bytes) }, SamsungFixtures.directory(bytes).map { Bytes(it.raw) })
            assertEquals(1, result.preservation.changes.size)
        }
    }

    @Test fun legacyFooterOrdinaryRecordsAndMissingAuthorityRemainReadOnly(): Unit = runImmediate {
        for ((index, fixture) in listOf(SamsungFixtures.photo(xmp = true, legacy = true), SamsungFixtures.photo(xmp = true, ordinaryRecord = true), SamsungFixtures.photo()).withIndex()) {
            val output = MemoryOutputTransaction(context, "samsung-key-rejected-$index")
            val request = SetKeyRequest(input(fixture.bytes), CoverPosition.FrameIndex(0uL), output = output, context = context)
            assertIs<CoreResult.Failure>(core.plan(request))
            assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(request))
            assertTrue(value(output.query()).assetIds.isEmpty())
        }
    }
}
