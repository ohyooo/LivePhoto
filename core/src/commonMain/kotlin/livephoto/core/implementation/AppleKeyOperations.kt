package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.apple.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** Preserve the complete primary and pair CID; only fixed owned key edit/tkhd duration bytes change. */
internal object AppleKeyOperations {
    private data class Prepared(val fixed: BinaryReader, val expected: Digest, val key: Time, val changes: List<Change>)
    private suspend fun prepare(request: SetKeyRequest, session: SourceSession): Prepared {
        val pair = session.applePair ?: fail("CAPABILITY_UNSUPPORTED", "Apple SetKey needs a complete pair", Stage.Plan)
        val media = session.videos[ProtocolIds.Apple]
            ?: fail("CAPABILITY_UNSUPPORTED", "Apple SetKey requires an independently parsed compatible movie", Stage.Plan)
        if (media.container !in setOf(VideoContainer.Mp4, VideoContainer.Mov) || session.inspection.issues.any { it.severity == Severity.Error })
            fail("CAPABILITY_UNSUPPORTED", "Apple SetKey requires a valid identified primary and compatible movie pair", Stage.Plan)
        val budget = ParseBudget(request.context)
        // HEIC remains byte-identical: no authority to clean/merge its MakerNote is required or inferred.
        // Keep the existing JPEG image gate and the identical closed movie dependency gate.
        if (session.heifItems != null) AppleClean.prepareVideo(session, budget).orThrow()
        else AppleClean.prepare(session, budget).orThrow() // Cleanup views are never published.
        val track = media.tracks.singleOrNull { it.handler == "meta" }
            ?: fail("CAPABILITY_UNSUPPORTED", "Apple SetKey needs one dedicated metadata track", Stage.Plan)
        if (track.timescale != media.movieTimescale || track.duration != 1uL || track.samples.size != 1 ||
            track.metadataKeys.size != 1 || track.metadataKeys.values.single().name != APPLE_STILL_TIME ||
            track.edit?.segmentDuration != 1uL || track.edit.mediaStart != 0L)
            fail("CAPABILITY_UNSUPPORTED", "Apple SetKey requires its finite exact one-tick metadata sample/edit profile", Stage.Plan)
        val reader = pair.videoReader
        val parser = BmffReader(reader, budget)
        val movie = parser.readBoxes(media.range).orThrow().single { it.type == "moov" }
        val trak = parser.readBoxes(movie.payload, 1u).orThrow().filter { it.type == "trak" }.singleOrNull { box ->
            val tkhd = parser.readBoxes(box.payload, 2u).orThrow().single { it.type == "tkhd" }
            reader.readBuffer(tkhd.payload.offset, 1u).orThrow()[0] == 0.toByte() && reader.readU32(tkhd.payload.offset + 12uL).orThrow() == track.trackId
        } ?: fail("CAPABILITY_UNSUPPORTED", "Apple SetKey requires one version-zero fixed-width metadata track header", Stage.Plan)
        val fields = parser.readBoxes(trak.payload, 2u).orThrow()
        val tkhd = fields.single { it.type == "tkhd" }
        val edts = fields.single { it.type == "edts" }
        val previous = AppleKeyEditTable.bytes(track.edit.emptyDuration)
        if (edts.headerLength != 8uL || edts.payload.length != previous.size.toULong() || reader.readExactly(edts.payload.offset, previous.size.toUInt()).orThrow() != previous)
            fail("CAPABILITY_UNSUPPORTED", "Apple SetKey cannot resize or interpret an unclassified edit envelope", Stage.Plan)
        val key = selectKey(media, request.position).position!!
        val numerator = checkedMultiply(key.value.toULong(), media.movieTimescale.toULong())
        if (numerator % key.timescale.toULong() != 0uL) fail("VALUE_NOT_REPRESENTABLE", "Apple SetKey does not round presentation times", Stage.Plan)
        val delay = numerator / key.timescale.toULong()
        if (delay >= UInt.MAX_VALUE.toULong() || delay + 1uL > media.movieDuration)
            fail("VALUE_NOT_REPRESENTABLE", "Apple SetKey exceeds fixed track-duration fields", Stage.Plan)
        val caps = request.output.capabilities()
        if (!caps.assetSetAtomic || !caps.canReadStaged || request.policy.atomicity != Atomicity.AssetSetRequired || request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Apple SetKey requires complete pair publication", Stage.Plan)
        val size = checkedAdd(pair.imageReader.identity().orThrow().size, reader.identity().orThrow().size)
        if (size > request.context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Apple SetKey pair exceeds shared output budget", Stage.Plan)
        if (Guarantee.ExactExtraction in request.policy.requiredGuarantees) fail("PRESERVATION_REQUIREMENT_FAILED", "Key metadata changes the movie; pair-wide ExactExtraction is not applicable", Stage.Plan)
        val fixed = BinaryReader(FixedPatchSource.create(reader, listOf(FixedPatch(edts.payload, AppleKeyEditTable.bytes(delay)),
            FixedPatch(ByteRange(tkhd.payload.offset + 20uL, 4uL), unsignedBytes(delay + 1uL, 4, Endian.Big)))).orThrow(), request.context)
        val expected = sha256Range(fixed, media.range).orThrow()
        session.recheck()
        return Prepared(fixed, expected, key, listOf(Change(APPLE_STILL_TIME, session.inspection.keyPhoto.position?.let { Value.Text("${it.value}/${it.timescale}") },
            Value.Text("${key.value}/${key.timescale}"), "Change owned metadata edit mapping and corresponding duration; preserve marker payload, CID, original primary and every media sample byte", true)))
    }
    suspend fun plan(request: SetKeyRequest, session: SourceSession): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request, session)
        val target = session.inspection.detection.primaryProtocol ?: fail("CAPABILITY_UNSUPPORTED", "Apple SetKey needs an identified pair profile", Stage.Plan)
        ExecutionPlan(session.snapshot, target, listOf(PlanStep(Stage.WriteProtocol, listOf(Operation.SetKey), emptyList(),
            "Fixed-width metadata edit/tkhd duration patch; byte-identical primary; jointly verify pair before commit")),
            PreservationReport(changes = prepared.changes), CapabilitySet(Availability.Conditional,
                listOf(DefaultLivePhotoCore().getProtocolCapabilities(target).operations.single { it.operation == Operation.SetKey })))
    }
    suspend fun set(request: SetKeyRequest, session: SourceSession): CoreResult<OperationResult> = attempt {
        val prepared = prepare(request, session)
        val pair = session.applePair!!
        val image = GoogleOperations.rawAsset(session, ByteRange(0uL, pair.imageReader.identity().orThrow().size), AssetRole.PrimaryImage,
            session.inspection.media.first().mime!!, request.context, inputReader = pair.imageReader)
        var imageId: AssetId? = null
        var imageIdentity: SourceIdentity? = null
        var imageHash: Digest? = null
        val verifiedImage = image.copy(verify = { id, reader ->
            val result = image.verify(id, reader)
            imageId = id; imageIdentity = reader.identity().orThrow(); imageHash = sha256Range(reader, ByteRange(0uL, imageIdentity.size)).orThrow()
            result
        })
        val media = session.videos[ProtocolIds.Apple] ?: fail("CAPABILITY_UNSUPPORTED", "Apple key movie facts are unavailable", Stage.Plan)
        val video = StagedAsset(OutputAssetSpec(AssetRole.MotionVideo, mime = videoFacts(media).mime!!), container = media.container,
            write = { writer -> copyRange(prepared.fixed, writer, ByteRange(0uL, prepared.fixed.identity().orThrow().size), request.context).orThrow() },
            verify = { id, reader ->
                val size = reader.identity().orThrow().size
                if (size != prepared.fixed.identity().orThrow().size || sha256Range(reader, ByteRange(0uL, size)).orThrow() != prepared.expected)
                    fail("POSTCONDITION_FAILED", "Apple SetKey changed bytes outside the two owned fixed patches", Stage.Verify)
                val source = request.output.openStaged(imageId ?: fail("POSTCONDITION_FAILED", "Primary must be verified first", Stage.Verify)).orThrow()
                var alias = false
                try {
                    val stagedImage = BinaryReader(source, request.context)
                    val identity = stagedImage.identity().orThrow()
                    if (session.readers.any { it.source === source || it.identity().orThrow().id == identity.id }) { alias = true; fail("OUTPUT_ALIASES_INPUT", "Pair verifier aliases an original input", Stage.Verify) }
                    if (identity != imageIdentity || sha256Range(stagedImage, ByteRange(0uL, identity.size)).orThrow() != imageHash)
                        fail("POSTCONDITION_FAILED", "Apple primary changed before joint SetKey verification", Stage.Verify)
                    val staged = SourceSession.open(SourceSet.Pair(source, reader.source), request.context, ParseBudget(request.context)).orThrow()
                    val report = validateSession(staged, listOf(Layer.Structure, Layer.Protocol)).orThrow()
                    val required = setOf("heif.item-locations", "heif.item-graph", "bmff.samples", "apple.pair", "apple.media-profile")
                    val validationComplete = if (session.heifItems == null) report.verdict == Verdict.Valid && report.coverage == Coverage.Complete
                        else required.all { check -> report.checks.singleOrNull { it.id == check }?.let { it.verdict == Verdict.Valid && it.coverage == Coverage.Complete } == true } &&
                            report.issues.none { it.severity == Severity.Error }
                    // Evidence belongs to the staged source generation, not the old source's address/identity.
                    if (!validationComplete || staged.inspection.detection.primaryProtocol != session.inspection.detection.primaryProtocol ||
                        staged.inspection.pairing?.copy(evidence = emptyList()) != session.inspection.pairing?.copy(evidence = emptyList()) ||
                        staged.inspection.keyPhoto.position?.compareTo(prepared.key) != 0 ||
                        staged.videos[ProtocolIds.Apple] == null ||
                        staged.videos[ProtocolIds.Apple]?.tracks?.filter { it.handler != "meta" } != session.videos[ProtocolIds.Apple]?.tracks?.filter { it.handler != "meta" })
                        fail("POSTCONDITION_FAILED", "Apple SetKey changed CID, retained media or failed pair/key validation", Stage.Verify)
                    AssetVerification(report, listOf(
                        GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable),
                        GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.NotApplicable),
                        GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.Verified, proof = "Complete AV sample/configuration/timeline unchanged; full movie differs only in owned metadata key fields"),
                        GuaranteeRecord(id, Guarantee.MetadataPreserving, GuaranteeOutcome.Verified, proof = "Every unrequested byte including CID, track IDs and timed marker payload remains at original offsets")), staged.inspection.keyPhoto)
                } finally { if (!alias) source.close() }
            })
        publish(request.output, request.policy, request.context, session.readers + prepared.fixed, listOf(verifiedImage, video), prepared.changes).orThrow()
    }
}
