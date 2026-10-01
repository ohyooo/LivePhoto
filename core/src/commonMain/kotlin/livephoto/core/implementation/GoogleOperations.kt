package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.jpeg.*
import livephoto.core.oplus.*
import livephoto.core.samsung.*
import livephoto.core.vivo.*
import livephoto.core.huawei.*
import livephoto.core.apple.*

internal object GoogleOperations {
    suspend fun create(request: CreateRequest, originalInputs: List<BinaryReader> = emptyList(), sourceChanges: List<Change> = emptyList(), sourceKey: KeyPhotoResult? = null, metadataUnproven: Boolean = false): CoreResult<OperationResult> = attempt {
        RequestValidation.validate(request).orThrow()
        if (Guarantee.ExactExtraction in request.policy.requiredGuarantees) fail("PRESERVATION_REQUIREMENT_FAILED", "Create constructs a new composite asset; whole-asset exact extraction is not applicable", Stage.Plan)
        if (request.context.limits.maxSources < 2u) fail("RESOURCE_LIMIT_EXCEEDED", "Create requires two input sources")
        val budget = ParseBudget(request.context)
        val image = SourceSession.open(SourceSet.Single(request.image), request.context, budget).orThrow()
        val videoReader = BinaryReader(request.video, request.context)
        val videoIdentity = videoReader.identity().orThrow()
        if (image.snapshot.identities.any { it.id == videoIdentity.id } || request.image === request.video) fail("INVALID_ARGUMENT", "Image and video inputs must have distinct identities")
        val video = BmffVideoProbe(videoReader, budget).probe(ByteRange(0uL, videoIdentity.size)).orThrow()
        requireGoogleWriteVideo(video, request.target)
        val facts = image.inspection.media.firstOrNull()
        if (facts?.width == null || facts.height == null || image.jpeg == null) fail("UNSUPPORTED_CONTAINER", "Create requires a supported JPEG frame structure")
        if (request.edits?.trim != null || request.edits?.replacementFrame != null) fail("CAPABILITY_UNSUPPORTED", "Requested media edits require backend orchestration")
        if (request.preference.imageFormat != null && request.preference.imageFormat != ImageFormat.Jpeg || request.preference.videoContainer != null && request.preference.videoContainer != video.container ||
            request.preference.videoCodec != null && video.tracks.filter { it.handler == "vide" }.any { it.codec != request.preference.videoCodec } ||
            request.preference.audioCodec != null && video.tracks.filter { it.handler == "soun" }.any { it.audioCodec != request.preference.audioCodec } || request.preference.dynamicRange != DynamicRangePolicy.Preserve) fail("CAPABILITY_UNSUPPORTED", "Media preference requires an unimplemented transformation")
        val huawei = request.target.protocol == ProtocolIds.Huawei
        if (huawei && !basicMediaEnvelope(videoReader, ByteRange(0uL, videoIdentity.size), budget)) fail("UNKNOWN_PROTOCOL_VARIANT", "Huawei writer cannot bind unknown media extensions", Stage.Plan)
        if (huawei && request.target.profile != null && request.target.profile != ProfileId("basic60")) fail("UNSUPPORTED_PROTOCOL", "Huawei writer only implements basic60 JPEG")
        val huaweiPlan = if (huawei) HuaweiJpegWriter.createPlan(image, video, videoIdentity.size, request.sourceBindings == SourceBindingPolicy.StripSourceBindings, request.edits?.keyPosition, budget).orThrow() else null
        val key = if (huawei) KeyPhotoResult() else if (request.edits?.keyPosition == null && sourceKey != null) sourceKey else selectKey(video, request.edits?.keyPosition)
        key.position?.let { position ->
            val duration = video.tracks.filter { it.handler == "vide" }.maxOfOrNull { it.presentationDuration }
                ?: fail("FRAME_INDEX_UNAVAILABLE", "Target video has no presentation timeline")
            if (position < Time.Zero || position >= duration) fail("INVALID_PRESENTATION_TIMESTAMP", "Preserved key position is outside the target video", Stage.Plan)
        }
        val timestamp = if (huawei) 0L else key.position?.let(::microseconds) ?: -1L
        val mime = if (video.container == VideoContainer.Mov) "video/quicktime" else "video/mp4"
        val oplus = request.target.protocol == ProtocolIds.Oplus
        val samsung = request.target.protocol == ProtocolIds.Samsung
        val vivo = request.target.protocol == ProtocolIds.VivoModern
        if (vivo && request.target.profile != null && request.target.profile != ProfileId("jpeg")) fail("UNSUPPORTED_PROTOCOL", "vivo writer only implements jpeg")
        if (samsung && request.target.profile != null && request.target.profile != ProfileId("jpeg-sef-mpv3")) fail("UNSUPPORTED_PROTOCOL", "Samsung writer only implements JPEG SEF mpv3")
        val samsungPlan = if (samsung) SamsungJpegWriter.createPlan(image, videoReader, ByteRange(0uL, videoIdentity.size), timestamp, request.sourceBindings == SourceBindingPolicy.StripSourceBindings, request.context, budget).orThrow() else null
        if (oplus && request.target.profile != null && request.target.profile != ProfileId("jpeg-no-tail")) fail("UNSUPPORTED_PROTOCOL", "Oplus writer only implements jpeg-no-tail")
        val rewrite = if (huaweiPlan != null) huaweiPlan.image else if (samsungPlan != null) samsungPlan.image else if (vivo) VivoJpegWriter.createPlan(image, videoIdentity.size, timestamp, request.sourceBindings == SourceBindingPolicy.StripSourceBindings, request.context, budget).orThrow() else if (oplus) OplusJpegWriter.createPlan(image, videoIdentity.size, timestamp,
            request.sourceBindings == SourceBindingPolicy.StripSourceBindings, request.context, budget).orThrow()
            else GoogleJpegWriter.createPlan(image, request.target, videoIdentity.size, mime, timestamp,
                request.sourceBindings == SourceBindingPolicy.StripSourceBindings, request.context).orThrow()
        val videoDigest = sha256Range(videoReader, ByteRange(0uL, videoIdentity.size)).orThrow()
        val coding = codingDigest(image)
        val metadata = ordinaryDigest(image, if (huawei) emptySet() else if (request.target.protocol == ProtocolIds.GoogleV1) V1_FIELDS else V2_FIELDS, oplus)
        val asset = StagedAsset(OutputAssetSpec(AssetRole.Composite, mime = "image/jpeg"), ImageFormat.Jpeg,
            write = { writer -> JpegRewrite.write(image.reader, writer, image.jpeg, rewrite, request.context).orThrow(); if (samsungPlan != null) SefWriter.write(samsungPlan.suffix, writer, request.context).orThrow() else copyRange(videoReader, writer, ByteRange(0uL, videoIdentity.size), request.context).orThrow(); if (huaweiPlan != null) writer.writeAll(huaweiPlan.tail).orThrow() },
            verify = { id, reader ->
                val staged = SourceSession.open(SourceSet.Single(reader.source), request.context, budget).orThrow()
                val report = validateSession(staged, listOf(Layer.Structure, Layer.Protocol), target = request.target).orThrow()
                val binding = staged.bindings.singleOrNull { it.protocol == request.target.protocol } ?: fail("POSTCONDITION_FAILED", "Created carrier has no unique target binding", Stage.Verify)
                if (binding.protocol != request.target.protocol || !binding.structurallyValid || binding.protocol !in staged.videos || report.verdict == Verdict.Invalid ||
                    report.checks.any { it.coverage != Coverage.Complete }) fail("POSTCONDITION_FAILED", "Created carrier failed required protocol and media checks", Stage.Verify)
                val extractedDigest = sha256Range(reader, binding.video!!).orThrow()
                val outputCoding = codingDigest(staged)
                val outputMetadata = ordinaryDigest(staged, verifiedExifRewrite = oplus)
                if (videoDigest != extractedDigest || coding != outputCoding || metadata != outputMetadata) fail("POSTCONDITION_FAILED", "Create changed unrequested video, image coding, or ordinary metadata", Stage.Verify)
                AssetVerification(report, listOf(
                    GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable, proof = "Create constructs a new composite carrier; no whole-carrier exact extraction claim"),
                    GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.Verified, videoDigest, extractedDigest, "Embedded resource ${videoId(binding.protocol).value}: complete encoded video suffix bytes unchanged"),
                    GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.Verified, coding, outputCoding, "JPEG frame/tables/scan headers/entropy bytes unchanged"),
                    GuaranteeRecord(id, Guarantee.MetadataPreserving, if (!metadataUnproven && opaqueOffsetsPreserved(image, staged)) GuaranteeOutcome.Verified else GuaranteeOutcome.Unknown, metadata, outputMetadata, "Ordinary XMP and raw APP/COM unchanged; opaque APP positions checked separately")), if (huawei || sourceKey != null) staged.inspection.keyPhoto else key.copy(position = Time(timestamp, 1_000_000u)))
            })
        val targetFields = if (huawei) emptySet() else if (request.target.protocol == ProtocolIds.GoogleV1) V1_FIELDS else V2_FIELDS
        val changes = targetFields.map { field ->
            val selector = "{$CAMERA_URI}$field"
            Change(selector, before = image.inspection.metadata.firstOrNull { it.selector == selector }?.value,
                after = Value.Text(when { field.endsWith("PresentationTimestampUs") -> timestamp.toString(); field == "MicroVideoOffset" -> videoIdentity.size.toString(); else -> "1" }), reason = "Write requested target protocol binding", requested = true)
        } + image.inspection.metadata.filter { it.owner == Ownership.SourceProtocol && it.selector !in targetFields.map { field -> "{$CAMERA_URI}$field" } }.map { entry ->
            Change(entry.selector, entry.value, null, "Explicitly strip verified source binding", true)
        } + if (request.target.protocol in setOf(ProtocolIds.GoogleV2, ProtocolIds.Oplus, ProtocolIds.Samsung, ProtocolIds.VivoModern)) listOf(Change("{$CONTAINER_URI}Directory", after = Value.Text("Canonical Primary/MotionPhoto resource directory"), reason = "Write requested target directory", requested = true)) else emptyList()
        val vendorChanges = if (samsung) listOf(
            Change("samsung:sef:MotionPhoto_Data", after = Value.Text("type=0x0A30; header=24; videoLength=${videoIdentity.size}"), reason = "Write requested Samsung motion record", requested = true),
            Change("samsung:sef:MotionPhoto_Version", after = Value.Text("mpv3"), reason = "Write requested Samsung version record", requested = true)) else emptyList()
        val vivoChanges = if (vivo) mapOf("VMotionPhotoVersion" to "1", "VMotionPhotoSource" to "1", "VMediaKitVersion" to "1.0.0.9").map { (field, value) -> Change("{$VIVO_URI}$field", after = Value.Text(value), reason = "Write requested minimal vivo binding", requested = true) } else emptyList()
        val huaweiChanges = if (huaweiPlan != null) listOf(Change("huawei:tail:first-field", after = Value.Text(huaweiPlan.tail.slice(0, 6).toByteArray().decodeToString().trimEnd(' ')), reason = "Write explicitly raw fixed-tail prefix", requested = true),
            Change("huawei:tail:history-field", after = Value.Text(huaweiPlan.tail.slice(20, 28).toByteArray().decodeToString().trimEnd(' ')), reason = "Write raw zero/count fields without claiming a timestamp unit", requested = true),
            Change("huawei:tail:LIVE", after = Value.Text(huaweiPlan.tail.slice(40, 60).toByteArray().decodeToString().trimEnd(' ')), reason = "Write bounded pure-video length plus 20", requested = true)) else emptyList()
        val allChanges = changes + vendorChanges + vivoChanges + huaweiChanges + if (oplus) listOf(
            Change("{$OPLUS_URI}VideoLength", after = Value.Text(videoIdentity.size.toString()), reason = "Write pure Oplus video length", requested = true),
            Change("{$OPLUS_URI}MotionPhotoPrimaryPresentationTimestampUs", after = Value.Text(timestamp.toString()), reason = "Synchronize the Oplus timestamp", requested = true),
            Change("{$OPLUS_URI}MotionPhotoOwner", after = Value.Text("oplus"), reason = "Write Oplus ownership", requested = true),
            Change("{$OPLUS_URI}OLivePhotoVersion", after = Value.Text("2"), reason = "Write the implemented Oplus version", requested = true),
            Change("exif:UserComment", after = Value.Text(OPLUS_MARKER), reason = "Write the requested vendor recognition marker", requested = true)) else emptyList()
        publish(request.output, request.policy, request.context, originalInputs + image.readers + videoReader, listOf(asset), sourceChanges + allChanges).orThrow()
    }

    suspend fun extract(request: ExtractRequest): CoreResult<OperationResult> = attempt {
        RequestValidation.validate(request).orThrow()
        val budget = ParseBudget(request.context)
        val session = SourceSession.open(request.input, request.context, budget).orThrow()
        if (request.snapshot != null && request.snapshot != session.snapshot) fail("SOURCE_CHANGED", "Extraction snapshot is stale", Stage.Extract)
        val selected = if (request.resources.isEmpty()) session.inspection.layout.resources.filter { it.kind == ResourceKind.Video || (session.legacyPair != null || session.applePair != null) && it.kind == ResourceKind.PrimaryImage }.distinctBy { it.extents.map { extent -> extent.source to extent.range } }.map { it.id } else request.resources
        if (selected.distinct().size != selected.size) fail("INVALID_ARGUMENT", "Duplicate extraction resource IDs")
        checkResourceAliases(session, selected)
        val assets = mutableListOf<StagedAsset>()
        for (id in selected) {
            val resource = session.inspection.layout.resources.firstOrNull { it.id == id } ?: fail("INVALID_ARGUMENT", "Resource ID does not belong to current source inspection")
            if (resource.extents.size != 1) fail("CAPABILITY_UNSUPPORTED", "Selected resource is not a contiguous raw extent")
            val range = resource.extents.single().range
            val binding = session.bindings.firstOrNull { videoId(it.protocol) == id }
            val video = binding?.let { session.videos[it.protocol] }
            val role = when (resource.kind) { ResourceKind.Video -> AssetRole.MotionVideo; ResourceKind.PrimaryImage -> AssetRole.PrimaryImage; ResourceKind.GainMap, ResourceKind.Depth, ResourceKind.Thumbnail -> AssetRole.AuxiliaryImage; ResourceKind.Trailer -> AssetRole.VendorTrailer; else -> AssetRole.SidecarMetadata }
            val mime = if (resource.kind == ResourceKind.PrimaryImage) session.inspection.media.firstOrNull()?.mime ?: "application/octet-stream" else if (resource.kind == ResourceKind.GainMap && session.gainMaps.any { it.range == range }) "image/jpeg" else if (video?.container == VideoContainer.Mov) "video/quicktime" else if (video?.container == VideoContainer.Mp4) "video/mp4" else binding?.items?.lastOrNull()?.mime ?: "application/octet-stream"
            assets += rawAsset(session, range, role, mime, request.context, video?.container, session.readerFor(resource.extents.single().source))
        }
        if (request.includeRawCarrier) {
            for (reader in session.readers) {
                val identity = reader.identity().orThrow()
                assets += rawAsset(session, ByteRange(0uL, identity.size), AssetRole.Composite, "application/octet-stream", request.context, inputReader = reader)
            }
        }
        if (assets.isEmpty()) fail("MOTION_VIDEO_MISSING", "No requested embedded resources are available", Stage.Extract)
        publish(request.output, MutationPolicy(), request.context, session.readers, assets).orThrow()
    }

    suspend fun split(request: SplitRequest): CoreResult<OperationResult> = attempt {
        RequestValidation.validate(request).orThrow()
        val budget = ParseBudget(request.context)
        val session = SourceSession.open(request.input, request.context, budget).orThrow()
        if (session.legacyPair != null) return@attempt VivoPairOperations.split(request, session).orThrow()
        if (session.applePair != null && request.mode == SplitMode.Clean) return@attempt ApplePairOperations.split(request, session).orThrow()
        if (request.mode == SplitMode.Raw) {
            val primary = session.inspection.layout.resources.firstOrNull { it.kind == ResourceKind.PrimaryImage }?.extents?.singleOrNull()?.range ?: fail("UNSUPPORTED_CONTAINER", "Raw split requires a contiguous primary range")
            val assets = mutableListOf(rawAsset(session, primary, AssetRole.PrimaryImage, session.inspection.media.firstOrNull()?.mime ?: "application/octet-stream", request.context))
            for (resource in session.inspection.layout.resources.filter { it.kind == ResourceKind.GainMap }.distinctBy { it.extents.map { extent -> extent.source to extent.range } }) assets += rawAsset(session, resource.extents.single().range, AssetRole.AuxiliaryImage, "image/jpeg", request.context)
            for (resource in session.inspection.layout.resources.filter { it.kind == ResourceKind.Video }.distinctBy { it.extents.map { extent -> extent.range } }) {
                val binding = session.bindings.first { videoId(it.protocol) == resource.id }
                val video = session.videos[binding.protocol]
                assets += rawAsset(session, resource.extents.single().range, AssetRole.MotionVideo, video?.let { videoFacts(it).mime } ?: "application/octet-stream", request.context, video?.container, session.readerFor(resource.extents.single().source))
            }
            for (resource in session.inspection.layout.resources.filter { it.kind == ResourceKind.Trailer && it.extents.singleOrNull()?.owner in setOf(ProtocolIds.Oplus, ProtocolIds.Huawei) }) assets += rawAsset(session, resource.extents.single().range, AssetRole.VendorTrailer, "application/octet-stream", request.context)
            return@attempt publish(request.output, request.policy, request.context, session.readers, assets).orThrow()
        }
        if (session.inspection.detection.disposition == Disposition.Ambiguous) fail("AMBIGUOUS_PROTOCOL", "Clean needs one trusted resource graph")
        val oplus = session.bindings.any { it.protocol == ProtocolIds.Oplus }
        val vivo = session.bindings.any { it.protocol == ProtocolIds.VivoModern } || session.gainMaps.isNotEmpty()
        val samsungPlan = if (session.sef != null) SamsungJpegWriter.cleanPlan(session, request.context, budget).orThrow() else null
        val rewrite = if (session.bindings.any { it.protocol == ProtocolIds.Huawei }) HuaweiJpegWriter.cleanPlan(session).orThrow() else if (samsungPlan != null) samsungPlan.image else if (vivo) VivoJpegWriter.cleanPlan(session, request.context, budget).orThrow() else if (oplus) OplusJpegWriter.cleanPlan(session, request.context, budget).orThrow() else GoogleJpegWriter.cleanPlan(session, request.context).orThrow()
        val coding = codingDigest(session)
        val metadata = ordinaryDigest(session, verifiedExifRewrite = oplus)
        val image = StagedAsset(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/jpeg"), ImageFormat.Jpeg,
            write = { writer -> JpegRewrite.write(session.reader, writer, session.jpeg!!, rewrite, request.context).orThrow(); if (samsungPlan != null) SefWriter.write(samsungPlan.suffix, writer, request.context).orThrow(); if (vivo) for (gainMap in session.gainMaps) copyRange(session.reader, writer, gainMap.range, request.context).orThrow() },
            verify = { id, reader ->
                val staged = SourceSession.open(SourceSet.Single(reader.source), request.context, budget).orThrow()
                if (staged.bindings.isNotEmpty() || staged.jpeg?.trailing?.length != 0uL && ((staged.sef == null || staged.sef.motionRecord != null || staged.sef.gaps.isNotEmpty()) && staged.gainMaps.none { it.range == staged.jpeg?.trailing })) fail("POSTCONDITION_FAILED", "Clean image still contains live bindings or hidden suffix", Stage.Verify)
                val outputCoding = codingDigest(staged)
                val outputMetadata = ordinaryDigest(staged, verifiedExifRewrite = oplus)
                if (coding != outputCoding || metadata != outputMetadata) fail("POSTCONDITION_FAILED", "Clean changed image coding or ordinary metadata", Stage.Verify)
                AssetVerification(validateSession(staged, listOf(Layer.Structure)).orThrow(), listOf(
                    GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable),
                    GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.NotApplicable),
                    GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.Verified, coding, outputCoding, "Primary JPEG coding and every validated GainMap resource preserved"),
                    GuaranteeRecord(id, Guarantee.MetadataPreserving, if (opaqueOffsetsPreserved(session, staged)) GuaranteeOutcome.Verified else GuaranteeOutcome.Unknown, metadata, outputMetadata, "Only owned Google bindings removed; opaque APP positions checked separately")))
            })
        val assets = mutableListOf(image)
        val binding = session.bindings.firstOrNull { it.selector == session.inspection.detection.primaryProtocol } ?: session.bindings.singleOrNull()
        if (session.bindings.isNotEmpty()) {
            val range = binding?.video ?: fail("MOTION_VIDEO_MISSING", "Clean needs a safe video range")
            val video = session.videos[binding.protocol] ?: fail("CAPABILITY_UNSUPPORTED", "Clean needs verified video structure")
            assets += rawAsset(session, range, AssetRole.MotionVideo, videoFacts(video).mime!!, request.context, video.container)
        }
        val changes = session.inspection.metadata.filter { it.owner == Ownership.SourceProtocol }.map { entry -> Change(entry.selector, entry.value, null, "Remove owned live-photo binding for clean split", true) } +
            if (session.bindings.any { it.protocol == ProtocolIds.GoogleV2 }) listOf(Change("{$CONTAINER_URI}Directory", after = if (session.gainMaps.isEmpty()) null else Value.Text("Preserved Primary/GainMap resource directory"), reason = "Remove owned MotionPhoto item and preserve ordinary auxiliary graph", requested = true)) else emptyList()
        publish(request.output, request.policy, request.context, session.readers, assets, changes).orThrow()
    }

    suspend fun plan(request: MutationRequest): CoreResult<ExecutionPlan> = attempt {
        RequestValidation.validate(request).orThrow()
        val input: SourceSet
        val context: Context
        val operation: Operation
        val target: ProtocolSelector?
        when (request) {
            is CreateRequest -> { input = SourceSet.Single(request.image); context = request.context; operation = Operation.Create; target = request.target }
            is ExtractRequest -> { input = request.input; context = request.context; operation = Operation.ExtractRaw; target = null }
            is SplitRequest -> { input = request.input; context = request.context; operation = if (request.mode == SplitMode.Clean) Operation.SplitClean else Operation.ExtractRaw; target = null }
            else -> fail("CAPABILITY_PLANNED", "Operation planning is not implemented for this request", Stage.Plan)
        }
        val budget = ParseBudget(context)
        val session = SourceSession.open(input, context, budget).orThrow()
        var snapshot = session.snapshot
        if (request is CreateRequest) {
            if (context.limits.maxSources < 2u) fail("RESOURCE_LIMIT_EXCEEDED", "Create planning requires two input sources")
            val videoReader = BinaryReader(request.video, context)
            val identity = videoReader.identity().orThrow()
            if (session.snapshot.identities.any { it.id == identity.id }) fail("INVALID_ARGUMENT", "Create source IDs must be distinct")
            val video = BmffVideoProbe(videoReader, budget).probe(ByteRange(0uL, identity.size)).orThrow()
            requireGoogleWriteVideo(video, request.target)
            if (Guarantee.ExactExtraction in request.policy.requiredGuarantees) fail("PRESERVATION_REQUIREMENT_FAILED", "Whole-asset exact extraction is not applicable to Create")
            if (request.edits?.trim != null || request.edits?.replacementFrame != null) fail("CAPABILITY_UNSUPPORTED", "Requested media edits require backend orchestration")
            if (request.preference.imageFormat != null && request.preference.imageFormat != ImageFormat.Jpeg || request.preference.videoContainer != null && request.preference.videoContainer != video.container ||
                request.preference.videoCodec != null && video.tracks.filter { it.handler == "vide" }.any { it.codec != request.preference.videoCodec } ||
                request.preference.audioCodec != null && video.tracks.filter { it.handler == "soun" }.any { it.audioCodec != request.preference.audioCodec } || request.preference.dynamicRange != DynamicRangePolicy.Preserve) fail("CAPABILITY_UNSUPPORTED", "Plan requires an unimplemented media transformation")
            val key = if (request.target.protocol == ProtocolIds.Huawei) KeyPhotoResult() else selectKey(video, request.edits?.keyPosition)
            if (request.target.protocol == ProtocolIds.Huawei) {
                if (!basicMediaEnvelope(videoReader, ByteRange(0uL, identity.size), budget)) fail("UNKNOWN_PROTOCOL_VARIANT", "Huawei plan cannot bind unknown media extensions", Stage.Plan)
                if (request.target.profile != null && request.target.profile != ProfileId("basic60")) fail("UNSUPPORTED_PROTOCOL", "Huawei plan only implements basic60")
                HuaweiJpegWriter.createPlan(session, video, identity.size, request.sourceBindings == SourceBindingPolicy.StripSourceBindings, request.edits?.keyPosition, budget).orThrow()
            } else if (request.target.protocol == ProtocolIds.Samsung) {
                if (request.target.profile != null && request.target.profile != ProfileId("jpeg-sef-mpv3")) fail("UNSUPPORTED_PROTOCOL", "Samsung plan only implements jpeg-sef-mpv3")
                SamsungJpegWriter.createPlan(session, videoReader, ByteRange(0uL, identity.size), microseconds(key.position!!), request.sourceBindings == SourceBindingPolicy.StripSourceBindings, context, budget).orThrow()
            } else if (request.target.protocol == ProtocolIds.VivoModern) {
                if (request.target.profile != null && request.target.profile != ProfileId("jpeg")) fail("UNSUPPORTED_PROTOCOL", "vivo plan only implements jpeg")
                VivoJpegWriter.createPlan(session, identity.size, microseconds(key.position!!), request.sourceBindings == SourceBindingPolicy.StripSourceBindings, context, budget).orThrow()
            } else if (request.target.protocol == ProtocolIds.Oplus) {
                if (request.target.profile != null && request.target.profile != ProfileId("jpeg-no-tail")) fail("UNSUPPORTED_PROTOCOL", "Oplus plan only implements jpeg-no-tail")
                OplusJpegWriter.createPlan(session, identity.size, microseconds(key.position!!), request.sourceBindings == SourceBindingPolicy.StripSourceBindings, context, budget).orThrow()
            } else GoogleJpegWriter.createPlan(session, request.target, identity.size, videoFacts(video).mime!!, microseconds(key.position!!), request.sourceBindings == SourceBindingPolicy.StripSourceBindings, context).orThrow()
            val hash = Sha256()
            hash.update(Bytes(session.snapshot.token.value.encodeToByteArray()))
            for (field in listOf(identity.id.value, identity.generation.value, identity.size.toString(), identity.digest?.value ?: "")) {
                val bytes = Bytes(field.encodeToByteArray()); hash.update(unsignedBytes(bytes.size.toULong(), 8, Endian.Big)); hash.update(bytes)
            }
            snapshot = Snapshot(session.snapshot.identities + identity, GenerationToken(hash.finish().value))
        } else if (request is SplitRequest && request.mode == SplitMode.Clean) {
            if (session.inspection.detection.disposition == Disposition.Ambiguous) fail("AMBIGUOUS_LAYOUT", "Clean plan needs one trusted resource graph")
            if (session.applePair != null) AppleClean.prepare(session, budget).orThrow()
            else if (session.legacyPair != null) VivoPairOperations.preflightClean(session) else if (session.bindings.any { it.protocol == ProtocolIds.Huawei }) HuaweiJpegWriter.cleanPlan(session).orThrow() else if (session.sef != null) SamsungJpegWriter.cleanPlan(session, context, budget).orThrow() else if (session.bindings.any { it.protocol == ProtocolIds.VivoModern } || session.gainMaps.isNotEmpty()) VivoJpegWriter.cleanPlan(session, context, budget).orThrow() else if (session.bindings.any { it.protocol == ProtocolIds.Oplus }) OplusJpegWriter.cleanPlan(session, context, budget).orThrow() else GoogleJpegWriter.cleanPlan(session, context).orThrow()
        }
        else if (request is ExtractRequest) {
            if (request.snapshot != null && request.snapshot != session.snapshot) fail("SOURCE_CHANGED", "Extraction plan snapshot is stale")
            if (request.resources.any { id -> session.inspection.layout.resources.none { it.id == id } }) fail("INVALID_ARGUMENT", "Extraction plan refers to unknown resource")
            checkResourceAliases(session, request.resources)
        }
        val output = when (request) { is CreateRequest -> request.output; is ExtractRequest -> request.output; is SplitRequest -> request.output }
        val policy = when (request) { is CreateRequest -> request.policy; is SplitRequest -> request.policy; else -> MutationPolicy() }
        val outputCaps = output.capabilities()
        if (!outputCaps.canReadStaged || policy.atomicity == Atomicity.AssetSetRequired && !outputCaps.assetSetAtomic || policy.existingOutput == ExistingOutput.Replace && !outputCaps.replacesAtomically) fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Plan cannot satisfy requested transaction guarantees")
        val implemented = (session.jpeg != null || operation == Operation.ExtractRaw && session.bindings.isNotEmpty()) && (target == null || target.protocol in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2, ProtocolIds.Oplus, ProtocolIds.Samsung, ProtocolIds.VivoModern, ProtocolIds.Huawei) && (target.profile == null || target.profile == ProfileId(if (target.protocol == ProtocolIds.Oplus) "jpeg-no-tail" else if (target.protocol == ProtocolIds.Samsung) "jpeg-sef-mpv3" else if (target.protocol == ProtocolIds.Huawei) "basic60" else "jpeg")))
        val entry = CapabilityEntry(operation, if (!implemented) Implementation.Unsupported else if (operation == Operation.ExtractRaw && session.jpeg != null) Implementation.Supported else Implementation.Experimental,
            conditions = listOf(Condition(ConditionOperator.Equals, "sourceContent", Value.Text(session.inspection.media.firstOrNull()?.mime ?: "unknown"))), verification = listOf(Verification.SourceReviewed))
        ExecutionPlan(snapshot, target, listOf(PlanStep(Stage.Verify, listOf(Operation.Validate), session.inspection.layout.resources.map { it.id }, "Verify staging and source identities before atomic publication")),
            PreservationReport(), CapabilitySet(if (implemented) Availability.Conditional else Availability.Unsupported, listOf(entry)))
    }

    internal suspend fun rawAsset(session: SourceSession, range: ByteRange, role: AssetRole, mime: String, context: Context, container: VideoContainer? = null, inputReader: BinaryReader = session.reader): StagedAsset {
        val digest = sha256Range(inputReader, range).orThrow()
        return StagedAsset(OutputAssetSpec(role, mime = mime), if (role == AssetRole.PrimaryImage || role == AssetRole.Composite) session.inspection.media.firstOrNull()?.imageFormat else if (role == AssetRole.AuxiliaryImage && session.gainMaps.any { it.range == range }) ImageFormat.Jpeg else null, container,
            write = { writer -> copyRange(inputReader, writer, range, context).orThrow() },
            verify = { id, reader ->
                val size = reader.identity().orThrow().size
                if (size != range.length || sha256Range(reader, ByteRange(0uL, size)).orThrow() != digest) fail("POSTCONDITION_FAILED", "Raw extraction is not byte-identical", Stage.Verify)
                val check = CheckResult("extraction.sha256", Layer.Preservation, Verdict.Valid, Coverage.Complete)
                val jpeg = session.jpeg
                val metadataSafe = role == AssetRole.Composite || role == AssetRole.MotionVideo || session.legacyPair != null && range == ByteRange(0uL, inputReader.identity().orThrow().size) || role == AssetRole.PrimaryImage && jpeg != null && jpeg.trailing.length == 0uL && !jpeg.hasMpf
                val videoVerified = if (role == AssetRole.Composite) session.videos.isNotEmpty() else session.bindings.any { it.video == range && it.protocol in session.videos }
                val imageVerified = if (role == AssetRole.AuxiliaryImage) session.gainMaps.any { it.range == range } else session.inspection.media.firstOrNull()?.width != null && session.inspection.media.firstOrNull()?.height != null
                AssetVerification(ValidationReport(Verdict.Valid, Coverage.Complete, listOf(check), snapshot = session.snapshot), exactRecords(id, digest, role, metadataSafe, videoVerified, imageVerified))
            })
    }
}

