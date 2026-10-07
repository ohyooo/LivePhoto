package livephoto.core

import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.*

/**
 * Content-based Core facade. Inputs are borrowed and operations execute serial staging IO.
 * Protocol handlers never publish; the transaction adapter owns final identity/atomicity guards.
 */
public class DefaultLivePhotoCore(private val backend: MediaBackend? = null) : LivePhotoCore {
    override suspend fun detect(request: ReadRequest): CoreResult<DetectionResult> = attempt { session(request).inspection.detection }
    override suspend fun inspect(request: ReadRequest): CoreResult<InspectionResult> = attempt { session(request).inspection }
    override suspend fun getKeyPhotoPosition(request: ReadRequest): CoreResult<KeyPhotoResult> = attempt { session(request).inspection.keyPhoto }
    override suspend fun analyze(request: AnalyzeRequest): CoreResult<AnalysisResult> = attempt {
        val session = session(ReadRequest(request.input, request.context))
        AnalysisResult(session.inspection, validateSession(session, request.layers).orThrow(),
            CapabilitySet(if (session.inspection.detection.primaryProtocol != null) Availability.Conditional else Availability.Unsupported,
                session.inspection.detection.primaryProtocol?.let { getProtocolCapabilities(it).operations } ?: emptyList()))
    }
    override suspend fun validate(request: ValidationRequest): CoreResult<ValidationReport> = attempt {
        validateSession(session(ReadRequest(request.input, request.context)), request.layers, request.requiredChecks, request.target).orThrow()
    }
    override suspend fun validateStructure(request: ValidationRequest): CoreResult<ValidationReport> = validate(ValidationRequest(request.input, listOf(Layer.Structure), request.requiredChecks, request.target, request.context))
    override suspend fun validateProtocol(request: ValidationRequest): CoreResult<ValidationReport> = validate(ValidationRequest(request.input, listOf(Layer.Protocol), request.requiredChecks, request.target, request.context))
    override suspend fun validateMedia(request: ValidationRequest): CoreResult<ValidationReport> = validate(ValidationRequest(request.input, listOf(Layer.Media), request.requiredChecks, request.target, request.context))

    override suspend fun create(request: CreateRequest): CoreResult<OperationResult> = when {
        request.edits?.replacementFrame != null -> CreateReplacementOperations.create(request, backend)
        request.edits?.trim != null -> CreateTrimOperations.create(request, backend)
        else -> GoogleOperations.create(request)
    }
    override suspend fun extract(request: ExtractRequest): CoreResult<OperationResult> = GoogleOperations.extract(request)
    override suspend fun split(request: SplitRequest): CoreResult<OperationResult> = GoogleOperations.split(request)
    override suspend fun convert(request: ConvertRequest): CoreResult<OperationResult> = ConvertOperations.convert(request, backend)
    override suspend fun repair(request: RepairRequest): CoreResult<RepairResult> = RepairOperations.repair(request)
    override suspend fun setKeyPhotoPosition(request: SetKeyRequest): CoreResult<OperationResult> = KeyMetadataOperations.set(request)
    override suspend fun extractFrame(request: ExtractFrameRequest): CoreResult<FrameResult> = FrameOperations.extract(request, backend)
    override suspend fun replacePrimaryImageFromFrame(request: ReplaceRequest): CoreResult<OperationResult> = ReplaceOperations.replace(request, backend)
    override suspend fun trim(request: TrimRequest): CoreResult<TrimResult> = TrimOperations.trim(request, backend)
    override suspend fun remux(request: RemuxRequest): CoreResult<OperationResult> = RemuxOperations.remux(request, backend)
    override suspend fun transcode(request: TranscodeRequest): CoreResult<OperationResult> = TranscodeOperations.transcode(request, backend)
    override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = ProbeOperations.probe(request, backend)

