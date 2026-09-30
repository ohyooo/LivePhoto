package livephoto.core

/** Entry point shared by applications, CLI and future UI adapters. Handles are borrowed. */
public interface LivePhotoCore {
    public suspend fun detect(request: ReadRequest): CoreResult<DetectionResult>
    public suspend fun inspect(request: ReadRequest): CoreResult<InspectionResult>
    public suspend fun analyze(request: AnalyzeRequest): CoreResult<AnalysisResult>
    public suspend fun validate(request: ValidationRequest): CoreResult<ValidationReport>
    public suspend fun validateStructure(request: ValidationRequest): CoreResult<ValidationReport>
    public suspend fun validateProtocol(request: ValidationRequest): CoreResult<ValidationReport>
    public suspend fun validateMedia(request: ValidationRequest): CoreResult<ValidationReport>
    public suspend fun create(request: CreateRequest): CoreResult<OperationResult>
    public suspend fun convert(request: ConvertRequest): CoreResult<OperationResult>
    public suspend fun extract(request: ExtractRequest): CoreResult<OperationResult>
    public suspend fun split(request: SplitRequest): CoreResult<OperationResult>
    public suspend fun repair(request: RepairRequest): CoreResult<RepairResult>
    public suspend fun getKeyPhotoPosition(request: ReadRequest): CoreResult<KeyPhotoResult>
    public suspend fun setKeyPhotoPosition(request: SetKeyRequest): CoreResult<OperationResult>
    public suspend fun extractFrame(request: ExtractFrameRequest): CoreResult<FrameResult>
    public suspend fun replacePrimaryImageFromFrame(request: ReplaceRequest): CoreResult<OperationResult>
    public suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts>
    public suspend fun trim(request: TrimRequest): CoreResult<TrimResult>
    public suspend fun remux(request: RemuxRequest): CoreResult<OperationResult>
    public suspend fun transcode(request: TranscodeRequest): CoreResult<OperationResult>
    public fun getProtocolCapabilities(target: ProtocolSelector): ProtocolCapabilities
    public fun getMediaCapabilities(): MediaCapabilities
    public suspend fun getOperationCapabilities(request: MutationRequest): CoreResult<CapabilitySet>
    public suspend fun plan(request: MutationRequest): CoreResult<ExecutionPlan>
}
