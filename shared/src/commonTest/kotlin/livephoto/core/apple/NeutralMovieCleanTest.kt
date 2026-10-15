package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

/** Synthetic container / preservation tests, not decoder or device evidence. */
class NeutralMovieCleanTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val strict = MutationPolicy(preservation = PreservationPolicy.Strict, requiredGuarantees = listOf(Guarantee.ExactExtraction, Guarantee.MetadataPreserving))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private suspend fun bytes(input: BinarySource) = BinaryReader(input, context).readExactly(0uL, input.size().orThrow().toUInt()).orThrow()

    @Test fun ordinaryAvcHevcAndAacMoviesAreByteExactAndRepeatedCleanIsIdempotent(): Unit = runImmediate {
        for (hevc in listOf(false, true)) for (aac in listOf(false, true)) {
            val original = GoogleFixtures.video(hevc = hevc, aac = aac).bytes
            var input: BinarySource = source(original, "neutral-movie-$hevc-$aac")
            repeat(2) { index ->
                val output = MemoryOutputTransaction(context, "neutral-repeat-$hevc-$aac-$index")
                val req = SplitRequest(SourceSet.Single(input), policy = strict, output = output, context = context)
                val plan = core.plan(req).orThrow()
                assertEquals(Operation.SplitClean, plan.capabilities.operations.single().operation)
                assertEquals(Implementation.Experimental, plan.capabilities.operations.single().implementation)
                assertTrue(plan.capabilities.operations.single().conditions.any { it.value == Value.Text("video/mp4") })
                assertEquals(listOf(input.identity().orThrow()), plan.snapshot.identities)
                assertTrue(plan.steps.any { it.stage == Stage.Clean && Operation.SplitClean in it.required })
                assertTrue(output.query().orThrow().assetIds.isEmpty())
                val result = core.split(req).orThrow()
                assertEquals(AssetRole.MotionVideo, result.output.assets.single().role)
                assertEquals(VideoContainer.Mp4, result.output.assets.single().videoContainer)
                assertTrue(result.preservation.changes.isEmpty()); assertTrue(result.execution.none { it.transcoded || it.remuxed })
                assertTrue(result.preservation.records.all { it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) })
                assertEquals(Bytes(original), bytes(input))
                input = result.output.assets.single().readableSource!!
                assertEquals(Bytes(original), bytes(input))
            }
        }
    }

    @Test fun appleCleanImageAndMovieCanEachBeCleanedAgainWithoutChangingAnyByte(): Unit = runImmediate {
        val pair = SourceSet.Pair(source(AppleFixtures.image(), "apple-idempotent-image"), source(AppleFixtures.movie(ordinaryKey = false), "apple-idempotent-movie"))
        val cleaned = core.split(SplitRequest(pair, output = MemoryOutputTransaction(context, "apple-first-clean"), context = context)).orThrow()
        for ((index, asset) in cleaned.output.assets.withIndex()) {
            val input = asset.readableSource!!; val before = bytes(input)
            // JPEG clean currently proves image/ordinary metadata rather than declaring ExactExtraction.
            val policy = if (asset.role == AssetRole.MotionVideo) strict else MutationPolicy(preservation = PreservationPolicy.Strict)
            val result = core.split(SplitRequest(SourceSet.Single(input), policy = policy,
                output = MemoryOutputTransaction(context, "apple-repeat-clean-$index"), context = context)).orThrow()
            assertEquals(asset.role, result.output.assets.single().role)
            assertEquals(before, bytes(result.output.assets.single().readableSource!!)); assertEquals(before, bytes(input))
            assertTrue(result.preservation.changes.isEmpty())
        }
    }

    @Test fun identifiedTimedAndUnknownMoviesCannotBeDeclaredClean(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        for ((index, movie) in listOf(AppleFixtures.movie(ordinaryKey = false),
            video + GoogleFixtures.box("uuid", ByteArray(16)), video + GoogleFixtures.box("free", byteArrayOf(1))).withIndex()) {
            val output = MemoryOutputTransaction(context, "neutral-reject-$index")
            val request = SplitRequest(SourceSet.Single(source(movie, "neutral-invalid-$index")), policy = strict, output = output, context = context)
            assertIs<CoreResult.Failure>(core.plan(request)); assertIs<CoreResult.Failure>(core.split(request))
            assertTrue(output.query().orThrow().assetIds.isEmpty()); assertTrue(output.committedAssets().isEmpty())
        }
    }

    @Test fun neutralMovieBudgetNonatomicAndSourceChangeNeverPublish(): Unit = runImmediate {
        val original = TestSource(GoogleFixtures.video().bytes)
        val base = MemoryOutputTransaction(context, "neutral-source-guard")
        val output = object : OutputTransaction by base {
            override suspend fun prepare(): CoreResult<Unit> {
                original.currentIdentity = original.currentIdentity.copy(generation = GenerationToken("changed"))
                return base.prepare()
            }
        }
        val request = SplitRequest(SourceSet.Single(original), policy = strict, output = output, context = context)
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(core.split(request)).error.code.value)
        assertEquals(TransactionState.Aborted, base.query().orThrow().state); assertTrue(base.committedAssets().isEmpty()); assertFalse(original.closed)
        val fresh = request.copy(input = SourceSet.Single(source(GoogleFixtures.video().bytes, "neutral-fresh")), output = MemoryOutputTransaction(context, "neutral-policy"))
        val nonAtomic = object : OutputTransaction by fresh.output { override fun capabilities() = fresh.output.capabilities().copy(assetSetAtomic = false) }
        for ((req, code) in listOf(fresh.copy(output = nonAtomic) to "ATOMIC_PUBLICATION_UNAVAILABLE",
            fresh.copy(context = context.copy(limits = context.limits.copy(maxOutputBytes = 1uL))) to "RESOURCE_LIMIT_EXCEEDED")) {
            assertEquals(code, assertIs<CoreResult.Failure>(core.plan(req)).error.code.value)
            assertEquals(code, assertIs<CoreResult.Failure>(core.split(req)).error.code.value)
            assertTrue(fresh.output.query().orThrow().assetIds.isEmpty())
        }
    }
}
