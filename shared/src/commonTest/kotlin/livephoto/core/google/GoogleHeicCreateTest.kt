package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.heif.HeifFixtures
import livephoto.core.memory.*
import kotlin.test.*

class GoogleHeicCreateTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic"))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun request(output: OutputTransaction, image: ByteArray = HeifFixtures.plain(), video: ByteArray = GoogleFixtures.video().bytes,
        preference: MediaPreference = MediaPreference(), edits: EditSpec? = null, ctx: Context = context, policy: MutationPolicy = MutationPolicy()) =
        CreateRequest(source(image, "heic-cover"), source(video, "heic-video"), target, preference, edits, policy = policy, output = output, context = ctx)
    @Test fun createDetectInspectValidateAndByteExactExtractArePublicAndNeverRequireAnEncoder(): Unit = runImmediate {
        var count = 0
        for (layout in listOf(Triple(false, false, false), Triple(true, false, false), Triple(false, true, false), Triple(false, false, true)))
            for (hevc in listOf(false, true)) for (aac in listOf(false, true)) {
                val image = HeifFixtures.plain(metaLast = layout.first, extended = layout.second, idat = layout.third, multiple = true)
                val video = GoogleFixtures.video(hevc = hevc, aac = aac).bytes
                val tx = MemoryOutputTransaction(context, "heic-public-create-${count++}")
                val req = request(tx, image, video, edits = EditSpec(keyPosition = CoverPosition.FrameIndex(1uL)))
                val imageBefore = req.image.identity().orThrow()
                val videoBefore = req.video.identity().orThrow()
                val plan = core.plan(req).orThrow()
                assertEquals(Availability.Conditional, plan.capabilities.availability)
                assertEquals(2, plan.snapshot.identities.size)
                assertTrue(tx.query().orThrow().assetIds.isEmpty())
                val created = core.create(req).orThrow()
                assertEquals(TransactionState.Committed, created.output.receipt.state)
                assertEquals(1, created.output.assets.size)
                assertEquals(ImageFormat.Heic, created.output.assets.single().imageFormat)
                assertTrue(created.execution.none { it.transcoded })
                val bytes = tx.committedAssets().values.single()
                val input = SourceSet.Single(source(bytes.toByteArray(), "heic-created"))
                val inspected = core.inspect(ReadRequest(input, context)).orThrow()
                assertEquals(target, inspected.detection.primaryProtocol)
                assertEquals(listOf(target), inspected.detection.matches.map { it.target })
                assertEquals(Time(40_000, 1_000_000u), inspected.keyPhoto.position)
                assertEquals(Coverage.Partial, created.validation.coverage)
                val validation = core.validateProtocol(ValidationRequest(input, target = target, context = context)).orThrow()
                assertEquals(Verdict.Valid, validation.verdict)
                assertEquals(Coverage.Complete, validation.coverage)
                val raw = MemoryOutputTransaction(context, "heic-created-raw-$count")
                core.extract(ExtractRequest(input, emptyList(), inspected.snapshot, output = raw, context = context)).orThrow()
                assertEquals(Bytes(video), raw.committedAssets().values.single())
                assertEquals(imageBefore, req.image.identity().orThrow()); assertEquals(videoBefore, req.video.identity().orThrow())
                assertEquals(Bytes(image), BinaryReader(req.image, context).readExactly(0uL, image.size.toUInt()).orThrow())
                for (guarantee in listOf(Guarantee.BitstreamPreserving, Guarantee.ImageDataPreserving, Guarantee.MetadataPreserving))
                    assertEquals(GuaranteeOutcome.Verified, created.preservation.records.single { it.guarantee == guarantee }.outcome)
            }
        assertEquals(16, count)
    }
    @Test fun strictFinitePreservationIsSeparateFromDecodeOrDeviceCoverage(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "heic-create-strict")
        val result = core.create(request(tx, policy = MutationPolicy(preservation = PreservationPolicy.Strict,
            requiredGuarantees = listOf(Guarantee.BitstreamPreserving, Guarantee.ImageDataPreserving, Guarantee.MetadataPreserving)))).orThrow()
        assertEquals(Coverage.Partial, result.validation.coverage)
        assertTrue(result.preservation.records.none { it.outcome == GuaranteeOutcome.Unknown || it.outcome == GuaranteeOutcome.Changed })
        assertTrue(core.getProtocolCapabilities(target).operations.none { Verification.DeviceTested in it.verification })
    }
    @Test fun unknownImageDependenciesAndIncompatiblePreferencesDoNotStageRenamedBytes(): Unit = runImmediate {
        val cases = listOf(HeifFixtures.plain(unknownProperty = true) to MediaPreference(), HeifFixtures.plain(hidden = true) to MediaPreference(),
            GoogleFixtures.jpeg() to MediaPreference(), HeifFixtures.plain() to MediaPreference(imageFormat = ImageFormat.Jpeg),
            HeifFixtures.plain() to MediaPreference(videoCodec = VideoCodec.Hevc))
        for ((index, pair) in cases.withIndex()) {
            val tx = MemoryOutputTransaction(context, "heic-create-refuse-$index")
            val req = request(tx, image = pair.first, preference = pair.second)
            assertIs<CoreResult.Failure>(core.plan(req)); assertIs<CoreResult.Failure>(core.create(req))
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
    }
    @Test fun knownLiveInputCannotBeImplicitlyCleanedOrReusedAsOrdinaryImage(): Unit = runImmediate {
        val first = MemoryOutputTransaction(context, "heic-create-existing")
        core.create(request(first)).orThrow()
        val live = source(first.committedAssets().values.single().toByteArray(), "heic-existing-source")
        for (policy in SourceBindingPolicy.entries) {
            val tx = MemoryOutputTransaction(context, "heic-existing-$policy")
            val req = CreateRequest(live, source(GoogleFixtures.video().bytes, "another-video"), target, sourceBindings = policy, output = tx, context = context)
            val expected = if (policy == SourceBindingPolicy.RejectAlreadyLive) "SOURCE_ALREADY_LIVE" else "CAPABILITY_UNSUPPORTED"
            assertEquals(IssueCode(expected), assertIs<CoreResult.Failure>(core.create(req)).error.code)
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
    }
    @Test fun outputBudgetExactExtractionAndAtomicityFailuresArePreflightNotPartialPublication(): Unit = runImmediate {
        val image = HeifFixtures.plain()
        val video = GoogleFixtures.video().bytes
        val limited = context.copy(limits = context.limits.copy(maxOutputBytes = (image.size + video.size).toULong()))
        val tx = MemoryOutputTransaction(limited, "heic-create-output-limit")
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(core.create(request(tx, image, video, ctx = limited))).error.code)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
        val exact = MemoryOutputTransaction(context, "heic-create-no-whole-exact")
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(core.create(request(exact, policy = MutationPolicy(requiredGuarantees = listOf(Guarantee.ExactExtraction))))).error.code)
        assertTrue(exact.query().orThrow().assetIds.isEmpty())
        val base = MemoryOutputTransaction(context, "heic-create-non-atomic")
        val output = object : OutputTransaction by base {
            override fun capabilities() = OutputCapabilities(true, false, true, false, true)
        }
        assertEquals(IssueCode("ATOMIC_PUBLICATION_UNAVAILABLE"), assertIs<CoreResult.Failure>(core.create(request(output))).error.code)
        assertTrue(base.query().orThrow().assetIds.isEmpty())
    }
    @Test fun corruptedStagingCannotPassGraphRoundTripOrPublish(): Unit = runImmediate {
        val base = MemoryOutputTransaction(context, "heic-create-corrupt-staging")
        var commits = 0
        val output = object : OutputTransaction by base {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                val original = base.openStaged(id).orThrow()
                return CoreResult.Success(object : BinarySource by original {
                    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = attempt {
                        val bytes = original.readAt(offset, length).orThrow().toByteArray()
                        // Kept ftyp minor-version bytes: structurally acceptable corruption must still fail retained-byte proof.
                        if (12uL >= offset && 12uL < offset + bytes.size.toULong()) bytes[(12uL - offset).toInt()] = (bytes[(12uL - offset).toInt()].toInt() xor 1).toByte()
                        Bytes(bytes)
                    }
                })
            }
            override suspend fun commit(): CoreResult<Receipt> { commits++; return base.commit() }
        }
        assertEquals(IssueCode("POSTCONDITION_FAILED"), assertIs<CoreResult.Failure>(core.create(request(output))).error.code)
        assertEquals(0, commits)
        assertEquals(TransactionState.Aborted, base.query().orThrow().state)
        assertTrue(base.committedAssets().isEmpty())
    }
}