    override fun getProtocolCapabilities(target: ProtocolSelector): ProtocolCapabilities {
        if (target.protocol == ProtocolIds.Apple) {
            val actual = if (target.profile == null) target.copy(profile = ProfileId("jpeg-mov")) else target
            if (actual.profile !in setOf(ProfileId("jpeg-mov"), ProfileId("jpeg-mp4"))) return ProtocolRegistry.planned().capabilities(actual)
            val reads = setOf(Operation.Detect, Operation.Analyze, Operation.Inspect, Operation.Validate, Operation.ExtractRaw, Operation.GetKey)
            val writes = setOf(Operation.SplitClean, Operation.ConvertFrom) + if (actual.profile == ProfileId("jpeg-mp4")) setOf(Operation.ConvertTo) else emptySet()
            return ProtocolCapabilities(actual, Operation.entries.map { operation -> CapabilityEntry(operation,
                if (operation in reads + writes) Implementation.Experimental else if (operation in setOf(Operation.Create, Operation.ConvertTo)) Implementation.Planned else Implementation.Unsupported,
                conditions = if (operation == Operation.ConvertTo && operation in writes) listOf(
                    Condition(ConditionOperator.Equals, "assembly", Value.Text("unique-live-source-classified-jpeg-without-existing-exif-mpf-extended-or-unknown-app-except-exact-canonical-jfif-mp4-movie-known-or-explicit-key-no-trim-replacement")),
                    Condition(ConditionOperator.Equals, "publication", Value.Text("independent-two-asset-reread-and-asset-set-atomic-no-encoding"))) else listOf(Condition(ConditionOperator.Equals, "sourceContent", Value.Text("exact-id-jpeg-quicktime-meta-pair")),
                    Condition(ConditionOperator.Equals, "timedMetadata", Value.Text("bounded-mebx-samples-with-exact-edit-mapping"))) +
                    if (operation in writes && operation != Operation.ConvertTo) listOf(Condition(ConditionOperator.Equals, "cleanup", Value.Text("cid-only-maker-note-and-dedicated-cid-meta-and-still-time-tracks")),
                        Condition(ConditionOperator.Equals, "preservation", Value.Text("fixed-offset-no-unclassified-track-fields-opaque-associations-unknown"))) +
                        if (operation == Operation.ConvertFrom) listOf(Condition(ConditionOperator.Equals, "target", Value.Text("google-jpeg-single-mdat-or-unchanged-same-target"))) else emptyList() else emptyList(),
                reasons = if (operation in reads + writes) emptyList() else listOf(IssueCode(if (operation in setOf(Operation.Create, Operation.ConvertTo)) "CAPABILITY_PLANNED" else "CAPABILITY_UNSUPPORTED")),
                verification = if (operation in reads + writes) listOf(Verification.SourceReviewed) else emptyList()) })
        }
        if (target.protocol in setOf(ProtocolIds.VivoLegacy, ProtocolIds.Fusion)) {
            val profile = ProfileId(if (target.protocol == ProtocolIds.VivoLegacy) "pair" else "jpeg")
            val actual = if (target.profile == null) target.copy(profile = profile) else target
            if (actual.profile != profile) return ProtocolRegistry.planned().capabilities(actual)
            val implemented = setOf(Operation.Detect, Operation.Analyze, Operation.Inspect, Operation.Validate, Operation.ExtractRaw, Operation.SplitClean, Operation.ConvertFrom, Operation.GetKey)
            return ProtocolCapabilities(actual, Operation.entries.map { operation -> CapabilityEntry(operation,
                if (operation in implemented) Implementation.Experimental else Implementation.Unsupported,
                lifecycle = Lifecycle.Legacy,
                exposure = if (operation in setOf(Operation.Create, Operation.ConvertTo)) Exposure.Hidden else Exposure.Public,
                conditions = listOf(Condition(ConditionOperator.Equals, "profile", Value.Text(if (target.protocol == ProtocolIds.VivoLegacy)
                    "exact-id-jpeg-mp4-pair-clean-only-terminal-owned-uuid" else "author-marker-canonical-sef-verified-vendor-authority"))) +
                    if (operation == Operation.ConvertFrom) listOf(Condition(ConditionOperator.Equals, "target", Value.Text("google-jpeg-no-auxiliary-or-ordinary-sef-relocation"))) else emptyList(),
                reasons = if (operation in implemented) emptyList() else listOf(IssueCode("CAPABILITY_UNSUPPORTED")),
                verification = if (operation in implemented) listOf(Verification.SourceReviewed) else emptyList()) })
        }
        if (target.protocol == ProtocolIds.Huawei) {
            val actual = if (target.profile == null) target.copy(profile = ProfileId("basic60")) else target
            if (actual.profile !in setOf(ProfileId("basic60"), ProfileId("honor-extended"))) return ProtocolRegistry.planned().capabilities(actual)
            val basic = actual.profile == ProfileId("basic60")
            val reads = setOf(Operation.Detect, Operation.Analyze, Operation.Inspect, Operation.Validate, Operation.ExtractRaw, Operation.GetKey)
            val writes = if (basic) setOf(Operation.Create, Operation.SplitClean) else emptySet()
            return ProtocolCapabilities(actual, Operation.entries.map { operation -> CapabilityEntry(operation,
                when { operation in reads -> if (basic) Implementation.Supported else Implementation.Experimental; operation in writes -> Implementation.Experimental; else -> Implementation.Unsupported },
                conditions = listOf(Condition(ConditionOperator.Equals, "sourceContent", Value.Text("jpeg-fixed-sixty-byte-tail")),
                    Condition(ConditionOperator.Equals, "keySemantics", Value.Text("raw-fields-unknown-units"))) +
                    if (operation in writes) listOf(Condition(ConditionOperator.Equals, "rewriteScope", Value.Text("plain-jpeg-mp4-no-gap-no-honor-extensions-no-explicit-key"))) else if (!basic)
                        listOf(Condition(ConditionOperator.Equals, "mediaBinding", Value.Text("unconfirmed-extensions-not-a-pure-video-claim"))) else emptyList(),
                reasons = if (operation in reads + writes) emptyList() else listOf(IssueCode("CAPABILITY_UNSUPPORTED")),
                verification = if (operation in reads + writes) listOf(Verification.SourceReviewed) else emptyList()) })
        }
        if (target.protocol == ProtocolIds.VivoModern) {
            val actual = if (target.profile == null) target.copy(profile = ProfileId("jpeg")) else target
            if (actual.profile != ProfileId("jpeg")) return ProtocolRegistry.planned().capabilities(actual)
            val reads = setOf(Operation.Detect, Operation.Analyze, Operation.Inspect, Operation.Validate, Operation.ExtractRaw, Operation.GetKey)
            val writes = setOf(Operation.Create, Operation.SplitClean, Operation.SetKey, Operation.ConvertFrom, Operation.ConvertTo, Operation.Repair)
            return ProtocolCapabilities(actual, Operation.entries.map { operation -> CapabilityEntry(operation,
                when { operation in reads -> Implementation.Supported; operation in writes -> Implementation.Experimental; else -> Implementation.Unsupported },
                conditions = listOf(Condition(ConditionOperator.Equals, "resourceGraph", Value.Text("complete-jpeg-optional-verified-jpeg-gainmap-mp4"))) +
                    if (operation in setOf(Operation.ConvertFrom, Operation.ConvertTo)) listOf(Condition(ConditionOperator.Equals, "conversionScope", Value.Text("unique-confirmed-source-jpeg-mp4-no-auxiliary-classified-cleanup-and-target-assembly-edits-through-verified-backend-only")))
                    else if (operation == Operation.Repair) listOf(Condition(ConditionOperator.Equals, "repairScope", Value.Text("known-minimal-vivo-version-one-inline-primary-motion-length-only-explicit-zero-padding-no-gainmap-safe-metadata-mode")))
                    else if (operation == Operation.SetKey) listOf(Condition(ConditionOperator.Equals, "rewriteScope", Value.Text("vivo-version-one-single-xmp-complete-video-suffix-no-gainmap-no-unknown-vendor-fields"))) else if (operation == Operation.Create) listOf(Condition(ConditionOperator.Equals, "inputImage", Value.Text("plain-jpeg-no-auxiliary-suffix"))) else if (operation == Operation.SplitClean)
                        listOf(Condition(ConditionOperator.Equals, "auxiliaryDependencies", Value.Text("no-mpf-exif-extended-xmp-relocation"))) else emptyList(),
                reasons = if (operation in reads + writes) emptyList() else listOf(IssueCode("CAPABILITY_UNSUPPORTED")),
                verification = if (operation in reads + writes) listOf(Verification.SourceReviewed) else emptyList()) })
        }
        if (target.protocol == ProtocolIds.Samsung) {
            val actual = if (target.profile == null) target.copy(profile = ProfileId("jpeg-sef-mpv3")) else target
            if (actual.profile !in setOf(ProfileId("jpeg-sef-mpv3"), ProfileId("heic-sef-mpv2"))) return ProtocolRegistry.planned().capabilities(actual)
            val reads = setOf(Operation.Detect, Operation.Analyze, Operation.Inspect, Operation.Validate, Operation.ExtractRaw, Operation.GetKey)
            val jpeg = actual.profile == ProfileId("jpeg-sef-mpv3")
            val writes = if (jpeg) setOf(Operation.Create, Operation.SplitClean, Operation.SetKey, Operation.Repair, Operation.ConvertFrom, Operation.ConvertTo) else emptySet()
            return ProtocolCapabilities(actual, Operation.entries.map { operation -> CapabilityEntry(operation,
                when { operation in reads -> if (jpeg) Implementation.Supported else Implementation.Experimental; operation in writes -> Implementation.Experimental; else -> Implementation.Unsupported },
                conditions = listOf(Condition(ConditionOperator.Equals, "sefGraph", Value.Text("unique-complete-indexed-records-107"))) +
                    if (operation in setOf(Operation.ConvertFrom, Operation.ConvertTo)) listOf(Condition(ConditionOperator.Equals, "conversionScope", Value.Text("jpeg-mpv3-no-ordinary-sef-suffix-classified-cleanup-and-target-assembly-source-domain-key-and-edits-through-verified-backend-only")))
                    else if (operation == Operation.Repair) listOf(Condition(ConditionOperator.Equals, "repairScope", Value.Text("unique-legacy-footer-length-only-safe-metadata-mode"))) else if (operation == Operation.SetKey) listOf(Condition(ConditionOperator.Equals, "rewriteScope", Value.Text("jpeg-canonical-live-only-sef-existing-v2-directory-whole-suffix-preserved"))) else if (operation in writes) listOf(Condition(ConditionOperator.Equals, "rewriteScope", Value.Text("jpeg-mpv3-verified-owned-binding-ordinary-sef-preserved"))) else if (!jpeg)
                        listOf(Condition(ConditionOperator.Equals, "coverage", Value.Text("verified-box-media-ranges-parsed-item-locations-and-links-codec-derived-semantics-decode-not-run"))) +
                            if (operation == Operation.ExtractRaw) listOf(Condition(ConditionOperator.Equals, "rawScope", Value.Text("verified-motion-range-or-parsed-item-extents-in-declared-order-not-an-independent-heic-carrier"))) else emptyList() else emptyList(),
                reasons = if (operation in reads + writes) emptyList() else listOf(IssueCode("CAPABILITY_UNSUPPORTED")),
                verification = if (operation in reads + writes) listOf(Verification.SourceReviewed) else emptyList()) })
        }
        val actual = if (target.profile == null && target.protocol in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2, ProtocolIds.Oplus)) target.copy(profile = ProfileId(if (target.protocol == ProtocolIds.Oplus) "jpeg-no-tail" else "jpeg")) else target
        if (actual.protocol == ProtocolIds.Oplus && actual.profile in setOf(ProfileId("jpeg-no-tail"), ProfileId("oneplus-tail-bearing"))) {
            val reads = setOf(Operation.Detect, Operation.Analyze, Operation.Inspect, Operation.Validate, Operation.ExtractRaw, Operation.GetKey)
            val writes = if (actual.profile == ProfileId("jpeg-no-tail")) setOf(Operation.Create, Operation.SplitClean, Operation.SetKey, Operation.ConvertFrom, Operation.ConvertTo, Operation.Repair) else emptySet()
            return ProtocolCapabilities(actual, Operation.entries.map { operation -> CapabilityEntry(operation,
                when { operation in reads -> Implementation.Supported; operation in writes -> Implementation.Experimental; else -> Implementation.Unsupported },
                conditions = listOf(Condition(ConditionOperator.Equals, "imageContent", Value.Text("jpeg")),
                    Condition(ConditionOperator.Equals, "videoContent", Value.Text("validated-mp4")),
                    Condition(ConditionOperator.Equals, "vendorMarker", Value.Text("standard-exif-usercomment"))) + if (operation == Operation.SetKey)
                    listOf(Condition(ConditionOperator.Equals, "rewriteScope", Value.Text("no-tail-no-auxiliary-no-unknown-vendor-fields-current-cover-only-original-primary-time-preserved"))) else if (operation == Operation.Repair)
                    listOf(Condition(ConditionOperator.Equals, "repairScope", Value.Text("known-marker-owner-version-inline-d-v-lengths-only-complete-video-suffix-no-declared-trailer-no-auxiliary-safe-metadata-mode"))) else if (operation in writes)
                    listOf(Condition(ConditionOperator.Equals, "exifDependencies", Value.Text("verified-safe-subset-no-ordinary-comment-collision"))) else emptyList(),
                reasons = if (operation in reads + writes) emptyList() else listOf(IssueCode("CAPABILITY_UNSUPPORTED")),
                verification = if (operation in reads + writes) listOf(Verification.SourceReviewed) else emptyList()) })
        }
        if (actual.protocol !in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2) || actual.profile != ProfileId("jpeg")) return ProtocolRegistry.planned().capabilities(actual)
        val reads = setOf(Operation.Detect, Operation.Analyze, Operation.Inspect, Operation.Validate, Operation.ExtractRaw, Operation.GetKey)
        val writes = setOf(Operation.Create, Operation.SplitClean, Operation.ConvertFrom, Operation.ConvertTo, Operation.SetKey, Operation.ReplaceCover)
        return ProtocolCapabilities(actual, Operation.entries.map { operation -> CapabilityEntry(operation,
            when { operation in reads -> Implementation.Supported; operation in writes || operation == Operation.Repair -> Implementation.Experimental; else -> Implementation.Planned },
            conditions = if (operation == Operation.ReplaceCover) ReplaceOperations.capability().conditions else if (operation == Operation.Repair) listOf(Condition(ConditionOperator.Equals, "mode", Value.Text("safe-metadata-only")),
                Condition(ConditionOperator.Equals, "evidence", Value.Text(if (actual.protocol == ProtocolIds.GoogleV1) "single-verified-post-jpeg-video-offset-only" else "unique-inline-primary-motion-directory-length-no-padding-or-auxiliary-resources"))) else if (operation in writes) listOf(Condition(ConditionOperator.Equals, "profile", Value.Text("jpeg")),
                Condition(ConditionOperator.Equals, "videoStructure", Value.Text("unfragmented-single-mdat-one-video-at-most-one-aac")),
                Condition(ConditionOperator.Equals, "metadataDependencies", Value.Text("verified-plain-resource-directory-no-unsafe-relocation")))
                else if (operation in reads) listOf(Condition(ConditionOperator.Equals, "profile", Value.Text("jpeg"))) else emptyList(),
            reasons = if (operation in reads + writes || operation == Operation.Repair) emptyList() else listOf(IssueCode("CAPABILITY_PLANNED")),
            verification = if (operation in reads + writes || operation == Operation.Repair) listOf(Verification.SourceReviewed) else emptyList()) })
    }
    override fun getMediaCapabilities(): MediaCapabilities = MediaCapabilities(backend?.capabilities()?.backendIds ?: emptyList(),
        listOf(CapabilityEntry(Operation.Probe, Implementation.Experimental,
            conditions = listOf(Condition(ConditionOperator.Equals, "structuralScope", Value.Text("verified-jpeg-or-bounded-bmff-resource-or-closed-single-hvc1-heic-carrier")),
                Condition(ConditionOperator.Equals, "decodeCheck", Value.Text(if (backend == null) "unsupported-without-decoder" else "injected-backend-with-resource-and-identity-guards"))),
            verification = listOf(Verification.SourceReviewed))) +
            listOf(if (backend?.capabilities()?.operations?.any { it.operation == Operation.Remux && it.implementation in setOf(Implementation.Experimental, Implementation.Supported) } == true)
                RemuxOperations.capability(backend) else CapabilityEntry(Operation.Remux, Implementation.Unsupported, reasons = listOf(IssueCode("CAPABILITY_UNSUPPORTED")))) +
            listOf(if (backend?.capabilities()?.operations?.any { it.operation == Operation.ExtractFrame && it.implementation in setOf(Implementation.Experimental, Implementation.Supported) } == true)
                FrameOperations.capability(backend) else CapabilityEntry(Operation.ExtractFrame, Implementation.Unsupported, reasons = listOf(IssueCode("CAPABILITY_UNSUPPORTED")))) +
            listOf(if (backend?.capabilities()?.operations?.any { it.operation == Operation.Trim && it.implementation in setOf(Implementation.Experimental, Implementation.Supported) } == true)
                TrimOperations.capability(backend) else CapabilityEntry(Operation.Trim, Implementation.Unsupported, reasons = listOf(IssueCode("CAPABILITY_UNSUPPORTED")))) +
            listOf(if (backend?.capabilities()?.operations?.any { it.operation == Operation.Transcode && it.implementation in setOf(Implementation.Experimental, Implementation.Supported) } == true)
                TranscodeOperations.capability() else CapabilityEntry(Operation.Transcode, Implementation.Unsupported, reasons = listOf(IssueCode("CAPABILITY_UNSUPPORTED")))))
    override suspend fun getOperationCapabilities(request: MutationRequest): CoreResult<CapabilitySet> = when (val result = plan(request)) {
        is CoreResult.Success -> CoreResult.Success(result.value.capabilities)
        is CoreResult.Failure -> if (result.error.code.value in setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "RESOURCE_LIMIT_EXCEEDED")) result
            else CoreResult.Success(CapabilitySet(Availability.Unsupported, emptyList(), listOf(Issue(result.error.code, Severity.Warning, Layer.Compatibility, result.error.location))))
    }
    override suspend fun plan(request: MutationRequest): CoreResult<ExecutionPlan> =
        when (request) {
            is ExtractFrameRequest -> FrameOperations.plan(request, backend)
            is TrimRequest -> TrimOperations.plan(request, backend)
            is ReplaceRequest -> ReplaceOperations.plan(request, backend)
            is RemuxRequest -> RemuxOperations.plan(request, backend)
            is TranscodeRequest -> TranscodeOperations.plan(request, backend)
            is ConvertRequest -> ConvertOperations.plan(request, backend)
            is CreateRequest -> when {
                request.edits?.replacementFrame != null -> CreateReplacementOperations.plan(request, backend)
                request.edits?.trim != null -> CreateTrimOperations.plan(request, backend)
                else -> GoogleOperations.plan(request)
            }
            is SetKeyRequest -> KeyMetadataOperations.plan(request)
            is RepairRequest -> RepairOperations.plan(request)
            else -> GoogleOperations.plan(request)
        }

    private suspend fun session(request: ReadRequest): SourceSession = SourceSession.open(request.input, request.context, ParseBudget(request.context)).orThrow()
    private fun <T> unavailable(request: MutationRequest): CoreResult<T> = attemptNow {
        RequestValidation.validate(request).orThrow()
        fail("CAPABILITY_PLANNED", "This operation has not been implemented by this Core", Stage.Plan)
    }
}
