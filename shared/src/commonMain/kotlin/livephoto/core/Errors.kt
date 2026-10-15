package livephoto.core

public data class Location(
    public val source: SourceId? = null,
    public val range: ByteRange? = null,
    public val resource: ResourceId? = null,
    public val selector: String? = null,
    public val track: TrackId? = null,
)

public data class Evidence(
    public val id: EvidenceId,
    public val kind: EvidenceKind,
    public val description: String,
    public val location: Location? = null,
    public val reference: String? = null,
)

public data class CoreError(
    public val code: IssueCode,
    public val stage: Stage,
    public val message: String,
    public val location: Location? = null,
    public val details: Map<String, Value> = emptyMap(),
    public val recoverability: Recoverability = Recoverability.Never,
)

public data class Issue(
    public val code: IssueCode,
    public val severity: Severity,
    public val layer: Layer,
    public val location: Location? = null,
    public val expected: Value? = null,
    public val observed: Value? = null,
    public val evidenceIds: List<EvidenceId> = emptyList(),
    public val repairability: Repairability = Repairability.Unknown,
)
