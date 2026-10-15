package livephoto.core

public data class Region(
    public val id: ResourceId,
    public val source: SourceId,
    public val range: ByteRange,
    public val kind: ResourceKind,
    public val owner: ProtocolId? = null,
)

public data class Resource(
    public val id: ResourceId,
    public val kind: ResourceKind,
    public val extents: List<Region>,
    public val standalone: Boolean,
    public val sharedWith: List<ResourceId> = emptyList(),
)

public data class Relationship(
    public val kind: RelationshipKind,
    public val from: ResourceId,
    public val to: ResourceId,
    public val details: Map<String, Value> = emptyMap(),
)

public data class Layout(
    public val sources: List<SourceIdentity>,
    public val regions: List<Region>,
    public val resources: List<Resource>,
    public val relationships: List<Relationship> = emptyList(),
)

public data class ProtocolSelector(public val protocol: ProtocolId, public val profile: ProfileId? = null)

public data class Match(
    public val target: ProtocolSelector,
    public val strength: MatchStrength,
    public val evidence: List<Evidence> = emptyList(),
    public val issues: List<Issue> = emptyList(),
    public val resourceIds: List<ResourceId> = emptyList(),
)

public data class DetectionResult(
    public val disposition: Disposition,
    public val primaryProtocol: ProtocolSelector? = null,
    public val matches: List<Match>,
    public val issues: List<Issue> = emptyList(),
    public val snapshot: Snapshot,
)

public data class MetadataEntry(
    public val selector: String,
    public val raw: ResourceId? = null,
    public val value: Value? = null,
    public val owner: Ownership,
    public val location: Location,
    public val origin: FactOrigin,
)

public data class ColorFacts(
    public val primaries: String? = null,
    public val transfer: String? = null,
    public val matrix: String? = null,
    public val range: String? = null,
    public val bitDepth: UInt? = null,
    public val icc: ResourceId? = null,
    public val hdr: List<MetadataEntry> = emptyList(),
)

public data class TrackFacts(
    public val id: TrackId,
    public val kind: String,
    public val videoCodec: VideoCodec? = null,
    public val audioCodec: AudioCodec? = null,
    public val codecString: String? = null,
    public val codecConfiguration: Bytes? = null,
    public val profile: String? = null,
    public val level: String? = null,
    public val duration: Time? = null,
    public val frameCount: ULong? = null,
    public val width: UInt? = null,
    public val height: UInt? = null,
    public val transform: Value? = null,
    public val color: ColorFacts? = null,
    public val origin: FactOrigin = FactOrigin.Unknown,
)

public data class MediaFacts(
    public val imageFormat: ImageFormat? = null,
    public val videoContainer: VideoContainer? = null,
    public val mime: String? = null,
    public val width: UInt? = null,
    public val height: UInt? = null,
    public val orientation: Value? = null,
    public val duration: Time? = null,
    public val tracks: List<TrackFacts> = emptyList(),
    public val auxiliary: List<ResourceId> = emptyList(),
    public val color: ColorFacts? = null,
    public val coverage: Coverage = Coverage.NotRun,
    public val issues: List<Issue> = emptyList(),
)

public data class RawKeyField(
    public val selector: String,
    public val rawValue: Value,
    public val unit: String? = null,
    public val location: Location,
)

public data class KeyPhotoResult(
    public val position: Time? = null,
    public val frameIndex: ULong? = null,
    public val trackId: TrackId? = null,
    public val source: KeySource = KeySource.Unknown,
    public val rawFields: List<RawKeyField> = emptyList(),
    public val issues: List<Issue> = emptyList(),
)

public data class PairingFacts(
    public val imageIdentifier: String? = null,
    public val videoIdentifier: String? = null,
    public val matches: Boolean? = null,
    public val evidence: List<Evidence> = emptyList(),
)

public data class InspectionResult(
    public val snapshot: Snapshot,
    public val detection: DetectionResult,
    public val layout: Layout,
    public val media: List<MediaFacts>,
    public val metadata: List<MetadataEntry>,
    public val keyPhoto: KeyPhotoResult,
    public val pairing: PairingFacts? = null,
    public val issues: List<Issue> = emptyList(),
)

public data class CheckResult(
    public val id: String,
    public val layer: Layer,
    public val verdict: Verdict,
    public val coverage: Coverage,
    public val issues: List<Issue> = emptyList(),
)

public data class ValidationReport(
    public val verdict: Verdict,
    public val coverage: Coverage,
    public val checks: List<CheckResult>,
    public val issues: List<Issue> = emptyList(),
    public val snapshot: Snapshot,
)
