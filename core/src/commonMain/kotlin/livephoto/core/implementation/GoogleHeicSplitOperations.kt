package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.heif.*

internal object GoogleHeicSplitOperations {
    suspend fun preflight(request: SplitRequest, session: SourceSession, budget: ParseBudget): HeifMotionCleanup {
        val cleanup = HeifMotionCleanup.prepare(session, budget).orThrow()
        val video = session.bindings.single().video!!
        if (checkedAdd(cleanup.byteLength, video.length) > request.context.limits.maxOutputBytes)
            fail("RESOURCE_LIMIT_EXCEEDED", "HEIC clean image/video set exceeds output budget", Stage.Plan)
        val caps = request.output.capabilities()
        if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic ||
            request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "HEIC clean split needs requested asset-set publication guarantees", Stage.Plan)
        session.recheck()
        return cleanup
    }
    suspend fun split(request: SplitRequest, session: SourceSession, budget: ParseBudget): CoreResult<OperationResult> = attempt {
        val cleanup = preflight(request, session, budget)
        val image = StagedAsset(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/heic"), ImageFormat.Heic,
            write = { writer -> cleanup.write(session.reader, writer).orThrow() }, verify = { id, reader ->
                cleanup.verify(session.reader, reader).orThrow()
                val staged = SourceSession.open(SourceSet.Single(reader.source), request.context, ParseBudget(request.context)).orThrow()
                if (staged.bindings.isNotEmpty() || staged.heifItems?.infos?.size != 1)
                    fail("POSTCONDITION_FAILED", "Clean HEIC retains motion authority or unclassified dependencies", Stage.Verify)
                AssetVerification(validateSession(staged, listOf(Layer.Structure)).orThrow(), listOf(
                    GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable),
                    GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.NotApplicable),
                    GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.Verified, proof = "Original primary logical item/configuration/dimensions and every unrequested retained byte independently verified"),
                    GuaranteeRecord(id, Guarantee.MetadataPreserving, GuaranteeOutcome.Verified, proof = "Only canonical owned motion XMP item/cdsc/isolated mdat/mpvd removed; retained table fields relocated at fixed widths; mixed/private/unknown dependencies rejected")))
            })
        val binding = session.bindings.single()
        val video = session.videos.getValue(binding.protocol)
        val motion = GoogleOperations.rawAsset(session, binding.video!!, AssetRole.MotionVideo, "video/mp4", request.context, video.container)
        publish(request.output, request.policy, request.context, session.readers, listOf(image, motion), listOf(
            Change("heif:motion-xmp-item", reason = "Remove the completely owned canonical motion packet and its item table entry/cdsc association", requested = true),
            Change("google:mpvd", reason = "Remove verified motion resource from ordinary HEIC; publish complete movie separately", requested = true))).orThrow()
    }
}
