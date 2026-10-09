package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.apple.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.exif.ExifPositionIndependenceProof
import livephoto.core.jpeg.*
import kotlin.uuid.Uuid

/** Shared finite assembler with distinct ordinary-media Create and live-source Convert gates. */
internal object AppleAssemblyOperations {
    private val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4"))
    private data class AssemblyRequest(val target: ProtocolSelector, val preference: MediaPreference, val edits: EditSpec?,
        val policy: MutationPolicy, val output: OutputTransaction, val context: Context, val creating: Boolean,
        val sourceBindings: SourceBindingPolicy = SourceBindingPolicy.RejectAlreadyLive)
    private fun ConvertRequest.assembly() = AssemblyRequest(target, preference, edits, policy, output, context, false)
    private fun CreateRequest.assembly() = AssemblyRequest(target, preference, edits, policy, output, context, true, sourceBindings)
    private data class Prepared(val image: SourceSession, val rewrite: JpegRewritePlan, val movie: AppleMoviePlan,
        val unknownMetadata: Boolean, val snapshot: Snapshot)

    fun validateTarget(request: ConvertRequest) {
        if (request.target !in setOf(target, ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mov"))))
            fail("CAPABILITY_PLANNED", "Apple conversion requires an implemented explicit JPEG movie profile", Stage.Plan)
        if (request.edits?.trim != null || request.edits?.replacementFrame != null)
            fail("CAPABILITY_UNSUPPORTED", "Apple conversion media edits do not yet have an assembly preservation proof", Stage.Plan)
    }

    private suspend fun prepare(request: AssemblyRequest, original: SourceSession?, inputs: Pair<BinarySource, BinarySource>, identifier: String): Prepared {
        val requestedContainer = when {
            request.target == target -> VideoContainer.Mp4
            request.target == ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mov")) -> VideoContainer.Mov
            request.creating && request.target == ProtocolSelector(ProtocolIds.Apple) -> VideoContainer.Mov
            else -> fail("CAPABILITY_PLANNED", "Apple assembly requires an implemented explicit JPEG movie profile", Stage.Plan)
        }
        if (request.edits?.trim != null || request.edits?.replacementFrame != null)
            fail("CAPABILITY_UNSUPPORTED", "Apple assembly media edits lack an independent preservation proof", Stage.Plan)
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
        if (request.creating && image.bindings.isNotEmpty()) fail(
            if (request.sourceBindings == SourceBindingPolicy.RejectAlreadyLive) "SOURCE_ALREADY_LIVE" else "CAPABILITY_UNSUPPORTED",
            "Ordinary-media Apple Create cannot silently strip an existing live binding", Stage.Plan)
        val jpeg = image.jpeg ?: fail("CAPABILITY_UNSUPPORTED", "Apple conversion currently requires JPEG", Stage.Plan)
        if (!request.creating && jpeg.hasExif) verifySourceExif(original, image, budget)
        val jfif = ReplaceOperations.canonicalJfif(image)
        if (image.bindings.isNotEmpty() || jpeg.segments.any { it.marker in 0xe0..0xef && it.payloadKind == AppPayloadKind.Unknown && it != jfif })
            fail("UNSAFE_METADATA_REWRITE", "Apple assembly cannot relocate unclassified APP or retain a source image binding", Stage.Plan)
        val videoReader = BinaryReader(inputs.second, context)
        val videoIdentity = videoReader.identity().orThrow()
        if (inputs.first === inputs.second || image.snapshot.identities.any { it.id == videoIdentity.id })
            fail("INVALID_ARGUMENT", "Apple assembly source identities must be distinct", Stage.Plan)
        val video = BmffVideoProbe(videoReader, budget).probe(ByteRange(0uL, videoReader.identity().orThrow().size)).orThrow()
        if (video.container != requestedContainer || request.preference.imageFormat?.let { it != ImageFormat.Jpeg } == true ||
            request.preference.videoContainer?.let { it != video.container } == true ||
            request.preference.videoCodec?.let { codec -> video.tracks.filter { it.handler == "vide" }.any { it.codec != codec } } == true ||
            request.preference.audioCodec?.let { codec -> video.tracks.filter { it.handler == "soun" }.any { it.audioCodec != codec } } == true ||
            request.preference.dynamicRange != DynamicRangePolicy.Preserve)
            fail("CAPABILITY_UNSUPPORTED", "Apple assembly preserves existing JPEG and the profile's existing movie container/color semantics; it does not remux", Stage.Plan)
        val key = if (request.edits?.keyPosition != null) selectKey(video, request.edits.keyPosition).position!!
            else if (request.creating) selectKey(video, null).position!!
            else original?.inspection?.keyPhoto?.position ?: fail("CAPABILITY_UNSUPPORTED", "Apple conversion requires a known source key or an explicit selected frame", Stage.Plan)
        val proof = AppleImagePatch.create(image.reader, identifier, budget).orThrow()
        val app = JpegRewrite.appSegment(0xe1, proof.payload).orThrow()
        val patchRange = if (proof.originalTiffRange == null) ByteRange(2uL, 0uL)
            else jpeg.segments.single { it.payloadKind == AppPayloadKind.Exif }.range
        val rewrite = JpegRewrite.plan(jpeg, listOf(JpegPatch(patchRange, app, appleProof = proof))).orThrow()
        val movie = AppleMovieAssembler.prepare(videoReader, identifier, key, budget).orThrow()
        if (checkedAdd(rewrite.outputLength, movie.byteLength) > context.limits.maxOutputBytes)
            fail("RESOURCE_LIMIT_EXCEEDED", "Apple pair exceeds the shared output budget", Stage.Plan)
        val unknown = original?.legacyPair != null || original?.jpeg?.hasExif == true
        if (unknown && (request.policy.preservation == PreservationPolicy.Strict || Guarantee.MetadataPreserving in request.policy.requiredGuarantees))
            fail("PRESERVATION_REQUIREMENT_FAILED", "Source cleanup has unproved private metadata dependencies", Stage.Plan)
        original?.recheck(); image.recheck(); videoReader.validateIdentity().orThrow()
        val identities = image.snapshot.identities + videoIdentity
        val hash = Sha256()
        for (identity in identities) for (field in listOf(identity.id.value, identity.generation.value, identity.size.toString(), identity.digest?.value ?: "")) {
            val bytes = Bytes(field.encodeToByteArray()); hash.update(unsignedBytes(bytes.size.toULong(), 8, Endian.Big)); hash.update(bytes)
        }
        return Prepared(image, rewrite, movie, unknown, Snapshot(identities, GenerationToken(hash.finish().value)))
    }

    /** Source cleanup and new CID append are separate proofs. Only unchanged standard Google JPEG EXIF is admitted. */
    private suspend fun verifySourceExif(original: SourceSession?, clean: SourceSession, budget: ParseBudget) {
        val source = original ?: fail("UNSAFE_METADATA_REWRITE", "Conversion EXIF requires its original source snapshot", Stage.Plan)
        if (source.applePair != null || source.legacyPair != null ||
            source.inspection.detection.primaryProtocol?.protocol !in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2) ||
            source.bindings.size != 1)
            fail("UNSAFE_METADATA_REWRITE", "This source protocol has no unchanged-EXIF conversion proof", Stage.Plan)
        val before = source.jpeg?.segments?.singleOrNull { it.payloadKind == AppPayloadKind.Exif }?.payload
            ?: fail("UNSAFE_METADATA_REWRITE", "Conversion needs one original EXIF payload", Stage.Plan)
        val after = clean.jpeg?.segments?.singleOrNull { it.payloadKind == AppPayloadKind.Exif }?.payload
            ?: fail("UNSAFE_METADATA_REWRITE", "Conversion needs one cleaned EXIF payload", Stage.Plan)
        if (before.length != after.length || sha256Range(source.reader, before).orThrow() != sha256Range(clean.reader, after).orThrow())
            fail("POSTCONDITION_FAILED", "Source-binding cleanup changed EXIF bytes before Apple assembly", Stage.Verify)
        // No private MakerNote, unknown tags/types, aliases, thumbnail offsets or nonzero unclassified slack.
        ExifPositionIndependenceProof.prove(source.reader, ByteRange(before.offset + 6uL, before.length - 6uL), budget).orThrow()
        ExifPositionIndependenceProof.prove(clean.reader, ByteRange(after.offset + 6uL, after.length - 6uL), budget).orThrow()
        source.recheck(); clean.recheck()
        // This does not upgrade the original source's Unknown metadata-association outcome to Verified.
    }

    suspend fun plan(request: ConvertRequest, original: SourceSession, inputs: Pair<BinarySource, BinarySource>): CoreResult<ExecutionPlan> = attempt {
        prepare(request.assembly(), original, inputs, "00000000-0000-4000-8000-000000000000") // Read-only planning, no UUID entropy or output IO.
        ExecutionPlan(original.snapshot, request.target, listOf(
            PlanStep(Stage.WriteProtocol, listOf(Operation.ConvertTo), emptyList(), "Assemble formal image CID and movie CID/timed sample; preserve retained coded tracks"),
            PlanStep(Stage.Verify, listOf(Operation.Validate), emptyList(), "Independently verify both staged assets and their matching pair before one commit")),
            predictedPreservation = PreservationReport(),
            capabilities = CapabilitySet(Availability.Conditional, listOf(DefaultLivePhotoCore().getProtocolCapabilities(request.target)
                .operations.single { it.operation == Operation.ConvertTo })))
    }

    suspend fun plan(request: CreateRequest): CoreResult<ExecutionPlan> = attempt {
        RequestValidation.validate(request).orThrow()
        val prepared = prepare(request.assembly(), null, request.image to request.video, "00000000-0000-4000-8000-000000000000")
        val resolvedTarget = if (request.target.profile == null) request.target.copy(profile = ProfileId("jpeg-mov")) else request.target
        ExecutionPlan(prepared.snapshot, resolvedTarget, listOf(
            PlanStep(Stage.WriteProtocol, listOf(Operation.Create), emptyList(), "Ordinary media to matching Apple image/movie CID and exact key metadata sample; no source Live Photo required"),
            PlanStep(Stage.Verify, listOf(Operation.Validate), emptyList(), "Independently reread both assets, retained coded media and complete pair before one commit")),
            PreservationReport(), CapabilitySet(Availability.Conditional, listOf(DefaultLivePhotoCore().getProtocolCapabilities(request.target).operations.single { it.operation == Operation.Create })))
    }
    suspend fun create(request: CreateRequest): CoreResult<OperationResult> = attempt {
        RequestValidation.validate(request).orThrow()
        assemble(request.assembly(), null, request.image to request.video, emptyList()).orThrow()
    }
    suspend fun convert(request: ConvertRequest, original: SourceSession, inputs: Pair<BinarySource, BinarySource>, sourceChanges: List<Change>): CoreResult<OperationResult> =
        assemble(request.assembly(), original, inputs, sourceChanges)

    private suspend fun assemble(request: AssemblyRequest, original: SourceSession?, inputs: Pair<BinarySource, BinarySource>, sourceChanges: List<Change>): CoreResult<OperationResult> = attempt {
        val identifier = try { Uuid.random().toString() } catch (_: Exception) { fail("BACKEND_UNAVAILABLE", "Platform UUID generation failed", Stage.Plan) }
        val prepared = prepare(request, original, inputs, identifier)
        val image = prepared.image
        val movie = prepared.movie
        val context = request.context
        val patch = prepared.rewrite.patches.single()
        val app = patch.replacement
        val size = image.reader.identity().orThrow().size
        val originalReaders = original?.readers.orEmpty()
        var imageId: AssetId? = null
        var imageIdentity: SourceIdentity? = null
        var imageDigest: Digest? = null
        fun records(id: AssetId, imageAsset: Boolean): List<GuaranteeRecord> = listOf(
            GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable, proof = "New target carrier; no file-exact promise"),
            GuaranteeRecord(id, Guarantee.ImageDataPreserving, if (imageAsset) GuaranteeOutcome.Verified else GuaranteeOutcome.NotApplicable, proof = "All JPEG coding bytes and unrequested segments remain around the authorized EXIF patch"),
            GuaranteeRecord(id, Guarantee.BitstreamPreserving, if (imageAsset) GuaranteeOutcome.NotApplicable else GuaranteeOutcome.Verified, proof = "All retained track configuration, sample bytes, order, offsets and timeline remain unchanged"),
            GuaranteeRecord(id, Guarantee.MetadataPreserving, if (prepared.unknownMetadata) GuaranteeOutcome.Unknown else GuaranteeOutcome.Verified, proof = "Exact authorized EXIF patch with independent ordinary field readback and unchanged TIFF value offsets; unrequested segments and classified movie fields retained"))
        val imageAsset = StagedAsset(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/jpeg"), ImageFormat.Jpeg,
            write = { JpegRewrite.write(image.reader, it, image.jpeg!!, prepared.rewrite, context).orThrow() },
            verify = { id, reader ->
                val jpeg = JpegParser.parse(reader, ParseBudget(context)).orThrow()
                if (reader.identity().orThrow().size != prepared.rewrite.outputLength || jpeg.primary.length != prepared.rewrite.outputLength ||
                    reader.readExactly(patch.range.offset, app.size.toUInt()).orThrow() != app ||
                    sha256Range(image.reader, ByteRange(0uL, patch.range.offset)).orThrow() != sha256Range(reader, ByteRange(0uL, patch.range.offset)).orThrow() ||
                    sha256Range(image.reader, ByteRange(patch.range.endExclusive, size - patch.range.endExclusive)).orThrow() !=
                        sha256Range(reader, ByteRange(patch.range.offset + app.size.toULong(), size - patch.range.endExclusive)).orThrow() ||
                    AppleImageReader.read(reader, jpeg, ParseBudget(context)).orThrow()?.value != identifier)
                    fail("POSTCONDITION_FAILED", "Apple primary image failed exact unrequested-byte/CID verification", Stage.Verify)
                imageId = id; imageIdentity = reader.identity().orThrow()
                imageDigest = sha256Range(reader, ByteRange(0uL, imageIdentity.size)).orThrow()
                val snapshot = SourceSession.open(SourceSet.Single(reader.source), context, ParseBudget(context)).orThrow().snapshot
                AssetVerification(ValidationReport(Verdict.Valid, Coverage.Complete, listOf(CheckResult("apple.image-cid", Layer.Protocol, Verdict.Valid, Coverage.Complete)), snapshot = snapshot), records(id, true))
            })
        val videoAsset = StagedAsset(OutputAssetSpec(AssetRole.MotionVideo, mime = videoFacts(movie.media).mime!!), container = movie.media.container,
            write = movie::write,
            verify = { id, reader ->
                movie.verify(reader)
                val imageSource = request.output.openStaged(imageId ?: fail("POSTCONDITION_FAILED", "Apple image must be verified before video", Stage.Verify)).orThrow()
                var aliasesInput = false
                try {
                    if ((originalReaders + image.readers + movie.reader).any { it.source === imageSource }) { aliasesInput = true; fail("OUTPUT_ALIASES_INPUT", "Pair reader aliases input", Stage.Verify) }
                    val imageReader = BinaryReader(imageSource, context)
                    val identity = imageReader.identity().orThrow()
                    if ((originalReaders + image.readers + movie.reader).any { it.identity().orThrow().id == identity.id }) { aliasesInput = true; fail("OUTPUT_ALIASES_INPUT", "Pair reader identity aliases input", Stage.Verify) }
                    if (identity != imageIdentity || sha256Range(imageReader, ByteRange(0uL, identity.size)).orThrow() != imageDigest)
                        fail("POSTCONDITION_FAILED", "Apple primary image changed before joint verification", Stage.Verify)
                    val pair = SourceSession.open(SourceSet.Pair(imageSource, reader.source), context, ParseBudget(context)).orThrow()
                    val validation = validateSession(pair, listOf(Layer.Structure, Layer.Protocol), target = request.target).orThrow()
                    if (pair.applePair == null || pair.inspection.pairing?.matches != true || validation.verdict != Verdict.Valid || validation.coverage != Coverage.Complete ||
                        pair.inspection.keyPhoto.position?.compareTo(movie.key) != 0)
                        fail("POSTCONDITION_FAILED", "Apple pair failed independent full structure/protocol/key verification", Stage.Verify)
                    AssetVerification(validation, records(id, false), pair.inspection.keyPhoto)
                } finally { if (!aliasesInput) imageSource.close() }
            })
        publish(request.output, request.policy, context, originalReaders + image.readers + movie.reader, listOf(imageAsset, videoAsset), sourceChanges + listOf(
            Change("apple:image:content-identifier", after = Value.Text(identifier), reason = "New requested Apple pair CID", requested = true),
            Change(APPLE_CID, after = Value.Text(identifier), reason = "Matching movie CID", requested = true),
            Change(APPLE_STILL_TIME, after = Value.Text("${movie.key.value}/${movie.key.timescale}"), reason = "Exact metadata-sample PTS, independent of zero marker payload", requested = true))).orThrow()
    }
}
