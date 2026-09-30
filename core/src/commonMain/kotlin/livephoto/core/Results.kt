package livephoto.core

public data class OutputAsset(
    public val id: AssetId,
    public val role: AssetRole,
    public val readableSource: BinarySource? = null,
    public val accessReference: String? = null,
    public val byteLength: ULong,
    public val mime: String,
    public val imageFormat: ImageFormat? = null,
    public val videoContainer: VideoContainer? = null,
    public val digest: Digest,
    public val relatedAssets: List<AssetId> = emptyList(),
    public val recommendedName: String? = null,
) {
    init {
        require(readableSource != null || !accessReference.isNullOrBlank()) { "An output asset must be accessible" }
        require(mime.isNotBlank())
    }
}

/** Successful outputs describe the entire committed set, including Apple image/video pairs. */
public class MediaOutput(assets: List<OutputAsset>, public val receipt: Receipt) {
    public val assets: List<OutputAsset> = frozenList(assets)
    init {
        require(receipt.state == TransactionState.Committed) { "MediaOutput must refer to committed assets" }
        require(this.assets.isNotEmpty())
        val ids = this.assets.map { it.id }
        require(ids.distinct().size == ids.size) { "Duplicate output asset" }
        require(ids.toSet() == receipt.assetIds.toSet()) { "Output assets must match the receipt" }
    }
}

public data class ExecutionRecord(
    public val stage: Stage,
    public val backendId: String? = null,
    public val reason: String,
    public val transcoded: Boolean,
    public val remuxed: Boolean,
    public val hardwareUsed: Boolean? = null,
    public val inputFacts: MediaFacts? = null,
    public val outputFacts: MediaFacts? = null,
)

public data class OperationResult(
    public val output: MediaOutput,
    public val validation: ValidationReport,
    public val preservation: PreservationReport,
    public val execution: List<ExecutionRecord> = emptyList(),
    public val keyPhoto: KeyPhotoResult? = null,
    public val issues: List<Issue> = emptyList(),
)

public data class RepairResult(
    public val issuesBefore: List<Issue>,
    public val proposedChanges: List<Change>,
    public val changesApplied: List<Change>,
    public val issuesAfter: List<Issue>,
    public val operation: OperationResult? = null,
    public val blocked: List<Issue> = emptyList(),
)

public data class FrameResult(
    public val operation: OperationResult,
    public val requested: CoverPosition,
    public val actualTime: Time,
    public val actualFrameIndex: ULong? = null,
    public val trackId: TrackId,
)

public data class TrackTrim(
    public val trackId: TrackId,
    public val presented: TimeRange,
    public val encoded: TimeRange,
    public val timelineMap: List<TimelineSegment>,
    public val transcoded: Boolean,
    public val samplesPreserved: Boolean,
)

public data class TrimResult(
    public val operation: OperationResult,
    public val requestedStart: Time,
    public val requestedEnd: Time,
    public val actualStart: Time,
    public val actualEnd: Time,
    public val tracks: List<TrackTrim>,
    public val wasTranscoded: Boolean,
    public val wasRemuxed: Boolean,
    public val wasBitstreamPreserved: Boolean,
    public val retainedHiddenContent: Boolean,
    public val exactTolerance: Time,
    public val timelineMap: List<TimelineSegment>,
)

public data class PlanStep(
    public val stage: Stage,
    public val required: List<Operation>,
    public val inputs: List<ResourceId>,
    public val reason: String,
)

public data class ExecutionPlan(
    public val snapshot: Snapshot,
    public val target: ProtocolSelector? = null,
    public val steps: List<PlanStep>,
    public val predictedPreservation: PreservationReport,
    public val capabilities: CapabilitySet,
    public val issues: List<Issue> = emptyList(),
)
