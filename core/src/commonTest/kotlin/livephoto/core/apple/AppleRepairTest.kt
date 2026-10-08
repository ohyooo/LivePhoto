package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.memory.*
import kotlin.test.*

class AppleRepairTest {
    private val context = Context(Limits(12_000_000uL, 12_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val otherId = "11112233-4455-6677-8899-aabbccddeeff"
    private suspend fun bytes(source: BinarySource) = BinaryReader(source, context).readExactly(0uL, source.size().orThrow().toUInt()).orThrow()
    private fun source(bytes: Bytes, id: String) = MemoryBinarySource(bytes, SourceId(id))
    private suspend fun mismatch(heic: Boolean = false, mov: Boolean = false): SourceSet.Pair {
        val base = AppleHeifKeyFixtures.pair(context, mov, idat = mov, audio = mov, hevc = heic)
        try {
            val inspection = core.inspect(ReadRequest(base, context)).orThrow()
            val id = inspection.pairing!!.imageIdentifier!!
            val image = if (heic) bytes(base.image) else Bytes(AppleFixtures.image(id))
            val range = inspection.metadata.single { it.selector == APPLE_CID }.location.range!!
            val movie = bytes(base.video).toByteArray()
            otherId.encodeToByteArray().copyInto(movie, range.offset.toInt())
            return SourceSet.Pair(source(image, "repair-primary"), source(Bytes(movie), "repair-movie"))
        } finally { base.image.close(); base.video.close() }
    }
    private suspend fun authority(input: SourceSet.Pair, video: Boolean = false): EvidenceId =
        core.inspect(ReadRequest(SourceSet.Single(if (video) input.video else input.image), context)).orThrow().pairing!!.evidence.single().id
    private fun policy(authority: EvidenceId, strict: Boolean = false, required: List<Guarantee> = emptyList()) = MutationPolicy(
        conflicts = ConflictPolicy.ExplicitAuthority, authority = authority,
        preservation = if (strict) PreservationPolicy.Strict else PreservationPolicy.BestEffortWithReport, requiredGuarantees = required)
    private suspend fun request(input: SourceSet.Pair, output: OutputTransaction? = null, video: Boolean = false, dry: Boolean = output == null): RepairRequest {
        val authority = authority(input, video)
        return RepairRequest(input, RepairMode.ExplicitRePair, authority = authority, dryRun = dry, policy = policy(authority), output = output, context = context)
    }

    @Test fun explicitImageOrVideoAuthorityChangesOnlyTheOtherCidAndPreservesCompleteMedia(): Unit = runImmediate {
        for (heic in listOf(false, true)) for (mov in listOf(false, true)) for (videoAuthority in listOf(false, true)) {
            val input = mismatch(heic, mov)
            val before = listOf(bytes(input.image), bytes(input.video))
            val selected = if (videoAuthority) input.video else input.image
            val chosen = core.inspect(ReadRequest(SourceSet.Single(selected), context)).orThrow().pairing!!.let { it.imageIdentifier ?: it.videoIdentifier!! }
            assertEquals("INVALID_PAIR_IDENTIFIER", assertIs<CoreResult.Failure>(core.inspect(ReadRequest(input, context))).error.code.value)
            val tx = MemoryOutputTransaction(context, "repair-$heic-$mov-$videoAuthority")
            val req = request(input, tx, videoAuthority)
            val plan = core.plan(req).orThrow()
            assertEquals(listOf(input.image.identity().orThrow(), input.video.identity().orThrow()), plan.snapshot.identities)
            assertEquals(Implementation.Experimental, plan.capabilities.operations.single().implementation)
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val preview = core.repair(req.copy(dryRun = true)).orThrow()
            assertEquals(1, preview.proposedChanges.size); assertTrue(preview.changesApplied.isEmpty()); assertNull(preview.operation)
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val result = core.repair(req).orThrow()
            val op = result.operation!!
            try {
                assertEquals(listOf(AssetRole.PrimaryImage, AssetRole.MotionVideo), op.output.assets.map { it.role })
                val after = SourceSet.Pair(op.output.assets[0].readableSource!!, op.output.assets[1].readableSource!!)
                val inspection = core.inspect(ReadRequest(after, context)).orThrow()
                assertEquals(true, inspection.pairing!!.matches); assertEquals(chosen, inspection.pairing.imageIdentifier); assertEquals(chosen, inspection.pairing.videoIdentifier)
                assertEquals(result.proposedChanges, result.changesApplied)
                assertTrue(result.issuesBefore.any { it.code.value == "INVALID_PAIR_IDENTIFIER" }); assertTrue(result.issuesAfter.none { it.severity == Severity.Error })
                val changed = if (videoAuthority) 0 else 1
                val originalField = core.inspect(ReadRequest(SourceSet.Single(if (changed == 0) input.image else input.video), context)).orThrow()
                    .metadata.single { it.selector == if (changed == 0) "apple:image:content-identifier" else APPLE_CID }.location.range!!
                for (index in 0..1) {
                    val expected = before[index].toByteArray()
                    if (index == changed) chosen.encodeToByteArray().copyInto(expected, originalField.offset.toInt())
                    assertEquals(Bytes(expected), bytes(op.output.assets[index].readableSource!!))
                }
                val originalReader = BinaryReader(input.video, context); val finalReader = BinaryReader(after.video, context)
                val originalMovie = BmffVideoProbe(originalReader, allowTimedMetadata = true).probe(ByteRange(0uL, before[1].size.toULong())).orThrow()
                val finalMovie = BmffVideoProbe(finalReader, allowTimedMetadata = true).probe(ByteRange(0uL, before[1].size.toULong())).orThrow()
                assertEquals(originalMovie.tracks, finalMovie.tracks)
                for (sample in originalMovie.tracks.flatMap { it.samples }) assertEquals(sha256Range(originalReader, sample.range).orThrow(), sha256Range(finalReader, sample.range).orThrow())
                assertEquals(1, op.preservation.records.count { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
                assertTrue(op.execution.none { it.transcoded || it.remuxed })
                if (heic) assertEquals(Coverage.Partial, op.validation.coverage)
                val againTx = MemoryOutputTransaction(context, "repair-noop")
                val again = core.repair(RepairRequest(after, RepairMode.ExplicitRePair, dryRun = false, output = againTx, context = context)).orThrow()
                assertTrue(again.proposedChanges.isEmpty()); assertTrue(again.changesApplied.isEmpty()); assertNull(again.operation)
                assertTrue(againTx.query().orThrow().assetIds.isEmpty())
            } finally { op.output.assets.forEach { it.readableSource?.close() } }
            assertEquals(before[0], bytes(input.image)); assertEquals(before[1], bytes(input.video))
        }
    }

    @Test fun absentStaleThirdSourceAndContradictoryAuthorityNeverSelectAnIdOrStage(): Unit = runImmediate {
        val input = mismatch(); val tx = MemoryOutputTransaction(context, "repair-authority-gates")
        val authorized = request(input, tx)
        assertEquals("REPAIR_AMBIGUOUS", assertIs<CoreResult.Failure>(core.repair(RepairRequest(input, RepairMode.ExplicitRePair, dryRun = false, output = tx, context = context))).error.code.value)
        val unknown = EvidenceId("unknown-evidence")
        assertEquals("REPAIR_AMBIGUOUS", assertIs<CoreResult.Failure>(core.repair(authorized.copy(authority = unknown, policy = policy(unknown)))).error.code.value)
        val third = source(bytes(input.image), "third-image")
        val thirdAuthority = core.inspect(ReadRequest(SourceSet.Single(third), context)).orThrow().pairing!!.evidence.single().id
        assertEquals("REPAIR_AMBIGUOUS", assertIs<CoreResult.Failure>(core.repair(authorized.copy(authority = thirdAuthority, policy = policy(thirdAuthority)))).error.code.value)
        assertEquals("INVALID_ARGUMENT", assertIs<CoreResult.Failure>(core.repair(authorized.copy(authority = authority(input, true)))).error.code.value)
        assertEquals("REPAIR_AMBIGUOUS", assertIs<CoreResult.Failure>(core.repair(authorized.copy(input = SourceSet.Candidates(listOf(input.image, input.video))))).error.code.value)
        val mutable = TestSource(bytes(input.image).toByteArray())
        val changed = SourceSet.Pair(mutable, input.video)
        val stale = request(changed, tx)
        mutable.currentIdentity = mutable.currentIdentity.copy(generation = GenerationToken("new-generation"))
        assertEquals("REPAIR_AMBIGUOUS", assertIs<CoreResult.Failure>(core.repair(stale)).error.code.value)
        val blocked = core.repair(authorized.copy(allowedIssueCodes = listOf(IssueCode("MOTION_VIDEO_LENGTH_MISMATCH")))).orThrow()
        assertEquals(listOf(IssueCode("INVALID_PAIR_IDENTIFIER")), blocked.blocked.map { it.code }); assertTrue(blocked.proposedChanges.isEmpty())
        assertTrue(tx.query().orThrow().assetIds.isEmpty()); assertFalse(mutable.closed)
    }

    @Test fun independentlyFramedUuidFieldsPreserveOriginalWidthAndSingleTerminator(): Unit = runImmediate {
        for (imageTerminated in listOf(false, true)) for (movieTerminated in listOf(false, true)) for (videoAuthority in listOf(false, true)) {
            val input = SourceSet.Pair(source(Bytes(AppleFixtures.image(terminated = imageTerminated)), "width-image"),
                source(Bytes(AppleFixtures.movie(otherId + if (movieTerminated) "\u0000" else "", ordinaryKey = false, singleMdat = true)), "width-movie"))
            val before = listOf(bytes(input.image), bytes(input.video))
            val oldInspections = listOf(input.image, input.video).map { core.inspect(ReadRequest(SourceSet.Single(it), context)).orThrow() }
            val fields = oldInspections.mapIndexed { index, inspected ->
                inspected.metadata.single { it.selector == if (index == 0) "apple:image:content-identifier" else APPLE_CID }.location.range!!
            }
            assertEquals(if (imageTerminated) 37uL else 36uL, fields[0].length)
            assertEquals(if (movieTerminated) 37uL else 36uL, fields[1].length)
            val result = core.repair(request(input, MemoryOutputTransaction(context, "width-$imageTerminated-$movieTerminated-$videoAuthority"), videoAuthority)).orThrow()
            try {
                val changedIndex = if (videoAuthority) 0 else 1
                val chosen = if (videoAuthority) otherId else AppleFixtures.ID
                for (index in 0..1) {
                    val expected = before[index].toByteArray()
                    if (index == changedIndex) chosen.encodeToByteArray().copyInto(expected, fields[index].offset.toInt())
                    assertEquals(Bytes(expected), bytes(result.operation!!.output.assets[index].readableSource!!))
                    assertEquals(before[index], bytes(if (index == 0) input.image else input.video))
                }
            } finally { result.operation!!.output.assets.forEach { it.readableSource?.close() }; input.image.close(); input.video.close() }
        }
    }

    @Test fun extraTerminatorsSharedIdentityAndMixedTimedMetadataNeverAuthorizePublication(): Unit = runImmediate {
        val image = source(Bytes(AppleFixtures.image()), "bounded-image")
        val extraNuls = source(Bytes(AppleFixtures.movie(otherId + "\u0000\u0000", ordinaryKey = false, singleMdat = true)), "extra-terminators")
        val mixed = source(Bytes(AppleFixtures.movie(otherId, ordinaryKey = true, singleMdat = true)), "mixed-timed-metadata")
        for (movie in listOf(extraNuls, mixed)) {
            val selected = SourceSet.Pair(image, movie)
            val before = listOf(bytes(image), bytes(movie))
            val tx = MemoryOutputTransaction(context, "bounded-reject-${movie.identity().orThrow().id.value}")
            val req = request(selected, tx)
            assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(core.plan(req)).error.code.value)
            assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(core.repair(req)).error.code.value)
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
            assertEquals(before[0], bytes(image)); assertEquals(before[1], bytes(movie))
        }
        val alias = source(Bytes(AppleFixtures.movie(otherId, ordinaryKey = false, singleMdat = true)), image.identity().orThrow().id.value)
        val tx = MemoryOutputTransaction(context, "bounded-alias")
        val req = request(SourceSet.Pair(image, alias), tx)
        assertEquals("INVALID_ARGUMENT", assertIs<CoreResult.Failure>(core.repair(req)).error.code.value)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
        image.close(); extraNuls.close(); mixed.close(); alias.close()
    }

    @Test fun strictExactMetadataOrdinaryMakerNoteWeakAtomicityAndBudgetArePreflightGates(): Unit = runImmediate {
        val input = mismatch(heic = true, mov = true)
        val tx = MemoryOutputTransaction(context, "repair-preflight")
        val req = request(input, tx)
        val selectedAuthority = requireNotNull(req.authority)
        for (p in listOf(policy(selectedAuthority, strict = true), policy(selectedAuthority, required = listOf(Guarantee.MetadataPreserving)), policy(selectedAuthority, required = listOf(Guarantee.ExactExtraction)))) {
            assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(core.repair(req.copy(policy = p))).error.code.value)
            assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(core.plan(req.copy(policy = p))).error.code.value)
        }
        val weak = object : OutputTransaction by tx { override fun capabilities() = tx.capabilities().copy(assetSetAtomic = false) }
        assertEquals("ATOMIC_PUBLICATION_UNAVAILABLE", assertIs<CoreResult.Failure>(core.repair(req.copy(output = weak))).error.code.value)
        val small = context.copy(limits = context.limits.copy(maxOutputBytes = input.image.size().orThrow() + input.video.size().orThrow() - 1uL))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", assertIs<CoreResult.Failure>(core.repair(req.copy(context = small))).error.code.value)
        val imageId = core.inspect(ReadRequest(SourceSet.Single(input.image), context)).orThrow().pairing!!.imageIdentifier!!
        val ordinary = SourceSet.Pair(source(Bytes(AppleFixtures.image(imageId, ordinaryNote = true)), "ordinary-note"), input.video)
        assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(core.repair(request(ordinary, tx))).error.code.value)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }

    @Test fun secondAssetFailureTamperSourceChangeAndCancellationRollBackWithoutClosingInput(): Unit = runImmediate {
        for (fault in 0..3) {
            val original = mismatch(); val mutable = TestSource(bytes(original.video).toByteArray())
            val input = SourceSet.Pair(original.image, mutable)
            val before = listOf(bytes(input.image), bytes(input.video))
            var cancelled = false; var creates = 0; var commits = 0
            val cancellable = context.copy(cancellation = Cancellation { cancelled })
            val base = MemoryOutputTransaction(cancellable, "repair-fault-$fault")
            val output = object : OutputTransaction by base {
                override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                    if (++creates == 2 && fault == 0) return CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "Synthetic second-asset failure"))
                    return base.create(spec)
                }
                override suspend fun prepare(): CoreResult<Unit> {
                    if (fault == 2) mutable.currentIdentity = mutable.currentIdentity.copy(generation = GenerationToken("changed"))
                    if (fault == 3) cancelled = true
                    return base.prepare()
                }
                override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                    val source = base.openStaged(id).orThrow()
                    if (fault != 1 || id.value != "asset-1") return CoreResult.Success(source)
                    val identity = source.identity().orThrow(); val changed = bytes(source).toByteArray(); source.close()
                    changed[changed.size - 3] = (changed[changed.size - 3].toInt() xor 1).toByte()
                    return CoreResult.Success(MemoryBinarySource(Bytes(changed), identity.id))
                }
                override suspend fun commit(): CoreResult<Receipt> { commits++; return base.commit() }
            }
            val result = core.repair(request(input, output).copy(context = cancellable))
            assertEquals(listOf("IO_WRITE_FAILED", "POSTCONDITION_FAILED", "SOURCE_CHANGED", "CANCELLED")[fault], assertIs<CoreResult.Failure>(result).error.code.value)
            assertEquals(0, commits); assertEquals(TransactionState.Aborted, base.query().orThrow().state); assertTrue(base.committedAssets().isEmpty())
            assertFalse(mutable.closed); assertEquals(before[0], bytes(input.image)); assertEquals(before[1], bytes(input.video))
        }
    }
}