internal fun checkResourceAliases(session: SourceSession, selected: List<ResourceId>): Unit {
    val resources = selected.map { id -> session.inspection.layout.resources.firstOrNull { it.id == id }
        ?: fail("INVALID_ARGUMENT", "Resource ID does not belong to current source inspection") }
    val aliases = resources.groupBy { it.extents.map { extent -> extent.source to extent.range } }.values.firstOrNull { it.size > 1 } ?: return
    throw CoreFault(CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Plan, "Explicit resource IDs refer to the same source bytes",
        details = mapOf("resourceIds" to Value.ArrayValue(aliases.map { Value.Text(it.id.value) }),
            "source" to Value.Text(aliases.first().extents.first().source.value),
            "offset" to Value.Number(aliases.first().extents.first().range.offset.toString()),
            "length" to Value.Number(aliases.first().extents.first().range.length.toString()))))
}

internal fun selectKey(video: VideoStructure, requested: CoverPosition?): KeyPhotoResult {
    val track = when (requested) {
        is CoverPosition.FrameIndex -> video.tracks.firstOrNull { it.handler == "vide" && (requested.trackId == null || TrackId(it.trackId.toString()) == requested.trackId) }
        else -> video.tracks.firstOrNull { it.handler == "vide" }
    } ?: fail("FRAME_INDEX_UNAVAILABLE", "No eligible presentation video track")
    if (video.tracks.count { it.handler == "vide" } > 1 && (requested !is CoverPosition.FrameIndex || requested.trackId == null)) fail("FRAME_INDEX_UNAVAILABLE", "Multiple video tracks need an explicit presentation track")
    val samples = track.samples.filter { it.presentationTime >= 0 && Time(it.presentationTime, track.timescale) < track.presentationDuration }.sortedBy { it.presentationTime }
    if (samples.isEmpty()) fail("FRAME_INDEX_UNAVAILABLE", "Video has no presented samples")
    val requestedTime = (requested as? CoverPosition.Timestamp)?.time
    val position = when (requested) {
        is CoverPosition.FrameIndex -> samples.getOrNull(if (requested.index > Int.MAX_VALUE.toULong()) -1 else requested.index.toInt())?.let { Time(it.presentationTime, track.timescale) } ?: fail("FRAME_INDEX_OUT_OF_RANGE", "Presentation frame index exceeds track")
        is CoverPosition.Timestamp -> {
            if (requestedTime!! < Time.Zero || requestedTime >= track.presentationDuration) fail("INVALID_PRESENTATION_TIMESTAMP", "Key position is outside presented video")
            val actual = when (requested.selection) {
                Selection.AtOrBefore -> samples.lastOrNull { Time(it.presentationTime, track.timescale) <= requestedTime }
                Selection.Exact -> samples.firstOrNull { Time(it.presentationTime, track.timescale).compareTo(requestedTime) == 0 }
                Selection.Nearest -> samples.minByOrNull { sample ->
                    // Normalize within exact signed microseconds; unrepresentable comparisons
                    // remain unsupported rather than silently selecting through floating point.
                    val sampleUs = microseconds(Time(sample.presentationTime, track.timescale))
                    val requestedUs = microseconds(requestedTime)
                    if (sampleUs >= requestedUs) sampleUs - requestedUs else requestedUs - sampleUs
                }
            } ?: fail("INVALID_PRESENTATION_TIMESTAMP", "Requested selection has no eligible presentation sample")
            val actualTime = Time(actual.presentationTime, track.timescale)
            val deltaUs = microseconds(actualTime).let { value -> val expected = microseconds(requestedTime); if (value >= expected) value - expected else expected - value }
            if (Time(deltaUs, 1_000_000u) > requested.tolerance) fail("INVALID_PRESENTATION_TIMESTAMP", "Selected presentation sample exceeds requested tolerance")
            actualTime
        }
        null -> {
            val middle = Time(track.presentationDuration.value / 2, track.presentationDuration.timescale)
            samples.lastOrNull { Time(it.presentationTime, track.timescale) <= middle }?.let { Time(it.presentationTime, track.timescale) } ?: Time(samples.first().presentationTime, track.timescale)
        }
    }
    return KeyPhotoResult(position, source = if (requested == null) KeySource.DerivedDefault else KeySource.ProtocolField)
}

private fun microseconds(time: Time): Long {
    if (time.value < 0) fail("INVALID_PRESENTATION_TIMESTAMP", "Key timestamp must be nonnegative")
    val whole = checkedMultiply((time.value / time.timescale.toLong()).toULong(), 1_000_000uL)
    val fractional = (time.value % time.timescale.toLong()).toULong() * 1_000_000uL / time.timescale.toULong()
    if ((time.value % time.timescale.toLong()).toULong() * 1_000_000uL % time.timescale.toULong() != 0uL) fail("VALUE_NOT_REPRESENTABLE", "Presentation time cannot be expressed exactly in integer microseconds")
    val result = checkedAdd(whole, fractional)
    if (result > Long.MAX_VALUE.toULong()) fail("VALUE_NOT_REPRESENTABLE", "Key timestamp exceeds signed microsecond range")
    return result.toLong()
}
