package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.huawei.*
import livephoto.core.oplus.*
import livephoto.core.samsung.*
import livephoto.core.vivo.*
import livephoto.core.jpeg.*
import livephoto.core.apple.*

internal object ConvertOperations {
    suspend fun plan(request: ConvertRequest, backend: MediaBackend? = null): CoreResult<ExecutionPlan> = attempt {
        RequestValidation.validate(request).orThrow()
        val budget = ParseBudget(request.context)
        val session = SourceSession.open(request.input, request.context, budget).orThrow()
        val prepared = prepare(request, session, budget, allowTrim = request.edits?.trim != null)
        val targetPlan = if (prepared != null) {
            val create = CreateRequest(prepared.first, prepared.second, request.target, request.preference, request.edits, policy = request.policy, output = request.output, context = request.context)
            if (request.edits?.trim != null) CreateTrimOperations.plan(create, backend, session.inspection.keyPhoto).orThrow() else GoogleOperations.plan(create).orThrow()
        } else null
        val outputCaps = request.output.capabilities()
        if (!outputCaps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !outputCaps.assetSetAtomic ||
            request.policy.existingOutput == ExistingOutput.Replace && !outputCaps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Conversion needs the requested transaction guarantees", Stage.Plan)
        session.recheck()
        ExecutionPlan(session.snapshot, request.target,
            listOf(PlanStep(Stage.Clean, listOf(Operation.ConvertFrom), session.inspection.layout.resources.map { it.id },
                if (prepared == null) "Copy unchanged same-target carriers" else "Remove confirmed source bindings through bounded read-only views")) + targetPlan?.steps.orEmpty(),
            PreservationReport(), CapabilitySet(Availability.Conditional, listOf(CapabilityEntry(Operation.ConvertFrom, Implementation.Experimental,
                lifecycle = if (session.legacyPair != null || session.bindings.any { it.protocol == ProtocolIds.Fusion }) Lifecycle.Legacy else Lifecycle.Active,
                verification = listOf(Verification.SourceReviewed)))))
    }

    suspend fun convert(request: ConvertRequest, backend: MediaBackend? = null): CoreResult<OperationResult> = attempt {
        RequestValidation.validate(request).orThrow()
        val budget = ParseBudget(request.context)
        val session = SourceSession.open(request.input, request.context, budget).orThrow()
        val prepared = prepare(request, session, budget, allowTrim = request.edits?.trim != null)
        if (prepared == null) {
            val selectedReaders = session.applePair?.let { listOf(it.imageReader, it.videoReader) } ?: session.legacyPair?.let { listOf(it.imageReader, it.videoReader) } ?: session.readers
            val assets = selectedReaders.map { reader ->
                val identity = reader.identity().orThrow()
                val role = if (session.legacyPair == null && session.applePair == null) AssetRole.Composite else if (reader === session.reader) AssetRole.PrimaryImage else AssetRole.MotionVideo
                GoogleOperations.rawAsset(session, ByteRange(0uL, identity.size), role,
                    if (role == AssetRole.MotionVideo) session.videos.values.firstOrNull()?.let { videoFacts(it).mime } ?: "application/octet-stream" else "image/jpeg", request.context,
                    container = if (role == AssetRole.MotionVideo) session.videos.values.firstOrNull()?.container else null, inputReader = reader)
            }
            return@attempt publish(request.output, request.policy, request.context, session.readers, assets).orThrow()
        }
        val changes = if (session.applePair != null) ApplePairOperations.changes(session) else session.inspection.metadata.filter { it.owner == Ownership.SourceProtocol }.map { Change(it.selector, it.value, null, "Remove confirmed source binding before conversion", true) } +
            if (session.legacyPair != null) listOf(Change("vivo:legacy:image-tail", reason = "Remove confirmed source tail", requested = true), Change("vivo:legacy:video-uuid", reason = "Remove terminal owned UUID", requested = true)) else emptyList()
        val create = CreateRequest(prepared.first, prepared.second, request.target, request.preference, request.edits, policy = request.policy, output = request.output, context = request.context)
        val metadataUnproven = session.jpeg!!.hasExif || session.sef?.records?.any { it.type !in setOf(0x0a30.toUShort(), 0x0a31.toUShort()) } == true || session.legacyPair != null
        if (request.edits?.trim != null) CreateTrimOperations.create(create, backend, session.readers, changes, session.inspection.keyPhoto, metadataUnproven).orThrow()
        else GoogleOperations.create(create, session.readers, changes, session.inspection.keyPhoto, metadataUnproven).orThrow()
    }

    /** null means an explicit same-target PreserveAsIs copy without edits. */
    suspend fun prepare(request: ConvertRequest, session: SourceSession, budget: ParseBudget, allowTrim: Boolean = false): Pair<BinarySource, BinarySource>? {
        val detection = session.inspection.detection
        if (detection.disposition == Disposition.Ambiguous) fail("AMBIGUOUS_LAYOUT", "Conversion needs a unique trusted source graph", Stage.Plan)
        val selector = detection.primaryProtocol ?: fail("SOURCE_NOT_LIVE", "No live source protocol is available", Stage.Plan)
        val binding = session.bindings.single { it.selector == selector }
        if (!binding.structurallyValid || binding.protocol !in session.videos || binding.issues.any { it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT") })
            fail("UNKNOWN_PROTOCOL_VARIANT", "Conversion source has incomplete or conflicting authority", Stage.Plan)
        val same = selector.protocol == request.target.protocol && (request.target.profile == null || request.target.profile == selector.profile)
        if (same && request.sameTarget == SameTargetPolicy.PreserveAsIs) {
            if (request.edits != null || request.preference != MediaPreference()) fail("INVALID_ARGUMENT", "PreserveAsIs does not apply requested media edits", Stage.Plan)
            return null
        }
        if (request.target.protocol !in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2)) fail("CAPABILITY_UNSUPPORTED", "This conversion batch implements Google JPEG targets", Stage.Plan)
        if (request.edits?.trim != null && !allowTrim || request.edits?.replacementFrame != null) fail("CAPABILITY_UNSUPPORTED", "Conversion media edits require backend orchestration", Stage.Plan)
        if (session.applePair != null) {
            val clean = AppleClean.prepare(session, budget).orThrow()
            return clean.image to clean.video
        }
        val pair = session.legacyPair
        val image: BinarySource
        val video: BinarySource
        if (pair != null) {
            VivoPairOperations.preflightClean(session)
            image = RangeSource(pair.imageReader, session.jpeg!!.primary)
            video = RangeSource(pair.videoReader, ByteRange(0uL, pair.uuid.range.offset))
        } else {
            if (session.gainMaps.isNotEmpty()) fail("GAINMAP_PRESERVATION_UNAVAILABLE", "Conversion cannot discard auxiliary resources", Stage.Plan)
            val rewrite = if (session.sef != null) {
                val clean = SamsungJpegWriter.cleanPlan(session, request.context, budget).orThrow()
                if (clean.suffix.length != 0uL) fail("CAPABILITY_UNSUPPORTED", "Conversion with ordinary SEF requires a target suffix relocation implementation", Stage.Plan)
                clean.image
            } else if (binding.protocol == ProtocolIds.Huawei) HuaweiJpegWriter.cleanPlan(session).orThrow()
            else if (binding.protocol == ProtocolIds.Oplus) OplusJpegWriter.cleanPlan(session, request.context, budget).orThrow()
            else if (binding.protocol == ProtocolIds.VivoModern) VivoJpegWriter.cleanPlan(session, request.context, budget).orThrow()
            else GoogleJpegWriter.cleanPlan(session, request.context).orThrow()
            image = JpegProjectionSource.create(session, rewrite).orThrow()
            video = RangeSource(session.reader, binding.video!!)
        }
        val cleanImage = SourceSession.open(SourceSet.Single(image), request.context, budget).orThrow()
        if (cleanImage.bindings.isNotEmpty()) fail("UNSAFE_METADATA_REWRITE", "Conversion image retains another source binding", Stage.Plan)
        val exifRewrite = session.bindings.any { it.protocol == ProtocolIds.Oplus }
        if (codingDigest(session) != codingDigest(cleanImage) ||
            ordinaryDigest(session, verifiedExifRewrite = exifRewrite) != ordinaryDigest(cleanImage, verifiedExifRewrite = exifRewrite))
            fail("POSTCONDITION_FAILED", "Source-binding cleanup changed ordinary image metadata or coding bytes", Stage.Verify)
        return image to video
    }
}
