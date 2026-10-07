package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.heif.*
import livephoto.core.memory.*
import kotlin.test.*

class GoogleHeicRepairTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic"))
    private fun source(bytes: Bytes, id: String) = MemoryBinarySource(bytes, SourceId(id))
    private data class Fixture(val good: Bytes, val broken: Bytes, val extent: ByteRange, val video: Bytes)
    private suspend fun fixture(idat: Boolean = false, extended: Boolean = false): Fixture {
        val video = Bytes(GoogleFixtures.video(aac = true).bytes)
        val output = MemoryOutputTransaction(context, "heic-repair-fixture-$idat-$extended")
        core.create(CreateRequest(source(Bytes(HeifFixtures.plain(idat = idat, extended = extended, multiple = true)), "repair-image"),
            source(video, "repair-movie"), target, edits = EditSpec(keyPosition = CoverPosition.FrameIndex(0uL)), output = output, context = context)).orThrow()
        val good = output.committedAssets().values.single()
        val reader = BinaryReader(source(good, "repair-layout"), context)
        val roots = BmffReader(reader).readBoxes(ByteRange(0uL, good.size.toULong())).orThrow()
        val graph = HeifItemGraphReader.read(reader, roots).orThrow()
        val extent = graph.locations.items.single { it.id != graph.primary }.extents.single().data
        val damaged = GoogleDirectoryWriter.heic(video.size.toULong() - 1uL, 0L, context)
        assertEquals(extent.length, damaged.size.toULong())
        val bytes = good.toByteArray(); damaged.copyInto(bytes, extent.offset.toInt())
        return Fixture(good, Bytes(bytes), extent, video)
    }
    @Test fun previewIsReadOnlyAndStrictRepairChangesOnlyProvenLengthThenBecomesIdempotent(): Unit = runImmediate {
        for (idat in listOf(false, true)) for (extended in listOf(false, true)) {
            val fixture = fixture(idat, extended)
            val input = SourceSet.Single(source(fixture.broken, "heic-broken-length"))
            assertEquals(Verdict.Invalid, core.validateProtocol(ValidationRequest(input, target = target, context = context)).orThrow().verdict)
            val output = MemoryOutputTransaction(context, "heic-repair-$idat-$extended")
            val previewRequest = RepairRequest(input, output = output, context = context)
            val preview = core.repair(previewRequest).orThrow()
            assertEquals(1, preview.proposedChanges.size)
            assertTrue(preview.changesApplied.isEmpty()); assertNull(preview.operation)
            assertTrue(output.query().orThrow().assetIds.isEmpty())
            assertEquals(Operation.Repair, core.plan(previewRequest).orThrow().capabilities.operations.single().operation)
            val result = core.repair(previewRequest.copy(dryRun = false, policy = MutationPolicy(preservation = PreservationPolicy.Strict))).orThrow()
            assertTrue(result.blocked.isEmpty()); assertEquals(preview.proposedChanges, result.changesApplied)
            assertEquals(fixture.good, output.committedAssets().values.single())
            assertTrue(result.issuesAfter.none { it.severity == Severity.Error })
            assertTrue(result.operation!!.preservation.records.none { it.outcome in setOf(GuaranteeOutcome.Unknown, GuaranteeOutcome.Changed) })
            val repaired = SourceSet.Single(source(fixture.good, "heic-length-repaired"))
            val again = MemoryOutputTransaction(context, "heic-repair-again")
            val repeated = core.repair(RepairRequest(repaired, dryRun = false, output = again, context = context)).orThrow()
            assertTrue(repeated.proposedChanges.isEmpty()); assertTrue(repeated.changesApplied.isEmpty()); assertNull(repeated.operation)
            assertTrue(again.query().orThrow().assetIds.isEmpty())
            val raw = MemoryOutputTransaction(context, "heic-repaired-video")
            core.extract(ExtractRequest(repaired, emptyList(), output = raw, context = context)).orThrow()
            assertEquals(fixture.video, raw.committedAssets().values.single())
            assertEquals(fixture.broken, BinaryReader(input.source, context).readExactly(0uL, fixture.broken.size.toUInt()).orThrow())
        }
    }
    @Test fun issueAllowlistAndExplicitModeNeverAuthorizeOtherEdits(): Unit = runImmediate {
        val f = fixture(); val input = SourceSet.Single(source(f.broken, "heic-repair-filter"))
        val output = MemoryOutputTransaction(context, "heic-repair-filtered")
        val result = core.repair(RepairRequest(input, allowedIssueCodes = listOf(IssueCode("INVALID_PRESENTATION_TIMESTAMP")), dryRun = false, output = output, context = context)).orThrow()
        assertTrue(result.blocked.isNotEmpty()); assertTrue(result.proposedChanges.isEmpty()); assertNull(result.operation)
        assertTrue(output.query().orThrow().assetIds.isEmpty())
        val unsupported = core.repair(RepairRequest(input, mode = RepairMode.ExplicitRemux, output = output, context = context))
        assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), assertIs<CoreResult.Failure>(unsupported).error.code)
    }
    @Test fun mixedMetadataWrongWidthAndMultiplePhysicalMoviesCannotBeGuessed(): Unit = runImmediate {
        val f = fixture()
        val mixed = f.broken.toByteArray().also { bytes ->
            val packet = bytes.copyOfRange(f.extent.offset.toInt(), f.extent.endExclusive.toInt()).decodeToString()
            val changed = packet.replace("MotionPhotoVersion", "MotionPhotoPrivate").encodeToByteArray()
            assertEquals(f.extent.length.toInt(), changed.size); changed.copyInto(bytes, f.extent.offset.toInt())
        }
        val multiple = f.broken.toByteArray() + GoogleFixtures.box("mpvd", f.video.toByteArray())
        val ordinary = BinaryReader(source(Bytes(HeifFixtures.plain()), "repair-short-field-image"), context)
        val append = HeifXmpAppender.prepare(ordinary, GoogleDirectoryWriter.heic(1uL, 0L, context)).orThrow()
        val privateTx = MemoryOutputTransaction(context, "repair-short-field-fixture")
        val handle = privateTx.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/heic")).orThrow()
        append.write(ordinary, BinaryWriter(handle.sink, context)).orThrow(); handle.sink.close().orThrow(); privateTx.prepare().orThrow()
        val staged = privateTx.openStaged(handle.id).orThrow()
        val image = BinaryReader(staged, context).readExactly(0uL, staged.size().orThrow().toUInt()).orThrow()
        staged.close(); privateTx.abort().orThrow()
        val shortWidth = Bytes(image.toByteArray() + GoogleFixtures.box("mpvd", f.video.toByteArray()))
        for ((index, bytes) in listOf(Bytes(mixed), Bytes(multiple), shortWidth).withIndex()) {
            val output = MemoryOutputTransaction(context, "heic-repair-no-guess-$index")
            val request = RepairRequest(SourceSet.Single(source(bytes, "heic-repair-unknown-$index")), dryRun = false, output = output, context = context)
            val result = core.repair(request)
            if (result is CoreResult.Success) { assertTrue(result.value.blocked.isNotEmpty()); assertNull(result.value.operation) }
            else assertIs<CoreResult.Failure>(result)
            assertTrue(output.query().orThrow().assetIds.isEmpty())
        }
    }
    @Test fun stagingTamperFailsWholeFileProofAndAbortsInsteadOfPublishing(): Unit = runImmediate {
        val f = fixture(); val base = MemoryOutputTransaction(context, "heic-repair-hostile-staging")
        val output = object : OutputTransaction by base {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                val staged = base.openStaged(id).orThrow()
                val identity = staged.identity().orThrow()
                val bytes = BinaryReader(staged, context).readExactly(0uL, identity.size.toUInt()).orThrow().toByteArray()
                staged.close(); bytes[0] = (bytes[0].toInt() xor 1).toByte()
                return CoreResult.Success(MemoryBinarySource(Bytes(bytes), identity.id))
            }
        }
        val result = core.repair(RepairRequest(SourceSet.Single(source(f.broken, "heic-repair-tamper-input")), dryRun = false, output = output, context = context))
        assertEquals(IssueCode("POSTCONDITION_FAILED"), assertIs<CoreResult.Failure>(result).error.code)
        assertEquals(TransactionState.Aborted, base.query().orThrow().state); assertTrue(base.committedAssets().isEmpty())
    }
}
