package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** The backend can write one isolated staging asset; only the normal Core publication path commits. */
internal object RemuxOperations {
    private data class Prepared(val session: SourceSession, val reader: BinaryReader, val video: VideoStructure, val metadata: RemuxVerification.Metadata)

    private suspend fun prepare(request: RemuxRequest, backend: MediaBackend?): Prepared {
        RequestValidation.validate(request).orThrow()
        if (request.target !in setOf(VideoContainer.Mp4, VideoContainer.Mov)) fail("CAPABILITY_UNSUPPORTED", "Only bounded MP4/MOV remux targets are implemented", Stage.Plan)
        if (Guarantee.ExactExtraction in request.policy.requiredGuarantees) fail("PRESERVATION_REQUIREMENT_FAILED", "Remux does not promise whole-file exact extraction", Stage.Plan)
        if (backend?.capabilities()?.operations?.none { it.operation == Operation.Remux && it.implementation in setOf(Implementation.Experimental, Implementation.Supported) } != false)
            fail("CAPABILITY_UNSUPPORTED", "No configured backend implements remux", Stage.Plan)
        val caps = request.output.capabilities()
        if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic || request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Remux requires independently readable atomic output", Stage.Plan)
        val session = SourceSession.open(request.video.input, request.context, ParseBudget(request.context)).orThrow()
        if (request.video.snapshot != null && request.video.snapshot != session.snapshot) fail("SOURCE_CHANGED", "Remux snapshot is stale", Stage.Plan)
        val resource = request.video.resourceId?.let { id -> session.inspection.layout.resources.singleOrNull { it.id == id }
            ?: fail("INVALID_ARGUMENT", "Remux resource does not belong to the input") }
        val original: BinaryReader
        val range: ByteRange
        if (resource != null) {
            if (resource.kind != ResourceKind.Video) fail("INVALID_ARGUMENT", "Remux requires a video resource")
            val extent = resource.extents.singleOrNull() ?: fail("CAPABILITY_UNSUPPORTED", "Remux requires one contiguous video extent")
            val binding = session.bindings.singleOrNull { videoId(it.protocol) == resource.id }
                ?: fail("CAPABILITY_UNSUPPORTED", "Video resource has no unique verified binding")
            if (!binding.structurallyValid || binding.video != extent.range || binding.protocol !in session.videos)
                fail("CAPABILITY_UNSUPPORTED", "Video resource is not structurally verified")
            original = session.readerFor(extent.source); range = extent.range
        } else {
            if (request.video.input !is SourceSet.Single || session.bindings.isNotEmpty()) fail("INVALID_ARGUMENT", "Select an explicit video resource for a live-photo source")
            original = session.readers.single(); range = ByteRange(0uL, original.identity().orThrow().size)
        }
        val reader = BinaryReader(RangeSource(original, range), request.context)
        val video = BmffVideoProbe(reader).probe(ByteRange(0uL, range.length)).orThrow()
        val metadata = RemuxVerification.metadata(reader, video)
        session.recheck()
        return Prepared(session, reader, video, metadata)
    }

