package livephoto.core

/** A backend receives staging access only and has no commit/publication authority. */
public interface StagingArea {
    public suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle>
    public suspend fun openForRead(id: AssetId): CoreResult<BinarySource>
}

public data class StagedAsset(
    public val id: AssetId,
    public val role: AssetRole,
    public val mime: String,
    public val byteLength: ULong,
)

public class BackendJob(
    public val operation: Operation,
    inputs: List<ResourceRef>,
    public val trim: TrimSpec? = null,
    public val position: CoverPosition? = null,
    public val imageEncoding: ImageEncoding? = null,
    public val videoEncoding: VideoEncoding? = null,
    public val remuxContainer: VideoContainer? = null,
    public val policy: MutationPolicy = MutationPolicy(),
    public val context: Context,
    public val destination: StagingArea,
) {
    public val inputs: List<ResourceRef> = frozenList(inputs)

    /** Copies through the constructor so caller-owned input lists remain detached. */
    public fun copy(
        operation: Operation = this.operation,
        inputs: List<ResourceRef> = this.inputs,
        trim: TrimSpec? = this.trim,
        position: CoverPosition? = this.position,
        imageEncoding: ImageEncoding? = this.imageEncoding,
        videoEncoding: VideoEncoding? = this.videoEncoding,
        remuxContainer: VideoContainer? = this.remuxContainer,
        policy: MutationPolicy = this.policy,
        context: Context = this.context,
        destination: StagingArea = this.destination,
    ): BackendJob = BackendJob(operation, inputs, trim, position, imageEncoding, videoEncoding, remuxContainer, policy, context, destination)

    /** Structured preflight; a backend must call this before performing any IO or media work. */
    public fun validate(): CoreResult<Unit> {
        if (inputs.size != 1) return invalidJob("Backend operations require exactly one video resource")
        val fieldsCorrect = when (operation) {
            Operation.Trim -> trim != null && position == null && imageEncoding == null && videoEncoding == null && remuxContainer == null
            Operation.Remux -> trim == null && position == null && imageEncoding == null && videoEncoding == null && remuxContainer != null
            Operation.Transcode -> trim == null && position == null && imageEncoding == null && videoEncoding != null && remuxContainer == null
            Operation.ExtractFrame -> trim == null && position != null && imageEncoding != null && videoEncoding == null && remuxContainer == null
            else -> false
        }
        if (!fieldsCorrect) return invalidJob("Backend fields do not match the requested operation")
        if (remuxContainer == VideoContainer.Unknown) return invalidJob("Unknown container cannot be a remux target")
        if (operation == Operation.Transcode && policy.transcode == TranscodePolicy.Forbid) {
            return CoreResult.Failure(CoreError(IssueCode("TRANSCODE_NOT_AUTHORIZED"), Stage.Plan, "Transcoding is forbidden by policy", recoverability = Recoverability.WithDifferentPolicy))
        }
        if (videoEncoding?.codec == VideoCodec.Other) {
            return CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Plan, "Other codec encoding requires a representable codec configuration", recoverability = Recoverability.AfterImplementation))
        }
        return CoreResult.Success(Unit)
    }
}

private fun invalidJob(message: String): CoreResult.Failure = CoreResult.Failure(
    CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Plan, message),
)

public data class BackendResult(
    public val assets: List<StagedAsset>,
    public val media: List<MediaFacts>,
    public val execution: List<ExecutionRecord>,
    public val tracks: List<TrackTrim> = emptyList(),
    public val timelineMap: List<TimelineSegment> = emptyList(),
    public val actualFrameTime: Time? = null,
    public val actualFrameIndex: ULong? = null,
    public val actualFrameTrack: TrackId? = null,
    public val issues: List<Issue> = emptyList(),
)

public interface MediaBackend {
    public fun capabilities(): MediaCapabilities
    public suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts>
    public suspend fun trim(job: BackendJob): CoreResult<BackendResult>
    public suspend fun remux(job: BackendJob): CoreResult<BackendResult>
    public suspend fun transcode(job: BackendJob): CoreResult<BackendResult>
    public suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult>
}
