package livephoto.core

public data class Change(
    public val selector: String,
    public val before: Value? = null,
    public val after: Value? = null,
    public val reason: String,
    public val requested: Boolean,
)

public data class GuaranteeRecord(
    public val assetId: AssetId,
    public val guarantee: Guarantee,
    public val outcome: GuaranteeOutcome,
    public val sourceDigest: Digest? = null,
    public val outputDigest: Digest? = null,
    public val proof: String? = null,
)

public data class PreservationReport(
    public val records: List<GuaranteeRecord> = emptyList(),
    public val changes: List<Change> = emptyList(),
    public val issues: List<Issue> = emptyList(),
)

public class MutationPolicy(
    public val preservation: PreservationPolicy = PreservationPolicy.BestEffortWithReport,
    requiredGuarantees: List<Guarantee> = emptyList(),
    public val loss: LossPolicy = LossPolicy.RejectUnrequested,
    allowedLosses: List<String> = emptyList(),
    // AGENTS.md / PHASE.md explicitly require this stricter default than the spec's WhenRequired.
    public val transcode: TranscodePolicy = TranscodePolicy.Forbid,
    public val conflicts: ConflictPolicy = ConflictPolicy.Reject,
    public val authority: EvidenceId? = null,
    public val existingOutput: ExistingOutput = ExistingOutput.Fail,
    public val atomicity: Atomicity = Atomicity.AssetSetRequired,
) {
    public val requiredGuarantees: List<Guarantee> = frozenList(requiredGuarantees)
    public val allowedLosses: List<String> = frozenList(allowedLosses)
}

public data class MediaPreference(
    public val imageFormat: ImageFormat? = null,
    public val videoContainer: VideoContainer? = null,
    public val videoCodec: VideoCodec? = null,
    public val audioCodec: AudioCodec? = null,
    public val dynamicRange: DynamicRangePolicy = DynamicRangePolicy.Preserve,
)

public data class ImageEncoding(
    public val format: ImageFormat,
    public val quality: UInt? = null,
    public val dynamicRange: DynamicRangePolicy = DynamicRangePolicy.Preserve,
    public val orientation: OrientationPolicy = OrientationPolicy.PreserveTransform,
) {
    init {
        require(format != ImageFormat.Unknown) { "Unknown image format cannot be an encoding target" }
        require(quality == null || quality <= 100u) { "Image quality must be in 0..100" }
    }
}

public data class VideoEncoding(
    public val codec: VideoCodec,
    public val container: VideoContainer,
    public val bitrate: ULong? = null,
    public val width: UInt? = null,
    public val height: UInt? = null,
    public val frameRatePolicy: FrameRatePolicy = FrameRatePolicy.Preserve,
    public val constantFrameRate: RationalRate? = null,
    public val dynamicRange: DynamicRangePolicy = DynamicRangePolicy.Preserve,
) {
    init {
        require(codec != VideoCodec.Unknown && container != VideoContainer.Unknown) { "Unknown format cannot be an encoding target" }
        require((frameRatePolicy == FrameRatePolicy.ExplicitConstantRate) == (constantFrameRate != null)) { "CFR policy and rate must agree" }
        require(width == null || width > 0u)
        require(height == null || height > 0u)
        require(bitrate == null || bitrate > 0uL)
    }
}

public data class TrimSpec(
    public val range: TimeRange,
    public val mode: TrimMode = TrimMode.LosslessPreferred,
    public val boundary: BoundaryPolicy = BoundaryPolicy.CoverRequestedRange,
    public val preroll: PrerollPolicy = PrerollPolicy.RejectHiddenRetainedContent,
    public val exactTolerance: Time = Time.Zero,
    public val maxBoundaryDeviation: Time? = null,
    public val keyOutside: KeyOutsidePolicy = KeyOutsidePolicy.Reject,
) {
    init {
        require(range.start >= Time.Zero && range.end > range.start) { "Trim range must be nonnegative and nonempty" }
        require(exactTolerance.value >= 0) { "Exact tolerance must be nonnegative" }
        require(maxBoundaryDeviation == null || maxBoundaryDeviation.value >= 0) { "Boundary deviation must be nonnegative" }
    }
}

public data class EditSpec(
    public val trim: TrimSpec? = null,
    public val keyPosition: CoverPosition? = null,
    public val replacementFrame: CoverPosition? = null,
)
