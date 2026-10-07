package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.heif.*
import livephoto.core.google.*

/** Metadata-only editing of a fully owned binding; never extracts/encodes a replacement image. */
internal object GoogleHeicKeyOperations {
    private data class Prepared(val create: CreateRequest, val changes: List<Change>)
    private suspend fun prepare(request: SetKeyRequest, session: SourceSession): Prepared {
        val budget = ParseBudget(request.context)
        val cleanup = HeifMotionCleanup.prepare(session, budget).orThrow()
        val binding = session.bindings.single()
        val key = selectKey(session.videos.getValue(binding.protocol), request.position)
        val timestamp = microseconds(key.position!!)
        val image = cleanup.view(session.reader, budget).orThrow()
        val video = RangeSource(session.reader, binding.video!!)
        val create = CreateRequest(image, video, binding.selector, edits = EditSpec(keyPosition = request.position),
            policy = request.policy, output = request.output, context = request.context)
        val changes = listOf(Change("{$CAMERA_URI}MotionPhotoPresentationTimestampUs",
            binding.key.rawFields.singleOrNull()?.rawValue, Value.Text(timestamp.toString()),
            "Set requested authoritative timestamp; rebuild owned tables as necessary without changing primary coding or complete movie bytes", true))
        session.recheck()
        return Prepared(create, changes)
    }
    suspend fun plan(request: SetKeyRequest, session: SourceSession): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request, session)
        val plan = GoogleHeicCreateOperations.plan(prepared.create).orThrow()
        session.recheck()
        plan.copy(snapshot = session.snapshot, steps = plan.steps.map { step ->
            if (step.stage == Stage.WriteProtocol) step.copy(required = listOf(Operation.SetKey),
                reason = "Update owned HEIC key metadata; fixed-width relocation and independent unchanged primary/movie proof") else step
        }, predictedPreservation = PreservationReport(changes = prepared.changes),
            capabilities = CapabilitySet(Availability.Conditional, listOf(DefaultLivePhotoCore().getProtocolCapabilities(plan.target!!).operations.single { it.operation == Operation.SetKey })))
    }
    suspend fun set(request: SetKeyRequest, session: SourceSession): CoreResult<OperationResult> = attempt {
        val prepared = prepare(request, session)
        GoogleHeicCreateOperations.create(prepared.create, session.readers, prepared.changes).orThrow()
    }
}
