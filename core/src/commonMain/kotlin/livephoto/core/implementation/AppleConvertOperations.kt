package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.apple.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.jpeg.*
import kotlin.uuid.Uuid

/** Finite ConvertTo only. Generic Create remains independently Planned. */
internal object AppleConvertOperations {
    private val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4"))
    private data class Prepared(val image: SourceSession, val rewrite: JpegRewritePlan, val movie: AppleMoviePlan, val unknownMetadata: Boolean)

    fun validateTarget(request: ConvertRequest) {
        if (request.target != target) fail("CAPABILITY_PLANNED", "Apple conversion currently requires the explicit jpeg-mp4 profile", Stage.Plan)
        if (request.edits?.trim != null || request.edits?.replacementFrame != null)
            fail("CAPABILITY_UNSUPPORTED", "Apple conversion media edits do not yet have an assembly preservation proof", Stage.Plan)
    }

    private suspend fun prepare(request: ConvertRequest, original: SourceSession, inputs: Pair<BinarySource, BinarySource>, identifier: String): Prepared {
        validateTarget(request)
        val context = request.context
        val caps = request.output.capabilities()
        if (!caps.assetSetAtomic || !caps.canReadStaged || request.policy.atomicity != Atomicity.AssetSetRequired ||
            request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Apple pair assembly requires verified asset-set atomic publication", Stage.Plan)
        if (context.limits.maxSources < 2u) fail("RESOURCE_LIMIT_EXCEEDED", "Apple pair verification needs two sources", Stage.Plan)
        if (Guarantee.ExactExtraction in request.policy.requiredGuarantees)
            fail("PRESERVATION_REQUIREMENT_FAILED", "Apple conversion constructs new carriers; whole-file exact extraction is not applicable", Stage.Plan)
        val budget = ParseBudget(context)
        val image = SourceSession.open(SourceSet.Single(inputs.first), context, budget).orThrow()
        val jpeg = image.jpeg ?: fail("CAPABILITY_UNSUPPORTED", "Apple conversion currently requires JPEG", Stage.Plan)
        val jfif = ReplaceOperations.canonicalJfif(image)
        if (image.bindings.isNotEmpty() || jpeg.segments.any { it.marker in 0xe0..0xef && it.payloadKind == AppPayloadKind.Unknown && it != jfif })
            fail("UNSAFE_METADATA_REWRITE", "Apple assembly cannot relocate unclassified APP or retain a source image binding", Stage.Plan)
        val videoReader = BinaryReader(inputs.second, context)
        val video = BmffVideoProbe(videoReader, budget).probe(ByteRange(0uL, videoReader.identity().orThrow().size)).orThrow()
        if (video.container != VideoContainer.Mp4 || request.preference.imageFormat?.let { it != ImageFormat.Jpeg } == true ||
            request.preference.videoContainer?.let { it != video.container } == true ||
            request.preference.videoCodec?.let { codec -> video.tracks.filter { it.handler == "vide" }.any { it.codec != codec } } == true ||
            request.preference.audioCodec?.let { codec -> video.tracks.filter { it.handler == "soun" }.any { it.audioCodec != codec } } == true ||
            request.preference.dynamicRange != DynamicRangePolicy.Preserve)
            fail("CAPABILITY_UNSUPPORTED", "Apple assembly only preserves existing JPEG/MP4 media and color semantics", Stage.Plan)
        val key = if (request.edits?.keyPosition != null) selectKey(video, request.edits.keyPosition).position!!
            else original.inspection.keyPhoto.position ?: fail("CAPABILITY_UNSUPPORTED", "Apple conversion requires a known source key or an explicit selected frame", Stage.Plan)
        val proof = AppleImagePatch.create(image.reader, identifier, budget).orThrow()
        val app = JpegRewrite.appSegment(0xe1, proof.payload).orThrow()
        val rewrite = JpegRewrite.plan(jpeg, listOf(JpegPatch(ByteRange(2uL, 0uL), app, appleProof = proof))).orThrow()
        val movie = AppleMovieAssembler.prepare(videoReader, identifier, key, budget).orThrow()
        if (checkedAdd(rewrite.outputLength, movie.byteLength) > context.limits.maxOutputBytes)
            fail("RESOURCE_LIMIT_EXCEEDED", "Apple pair exceeds the shared output budget", Stage.Plan)
        val unknown = original.legacyPair != null || original.jpeg?.hasExif == true
        if (unknown && (request.policy.preservation == PreservationPolicy.Strict || Guarantee.MetadataPreserving in request.policy.requiredGuarantees))
            fail("PRESERVATION_REQUIREMENT_FAILED", "Source cleanup has unproved private metadata dependencies", Stage.Plan)
        original.recheck(); image.recheck(); videoReader.validateIdentity().orThrow()
        return Prepared(image, rewrite, movie, unknown)
    }

    suspend fun plan(request: ConvertRequest, original: SourceSession, inputs: Pair<BinarySource, BinarySource>): CoreResult<ExecutionPlan> = attempt {
        prepare(request, original, inputs, "00000000-0000-4000-8000-000000000000") // Read-only planning, no UUID entropy or output IO.
        ExecutionPlan(original.snapshot, request.target, listOf(
            PlanStep(Stage.WriteProtocol, listOf(Operation.ConvertTo), emptyList(), "Assemble formal image CID and movie CID/timed sample; preserve retained coded tracks"),
            PlanStep(Stage.Verify, listOf(Operation.Validate), emptyList(), "Independently verify both staged assets and their matching pair before one commit")),
            predictedPreservation = PreservationReport(),
            capabilities = CapabilitySet(Availability.Conditional, listOf(CapabilityEntry(Operation.ConvertTo, Implementation.Experimental,
                conditions = listOf(Condition(ConditionOperator.Equals, "profile", Value.Text("jpeg-mp4-no-existing-exif-classified-movie-exact-key-asset-set-atomic")))))))
    }

    suspend fun convert(request: ConvertRequest, original: SourceSession, inputs: Pair<BinarySource, BinarySource>, sourceChanges: List<Change>): CoreResult<OperationResult> = attempt {
        val identifier = try { Uuid.random().toString() } catch (_: Exception) { fail("BACKEND_UNAVAILABLE", "Platform UUID generation failed", Stage.Plan) }
        val prepared = prepare(request, original, inputs, identifier)
        val image = prepared.image
        val movie = prepared.movie
        val context = request.context
        val app = prepared.rewrite.patches.single().replacement
        val size = image.reader.identity().orThrow().size
        var imageId: AssetId? = null
        var imageIdentity: SourceIdentity? = null
        var imageDigest: Digest? = null
        fun records(id: AssetId, imageAsset: Boolean): List<GuaranteeRecord> = listOf(
            GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable, proof = "New target carrier; no file-exact promise"),
            GuaranteeRecord(id, Guarantee.ImageDataPreserving, if (imageAsset) GuaranteeOutcome.Verified else GuaranteeOutcome.NotApplicable, proof = "All original JPEG bytes remain around the new owned APP"),
            GuaranteeRecord(id, Guarantee.BitstreamPreserving, if (imageAsset) GuaranteeOutcome.NotApplicable else GuaranteeOutcome.Verified, proof = "All retained track configuration, sample bytes, order, offsets and timeline remain unchanged"),
            GuaranteeRecord(id, Guarantee.MetadataPreserving, if (prepared.unknownMetadata) GuaranteeOutcome.Unknown else GuaranteeOutcome.Verified, proof = "Exact new owned metadata, exact original ordinary image bytes and classified movie fields; source cleanup proof retained"))
        val imageAsset = StagedAsset(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/jpeg"), ImageFormat.Jpeg,
            write = { JpegRewrite.write(image.reader, it, image.jpeg!!, prepared.rewrite, context).orThrow() },
            verify = { id, reader ->
                val jpeg = JpegParser.parse(reader, ParseBudget(context)).orThrow()
                if (reader.identity().orThrow().size != prepared.rewrite.outputLength || jpeg.primary.length != prepared.rewrite.outputLength ||
                    reader.readExactly(2uL, app.size.toUInt()).orThrow() != app ||
                    sha256Range(image.reader, ByteRange(0uL, 2uL)).orThrow() != sha256Range(reader, ByteRange(0uL, 2uL)).orThrow() ||
                    sha256Range(image.reader, ByteRange(2uL, size - 2uL)).orThrow() != sha256Range(reader, ByteRange(2uL + app.size.toULong(), size - 2uL)).orThrow() ||
                    AppleImageReader.read(reader, jpeg, ParseBudget(context)).orThrow()?.value != identifier)
                    fail("POSTCONDITION_FAILED", "Apple primary image failed exact unrequested-byte/CID verification", Stage.Verify)
                imageId = id; imageIdentity = reader.identity().orThrow()
                imageDigest = sha256Range(reader, ByteRange(0uL, imageIdentity.size)).orThrow()
                val snapshot = SourceSession.open(SourceSet.Single(reader.source), context, ParseBudget(context)).orThrow().snapshot
                AssetVerification(ValidationReport(Verdict.Valid, Coverage.Complete, listOf(CheckResult("apple.image-cid", Layer.Protocol, Verdict.Valid, Coverage.Complete)), snapshot = snapshot), records(id, true))
            })
        val videoAsset = StagedAsset(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/mp4"), container = VideoContainer.Mp4,
            write = movie::write,
            verify = { id, reader ->
                movie.verify(reader)
                val imageSource = request.output.openStaged(imageId ?: fail("POSTCONDITION_FAILED", "Apple image must be verified before video", Stage.Verify)).orThrow()
                var aliasesInput = false
                try {
                    if ((original.readers + image.readers + movie.reader).any { it.source === imageSource }) { aliasesInput = true; fail("OUTPUT_ALIASES_INPUT", "Pair reader aliases input", Stage.Verify) }
                    val imageReader = BinaryReader(imageSource, context)
                    val identity = imageReader.identity().orThrow()
                    if ((original.readers + image.readers + movie.reader).any { it.identity().orThrow().id == identity.id }) { aliasesInput = true; fail("OUTPUT_ALIASES_INPUT", "Pair reader identity aliases input", Stage.Verify) }
                    if (identity != imageIdentity || sha256Range(imageReader, ByteRange(0uL, identity.size)).orThrow() != imageDigest)
                        fail("POSTCONDITION_FAILED", "Apple primary image changed before joint verification", Stage.Verify)
                    val pair = SourceSession.open(SourceSet.Pair(imageSource, reader.source), context, ParseBudget(context)).orThrow()
                    val validation = validateSession(pair, listOf(Layer.Structure, Layer.Protocol), target = target).orThrow()
                    if (pair.applePair == null || pair.inspection.pairing?.matches != true || validation.verdict != Verdict.Valid || validation.coverage != Coverage.Complete ||
                        pair.inspection.keyPhoto.position?.compareTo(movie.key) != 0)
                        fail("POSTCONDITION_FAILED", "Apple pair failed independent full structure/protocol/key verification", Stage.Verify)
                    AssetVerification(validation, records(id, false), pair.inspection.keyPhoto)
                } finally { if (!aliasesInput) imageSource.close() }
            })
        publish(request.output, request.policy, context, original.readers + image.readers + movie.reader, listOf(imageAsset, videoAsset), sourceChanges + listOf(
            Change("apple:image:content-identifier", after = Value.Text(identifier), reason = "New requested Apple pair CID", requested = true),
            Change(APPLE_CID, after = Value.Text(identifier), reason = "Matching movie CID", requested = true),
            Change(APPLE_STILL_TIME, after = Value.Text("${movie.key.value}/${movie.key.timescale}"), reason = "Exact metadata-sample PTS, independent of zero marker payload", requested = true))).orThrow()
    }
}
