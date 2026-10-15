package livephoto.core

public sealed interface MutationRequest

public data class ReadRequest(public val input: SourceSet, public val context: Context)
public class ValidationRequest(
    public val input: SourceSet,
    layers: List<Layer> = listOf(Layer.Structure, Layer.Protocol, Layer.Media),
    requiredChecks: List<String> = emptyList(),
    public val target: ProtocolSelector? = null,
    public val context: Context,
) {
    public val layers: List<Layer> = frozenList(layers)
    public val requiredChecks: List<String> = frozenList(requiredChecks)
}
public class AnalyzeRequest(
    public val input: SourceSet,
    layers: List<Layer> = listOf(Layer.Structure, Layer.Protocol, Layer.Media),
    public val context: Context,
) { public val layers: List<Layer> = frozenList(layers) }

public data class CreateRequest(
    public val image: BinarySource,
    public val video: BinarySource,
    public val target: ProtocolSelector,
    public val preference: MediaPreference = MediaPreference(),
    public val edits: EditSpec? = null,
    public val sourceBindings: SourceBindingPolicy = SourceBindingPolicy.RejectAlreadyLive,
    public val policy: MutationPolicy = MutationPolicy(),
    public val output: OutputTransaction,
    public val context: Context,
) : MutationRequest

public data class ConvertRequest(
    public val input: SourceSet,
    public val target: ProtocolSelector,
    public val preference: MediaPreference = MediaPreference(),
    public val edits: EditSpec? = null,
    public val sameTarget: SameTargetPolicy = SameTargetPolicy.PreserveAsIs,
    public val policy: MutationPolicy = MutationPolicy(),
    public val output: OutputTransaction,
    public val context: Context,
) : MutationRequest

public class ExtractRequest(
    public val input: SourceSet,
    resources: List<ResourceId>,
    public val snapshot: Snapshot? = null,
    public val includeRawCarrier: Boolean = false,
    public val output: OutputTransaction,
    public val context: Context,
) : MutationRequest { public val resources: List<ResourceId> = frozenList(resources) }

public data class SplitRequest(
    public val input: SourceSet,
    public val mode: SplitMode = SplitMode.Clean,
    public val policy: MutationPolicy = MutationPolicy(),
    public val output: OutputTransaction,
    public val context: Context,
) : MutationRequest

/** An empty allowedIssueCodes list allows all proven-safe repairs; dryRun never writes. */
public class RepairRequest(
    public val input: SourceSet,
    public val mode: RepairMode = RepairMode.SafeMetadataOnly,
    allowedIssueCodes: List<IssueCode> = emptyList(),
    public val authority: EvidenceId? = null,
    public val dryRun: Boolean = true,
    public val policy: MutationPolicy = MutationPolicy(),
    public val output: OutputTransaction? = null,
    public val context: Context,
) : MutationRequest {
    public val allowedIssueCodes: List<IssueCode> = frozenList(allowedIssueCodes)

    public fun copy(
        input: SourceSet = this.input,
        mode: RepairMode = this.mode,
        allowedIssueCodes: List<IssueCode> = this.allowedIssueCodes,
        authority: EvidenceId? = this.authority,
        dryRun: Boolean = this.dryRun,
        policy: MutationPolicy = this.policy,
        output: OutputTransaction? = this.output,
        context: Context = this.context,
    ): RepairRequest = RepairRequest(input, mode, allowedIssueCodes, authority, dryRun, policy, output, context)
}

public data class SetKeyRequest(
    public val input: SourceSet,
    public val position: CoverPosition,
    public val policy: MutationPolicy = MutationPolicy(),
    public val output: OutputTransaction,
    public val context: Context,
) : MutationRequest

public data class ExtractFrameRequest(
    public val video: ResourceRef,
    public val position: CoverPosition,
    public val encoding: ImageEncoding,
    public val output: OutputTransaction,
    public val context: Context,
) : MutationRequest

public data class ReplaceRequest(
    public val input: SourceSet,
    public val position: CoverPosition,
    public val encoding: ImageEncoding,
    public val updateKeyPosition: Boolean = false,
    public val policy: MutationPolicy = MutationPolicy(),
    public val output: OutputTransaction,
    public val context: Context,
) : MutationRequest

public data class ProbeRequest(
    public val media: ResourceRef,
    public val decodeCheck: Boolean = false,
    public val context: Context,
)

public data class TrimRequest(
    public val video: ResourceRef,
    public val spec: TrimSpec,
    public val policy: MutationPolicy = MutationPolicy(),
    public val output: OutputTransaction,
    public val context: Context,
) : MutationRequest

public data class RemuxRequest(
    public val video: ResourceRef,
    public val target: VideoContainer,
    public val policy: MutationPolicy = MutationPolicy(),
    public val output: OutputTransaction,
    public val context: Context,
) : MutationRequest

public data class TranscodeRequest(
    public val video: ResourceRef,
    public val encoding: VideoEncoding,
    public val policy: MutationPolicy = MutationPolicy(),
    public val output: OutputTransaction,
    public val context: Context,
) : MutationRequest
