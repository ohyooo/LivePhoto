package livephoto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackendJobContractsTest {
    private val context = Context(Limits(maxSpoolBytes = 1024uL, maxOutputBytes = 4096uL))
    private val source: BinarySource = object : BinarySource {
        override suspend fun identity(): CoreResult<SourceIdentity> = error("Preflight must not access the source")
        override suspend fun size(): CoreResult<ULong> = error("Preflight must not access the source")
        override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = error("Preflight must not access the source")
        override suspend fun close(): Unit = error("Preflight must not close borrowed sources")
    }
    private val staging: StagingArea = object : StagingArea {
        override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = error("Preflight must not create output")
        override suspend fun openForRead(id: AssetId): CoreResult<BinarySource> = error("Preflight must not create output")
    }
    private val video = ResourceRef(SourceSet.Single(source), ResourceId("video"))
    private val transaction: OutputTransaction = object : OutputTransaction {
        override fun capabilities(): OutputCapabilities = error("Syntactic preflight must not inspect output")
        override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = error("Preflight must not write")
        override suspend fun prepare(): CoreResult<Unit> = error("Preflight must not write")
        override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> = error("Preflight must not read")
        override suspend fun commit(): CoreResult<Receipt> = error("Preflight must not publish")
        override suspend fun abort(): CoreResult<Unit> = error("Preflight must not write")
        override suspend fun query(): CoreResult<Receipt> = error("Preflight must not access output")
    }

    @Test
    fun matchingBackendJobsPreflightWithoutReadingOrWriting() {
        val trim = job(Operation.Trim).copy(trim = TrimSpec(TimeRange(Time.Zero, Time(1, 1u))))
        val remux = job(Operation.Remux).copy(remuxContainer = VideoContainer.Mov)
        val extract = job(Operation.ExtractFrame).copy(position = CoverPosition.FrameIndex(0uL), imageEncoding = ImageEncoding(ImageFormat.Jpeg))
        val transcode = job(Operation.Transcode).copy(
            videoEncoding = VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4),
            policy = MutationPolicy(transcode = TranscodePolicy.Explicit),
        )
        for (valid in listOf(trim, remux, extract, transcode)) {
            assertIs<CoreResult.Success<Unit>>(valid.validate())
        }
    }

    @Test
    fun backendRejectsExtraFieldsOrAnotherOperationBeforeAnyIo() {
        val remux = job(Operation.Remux).copy(remuxContainer = VideoContainer.Mp4)
        assertFailure("INVALID_ARGUMENT", remux.copy(position = CoverPosition.FrameIndex(0uL)).validate())
        assertFailure("INVALID_ARGUMENT", remux.copy(imageEncoding = ImageEncoding(ImageFormat.Jpeg)).validate())
        assertFailure("INVALID_ARGUMENT", job(Operation.Trim).validate())
        assertFailure("INVALID_ARGUMENT", job(Operation.Create).validate())
        assertFailure("INVALID_ARGUMENT", remux.copy(inputs = emptyList()).validate())
        assertFailure("INVALID_ARGUMENT", remux.copy(inputs = listOf(video, video)).validate())
        assertFailure("INVALID_ARGUMENT", remux.copy(remuxContainer = VideoContainer.Unknown).validate())
    }

    @Test
    fun explicitlyRequestingTranscodeDoesNotOverrideDefaultForbid() {
        val transcode = job(Operation.Transcode).copy(videoEncoding = VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4))
        val error = assertFailure("TRANSCODE_NOT_AUTHORIZED", transcode.validate())
        assertEquals(Recoverability.WithDifferentPolicy, error.recoverability)
        assertEquals(Stage.Plan, error.stage)
    }

    @Test
    fun unrepresentableOtherCodecIsUnsupportedEvenWithTranscodingPermission() {
        val transcode = job(Operation.Transcode).copy(
            videoEncoding = VideoEncoding(VideoCodec.Other, VideoContainer.Mp4),
            policy = MutationPolicy(transcode = TranscodePolicy.Explicit),
        )
        assertFailure("CAPABILITY_UNSUPPORTED", transcode.validate())
    }

    @Test
    fun candidateSetOwnsItsListButBorrowsEachSource() {
        val input = mutableListOf(source)
        val candidates = SourceSet.Candidates(input)
        input.clear()
        assertEquals(listOf(source), candidates.sources)
        try {
            (candidates.sources as? MutableList<BinarySource>)?.clear()
        } catch (_: UnsupportedOperationException) {
            // Immutable collection views are allowed.
        }
        assertEquals(listOf(source), candidates.sources)
    }

    @Test
    fun repairDryRunNeedsNoTransactionButMutationRequiresOne() {
        val dryRun = RepairRequest(input = SourceSet.Single(source), context = context)
        assertIs<CoreResult.Success<Unit>>(RequestValidation.validate(dryRun))
        assertFailure("INVALID_ARGUMENT", RequestValidation.validate(dryRun.copy(dryRun = false)))
        assertIs<CoreResult.Success<Unit>>(RequestValidation.validate(dryRun.copy(dryRun = false, output = transaction)))
    }

    @Test
    fun conflictingMutationPoliciesCannotSilentlyExpandAuthority() {
        val split = SplitRequest(input = SourceSet.Single(source), output = transaction, context = context)
        assertEquals(SplitMode.Clean, split.mode)
        assertIs<CoreResult.Success<Unit>>(RequestValidation.validate(split))
        assertFailure("INVALID_ARGUMENT", RequestValidation.validate(split.copy(policy = MutationPolicy(conflicts = ConflictPolicy.ExplicitAuthority))))
        assertFailure("INVALID_ARGUMENT", RequestValidation.validate(split.copy(policy = MutationPolicy(authority = EvidenceId("authority")))))
        assertFailure("INVALID_ARGUMENT", RequestValidation.validate(split.copy(policy = MutationPolicy(allowedLosses = listOf("gps")))))
        assertIs<CoreResult.Success<Unit>>(RequestValidation.validate(split.copy(policy = MutationPolicy(loss = LossPolicy.AllowListed, allowedLosses = listOf("gps")))))
    }

    @Test
    fun applicationPreflightRefusesUnauthorizedTranscodeAndUnknownRemuxContainer() {
        val transcode = TranscodeRequest(video, VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4), output = transaction, context = context)
        assertFailure("TRANSCODE_NOT_AUTHORIZED", RequestValidation.validate(transcode))
        val remux = RemuxRequest(video, VideoContainer.Unknown, output = transaction, context = context)
        assertFailure("INVALID_ARGUMENT", RequestValidation.validate(remux))
    }

    private fun job(operation: Operation): BackendJob = BackendJob(
        operation = operation,
        inputs = listOf(video),
        context = context,
        destination = staging,
    )

    private fun assertFailure(code: String, result: CoreResult<Unit>): CoreError {
        val failure = assertIs<CoreResult.Failure>(result)
        assertEquals(IssueCode(code), failure.error.code)
        return failure.error
    }
}
