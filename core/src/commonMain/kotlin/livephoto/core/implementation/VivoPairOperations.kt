package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

internal object VivoPairOperations {
    fun preflightClean(session: SourceSession): Unit {
        val pair = session.legacyPair ?: fail("PAIR_ASSET_MISSING", "A confirmed legacy pair is required")
        if (pair.uuid.range.endExclusive != pair.videoTail.sourceIdentity.size)
            fail("CAPABILITY_UNSUPPORTED", "Only terminal owned UUID removal is implemented; other positions require offset relocation", Stage.Plan)
        val owned = setOf("com.android.camera.livephoto", "com.android.camera.imageTime", "com.vivo.gallery.livePhoto.newCoverTime")
        if (listOf(pair.imageTail, pair.videoTail).any { it.json.entries.keys.any { field -> field !in owned } })
            fail("UNSAFE_METADATA_REWRITE", "Unknown legacy JSON metadata cannot be discarded by clean", Stage.Plan)
        if (session.jpeg?.primary?.endExclusive != pair.imageTail.range.offset)
            fail("UNSAFE_METADATA_REWRITE", "The complete legacy tail must directly follow the JPEG", Stage.Plan)
    }

    suspend fun split(request: SplitRequest, session: SourceSession): CoreResult<OperationResult> = attempt {
        val pair = session.legacyPair ?: fail("PAIR_ASSET_MISSING", "A confirmed legacy pair is required")
        val clean = request.mode == SplitMode.Clean
        if (clean) preflightClean(session)
        val imageRange = if (clean) session.jpeg!!.primary else ByteRange(0uL, pair.imageTail.sourceIdentity.size)
        val videoRange = ByteRange(0uL, if (clean) pair.uuid.range.offset else pair.videoTail.sourceIdentity.size)
        val image = GoogleOperations.rawAsset(session, imageRange, AssetRole.PrimaryImage, "image/jpeg", request.context, inputReader = pair.imageReader)
        val video = GoogleOperations.rawAsset(session, videoRange, AssetRole.MotionVideo, "video/mp4", request.context, VideoContainer.Mp4, pair.videoReader)
        if (!clean) return@attempt publish(request.output, request.policy, request.context, session.readers, listOf(image, video)).orThrow()
        val budget = ParseBudget(request.context)
        val cleanImageSession = SourceSession.open(SourceSet.Single(RangeSource(pair.imageReader, imageRange)), request.context, budget).orThrow()
        if (cleanImageSession.bindings.isNotEmpty() || cleanImageSession.jpeg?.trailing?.length != 0uL)
            fail("UNSAFE_METADATA_REWRITE", "Legacy tail removal would leave another live image binding", Stage.Plan)
        val cleanVideo = BmffVideoProbe(pair.videoReader, budget).probe(videoRange).orThrow()
        val originalVideo = session.videos.getValue(ProtocolIds.VivoLegacy)
        if (cleanVideo.tracks != originalVideo.tracks) fail("POSTCONDITION_FAILED", "Terminal UUID removal changed sample structure", Stage.Verify)
        val imageDigest = sha256Range(pair.imageReader, imageRange).orThrow()
        val videoDigest = sha256Range(pair.videoReader, videoRange).orThrow()
        val checkedImage = image.copy(verify = { id, reader ->
            val verification = image.verify(id, reader)
            val staged = SourceSession.open(SourceSet.Single(reader.source), request.context, budget).orThrow()
            if (staged.bindings.isNotEmpty() || staged.jpeg?.trailing?.length != 0uL) fail("POSTCONDITION_FAILED", "Clean image retains live metadata", Stage.Verify)
            verification.copy(guarantees = listOf(
                GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable),
                GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.Verified, imageDigest, imageDigest, "Complete JPEG bytes unchanged; only confirmed terminal binding removed"),
                GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.NotApplicable),
                GuaranteeRecord(id, Guarantee.MetadataPreserving, if (session.jpeg!!.hasExif || session.jpeg.segments.any { it.payloadKind == livephoto.core.jpeg.AppPayloadKind.Unknown && it.marker in 0xe0..0xef }) GuaranteeOutcome.Unknown else GuaranteeOutcome.Verified,
                    proof = "All ordinary JPEG bytes retained; opaque associations to removed binding are not proven")))
        })
        val checkedVideo = video.copy(verify = { id, reader ->
            val verification = video.verify(id, reader)
            val size = reader.identity().orThrow().size
            val staged = BmffVideoProbe(reader, budget).probe(ByteRange(0uL, size)).orThrow()
            if (staged.tracks != cleanVideo.tracks) fail("POSTCONDITION_FAILED", "Staged clean video samples changed", Stage.Verify)
            val boxes = BmffReader(reader, budget).readBoxes(ByteRange(0uL, size)).orThrow()
            if (boxes.any { it.type == "uuid" && it.userType == Bytes("vivoMediaExtInfo".encodeToByteArray()) }) fail("POSTCONDITION_FAILED", "Owned UUID remains", Stage.Verify)
            verification.copy(guarantees = listOf(
                GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable),
                GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.NotApplicable),
                GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.Verified, videoDigest, videoDigest, "Encoded samples/configuration/timeline and offsets unchanged"),
                GuaranteeRecord(id, Guarantee.MetadataPreserving, GuaranteeOutcome.Unknown,
                    proof = "Ordinary boxes retained byte-for-byte; private references inside unparsed boxes to the removed UUID remain unproven")))
        })
        val changes = listOf(
            Change("vivo:legacy:image-tail", before = Value.Text("Confirmed terminal legacy binding"), reason = "Remove owned JPEG tail", requested = true),
            Change("vivo:legacy:video-uuid", before = Value.Text("Confirmed terminal owned UUID"), reason = "Remove owned video binding without shifting sample offsets", requested = true))
        publish(request.output, request.policy, request.context, session.readers, listOf(checkedImage, checkedVideo), changes).orThrow()
    }
}
