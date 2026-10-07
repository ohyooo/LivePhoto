package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.heif.*

/** Same-profile conversion: PreserveAsIs is exact; Normalize is an explicitly authorized, independently proven rebuild. */
internal object GoogleHeicConvertOperations {
    private data class Prepared(val session: SourceSession, val create: CreateRequest?)
    private suspend fun prepare(request: ConvertRequest): Prepared {
        RequestValidation.validate(request).orThrow()
        val budget = ParseBudget(request.context)
        val session = SourceSession.open(request.input, request.context, budget).orThrow()
        val binding = session.bindings.singleOrNull { it.selector == request.target }
            ?: fail("CAPABILITY_UNSUPPORTED", "HEIC conversion currently requires a same-profile Google V2 heic source; no implicit image encoding", Stage.Plan)
        if (session.heifItems == null || session.bindings.size != 1 || !binding.structurallyValid || binding.protocol !in session.videos ||
            session.inspection.issues.any { it.severity == Severity.Error } || binding.issues.any { it.layer == Layer.Protocol })
            fail("UNKNOWN_PROTOCOL_VARIANT", "HEIC conversion needs one independently verified binding/movie", Stage.Plan)
        if (request.sameTarget == SameTargetPolicy.PreserveAsIs) {
            if (request.edits != null || request.preference != MediaPreference()) fail("INVALID_ARGUMENT", "PreserveAsIs cannot apply media edits or preferences", Stage.Plan)
            if (session.reader.identity().orThrow().size > request.context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Preserved HEIC exceeds output budget", Stage.Plan)
            return Prepared(session, null)
        }
        if (request.edits?.trim != null || request.edits?.replacementFrame != null)
            fail("CAPABILITY_UNSUPPORTED", "HEIC normalization does not implement trim or primary-image encoding", Stage.Plan)
        val cleanup = HeifMotionCleanup.prepare(session, budget).orThrow()
        val image = cleanup.view(session.reader, budget).orThrow()
        val video = RangeSource(session.reader, binding.video!!)
        val create = CreateRequest(image, video, request.target, request.preference, request.edits, policy = request.policy, output = request.output, context = request.context)
        GoogleHeicCreateOperations.plan(create, binding.key).orThrow()
        session.recheck()
        return Prepared(session, create)
    }
    suspend fun plan(request: ConvertRequest): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request)
        val caps = request.output.capabilities()
        if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic ||
            request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "HEIC conversion needs requested publication guarantees", Stage.Plan)
        prepared.session.recheck()
        ExecutionPlan(prepared.session.snapshot, request.target, listOf(PlanStep(Stage.WriteProtocol,
            listOf(Operation.ConvertFrom, Operation.ConvertTo), prepared.session.inspection.layout.resources.map { it.id },
            if (prepared.create == null) "Byte-identical same-target carrier copy without metadata normalization" else "Verified read-only cleanup views; preserve source key unless explicitly edited; rebuild only requested binding"),
            PlanStep(Stage.Verify, listOf(Operation.Validate), emptyList(), "Independent retained-media proof and original/derived input identity guards before final atomic publication")),
            PreservationReport(), CapabilitySet(Availability.Conditional, DefaultLivePhotoCore().getProtocolCapabilities(request.target).operations.filter { it.operation in setOf(Operation.ConvertFrom, Operation.ConvertTo) }))
    }
    suspend fun convert(request: ConvertRequest): CoreResult<OperationResult> = attempt {
        val prepared = prepare(request)
        val create = prepared.create
        if (create == null) {
            val identity = prepared.session.reader.identity().orThrow()
            val asset = GoogleOperations.rawAsset(prepared.session, ByteRange(0uL, identity.size), AssetRole.Composite, "image/heic", request.context,
                prepared.session.videos.values.single().container)
            publish(request.output, request.policy, request.context, prepared.session.readers, listOf(asset)).orThrow()
        } else {
            GoogleHeicCreateOperations.create(create, prepared.session.readers, listOf(
                Change("heif:motion-binding-normalize", reason = "Explicit same-target normalization through proven cleanup views; ordinary media bytes preserved", requested = true)),
                prepared.session.bindings.single().key).orThrow()
        }
    }
}
