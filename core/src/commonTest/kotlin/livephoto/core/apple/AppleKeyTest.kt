package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

class AppleKeyTest {
    private val context = Context(Limits(12_000_000uL, 12_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4"))
    private fun source(bytes: Bytes, name: String) = MemoryBinarySource(bytes, SourceId(name))
    private suspend fun pair(audio: Boolean = false, hevc: Boolean = false): SourceSet.Pair {
        val tx = MemoryOutputTransaction(context, "apple-key-fixture-$audio-$hevc")
        val result = core.create(CreateRequest(source(Bytes(GoogleFixtures.jpeg()), "key-image"), source(Bytes(GoogleFixtures.video(aac = audio, hevc = hevc).bytes), "key-movie"),
            target, edits = EditSpec(keyPosition = CoverPosition.FrameIndex(0uL)), output = tx, context = context)).orThrow()
        return SourceSet.Pair(result.output.assets[0].readableSource!!, result.output.assets[1].readableSource!!)
    }
    private suspend fun bytes(source: BinarySource) = BinaryReader(source, context).readExactly(0uL, source.size().orThrow().toUInt()).orThrow()
    @Test fun zeroToNonzeroToZeroKeepsPrimaryCidTrackIdsAndAllMovieBytesOutsideOwnedTimes(): Unit = runImmediate {
        for (audio in listOf(false, true)) for (hevc in listOf(false, true)) {
            val input = pair(audio, hevc)
            val originalImage = bytes(input.image); val originalMovie = bytes(input.video)
            val before = core.inspect(ReadRequest(input, context)).orThrow()
            val tx = MemoryOutputTransaction(context, "apple-key-edit-$audio-$hevc")
            val req = SetKeyRequest(input, CoverPosition.FrameIndex(1uL), policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context)
            val plan = core.plan(req).orThrow()
            assertEquals(Operation.SetKey, plan.capabilities.operations.single().operation)
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val result = core.setKeyPhotoPosition(req).orThrow()
            val edited = SourceSet.Pair(result.output.assets[0].readableSource!!, result.output.assets[1].readableSource!!)
            assertEquals(originalImage, bytes(edited.image))
            assertEquals(originalMovie.size, bytes(edited.video).size)
            val after = core.inspect(ReadRequest(edited, context)).orThrow()
            assertEquals(before.pairing, after.pairing)
            assertEquals(0, after.keyPhoto.position!!.compareTo(Time(40, 1000u)))
            assertEquals("0", (after.keyPhoto.rawFields.single().rawValue as Value.Number).decimal) // Marker payload remains zero.
            val original = BinaryReader(input.video, context); val changed = BinaryReader(edited.video, context)
            val originalFacts = BmffVideoProbe(original, allowTimedMetadata = true).probe(ByteRange(0uL, originalMovie.size.toULong())).orThrow()
            val changedFacts = BmffVideoProbe(changed, allowTimedMetadata = true).probe(ByteRange(0uL, originalMovie.size.toULong())).orThrow()
            assertEquals(originalFacts.tracks.map { it.trackId }, changedFacts.tracks.map { it.trackId })
            assertEquals(originalFacts.tracks.filter { it.handler != "meta" }, changedFacts.tracks.filter { it.handler != "meta" })
            for ((left, right) in originalFacts.tracks.flatMap { it.samples }.zip(changedFacts.tracks.flatMap { it.samples })) {
                assertEquals(left.range, right.range)
                assertEquals(sha256Range(original, left.range).orThrow(), sha256Range(changed, right.range).orThrow())
            }
            assertTrue(result.execution.none { it.transcoded || it.remuxed || it.stage in setOf(Stage.DecodeFrame, Stage.EncodeImage) })
            assertTrue(result.preservation.records.all { it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) })
            val reverted = core.setKeyPhotoPosition(SetKeyRequest(edited, CoverPosition.FrameIndex(0uL), policy = MutationPolicy(preservation = PreservationPolicy.Strict),
                output = MemoryOutputTransaction(context, "apple-key-zero-again-$audio-$hevc"), context = context)).orThrow()
            assertEquals(originalImage, bytes(reverted.output.assets[0].readableSource!!))
            assertEquals(originalMovie, bytes(reverted.output.assets[1].readableSource!!))
            assertEquals(originalMovie, bytes(input.video))
        }
    }
    @Test fun invalidIndexMissingFixedEnvelopeAndWeakerAtomicityNeverStage(): Unit = runImmediate {
        val input = pair()
        val tx = MemoryOutputTransaction(context, "apple-key-invalid")
        assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(2uL), output = tx, context = context)))
        val weak = object : OutputTransaction by tx { override fun capabilities() = tx.capabilities().copy(assetSetAtomic = false) }
        assertEquals(IssueCode("ATOMIC_PUBLICATION_UNAVAILABLE"), assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(1uL), output = weak, context = context))).error.code)
        val old = SourceSet.Pair(source(Bytes(AppleFixtures.image()), "old-image"), source(Bytes(AppleFixtures.movie(ordinaryKey = false)), "old-movie"))
        assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(old, CoverPosition.FrameIndex(0uL), output = tx, context = context)))
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }
    @Test fun stagingTamperAndSecondAssetFailureNeverPublishHalfPair(): Unit = runImmediate {
        for (corrupt in listOf(false, true)) {
            val input = pair(); val base = MemoryOutputTransaction(context, "apple-key-fault-$corrupt")
            var creates = 0; var commits = 0
            val output = object : OutputTransaction by base {
                override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                    if (++creates == 2 && !corrupt) return CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "Injected movie write failure"))
                    return base.create(spec)
                }
                override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                    val staged = base.openStaged(id).orThrow()
                    if (!corrupt || id.value != "asset-1") return CoreResult.Success(staged)
                    val identity = staged.identity().orThrow(); val data = bytes(staged).toByteArray(); staged.close()
                    data[0] = (data[0].toInt() xor 1).toByte()
                    return CoreResult.Success(MemoryBinarySource(Bytes(data), identity.id))
                }
                override suspend fun commit(): CoreResult<Receipt> { commits++; return base.commit() }
            }
            val result = core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(1uL), output = output, context = context))
            assertEquals(IssueCode(if (corrupt) "POSTCONDITION_FAILED" else "IO_WRITE_FAILED"), assertIs<CoreResult.Failure>(result).error.code)
            assertEquals(0, commits); assertEquals(TransactionState.Aborted, base.query().orThrow().state)
            assertTrue(base.committedAssets().isEmpty())
        }
    }
    @Test fun changingOriginalGenerationDuringPrepareAbortsAndKeepsBorrowedInputOpen(): Unit = runImmediate {
        val input = pair(); val original = TestSource(bytes(input.video).toByteArray())
        val base = MemoryOutputTransaction(context, "apple-key-generation-change")
        val output = object : OutputTransaction by base {
            override suspend fun prepare(): CoreResult<Unit> {
                original.currentIdentity = original.currentIdentity.copy(generation = GenerationToken("changed")); return base.prepare()
            }
        }
        val result = core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Pair(input.image, original), CoverPosition.FrameIndex(1uL), output = output, context = context))
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(result).error.code)
        assertEquals(TransactionState.Aborted, base.query().orThrow().state); assertFalse(original.closed)
    }
    @Test fun timestampSelectionUsesPresentationFramesExplicitToleranceAndEarlierTie(): Unit = runImmediate {
        val input = pair()
        val originalImage = bytes(input.image)
        val cases = listOf(
            CoverPosition.Timestamp(Time(20, 1000u), Selection.Nearest, Time(20, 1000u)) to Time.Zero,
            CoverPosition.Timestamp(Time(60, 1000u), Selection.AtOrBefore, Time(20, 1000u)) to Time(40, 1000u),
            CoverPosition.Timestamp(Time(40, 1000u), Selection.Exact) to Time(40, 1000u),
            CoverPosition.FrameIndex(1uL, TrackId("1")) to Time(40, 1000u))
        for ((index, case) in cases.withIndex()) {
            val result = core.setKeyPhotoPosition(SetKeyRequest(input, case.first,
                output = MemoryOutputTransaction(context, "apple-selection-$index"), context = context)).orThrow()
            assertEquals(originalImage, bytes(result.output.assets[0].readableSource!!))
            val read = core.getKeyPhotoPosition(ReadRequest(SourceSet.Pair(result.output.assets[0].readableSource!!, result.output.assets[1].readableSource!!), context)).orThrow()
            assertEquals(0, read.position!!.compareTo(case.second))
        }
        for ((index, position) in listOf(
            CoverPosition.Timestamp(Time(20, 1000u), Selection.Exact),
            CoverPosition.Timestamp(Time(20, 1000u), Selection.Nearest),
            CoverPosition.Timestamp(Time(80, 1000u), Selection.Exact),
            CoverPosition.FrameIndex(0uL, TrackId("2"))).withIndex()) {
            val tx = MemoryOutputTransaction(context, "apple-selection-reject-$index")
            assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(input, position, output = tx, context = context)))
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
    }
    @Test fun unclassifiedOrNonzeroPaddingAndZeroDurationCannotAuthorizeSetKey(): Unit = runImmediate {
        val input = pair()
        val original = bytes(input.video)
        val reader = BinaryReader(input.video, context)
        val facts = BmffVideoProbe(reader, allowTimedMetadata = true).probe(ByteRange(0uL, original.size.toULong())).orThrow()
        val metadataId = facts.tracks.single { it.handler == "meta" }.trackId
        val parser = BmffReader(reader)
        val movie = parser.readBoxes(ByteRange(0uL, original.size.toULong())).orThrow().single { it.type == "moov" }
        val track = parser.readBoxes(movie.payload).orThrow().filter { it.type == "trak" }.single { trak ->
            val tkhd = parser.readBoxes(trak.payload).orThrow().single { it.type == "tkhd" }
            reader.readU32(tkhd.payload.offset + 12uL).orThrow() == metadataId
        }
        val edts = parser.readBoxes(track.payload).orThrow().single { it.type == "edts" }
        val padding = parser.readBoxes(edts.payload).orThrow().single { it.type == "free" }
        for (variant in 0..3) {
            val changed = original.toByteArray()
            when (variant) {
                0 -> changed[padding.payload.offset.toInt()] = 1
                1 -> ByteArray(4).copyInto(changed, padding.range.offset.toInt()) // Parent-extending size zero is not the owned explicit envelope.
                2 -> "uuid".encodeToByteArray().copyInto(changed, padding.range.offset.toInt() + 4)
                3 -> ByteArray(4).copyInto(changed, edts.payload.offset.toInt() + 16) // Media edit duration must not be zero.
            }
            val corrupted = source(Bytes(changed), "apple-malformed-edit-$variant")
            assertIs<CoreResult.Failure>(BmffVideoProbe(BinaryReader(corrupted, context), allowTimedMetadata = true).probe(ByteRange(0uL, changed.size.toULong())))
            val tx = MemoryOutputTransaction(context, "apple-malformed-edit-output-$variant")
            assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Pair(input.image, corrupted), CoverPosition.FrameIndex(1uL), output = tx, context = context)))
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
            assertEquals(Bytes(changed), bytes(corrupted))
        }
        assertEquals(original, bytes(input.video))
    }
    @Test fun pairWideExactRequirementAndSharedOutputBudgetFailBeforeStaging(): Unit = runImmediate {
        val input = pair()
        val exact = MemoryOutputTransaction(context, "apple-key-exact-rejected")
        val request = SetKeyRequest(input, CoverPosition.FrameIndex(1uL), policy = MutationPolicy(requiredGuarantees = listOf(Guarantee.ExactExtraction)), output = exact, context = context)
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(core.plan(request)).error.code)
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(request)).error.code)
        assertTrue(exact.query().orThrow().assetIds.isEmpty())
        val total = input.image.size().orThrow() + input.video.size().orThrow()
        val limited = context.copy(limits = context.limits.copy(maxOutputBytes = total - 1uL))
        val tx = MemoryOutputTransaction(limited, "apple-key-budget-rejected")
        val budgetRequest = SetKeyRequest(input, CoverPosition.FrameIndex(1uL), output = tx, context = limited)
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(core.plan(budgetRequest)).error.code)
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(budgetRequest)).error.code)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }
    @Test fun cancellationAfterStagingAbortsBothAssetsAndNeverCommits(): Unit = runImmediate {
        val input = pair(); val imageBefore = bytes(input.image); val movieBefore = bytes(input.video)
        var cancelled = false; var commits = 0
        val cancellable = context.copy(cancellation = Cancellation { cancelled })
        val base = MemoryOutputTransaction(cancellable, "apple-key-cancelled")
        val output = object : OutputTransaction by base {
            override suspend fun prepare(): CoreResult<Unit> {
                val result = base.prepare(); cancelled = true; return result
            }
            override suspend fun commit(): CoreResult<Receipt> { commits++; return base.commit() }
        }
        val result = core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(1uL), output = output, context = cancellable))
        assertEquals(IssueCode("CANCELLED"), assertIs<CoreResult.Failure>(result).error.code)
        assertEquals(0, commits); assertEquals(TransactionState.Aborted, base.query().orThrow().state)
        assertTrue(base.committedAssets().isEmpty())
        assertEquals(imageBefore, bytes(input.image)); assertEquals(movieBefore, bytes(input.video))
    }
}
