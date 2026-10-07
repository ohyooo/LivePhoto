package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class AppleCreateTest {
    private val context = Context(Limits(12_000_000uL, 12_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4"))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun request(output: OutputTransaction, hevc: Boolean = false, audio: Boolean = false, explicit: Boolean = false) = CreateRequest(
        source(GoogleFixtures.jpeg(), "apple-create-image"), source(GoogleFixtures.video(hevc = hevc, aac = audio).bytes, "apple-create-movie"), target,
        edits = if (explicit) EditSpec(keyPosition = CoverPosition.FrameIndex(0uL)) else null,
        policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = output, context = context)
    @Test fun ordinaryMediaCreateHasItsOwnPlanAndPublishesOnlyCompleteVerifiedPairs(): Unit = runImmediate {
        val identifiers = mutableSetOf<String>()
        for (hevc in listOf(false, true)) for (audio in listOf(false, true)) for (explicit in listOf(false, true)) {
            val tx = MemoryOutputTransaction(context, "apple-create-$hevc-$audio-$explicit")
            val req = request(tx, hevc, audio, explicit)
            val plan = core.plan(req).orThrow()
            assertEquals(Operation.Create, plan.capabilities.operations.single().operation)
            assertEquals(listOf(req.image.identity().orThrow(), req.video.identity().orThrow()), plan.snapshot.identities)
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val result = core.create(req).orThrow()
            assertEquals(listOf(AssetRole.PrimaryImage, AssetRole.MotionVideo), result.output.assets.map { it.role })
            assertEquals(Verdict.Valid, result.validation.verdict); assertEquals(Coverage.Complete, result.validation.coverage)
            assertEquals(Implementation.Experimental, core.getProtocolCapabilities(target).operations.single { it.operation == Operation.Create }.implementation)
            assertTrue(result.execution.none { it.transcoded || it.remuxed })
            val pair = SourceSet.Pair(result.output.assets[0].readableSource!!, result.output.assets[1].readableSource!!)
            val inspected = core.inspect(ReadRequest(pair, context)).orThrow()
            assertEquals(true, inspected.pairing?.matches)
            assertTrue(identifiers.add(inspected.pairing!!.imageIdentifier!!))
            assertEquals(0, inspected.keyPhoto.position!!.compareTo(if (explicit) Time.Zero else Time(40, 1000u)))
            val original = BinaryReader(req.video, context)
            val inputFacts = BmffVideoProbe(original).probe(ByteRange(0uL, req.video.size().orThrow())).orThrow()
            val movie = BinaryReader(pair.video, context)
            val outputFacts = BmffVideoProbe(movie, allowTimedMetadata = true).probe(ByteRange(0uL, pair.video.size().orThrow())).orThrow()
            RemuxVerification.verify(original, inputFacts, movie, outputFacts.copy(tracks = outputFacts.tracks.filter { it.handler != "meta" }))
            assertTrue(result.preservation.records.all { it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) })
            assertEquals(Bytes(GoogleFixtures.jpeg()), BinaryReader(req.image, context).readExactly(0uL, req.image.size().orThrow().toUInt()).orThrow())
        }
    }
    @Test fun existingLiveBindingIsNotConvertedOrSilentlyStrippedByCreate(): Unit = runImmediate {
        for (policy in SourceBindingPolicy.entries) {
            val tx = MemoryOutputTransaction(context, "apple-create-already-live-$policy")
            val req = request(tx).copy(image = source(GoogleFixtures.v2Photo(), "already-live"), sourceBindings = policy)
            val result = core.create(req)
            assertEquals(IssueCode(if (policy == SourceBindingPolicy.RejectAlreadyLive) "SOURCE_ALREADY_LIVE" else "CAPABILITY_UNSUPPORTED"), assertIs<CoreResult.Failure>(result).error.code)
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
    }
    @Test fun unknownExifGenericProfilesPreferencesAndNonAtomicOutputNeverStage(): Unit = runImmediate {
        for (profile in listOf(null, ProfileId("heic-mov"))) {
            val tx = MemoryOutputTransaction(context, "apple-create-planned-$profile")
            val req = request(tx).copy(target = ProtocolSelector(ProtocolIds.Apple, profile))
            assertEquals(IssueCode("CAPABILITY_PLANNED"), assertIs<CoreResult.Failure>(core.create(req)).error.code)
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
        val wrongContainer = MemoryOutputTransaction(context, "apple-create-mov-requires-mov")
        assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), assertIs<CoreResult.Failure>(core.create(request(wrongContainer).copy(target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mov"))))).error.code)
        assertTrue(wrongContainer.query().orThrow().assetIds.isEmpty())
        val tx = MemoryOutputTransaction(context, "apple-create-gates")
        val base = request(tx)
        val duplicate = base.copy(video = source(GoogleFixtures.video().bytes, "apple-create-image"))
        val exif = base.copy(image = source(AppleFixtures.image(), "apple-existing-maker-note"))
        val encoding = base.copy(preference = MediaPreference(imageFormat = ImageFormat.Heic))
        val nonAtomic = object : OutputTransaction by tx { override fun capabilities() = tx.capabilities().copy(assetSetAtomic = false) }
        for (req in listOf(duplicate, exif, encoding, base.copy(output = nonAtomic))) {
            assertIs<CoreResult.Failure>(core.plan(req)); assertIs<CoreResult.Failure>(core.create(req))
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
    }
    @Test fun secondAssetFailureAbortsTheFirstAndNeverPublishesHalfAPair(): Unit = runImmediate {
        val base = MemoryOutputTransaction(context, "apple-create-second-failure")
        var count = 0; var commits = 0
        val output = object : OutputTransaction by base {
            override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                if (++count == 2) return CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "Injected second asset failure"))
                return base.create(spec)
            }
            override suspend fun commit(): CoreResult<Receipt> { commits++; return base.commit() }
        }
        assertEquals(IssueCode("IO_WRITE_FAILED"), assertIs<CoreResult.Failure>(core.create(request(output))).error.code)
        assertEquals(2, count); assertEquals(0, commits)
        assertEquals(TransactionState.Aborted, base.query().orThrow().state); assertTrue(base.committedAssets().isEmpty())
    }
    @Test fun originalGenerationChangeDuringStagingAbortsWithoutClosingBorrowedSources(): Unit = runImmediate {
        val base = MemoryOutputTransaction(context, "apple-create-source-change")
        val original = TestSource(GoogleFixtures.video().bytes)
        val output = object : OutputTransaction by base {
            override suspend fun prepare(): CoreResult<Unit> {
                original.currentIdentity = original.currentIdentity.copy(generation = GenerationToken("changed"))
                return base.prepare()
            }
        }
        val req = request(output).copy(video = original)
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(core.create(req)).error.code)
        assertEquals(TransactionState.Aborted, base.query().orThrow().state); assertFalse(original.closed)
    }
}
