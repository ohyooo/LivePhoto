package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.memory.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

class WindowsRemuxTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    private fun backend(): MediaBackend {
        val candidates = WindowsMediaFoundationBackend.available()
        if (System.getenv("LIVEPHOTO_REQUIRE_WINDOWS_MEDIA") == "true") assertEquals(1, candidates.size)
        assumeTrue("Windows API unavailable; no system remux was run", candidates.isNotEmpty())
        return candidates.single()
    }
    private fun bytes(): ByteArray = javaClass.getResourceAsStream("/windows-media/frame-baseline.mp4.base64")!!.use {
        java.util.Base64.getDecoder().decode(it.readNBytes(100_000).toString(Charsets.US_ASCII).trim())
    }
    @Test fun actualCoreRemuxPublishesOnlyFullyVerifiedSameMp4(): Unit = runImmediate {
        checkCoreRemux("baseline")
    }
    @Test fun actualMainProfileNoBRemuxPreservesAllPacketsAndMetadata(): Unit = runImmediate {
        checkCoreRemux("main")
    }
    @Test fun actualHighEightBitNoBRemuxPreservesAllPacketsAndMetadata(): Unit = runImmediate {
        checkCoreRemux("high")
    }
    private suspend fun checkCoreRemux(profile: String) {
        val backend = backend(); val bytes = if (profile == "baseline") bytes() else WindowsEncodedFixtures.bytes("remux-$profile")
        val source = MemoryBinarySource(Bytes(bytes), SourceId("system-remux-baseline"))
        val tx = MemoryOutputTransaction(context, "system-remux-positive")
        val policy = MutationPolicy(preservation = PreservationPolicy.Strict, requiredGuarantees = listOf(Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving))
        val result = DefaultLivePhotoCore(backend).remux(RemuxRequest(ResourceRef(SourceSet.Single(source)), VideoContainer.Mp4, policy, tx, context)).orThrow()
        try {
            assertEquals(TransactionState.Committed, tx.query().orThrow().state)
            assertEquals(VideoContainer.Mp4, result.output.assets.single().videoContainer)
            assertContentEquals(bytes, tx.committedAssets().values.single().toByteArray())
            assertTrue(result.execution.any { it.backendId == "windows-media-foundation" && it.remuxed && !it.transcoded })
            assertTrue(result.execution.none { it.transcoded })
            for (guarantee in listOf(Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving))
                assertEquals(GuaranteeOutcome.Verified, result.preservation.records.single { it.guarantee == guarantee }.outcome)
            val input = BinaryReader(source, context); val output = BinaryReader(result.output.assets.single().readableSource!!, context)
            val before = BmffVideoProbe(input).probe(ByteRange(0uL, bytes.size.toULong())).orThrow()
            assertEquals(when (profile) { "baseline" -> 66; "main" -> 77; else -> 100 }, before.tracks.single().codecConfiguration[1].toInt() and 255)
            assertTrue(before.tracks.single().samples.all { it.decodeTime == it.presentationTime.toULong() })
            val after = BmffVideoProbe(output).probe(ByteRange(0uL, output.identity().orThrow().size)).orThrow()
            RemuxVerification.verify(input, before, output, after)
            RemuxVerification.verifyMetadata(RemuxVerification.metadata(input, before), RemuxVerification.metadata(output, after))
            assertContentEquals(bytes, input.readBuffer(0uL, bytes.size.toUInt()).orThrow().toByteArray())
        } finally { result.output.assets.forEach { it.readableSource?.close() }; source.close() }
    }
    @Test fun unsupportedTargetsTracksAndBudgetsNeverPublish(): Unit = runImmediate {
        val backend = backend()
        val baseline = bytes()
        val main = javaClass.getResourceAsStream("/windows-media/frame-main.mp4.base64")!!.use {
            java.util.Base64.getDecoder().decode(it.readNBytes(100_000).toString(Charsets.US_ASCII).trim())
        }
        val high = javaClass.getResourceAsStream("/windows-media/frame-high.mp4.base64")!!.use {
            java.util.Base64.getDecoder().decode(it.readNBytes(100_000).toString(Charsets.US_ASCII).trim())
        }
        for ((data, target, jobContext) in listOf(
            Triple(baseline, VideoContainer.Mov, context), Triple(main, VideoContainer.Mp4, context), Triple(high, VideoContainer.Mp4, context),
            Triple(WindowsEncodedFixtures.bytes("audio"), VideoContainer.Mp4, context),
            Triple(baseline, VideoContainer.Mp4, context.copy(limits = context.limits.copy(maxSpoolBytes = 1uL))),
            Triple(baseline, VideoContainer.Mp4, context.copy(cancellation = Cancellation { true })))) {
            val source = MemoryBinarySource(Bytes(data), SourceId("system-remux-negative"))
            val tx = MemoryOutputTransaction(jobContext, "system-remux-refused")
            val result = DefaultLivePhotoCore(backend).remux(RemuxRequest(ResourceRef(SourceSet.Single(source)), target, output = tx, context = jobContext))
            assertIs<CoreResult.Failure>(result)
            assertTrue(tx.committedAssets().isEmpty())
            source.close()
        }
    }
}
