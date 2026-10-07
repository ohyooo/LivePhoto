package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.heif.*

/** Finite lossless assembly only. No implicit image/video encoding, cleanup, or HEIF table-width changes. */
internal object GoogleHeicCreateOperations {
    fun accepts(target: ProtocolSelector): Boolean = target == ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic"))
    private data class Prepared(val image: SourceSession, val video: BinaryReader, val movie: VideoStructure,
        val append: HeifXmpAppender, val key: KeyPhotoResult, val size: ULong, val videoDigest: Digest, val coding: Digest, val snapshot: Snapshot)

    private suspend fun coding(reader: BinaryReader, graph: HeifItemGraph, budget: ParseBudget): Digest {
        val image = HeifCodedItemProbe.primary(reader, graph, budget).orThrow()
        val extents = graph.locations.items.single { it.id == graph.primary }.extents.map { it.data }
        val source = ExtentSource.create(reader, extents, budget).orThrow()
        val digest = sha256Range(BinaryReader(source, reader.context), ByteRange(0uL, source.size().orThrow())).orThrow()
        val hash = Sha256()
        hash.update(Bytes(image.configurationDigest.value.encodeToByteArray())); hash.update(Bytes(digest.value.encodeToByteArray()))
        hash.update(unsignedBytes(image.declaredWidth.toULong(), 4, Endian.Big)); hash.update(unsignedBytes(image.declaredHeight.toULong(), 4, Endian.Big))
        return hash.finish()
    }
    private suspend fun prepare(request: CreateRequest, inheritedKey: KeyPhotoResult? = null): Prepared {
        RequestValidation.validate(request).orThrow()
        if (!accepts(request.target)) fail("UNSUPPORTED_PROTOCOL", "HEIC assembly only implements Google V2 heic", Stage.Plan)
        if (request.context.limits.maxSources < 2u) fail("RESOURCE_LIMIT_EXCEEDED", "HEIC create requires two sources", Stage.Plan)
        if (Guarantee.ExactExtraction in request.policy.requiredGuarantees) fail("PRESERVATION_REQUIREMENT_FAILED", "Create constructs a carrier; whole-asset exact extraction is not applicable", Stage.Plan)
        if (request.edits?.trim != null || request.edits?.replacementFrame != null) fail("CAPABILITY_UNSUPPORTED", "Finite HEIC assembly does not implement media edits", Stage.Plan)
        val budget = ParseBudget(request.context)
        val image = SourceSession.open(SourceSet.Single(request.image), request.context, budget).orThrow()
        if (image.bindings.isNotEmpty()) fail(if (request.sourceBindings == SourceBindingPolicy.RejectAlreadyLive) "SOURCE_ALREADY_LIVE" else "CAPABILITY_UNSUPPORTED",
            "HEIC assembly cannot implicitly clean existing source bindings", Stage.Plan)
        if (image.heifItems == null || image.inspection.media.firstOrNull()?.imageFormat != ImageFormat.Heic)
            fail("UNSUPPORTED_CONTAINER", "HEIC assembly requires actual HEIC item tables", Stage.Plan)
        val video = BinaryReader(request.video, request.context)
        val identity = video.identity().orThrow()
        if (image.snapshot.identities.any { it.id == identity.id } || request.image === request.video) fail("INVALID_ARGUMENT", "Create source identities must be distinct", Stage.Plan)
        val movie = BmffVideoProbe(video, budget).probe(ByteRange(0uL, identity.size)).orThrow()
        requireGoogleWriteVideo(movie, request.target)
        if (movie.container != VideoContainer.Mp4 || request.preference.imageFormat?.let { it != ImageFormat.Heic } == true ||
            request.preference.videoContainer?.let { it != VideoContainer.Mp4 } == true ||
            request.preference.videoCodec?.let { codec -> movie.tracks.filter { it.handler == "vide" }.any { it.codec != codec } } == true ||
            request.preference.audioCodec?.let { codec -> movie.tracks.filter { it.handler == "soun" }.any { it.audioCodec != codec } } == true ||
            request.preference.dynamicRange != DynamicRangePolicy.Preserve) fail("CAPABILITY_UNSUPPORTED", "HEIC assembly refuses preferences requiring a transformation", Stage.Plan)
        val sourceKey = if (request.edits?.keyPosition != null) selectKey(movie, request.edits.keyPosition) else inheritedKey ?: selectKey(movie, null)
        sourceKey.position?.let { position ->
            val duration = movie.tracks.filter { it.handler == "vide" }.maxOf { it.presentationDuration }
            if (position < Time.Zero || position >= duration) fail("INVALID_PRESENTATION_TIMESTAMP", "Preserved HEIC key is outside the movie presentation timeline", Stage.Plan)
        }
        val timestamp = sourceKey.position?.let(::microseconds) ?: -1L
        // Time data-class equality includes its timescale: normalize only after exact representability succeeds.
        val key = sourceKey.copy(position = if (timestamp < 0) null else Time(timestamp, 1_000_000u))
        val append = HeifXmpAppender.prepare(image.reader, GoogleDirectoryWriter.heic(identity.size, timestamp, request.context), budget).orThrow()
        val mpvdLength = checkedAdd(identity.size, 8uL)
        if (mpvdLength > UInt.MAX_VALUE.toULong()) fail("VALUE_NOT_REPRESENTABLE", "Google HEIC mpvd requires a standard 32-bit box size", Stage.Plan)
        val size = checkedAdd(append.byteLength, mpvdLength)
        if (size > request.context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "HEIC carrier exceeds output budget", Stage.Plan)
        val caps = request.output.capabilities()
        if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic ||
            request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "HEIC assembly needs requested staging and publication guarantees", Stage.Plan)
        val hash = Sha256()
        val identities = image.snapshot.identities + identity
        for (input in identities) for (field in listOf(input.id.value, input.generation.value, input.size.toString(), input.digest?.value ?: "")) {
            val bytes = Bytes(field.encodeToByteArray()); hash.update(unsignedBytes(bytes.size.toULong(), 8, Endian.Big)); hash.update(bytes)
        }
        val prepared = Prepared(image, video, movie, append, key, size, sha256Range(video, ByteRange(0uL, identity.size)).orThrow(),
            coding(image.reader, image.heifItems, budget), Snapshot(identities, GenerationToken(hash.finish().value)))
        image.recheck(); video.validateIdentity().orThrow()
        return prepared
    }
    suspend fun plan(request: CreateRequest, inheritedKey: KeyPhotoResult? = null): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request, inheritedKey)
        ExecutionPlan(prepared.snapshot, request.target, listOf(
            PlanStep(Stage.WriteProtocol, listOf(Operation.Create), prepared.image.inspection.layout.resources.map { it.id }, "Append owned XMP item/cdsc with fixed-width relocation and final standard mpvd; no encoding"),
            PlanStep(Stage.Verify, listOf(Operation.Validate), emptyList(), "Verify all retained bytes, original coding/configuration, target binding and exact video before atomic publication")),
            PreservationReport(), CapabilitySet(Availability.Conditional, listOf(DefaultLivePhotoCore().getProtocolCapabilities(request.target).operations.single { it.operation == Operation.Create })))
    }
    suspend fun create(request: CreateRequest, originalInputs: List<BinaryReader> = emptyList(), sourceChanges: List<Change> = emptyList(),
        inheritedKey: KeyPhotoResult? = null): CoreResult<OperationResult> = attempt {
        val prepared = prepare(request, inheritedKey)
        val identity = prepared.video.identity().orThrow()
        val asset = StagedAsset(OutputAssetSpec(AssetRole.Composite, mime = "image/heic"), ImageFormat.Heic, prepared.movie.container,
            write = { writer ->
                writer.budget.checkCapacity(prepared.size)
                prepared.append.write(prepared.image.reader, writer).orThrow()
                writer.writeAll(Bytes(unsignedBytes(identity.size + 8uL, 4, Endian.Big).toByteArray() + "mpvd".encodeToByteArray())).orThrow()
                copyRange(prepared.video, writer, ByteRange(0uL, identity.size), request.context).orThrow()
            }, verify = { id, reader ->
                val prefix = BinaryReader(RangeSource(reader, ByteRange(0uL, prepared.append.byteLength)), request.context)
                prepared.append.verify(prepared.image.reader, prefix).orThrow()
                val budget = ParseBudget(request.context)
                val session = SourceSession.open(SourceSet.Single(reader.source), request.context, budget).orThrow()
                val binding = session.bindings.singleOrNull { it.selector == request.target }
                    ?: fail("POSTCONDITION_FAILED", "Created HEIC has no unique Google V2 binding", Stage.Verify)
                val expected = ByteRange(prepared.append.byteLength + 8uL, identity.size)
                if (reader.identity().orThrow().size != prepared.size || session.bindings.size != 1 || !binding.structurallyValid || binding.video != expected ||
                    binding.padding != ByteRange(prepared.append.byteLength, 8uL) || binding.protocol !in session.videos || binding.key.position != prepared.key.position)
                    fail("POSTCONDITION_FAILED", "Created HEIC binding/key/media failed independent checks", Stage.Verify)
                val digest = sha256Range(reader, expected).orThrow()
                val coding = coding(reader, session.heifItems ?: fail("POSTCONDITION_FAILED", "Created HEIC item graph is absent", Stage.Verify), budget)
                if (digest != prepared.videoDigest || coding != prepared.coding) fail("POSTCONDITION_FAILED", "HEIC create changed unrequested media bytes", Stage.Verify)
                val report = validateSession(session, listOf(Layer.Structure, Layer.Protocol), target = request.target).orThrow()
                if (report.verdict == Verdict.Invalid || report.checks.filter { it.layer == Layer.Protocol }.any { it.coverage != Coverage.Complete || it.verdict != Verdict.Valid })
                    fail("POSTCONDITION_FAILED", "Created HEIC protocol validation failed", Stage.Verify)
                AssetVerification(report, listOf(
                    GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable, proof = "Create constructs a new composite carrier"),
                    GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.Verified, prepared.videoDigest, digest, "Entire embedded MP4 unchanged; mpvd header excluded"),
                    GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.Verified, prepared.coding, coding, "Primary logical encoded item, hvcC and declared dimensions unchanged"),
                    GuaranteeRecord(id, Guarantee.MetadataPreserving, GuaranteeOutcome.Verified, proof = "Finite classified carrier: all pre-existing bytes retained except requested structural size/count/iloc relocation fields; no unknown/auxiliary/private metadata admitted")), binding.key)
            })
        val changes = listOf(Change("heif:owned-xmp-item", after = Value.Number(prepared.append.itemId.toString()), reason = "Append requested linked motion-photo XMP item", requested = true),
            Change("google:mpvd", after = Value.Number(identity.size.toString()), reason = "Append standard eight-byte mpvd header and byte-identical video", requested = true))
        publish(request.output, request.policy, request.context, originalInputs + prepared.image.readers + prepared.video, listOf(asset), sourceChanges + changes).orThrow()
    }
}
