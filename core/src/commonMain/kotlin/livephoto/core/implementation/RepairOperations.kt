package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.xml.*
import livephoto.core.xmp.*
import livephoto.core.jpeg.*
import livephoto.core.vivo.*
import livephoto.core.oplus.*
import livephoto.core.exif.ExifPositionIndependenceProof

/** Empty issue filters allow every proven-safe repair; dryRun remains independently enforced. */
internal object RepairOperations {
    private data class Prepared(val session: SourceSession, val result: RepairResult, val rewrite: JpegRewritePlan? = null,
        val fixed: BinarySource? = null, val protocol: ProtocolId = ProtocolIds.GoogleV1,
        val exifProofs: List<ExifPositionIndependenceProof> = emptyList())
    suspend fun repair(request: RepairRequest): CoreResult<RepairResult> = attempt {
        if (request.mode == RepairMode.ExplicitRePair) return@attempt AppleRepairOperations.repair(request).orThrow()
        val budget = ParseBudget(request.context)
        val source = open(request, budget)
        if (source.heifItems != null) return@attempt GoogleHeicRepairOperations.repair(request, source, budget).orThrow()
        val prepared = prepare(request, source, budget)
        val result = prepared.result
        if (request.dryRun || result.blocked.isNotEmpty() || result.proposedChanges.isEmpty()) return@attempt result
        val session = prepared.session
        val video = if (prepared.protocol == ProtocolIds.Samsung) session.sef!!.pureVideoRange!! else session.jpeg!!.trailing
        val expectedReader = prepared.fixed?.let { BinaryReader(it, request.context) }
        val expectedHash = expectedReader?.let { sha256Range(it, ByteRange(0uL, it.identity().orThrow().size)).orThrow() }
        val videoHash = sha256Range(session.reader, video).orThrow()
        val imageHash = codingDigest(session)
        val metadataHash = ordinaryDigest(session)
        var after: List<Issue> = emptyList()
        val asset = StagedAsset(OutputAssetSpec(AssetRole.Composite, mime = "image/jpeg"), ImageFormat.Jpeg,
            write = { writer ->
                if (expectedReader != null) copyRange(expectedReader, writer, ByteRange(0uL, expectedReader.identity().orThrow().size), request.context).orThrow()
                else {
                    JpegRewrite.write(session.reader, writer, session.jpeg!!, prepared.rewrite!!, request.context).orThrow()
                    copyRange(session.reader, writer, video, request.context).orThrow()
                }
            },
            verify = { id, reader ->
                val staged = SourceSession.open(SourceSet.Single(reader.source), request.context, ParseBudget(request.context)).orThrow()
                val report = validateSession(staged, listOf(Layer.Structure, Layer.Protocol)).orThrow()
                val binding = staged.bindings.singleOrNull { it.protocol == prepared.protocol }
                if (report.verdict == Verdict.Invalid || report.coverage != Coverage.Complete || binding == null ||
                    !binding.structurallyValid || (prepared.protocol in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2, ProtocolIds.VivoModern, ProtocolIds.Oplus) && binding.video != staged.jpeg?.trailing) || binding.protocol !in staged.videos ||
                    session.bindings.map { it.selector }.toSet() != staged.bindings.map { it.selector }.toSet())
                    fail("POSTCONDITION_FAILED", "Repaired carrier failed complete structural and protocol validation", Stage.Verify)
                if (expectedHash != null && sha256Range(reader, ByteRange(0uL, reader.identity().orThrow().size)).orThrow() != expectedHash)
                    fail("POSTCONDITION_FAILED", "Repair changed bytes outside the proven fixed-width patch", Stage.Verify)
                prepared.rewrite?.let { rewrite ->
                    val packet = staged.jpeg!!.segments.singleOrNull { it.payloadKind == AppPayloadKind.Xmp }
                        ?: fail("POSTCONDITION_FAILED", "Repair lost its unique planned XMP packet", Stage.Verify)
                    if (reader.readExactly(packet.range.offset, checkedInt(packet.range.length).toUInt()).orThrow() != rewrite.patches.single().replacement)
                        fail("POSTCONDITION_FAILED", "Repair changed unrequested metadata outside the proved XMP patch", Stage.Verify)
                }
                for (field in V1_FIELDS - "MicroVideoOffset") if (session.xmp!!.scalar(CAMERA_URI, field).orThrow() != staged.xmp!!.scalar(CAMERA_URI, field).orThrow())
                    fail("POSTCONDITION_FAILED", "Repair changed an unrequested protocol field", Stage.Verify)
                val outVideo = sha256Range(reader, binding.video!!).orThrow()
                val outImage = codingDigest(staged)
                val outMetadata = ordinaryDigest(staged)
                if (outVideo != videoHash || outImage != imageHash || outMetadata != metadataHash)
                    fail("POSTCONDITION_FAILED", "Repair changed media bytes or ordinary metadata", Stage.Verify)
                val originalIssues = result.issuesBefore.map { Triple(it.code, it.layer, it.severity) }.toSet()
                val introduced = staged.inspection.issues.firstOrNull { Triple(it.code, it.layer, it.severity) !in originalIssues }
                if (introduced != null) throw CoreFault(CoreError(IssueCode("POSTCONDITION_FAILED"), Stage.Verify,
                    "Repair introduced a new inspection issue", introduced.location, details = mapOf("issueCode" to Value.Text(introduced.code.value),
                        "layer" to Value.Text(introduced.layer.name), "severity" to Value.Text(introduced.severity.name))))
                after = staged.inspection.issues
                AssetVerification(report, listOf(
                    GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable),
                    GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.Verified, imageHash, outImage),
                    GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.Verified, videoHash, outVideo),
                    GuaranteeRecord(id, Guarantee.MetadataPreserving, if (opaqueOffsetsPreserved(session, staged, prepared.exifProofs)) GuaranteeOutcome.Verified else GuaranteeOutcome.Unknown, metadataHash, outMetadata)), staged.inspection.keyPhoto)
            })
        val operation = publish(request.output!!, request.policy, request.context, session.readers, listOf(asset), result.proposedChanges).orThrow()
        result.copy(changesApplied = result.proposedChanges, issuesAfter = after, operation = operation)
    }

    suspend fun plan(request: RepairRequest): CoreResult<ExecutionPlan> = attempt {
        if (request.mode == RepairMode.ExplicitRePair) return@attempt AppleRepairOperations.plan(request).orThrow()
        val budget = ParseBudget(request.context)
        val source = open(request, budget)
        if (source.heifItems != null) return@attempt GoogleHeicRepairOperations.plan(request, source, budget).orThrow()
        val prepared = prepare(request, source, budget)
        val blocked = prepared.result.blocked
        if (!request.dryRun && blocked.isEmpty() && (prepared.rewrite != null || prepared.fixed != null)) {
            val caps = request.output!!.capabilities()
            if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic || request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
                fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Repair requires verified atomic publication", Stage.Plan)
        }
        prepared.session.recheck()
        ExecutionPlan(prepared.session.snapshot, prepared.session.bindings.single { it.protocol == prepared.protocol }.selector,
            listOf(PlanStep(if (request.dryRun) Stage.Plan else Stage.WriteProtocol, listOf(Operation.Repair), prepared.session.inspection.layout.resources.map { it.id }, if (request.dryRun) "Read-only proven-metadata preview; no staging or publication" else "Repair only proven metadata; validate staging before atomic publication")),
            PreservationReport(changes = prepared.result.proposedChanges), CapabilitySet(if (blocked.isEmpty()) Availability.Conditional else Availability.Unsupported,
                listOf(CapabilityEntry(Operation.Repair, if (blocked.isEmpty()) Implementation.Experimental else Implementation.Unsupported,
                    conditions = listOf(Condition(ConditionOperator.Equals, "repairScope", Value.Text(when (prepared.protocol) {
                        ProtocolIds.Samsung -> "unique-sef-legacy-footer-length"
                        ProtocolIds.GoogleV2 -> "unique-inline-primary-motion-directory-length-no-padding-or-auxiliary-resources"
                        ProtocolIds.VivoModern -> "vivo-version-one-inline-primary-motion-length-explicit-zero-padding-no-auxiliary-resources"
                        ProtocolIds.Oplus -> "known-oplus-marker-inline-primary-motion-d-v-lengths-no-trailer-or-auxiliary-resources"
                        else -> "unique-google-v1-offset"
                    }))), reasons = blocked.map { it.code })), blocked))
    }

    private suspend fun open(request: RepairRequest, budget: ParseBudget): SourceSession {
        RequestValidation.validate(request).orThrow()
        if (request.mode != RepairMode.SafeMetadataOnly || request.authority != null || request.policy.authority != null)
            fail("CAPABILITY_UNSUPPORTED", "This preview cannot remux, re-pair, or select conflicting authority", Stage.Plan)
        if (request.input !is SourceSet.Single) fail("REPAIR_AMBIGUOUS", "Offset preview requires one explicit carrier", Stage.Plan)
        return SourceSession.open(request.input, request.context, budget, probeEmbeddedVideo = false).orThrow()
    }
    private suspend fun prepare(request: RepairRequest, session: SourceSession, budget: ParseBudget): Prepared {
        val jpeg = session.jpeg ?: fail("REPAIR_NOT_POSSIBLE", "Offset preview requires a parsed JPEG", Stage.Plan)
        if (session.bindings.any { it.protocol == ProtocolIds.Samsung }) return prepareSamsung(request)
        if (session.bindings.map { it.protocol }.toSet() == setOf(ProtocolIds.GoogleV2, ProtocolIds.VivoModern) && session.bindings.size == 2)
            return prepareInlineLength(request, session, budget, ProtocolIds.VivoModern)
        if (session.bindings.map { it.protocol }.toSet() == setOf(ProtocolIds.GoogleV2, ProtocolIds.Oplus) && session.bindings.size == 2)
            return prepareInlineLength(request, session, budget, ProtocolIds.Oplus)
        if (session.bindings.size == 1 && session.bindings.single().protocol == ProtocolIds.GoogleV2) return prepareInlineLength(request, session, budget)
        if (session.bindings.size != 1 || session.bindings.single().protocol != ProtocolIds.GoogleV1 || session.gainMaps.isNotEmpty())
            fail("REPAIR_AMBIGUOUS", "Preview requires only the Google V1 authority and no auxiliary resource graph", Stage.Plan)
        val xmp = session.xmp!!
        if (!xmp.rewriteAllowed || xmp.scalar(CAMERA_URI, "MicroVideo").orThrow() != "1" || xmp.scalar(CAMERA_URI, "MicroVideoVersion").orThrow() != "1")
            fail("REPAIR_AMBIGUOUS", "Preview cannot choose duplicate, qualified, or unknown protocol authority", Stage.Plan)
        val field = "MicroVideoOffset"
        val old = xmp.scalar(CAMERA_URI, field).orThrow()
        val sourceIssues = session.inspection.issues
        if (sourceIssues.any { it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT", "CONFLICTING_METADATA") })
            fail("REPAIR_AMBIGUOUS", "Unknown or qualified metadata cannot authorize an offset proposal", Stage.Plan)
        val independentErrors = sourceIssues.filter { it.severity == Severity.Error && it.code.value !in setOf("MOTION_VIDEO_LENGTH_MISMATCH", "OFFSET_OUT_OF_BOUNDS", "MISSING_REQUIRED_XMP", "MALFORMED_XMP") }
        if (independentErrors.isNotEmpty()) return Prepared(session, RepairResult(sourceIssues, emptyList(), emptyList(), sourceIssues, blocked = independentErrors))
        if (jpeg.trailing.length == 0uL) fail("REPAIR_NOT_POSSIBLE", "JPEG has no complete physical suffix", Stage.Plan)
        // No byte scanning or magic guesses: only the exact post-EOI extent is eligible.
        val candidate = when (val result = BmffVideoProbe(session.reader, budget).probe(jpeg.trailing)) {
            is CoreResult.Success -> result.value
            is CoreResult.Failure -> {
                if (result.error.code.value in setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "RESOURCE_LIMIT_EXCEEDED")) throw CoreFault(result.error)
                throw CoreFault(CoreError(IssueCode("REPAIR_NOT_POSSIBLE"), Stage.Plan, "The whole post-JPEG extent is not one verified video",
                    details = mapOf("cause" to Value.Text(result.error.code.value))))
            }
        }
        if (googleVideoIssues(candidate, session.bindings.single().selector, false).isNotEmpty()) fail("REPAIR_NOT_POSSIBLE", "Physical suffix does not satisfy source profile", Stage.Plan)
        val rawKey = xmp.scalar(CAMERA_URI, "MicroVideoPresentationTimestampUs").orThrow()
        if (rawKey != null && rawKey != "-1") {
            val ticks = rawKey.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()
            val end = candidate.tracks.filter { it.handler == "vide" }.maxOf { it.presentationDuration }
            if (ticks == null || Time(ticks, 1_000_000u) >= end) {
                val invalidKey = Issue(IssueCode("INVALID_PRESENTATION_TIMESTAMP"), Severity.Error, Layer.Protocol,
                    Location(selector = "{$CAMERA_URI}MicroVideoPresentationTimestampUs"), observed = Value.Text(rawKey))
                val before = sourceIssues + invalidKey
                return Prepared(session, RepairResult(before, emptyList(), emptyList(), before, blocked = listOf(invalidKey)))
            }
        }
        val target = jpeg.trailing.length.toString()
        if (old == target) {
            val normal = SourceSession.open(request.input, request.context, budget).orThrow()
            return Prepared(normal, RepairResult(normal.inspection.issues, emptyList(), emptyList(), normal.inspection.issues))
        }
        val issue = Issue(IssueCode(if (old == null) "MISSING_REQUIRED_XMP" else "MOTION_VIDEO_LENGTH_MISMATCH"), Severity.Error, Layer.Protocol,
            Location(source = session.snapshot.identities.single().id, range = jpeg.trailing, selector = "{$CAMERA_URI}$field"),
            expected = Value.Text(target), observed = old?.let(Value::Text), repairability = Repairability.Safe)
        val before = sourceIssues + issue
        val repairedCodes = session.bindings.single().issues.filter { it.code.value in setOf("MOTION_VIDEO_LENGTH_MISMATCH", "OFFSET_OUT_OF_BOUNDS", "MISSING_REQUIRED_XMP", "MALFORMED_XMP") }.map { it.code } + issue.code
        if (request.allowedIssueCodes.isNotEmpty() && repairedCodes.none { it in request.allowedIssueCodes })
            return Prepared(session, RepairResult(before, emptyList(), emptyList(), before, blocked = listOf(issue)))
        val replacement = XmpWriter.merge(xmp.packets.single(), mapOf(ExpandedName(CAMERA_URI, field) to target), request.context).orThrow()
        // Even a preview must pass the existing EXIF/MPF/XMP relocation gate.
        val rewrite = GoogleJpegWriter.patch(jpeg, replacement)
        session.recheck()
        val change = Change("{$CAMERA_URI}$field", old?.let(Value::Text), Value.Text(target), "Unique verified complete video starts exactly after JPEG; offset is its byte length", true)
        return Prepared(session, RepairResult(before, listOf(change), emptyList(), before), rewrite)
    }

    private suspend fun prepareInlineLength(request: RepairRequest, session: SourceSession, budget: ParseBudget, protocol: ProtocolId = ProtocolIds.GoogleV2): Prepared {
        val jpeg = session.jpeg!!; val xmp = session.xmp!!
        val vivo = protocol == ProtocolIds.VivoModern
        val oplus = protocol == ProtocolIds.Oplus
        if (!xmp.rewriteAllowed || session.gainMaps.isNotEmpty() || xmp.scalar(CAMERA_URI, "MotionPhoto").orThrow() != "1" || xmp.scalar(CAMERA_URI, "MotionPhotoVersion").orThrow() != "1")
            fail("REPAIR_AMBIGUOUS", "V2 length repair requires one known standard authority without auxiliary dependencies", Stage.Plan)
        val packet = xmp.packets.single()
        val authority = V2_FIELDS.map { ExpandedName(CAMERA_URI, it) } +
            (if (vivo) VIVO_FIELDS.map { ExpandedName(VIVO_URI, it) } else if (oplus) OPLUS_FIELDS.map { ExpandedName(OPLUS_URI, it) } else emptyList())
        if (authority.any { packet.properties(it.uri, it.local).size > 1 })
            fail("REPAIR_AMBIGUOUS", "Duplicate protocol authority cannot be normalized even when scalar values agree", Stage.Plan)
        if (oplus) {
            val binding = session.bindings.single { it.protocol == protocol }
            if (binding.profile != ProfileId("jpeg-no-tail") || binding.trailer != null ||
                session.exifComments.flatMap { it.comments }.singleOrNull()?.text !in setOf(OPLUS_MARKER, "oplus_8388608") ||
                xmp.scalar(OPLUS_URI, "MotionPhotoOwner").orThrow() != "oplus" || xmp.scalar(OPLUS_URI, "OLivePhotoVersion").orThrow() !in setOf("1", "2") ||
                packet.descriptions.any { description ->
                    description.attributes.any { it.name.expanded.uri == OPLUS_URI && it.name.expanded.local !in OPLUS_FIELDS } ||
                        description.children.filterIsInstance<XmlElement>().any { it.name.expanded.uri == OPLUS_URI && (it.name.expanded.local !in OPLUS_FIELDS || it.attributes.isNotEmpty()) }
                }) fail("REPAIR_AMBIGUOUS", "Oplus length repair requires an existing known marker/profile without a declared trailer or unknown vendor fields", Stage.Plan)
        }
        val oldVendorLength = if (oplus) xmp.scalar(OPLUS_URI, "VideoLength").orThrow() else null
        if (oldVendorLength != null && (oldVendorLength.isEmpty() || oldVendorLength.any { it !in '0'..'9' } || oldVendorLength.toULongOrNull() == null))
            fail("REPAIR_AMBIGUOUS", "Unknown Oplus VideoLength lexical/overflow semantics cannot be guessed", Stage.Plan)
        if (vivo) {
            val known = mapOf("VMotionPhotoVersion" to "1", "VMotionPhotoSource" to "1", "VMediaKitVersion" to "1.0.0.9")
            if (known.any { (field, value) -> xmp.scalar(VIVO_URI, field).orThrow() != value } ||
                packet.descriptions.any { description -> description.attributes.any { it.name.expanded.uri == VIVO_URI && it.name.expanded.local !in VIVO_FIELDS } ||
                    description.children.filterIsInstance<XmlElement>().any { it.name.expanded.uri == VIVO_URI && it.name.expanded.local !in VIVO_FIELDS } })
                fail("REPAIR_AMBIGUOUS", "Vivo repair requires the known minimal version-one profile; unknown vendor fields cannot authorize a rewrite", Stage.Plan)
        }
        val directory = packet.properties(CONTAINER_URI, "Directory").singleOrNull()?.element
            ?: fail("REPAIR_AMBIGUOUS", "V2 length repair requires exactly one inline directory", Stage.Plan)
        fun inline(element: XmlElement, allowed: Set<ExpandedName> = emptySet()) {
            if (element.attributes.any { it.name.expanded !in allowed } || element.children.any { node ->
                    node is XmlText && node.text.any { it !in " \t\r\n" } || node is XmlCData && node.text.any { it !in " \t\r\n" } })
                fail("REPAIR_AMBIGUOUS", "Qualified/reference/literal directory authority is not a repairable inline graph", Stage.Plan)
        }
        inline(directory)
        val seq = directory.elements(RDF_URI, "Seq").singleOrNull()
            ?: fail("REPAIR_AMBIGUOUS", "V2 repair requires a single RDF sequence", Stage.Plan)
        if (directory.children.filterIsInstance<XmlElement>() != listOf(seq)) fail("REPAIR_AMBIGUOUS", "V2 directory has extra resource representations", Stage.Plan)
        inline(seq)
        val entries = seq.children.filterIsInstance<XmlElement>()
        if (entries.size != 2 || entries.any { it.name.expanded != ExpandedName(RDF_URI, "li") })
            fail("REPAIR_AMBIGUOUS", "Only Primary + MotionPhoto directories have a unique proved suffix-length repair", Stage.Plan)
        val items = entries.map { entry ->
            inline(entry, setOf(ExpandedName(RDF_URI, "parseType")))
            if (entry.attribute(RDF_URI, "parseType") != "Resource") fail("REPAIR_AMBIGUOUS", "V2 repair requires explicit inline resource items", Stage.Plan)
            val item = entry.elements(CONTAINER_URI, "Item").singleOrNull() ?: fail("REPAIR_AMBIGUOUS", "V2 repair requires one Container Item per entry", Stage.Plan)
            if (entry.children.filterIsInstance<XmlElement>() != listOf(item)) fail("REPAIR_AMBIGUOUS", "V2 repair cannot discard unknown item representations", Stage.Plan)
            inline(item, setOf("Semantic", "Mime", "Length", "Padding").map { ExpandedName(ITEM_URI, it) }.toSet())
            if (item.children.filterIsInstance<XmlElement>().isNotEmpty()) fail("REPAIR_AMBIGUOUS", "Only attribute-form directory fields are admitted by this repair profile", Stage.Plan)
            item
        }
        val primary = items.first(); val motion = items.last()
        if (primary.attribute(ITEM_URI, "Semantic") != "Primary" || primary.attribute(ITEM_URI, "Mime") != "image/jpeg" ||
            primary.attribute(ITEM_URI, "Length") !in setOf(null, "0") || primary.attribute(ITEM_URI, "Padding") !in setOf(null, "0") ||
            motion.attribute(ITEM_URI, "Semantic") != "MotionPhoto" || motion.attribute(ITEM_URI, "Mime") !in setOf("video/mp4", "video/quicktime") ||
            motion.attribute(ITEM_URI, "Padding") != (if (vivo) "0" else null) ||
            vivo && (primary.attribute(ITEM_URI, "Length") != null || primary.attribute(ITEM_URI, "Padding") != null || motion.attribute(ITEM_URI, "Mime") != "video/mp4") ||
            oplus && motion.attribute(ITEM_URI, "Mime") != "video/mp4")
            fail("REPAIR_AMBIGUOUS", "Only zero-padding ordinary JPEG plus a final sole video is repairable", Stage.Plan)
        val old = motion.attribute(ITEM_URI, "Length")
        if (old != null && (old.isEmpty() || old.any { it !in '0'..'9' } || old.toULongOrNull() == null))
            fail("REPAIR_AMBIGUOUS", "Unknown length lexical/overflow semantics are outside this repair profile", Stage.Plan)
        // A failed directory parse may not yet emit its compatibility warning. The
        // fully checked original inline graph above already proves the zero-padding
        // dialect, so report that existing fact in the preview, not as a new issue
        // caused by the length repair. Keep the publication regression guard intact.
        val dialect = if (vivo) listOf(
            Issue(IssueCode("MALFORMED_XMP"), Severity.Warning, Layer.Compatibility,
                Location(source = session.snapshot.identities.single().id, selector = "{$ITEM_URI}Padding")),
            Issue(IssueCode("MALFORMED_XMP"), Severity.Error, Layer.Protocol,
                Location(source = session.snapshot.identities.single().id, selector = "{$ITEM_URI}Padding"))) else emptyList()
        val before = (session.inspection.issues + dialect).distinct()
        val fixable = setOf("MOTION_VIDEO_LENGTH_MISMATCH", "OFFSET_OUT_OF_BOUNDS", "MISSING_REQUIRED_XMP") + if (oplus) setOf("MOTION_VIDEO_MISSING") else emptySet()
        // The checked vivo dialect explicitly requires zero Motion Padding. Do not suppress
        // any other malformed-XMP error or apply this exception to a generic Google carrier.
        val unrelated = before.filter { issue ->
            val knownPaddingDialect = vivo && issue.code.value == "MALFORMED_XMP" && issue.layer == Layer.Protocol && issue.location?.selector == "{$ITEM_URI}Padding"
            issue.severity == Severity.Error && issue.code.value !in fixable && !knownPaddingDialect || issue.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT", "CONFLICTING_METADATA")
        }
        if (unrelated.isNotEmpty()) return Prepared(session, RepairResult(before, emptyList(), emptyList(), before, blocked = unrelated), protocol = protocol)
        if (jpeg.trailing.length == 0uL) fail("REPAIR_NOT_POSSIBLE", "No complete post-JPEG video extent", Stage.Plan)
        val candidate = when (val value = BmffVideoProbe(session.reader, budget).probe(jpeg.trailing)) {
            is CoreResult.Success -> value.value
            is CoreResult.Failure -> {
                if (value.error.code.value in setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "RESOURCE_LIMIT_EXCEEDED")) throw CoreFault(value.error)
                fail("REPAIR_NOT_POSSIBLE", "The complete physical suffix is not one verified video", Stage.Plan)
            }
        }
        if (googleVideoIssues(candidate, session.bindings.single { it.protocol == protocol }.selector, false).isNotEmpty() || videoFacts(candidate).mime != motion.attribute(ITEM_URI, "Mime"))
            fail("REPAIR_NOT_POSSIBLE", "Physical suffix conflicts with the declared target container/profile", Stage.Plan)
        val key = xmp.scalar(CAMERA_URI, "MotionPhotoPresentationTimestampUs").orThrow()
        if (key != null && key != "-1") {
            val ticks = key.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }?.toLongOrNull()
            if (ticks == null || Time(ticks, 1_000_000u) >= candidate.tracks.filter { it.handler == "vide" }.maxOf { it.presentationDuration }) {
                val issue = Issue(IssueCode("INVALID_PRESENTATION_TIMESTAMP"), Severity.Error, Layer.Protocol, Location(selector = "{$CAMERA_URI}MotionPhotoPresentationTimestampUs"), observed = Value.Text(key))
                return Prepared(session, RepairResult(before + issue, emptyList(), emptyList(), before + issue, blocked = listOf(issue)), protocol = protocol)
            }
        }
        val target = jpeg.trailing.length.toString()
        if (old == target && (!oplus || oldVendorLength == target)) return Prepared(session, RepairResult(before, emptyList(), emptyList(), before), protocol = protocol)
        val selector = "{$CONTAINER_URI}Directory/MotionPhoto/{$ITEM_URI}Length"
        val issue = Issue(IssueCode(if (old == null) "MISSING_REQUIRED_XMP" else "MOTION_VIDEO_LENGTH_MISMATCH"), Severity.Error, Layer.Protocol,
            Location(source = session.snapshot.identities.single().id, range = jpeg.trailing, selector = selector), expected = Value.Text(target), observed = old?.let(Value::Text), repairability = Repairability.Safe)
        val vendorSelector = "{$OPLUS_URI}VideoLength"
        val vendorIssue = if (oplus && oldVendorLength != target) Issue(IssueCode(if (oldVendorLength == null) "MISSING_REQUIRED_XMP" else "MOTION_VIDEO_LENGTH_MISMATCH"),
            Severity.Error, Layer.Protocol, Location(source = session.snapshot.identities.single().id, range = jpeg.trailing, selector = vendorSelector),
            expected = Value.Text(target), observed = oldVendorLength?.let(Value::Text), repairability = Repairability.Safe) else null
        val lengthIssues = listOfNotNull(issue.takeIf { old != target }, vendorIssue)
        if (oplus && request.allowedIssueCodes.isNotEmpty() && lengthIssues.any { it.code !in request.allowedIssueCodes })
            return Prepared(session, RepairResult(before + lengthIssues, emptyList(), emptyList(), before + lengthIssues, blocked = lengthIssues.filter { it.code !in request.allowedIssueCodes }), protocol = protocol)
        if (request.allowedIssueCodes.isNotEmpty() && (before.map { it.code.value } + issue.code.value).none { it in fixable && IssueCode(it) in request.allowedIssueCodes })
            return Prepared(session, RepairResult(before + issue, emptyList(), emptyList(), before + issue, blocked = listOf(issue)), protocol = protocol)
        val semantic = motion.attributes.single { it.name.expanded == ExpandedName(ITEM_URI, "Semantic") }
        val lengthName = motion.attributes.singleOrNull { it.name.expanded == ExpandedName(ITEM_URI, "Length") }?.name
            ?: XmlName(semantic.name.raw.substringBefore(':') + ":Length", ExpandedName(ITEM_URI, "Length"))
        val changed = motion.copy(attributes = motion.attributes.filterNot { it.name.expanded == ExpandedName(ITEM_URI, "Length") } + XmlAttribute(lengthName, target))
        fun replace(element: XmlElement): XmlElement = if (element === motion) changed else element.copy(children = element.children.map { if (it is XmlElement) replace(it) else it })
        val root = replace(packet.document.root)
        val directoryXml = XmlWriter.write(XmlDocument(root, packet.document.nodes.map { if (it === packet.document.root) root else it }), request.context).orThrow()
        val xml = if (vendorIssue != null) XmpWriter.merge(XmpReader.parse(directoryXml, request.context).orThrow(),
            mapOf(ExpandedName(OPLUS_URI, "VideoLength") to target), request.context).orThrow() else directoryXml
        val rewrite = GoogleJpegWriter.patch(jpeg, xml)
        val exifProofs = if (oplus) session.exifComments.mapNotNull { facts ->
            when (val proof = ExifPositionIndependenceProof.prove(session.reader, facts.document.range, budget)) {
                is CoreResult.Success -> proof.value
                is CoreResult.Failure -> if (proof.error.code.value == "UNSAFE_METADATA_REWRITE") null else throw CoreFault(proof.error)
            }
        } else emptyList()
        if (oplus && (request.policy.preservation == PreservationPolicy.Strict || Guarantee.MetadataPreserving in request.policy.requiredGuarantees) &&
            exifProofs.size != jpeg.segments.count { it.payloadKind == AppPayloadKind.Exif })
            fail("PRESERVATION_REQUIREMENT_FAILED", "Oplus repair cannot prove unknown EXIF dependencies unchanged", Stage.Plan)
        // Prove the projected carrier valid before even offering the repair; no output transaction is involved.
        val projected = SourceSession.open(SourceSet.Single(JpegProjectionSource.create(session, rewrite, includeTrailing = true).orThrow()), request.context, ParseBudget(request.context)).orThrow()
        val report = validateSession(projected, listOf(Layer.Structure, Layer.Protocol)).orThrow()
        if (report.verdict == Verdict.Invalid || report.coverage != Coverage.Complete) fail("REPAIR_NOT_POSSIBLE", "The proved length patches do not fully repair the carrier", Stage.Plan)
        session.recheck()
        val reason = "Unique inline Primary + MotionPhoto graph and independently verified complete physical suffix prove video length"
        val changes = listOfNotNull(if (old != target) Change(selector, old?.let(Value::Text), Value.Text(target), reason, true) else null,
            vendorIssue?.let { Change(vendorSelector, oldVendorLength?.let(Value::Text), Value.Text(target), "$reason; no trailer bytes are present", true) })
        return Prepared(session, RepairResult(before + lengthIssues, changes, emptyList(), before + lengthIssues), rewrite, protocol = protocol, exifProofs = exifProofs)
    }

    private suspend fun prepareSamsung(request: RepairRequest): Prepared {
        val session = SourceSession.open(request.input, request.context, ParseBudget(request.context)).orThrow()
        val sef = session.sef ?: fail("REPAIR_NOT_POSSIBLE", "Repair requires a uniquely parsed SEF footer", Stage.Plan)
        if (session.bindings.any { it.protocol !in setOf(ProtocolIds.Samsung, ProtocolIds.GoogleV2) } || session.gainMaps.isNotEmpty() ||
            sef.gaps.isNotEmpty() || sef.version != 107u || sef.versionRecord == null || ProtocolIds.Samsung !in session.videos)
            fail("REPAIR_AMBIGUOUS", "SEF repair needs a complete unique record graph and verified video", Stage.Plan)
        val sourceIssues = session.inspection.issues
        val scoped = validateSession(session, listOf(Layer.Structure, Layer.Protocol), target = session.bindings.single { it.protocol == ProtocolIds.Samsung }.selector).orThrow()
        val footerIssues = scoped.issues.filter { it.code.value == "SEF_DIRECTORY_INVALID" && it.location?.range == sef.footer && it.observed == Value.Text("legacy-footer-inclusive") }
        val unrelated = scoped.issues.filter { it.severity == Severity.Error && it !in footerIssues || it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT") }
        if (unrelated.isNotEmpty()) return Prepared(session, RepairResult(sourceIssues, emptyList(), emptyList(), sourceIssues, blocked = unrelated), protocol = ProtocolIds.Samsung)
        if (!sef.legacyDialect) return Prepared(session, RepairResult(sourceIssues, emptyList(), emptyList(), sourceIssues), protocol = ProtocolIds.Samsung)
        if (footerIssues.isEmpty()) fail("REPAIR_AMBIGUOUS", "No specific legacy footer issue proves this repair", Stage.Plan)
        if (request.allowedIssueCodes.isNotEmpty() && IssueCode("SEF_DIRECTORY_INVALID") !in request.allowedIssueCodes)
            return Prepared(session, RepairResult(sourceIssues, emptyList(), emptyList(), sourceIssues, blocked = footerIssues), protocol = ProtocolIds.Samsung)
        val fixed = FixedPatchSource.create(session.reader, listOf(FixedPatch(ByteRange(sef.footer.offset, 4uL), unsignedBytes(sef.table.length, 4, Endian.Little)))).orThrow()
        val projected = SourceSession.open(SourceSet.Single(fixed), request.context, ParseBudget(request.context)).orThrow()
        val projectedSef = projected.sef ?: fail("REPAIR_NOT_POSSIBLE", "Canonical footer lost its SEF graph", Stage.Plan)
        val verified = validateSession(projected, listOf(Layer.Structure, Layer.Protocol)).orThrow()
        if (verified.verdict == Verdict.Invalid || verified.coverage != Coverage.Complete || projectedSef.legacyDialect ||
            projectedSef.records != sef.records || projectedSef.pureVideoRange != sef.pureVideoRange)
            fail("REPAIR_NOT_POSSIBLE", "Canonical footer patch does not preserve a valid indexed record graph", Stage.Plan)
        session.recheck()
        val change = Change("samsung:SEFT:directoryLength", Value.Number((sef.table.length + 8uL).toString()), Value.Number(sef.table.length.toString()),
            "Unique fully indexed SEF graph proves the legacy footer incorrectly includes its own eight bytes", true)
        return Prepared(session, RepairResult(sourceIssues, listOf(change), emptyList(), sourceIssues), fixed = fixed, protocol = ProtocolIds.Samsung)
    }
}