    suspend fun plan(request: RemuxRequest, backend: MediaBackend?): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request, backend)
        ExecutionPlan(prepared.session.snapshot, steps = listOf(
            PlanStep(Stage.Remux, listOf(Operation.Remux), listOfNotNull(request.video.resourceId), "Streamcopy all verified tracks; never encode"),
            PlanStep(Stage.Verify, emptyList(), emptyList(), "Independently verify every sample, configuration, timestamp and classified metadata before atomic publication")),
            predictedPreservation = PreservationReport(changes = listOf(containerChange(prepared.video.container, request.target))),
            capabilities = CapabilitySet(Availability.Conditional, listOf(capability(backend))))
    }

    fun capability(backend: MediaBackend? = null): CapabilityEntry = CapabilityEntry(Operation.Remux, Implementation.Experimental,
        conditions = listOf(Condition(ConditionOperator.Equals, "input", Value.Text("bounded-single-mdat-mp4-mov-classified-metadata-only")),
            Condition(ConditionOperator.Equals, "stagingIO", Value.Text("one-sequential-output-core-readback-after-freeze")),
            Condition(ConditionOperator.Equals, "verification", Value.Text("all-tracks-samples-config-dts-pts-edits-and-metadata-unchanged"))) +
            (backend?.capabilities()?.operations?.firstOrNull { it.operation == Operation.Remux }?.conditions ?: emptyList()),
        verification = listOf(Verification.SourceReviewed))

    suspend fun remux(request: RemuxRequest, backend: MediaBackend?): CoreResult<OperationResult> = attempt {
        val prepared = prepare(request, backend)
        val processor = backend ?: fail("CAPABILITY_UNSUPPORTED", "No configured remux backend", Stage.Plan)
        val spec = OutputAssetSpec(AssetRole.MotionVideo, recommendedName = if (request.target == VideoContainer.Mov) "video.mov" else "video.mp4",
            mime = if (request.target == VideoContainer.Mov) "video/quicktime" else "video/mp4")
        var backendResult: BackendResult? = null
        val asset = StagedAsset(spec, container = request.target,
            write = { writer ->
                val staging = SingleAssetStaging(spec, writer, request.context)
                val job = BackendJob(Operation.Remux, listOf(ResourceRef(SourceSet.Single(prepared.reader.source))),
                    remuxContainer = request.target, policy = request.policy, context = request.context, destination = staging)
                val result = processor.remux(job)
                prepared.session.recheck()
                val declared = result.orThrow()
                backendResult = declared
                staging.validateResult(declared)
                if (declared.execution.isEmpty() || declared.execution.any { it.stage != Stage.Remux || it.transcoded || !it.remuxed || it.backendId !in processor.capabilities().backendIds } ||
                    declared.tracks.isNotEmpty() || declared.timelineMap.isNotEmpty() || declared.actualFrameTime != null || declared.actualFrameIndex != null || declared.actualFrameTrack != null || declared.issues.any { it.severity == Severity.Error })
                    fail("POSTCONDITION_FAILED", "Backend did not return a valid no-encoding remux execution", Stage.Verify)
                if (declared.execution.any { it.inputFacts != videoFacts(prepared.video) || it.outputFacts != declared.media.singleOrNull() })
                    fail("POSTCONDITION_FAILED", "Backend execution facts contradict verified input or declared output", Stage.Verify)
            },
            verify = { id, reader ->
                val identity = reader.identity().orThrow()
                val actual = BmffVideoProbe(reader).probe(ByteRange(0uL, identity.size)).orThrow()
                if (actual.container != request.target) fail("POSTCONDITION_FAILED", "Backend produced the wrong container", Stage.Verify)
                val digest = RemuxVerification.verify(prepared.reader, prepared.video, reader, actual)
                val metadata = RemuxVerification.metadata(reader, actual)
                RemuxVerification.verifyMetadata(prepared.metadata, metadata)
                val result = backendResult ?: fail("POSTCONDITION_FAILED", "Missing backend result", Stage.Verify)
                if (result.assets.single().byteLength != identity.size || result.media != listOf(videoFacts(actual)))
                    fail("POSTCONDITION_FAILED", "Backend declarations contradict independently verified media", Stage.Verify)
                val snapshot = Snapshot(listOf(identity), identity.generation)
                val report = ValidationReport(Verdict.Warning, Coverage.Partial, listOf(
                    CheckResult("remux.structure", Layer.Structure, Verdict.Valid, Coverage.Complete),
                    CheckResult("remux.samples-timeline-metadata", Layer.Media, Verdict.Valid, Coverage.Partial)), snapshot = snapshot)
                AssetVerification(report, listOf(
                    GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable),
                    GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.NotApplicable),
                    GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.Verified, digest, digest, "Every encoded sample/configuration, track, DTS/PTS/duration, dependency and edit mapping independently compared"),
                    GuaranteeRecord(id, Guarantee.MetadataPreserving, GuaranteeOutcome.Verified, prepared.metadata.digest, metadata.digest, "Classified header/sample-entry semantics preserved; private boxes and unreferenced data refused")))
            })
        val result = publish(request.output, request.policy, request.context, prepared.session.readers, listOf(asset),
            listOf(containerChange(prepared.video.container, request.target))).orThrow()
        val declared = backendResult ?: fail("POSTCONDITION_FAILED", "Missing verified backend execution", Stage.Verify)
        result.copy(execution = declared.execution + result.execution, issues = result.issues + declared.issues)
    }

    private fun containerChange(before: VideoContainer, after: VideoContainer): Change = Change("videoContainer", Value.Text(before.name), Value.Text(after.name), "Explicitly requested remux, without encoding", true)

    /** Sequential-only staging. Reading is unavailable until Core freezes and independently verifies output. */
    private class SingleAssetStaging(val expected: OutputAssetSpec, val writer: BinaryWriter, val context: Context) : StagingArea {
        private val id = AssetId("backend-remux")
        private var created = false
        private var closed = false
        private var length = 0uL
        override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = attempt {
            checkCancelled(context)
            if (created || spec.role != expected.role || spec.mime != expected.mime) fail("POSTCONDITION_FAILED", "Backend may create only the requested video staging asset", Stage.Remux)
            created = true
            OutputHandle(id, object : BinarySink {
                override suspend fun write(bytes: Bytes): CoreResult<UInt> = attempt {
                    if (closed) fail("IO_WRITE_FAILED", "Backend staging writer is closed", Stage.Remux)
                    writer.writeAll(bytes).orThrow(); length = checkedAdd(length, bytes.size.toULong()); bytes.size.toUInt()
                }
                override suspend fun seek(offset: ULong): CoreResult<Unit> = unsupported()
                override suspend fun truncate(length: ULong): CoreResult<Unit> = unsupported()
                override suspend fun flush(): CoreResult<Unit> = attempt<Unit> { checkCancelled(context); if (closed) fail("IO_WRITE_FAILED", "Backend staging writer is closed") }
                override suspend fun close(): CoreResult<Unit> { closed = true; return CoreResult.Success(Unit) }
            })
        }
        override suspend fun openForRead(id: AssetId): CoreResult<BinarySource> = unsupported()
        private fun unsupported(): CoreResult.Failure = CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Remux, "Sequential staging is read back only by Core after prepare"))
        fun validateResult(result: BackendResult) {
            val asset = result.assets.singleOrNull()
            if (!created || !closed || length == 0uL || asset == null || asset.id != id || asset.role != expected.role || asset.mime != expected.mime || asset.byteLength != length)
                fail("POSTCONDITION_FAILED", "Backend asset declarations do not match isolated staging writes", Stage.Verify)
        }
    }
}
