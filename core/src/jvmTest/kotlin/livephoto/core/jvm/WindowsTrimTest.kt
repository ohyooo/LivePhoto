package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.*
import livephoto.core.memory.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

class WindowsTrimTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    private fun backend(): MediaBackend {
        val candidates = WindowsMediaFoundationBackend.available()
        if (System.getenv("LIVEPHOTO_REQUIRE_WINDOWS_MEDIA") == "true") assertEquals(1, candidates.size)
        assumeTrue("Windows API unavailable; no system trim was run", candidates.isNotEmpty())
        return candidates.single()
    }
    private suspend fun check(spec: TrimSpec): TrimResult {
        val backend = backend(); val bytes = WindowsEncodedFixtures.bytes("trim-high")
        val source = MemoryBinarySource(Bytes(bytes), SourceId("system-trim"))
        val input = BinaryReader(source, context)
        val original = BmffVideoProbe(input).probe(ByteRange(0uL, bytes.size.toULong())).orThrow()
        val plan = planLosslessTrim(input, original, spec)
        val tx = MemoryOutputTransaction(context, "system-trim-positive")
        val result = DefaultLivePhotoCore(backend).trim(TrimRequest(ResourceRef(SourceSet.Single(source)), spec,
            policy = MutationPolicy(preservation = PreservationPolicy.Strict, requiredGuarantees = listOf(Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving)), output = tx, context = context)).orThrow()
        try {
            assertEquals(TransactionState.Committed, tx.query().orThrow().state)
            assertEquals(0, plan.start.compareTo(result.actualStart)); assertEquals(0, plan.end.compareTo(result.actualEnd))
            assertEquals(spec.range.start, result.requestedStart); assertEquals(spec.range.end, result.requestedEnd)
            assertEquals(plan.mapping, result.timelineMap); assertEquals(listOf(plan.trackTrim), result.tracks)
            assertFalse(result.wasTranscoded); assertFalse(result.retainedHiddenContent); assertTrue(result.wasBitstreamPreserved)
            assertTrue(result.operation.execution.any { it.stage == Stage.Trim && it.backendId == "windows-media-foundation" && it.remuxed && !it.transcoded })
            for (guarantee in listOf(Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving))
                assertEquals(GuaranteeOutcome.Verified, result.operation.preservation.records.single { it.guarantee == guarantee }.outcome)
            val outputSource = result.operation.output.assets.single().readableSource!!
            val output = BinaryReader(outputSource, context)
            val actual = BmffVideoProbe(output).probe(ByteRange(0uL, output.identity().orThrow().size)).orThrow()
            assertTrue(actual.tracks.single().samples.size < original.tracks.single().samples.size)
            verifyTrimDurationHeaders(output, actual, plan)
            RemuxVerification.verify(input, plan.expected(), output, actual)
            RemuxVerification.verifyMetadata(RemuxVerification.metadata(input, original, true), RemuxVerification.metadata(output, actual, true))
            backend.probe(ProbeRequest(ResourceRef(SourceSet.Single(outputSource)), true, context)).orThrow()
            assertContentEquals(bytes, input.readBuffer(0uL, bytes.size.toUInt()).orThrow().toByteArray())
            return result
        } finally { result.operation.output.assets.forEach { it.readableSource?.close() }; source.close() }
    }
    @Test fun actualNonzeroClosedIdrTrimPublishesThreeExactVfrSamples(): Unit = runImmediate {
        val result = check(TrimSpec(TimeRange(Time(120, 1000u), Time(320, 1000u)), TrimMode.LosslessOnly))
        assertEquals(0, Time(120, 1000u).compareTo(result.actualStart))
    }
    @Test fun preferredCoversRequestWithoutEncodingAndDisclosesActualRange(): Unit = runImmediate {
        val result = check(TrimSpec(TimeRange(Time(140, 1000u), Time(300, 1000u))))
        assertEquals(0, Time(120, 1000u).compareTo(result.actualStart)); assertEquals(0, Time(320, 1000u).compareTo(result.actualEnd))
    }
    @Test fun exactOnIndependentBoundariesDoesNotNeedEncoder(): Unit = runImmediate {
        check(TrimSpec(TimeRange(Time(120, 1000u), Time(320, 1000u)), TrimMode.Exact))
    }
    @Test fun unsafeRequestsUnsupportedTracksAndBudgetsNeverPublish(): Unit = runImmediate {
        val backend = backend()
        val whole = TrimSpec(TimeRange(Time.Zero, Time(80, 1000u)))
        val exact = TrimSpec(TimeRange(Time(40, 1000u), Time(160, 1000u)), TrimMode.Exact)
        for ((bytes, spec, ctx) in listOf(
            Triple(WindowsEncodedFixtures.bytes("trim-high"), exact, context),
            Triple(WindowsEncodedFixtures.bytes("trim-high"), exact.copy(mode = TrimMode.LosslessOnly), context),
            Triple(WindowsEncodedFixtures.bytes("trim-high"), whole, context.copy(limits = context.limits.copy(maxSpoolBytes = 1uL))),
            Triple(WindowsEncodedFixtures.bytes("trim-high"), whole, context.copy(cancellation = Cancellation { true })),
            Triple(WindowsEncodedFixtures.bytes("audio"), whole, context),
            Triple(WindowsEncodedFixtures.bytes("b2"), whole, context),
            Triple(WindowsEncodedFixtures.movBytes("trim-high"), whole, context))) {
            val source = MemoryBinarySource(Bytes(bytes), SourceId("system-trim-refused")); val tx = MemoryOutputTransaction(ctx, "trim-refused")
            val result = DefaultLivePhotoCore(backend).trim(TrimRequest(ResourceRef(SourceSet.Single(source)), spec,
                policy = MutationPolicy(transcode = TranscodePolicy.Explicit), output = tx, context = ctx))
            assertIs<CoreResult.Failure>(result); assertTrue(tx.committedAssets().isEmpty()); source.close()
        }
    }
}
