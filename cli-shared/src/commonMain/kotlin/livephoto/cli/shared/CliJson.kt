package livephoto.cli.shared

import livephoto.core.*

/** CLI-only DTO formatting. No protocol interpretation, handles, or generation tokens are serialized. */
public object CliJson {
    fun encode(value: Any?, output: ((AssetId) -> String)? = null): String = when (value) {
        null, Value.Null -> "null"
        is String -> quote(value)
        is Long, is ULong -> quote(value.toString())
        is Boolean, is Number, is UInt -> value.toString()
        is Enum<*> -> quote(value.name)
        is Value.Text -> quote(value.value)
        is Value.Number -> value.decimal
        is Value.BooleanValue -> value.value.toString()
        is Value.ArrayValue -> encode(value.values, output)
        is Value.ObjectValue -> encode(value.entries, output)
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + encode(it.value, output) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it, output) }
        is BinarySource, is GenerationToken -> quote("redacted")
        is Bytes -> encode(mapOf("byteLength" to value.size))
        is Time -> encode(mapOf("value" to value.value, "timescale" to value.timescale))
        is CoverPosition.Timestamp -> encode(mapOf("kind" to "Timestamp", "time" to value.time,
            "selection" to value.selection, "tolerance" to value.tolerance), output)
        is CoverPosition.FrameIndex -> encode(mapOf("kind" to "FrameIndex", "index" to value.index,
            "trackId" to value.trackId), output)
        is ByteRange -> encode(mapOf("offset" to value.offset, "length" to value.length))
        is SourceIdentity -> encode(mapOf("size" to value.size, "digest" to value.digest), output)
        is Snapshot -> encode(mapOf("sources" to value.identities), output)
        is Receipt -> encode(mapOf("state" to value.state, "assetIds" to value.assetIds, "atomicity" to value.atomicity, "durability" to value.durability), output)
        is CoreResult.Success<*> -> encode(mapOf("result" to value.value), output)
        is CoreResult.Failure -> encode(mapOf("error" to value.error), output)
        is OutputAsset -> encode(mapOf("id" to value.id, "role" to value.role, "mime" to value.mime,
            "imageFormat" to value.imageFormat, "videoContainer" to value.videoContainer,
            "byteLength" to value.byteLength, "digest" to value.digest, "path" to output?.invoke(value.id)), output)
        is SourceId -> encode(mapOf("value" to value.value), output)
        is ResourceId -> encode(mapOf("value" to value.value), output)
        is AssetId -> encode(mapOf("value" to value.value), output)
        is TrackId -> encode(mapOf("value" to value.value), output)
        is ProtocolId -> encode(mapOf("value" to value.value), output)
        is ProfileId -> encode(mapOf("value" to value.value), output)
        is IssueCode -> encode(mapOf("value" to value.value), output)
        is EvidenceId -> encode(mapOf("value" to value.value), output)
        is Digest -> encode(mapOf("value" to value.value), output)
        is Location -> encode(mapOf("source" to value.source, "range" to value.range, "resource" to value.resource, "selector" to value.selector, "track" to value.track), output)
        is Evidence -> encode(mapOf("id" to value.id, "kind" to value.kind, "description" to value.description, "location" to value.location, "reference" to value.reference), output)
        is CoreError -> encode(mapOf("code" to value.code, "stage" to value.stage, "message" to value.message, "location" to value.location, "details" to value.details, "recoverability" to value.recoverability), output)
        is Issue -> encode(mapOf("code" to value.code, "severity" to value.severity, "layer" to value.layer, "location" to value.location, "expected" to value.expected, "observed" to value.observed, "evidenceIds" to value.evidenceIds, "repairability" to value.repairability), output)
        is Condition -> encode(mapOf("operator" to value.operator, "key" to value.key, "value" to value.value, "operands" to value.operands), output)
        is CapabilityEntry -> encode(mapOf("operation" to value.operation, "implementation" to value.implementation, "lifecycle" to value.lifecycle, "exposure" to value.exposure, "conditions" to value.conditions, "reasons" to value.reasons, "verification" to value.verification, "evidence" to value.evidence), output)
        is ProtocolCapabilities -> encode(mapOf("target" to value.target, "operations" to value.operations), output)
        is MediaCapabilities -> encode(mapOf("backendIds" to value.backendIds, "operations" to value.operations), output)
        is CapabilitySet -> encode(mapOf("availability" to value.availability, "operations" to value.operations, "issues" to value.issues), output)
        is AnalysisResult -> encode(mapOf("inspection" to value.inspection, "validation" to value.validation, "capabilities" to value.capabilities), output)
        is Region -> encode(mapOf("id" to value.id, "source" to value.source, "range" to value.range, "kind" to value.kind, "owner" to value.owner), output)
        is Resource -> encode(mapOf("id" to value.id, "kind" to value.kind, "extents" to value.extents, "standalone" to value.standalone, "sharedWith" to value.sharedWith), output)
        is Relationship -> encode(mapOf("kind" to value.kind, "from" to value.from, "to" to value.to, "details" to value.details), output)
        is Layout -> encode(mapOf("sources" to value.sources, "regions" to value.regions, "resources" to value.resources, "relationships" to value.relationships), output)
        is ProtocolSelector -> encode(mapOf("protocol" to value.protocol, "profile" to value.profile), output)
        is Match -> encode(mapOf("target" to value.target, "strength" to value.strength, "evidence" to value.evidence, "issues" to value.issues, "resourceIds" to value.resourceIds), output)
        is DetectionResult -> encode(mapOf("disposition" to value.disposition, "primaryProtocol" to value.primaryProtocol, "matches" to value.matches, "issues" to value.issues, "snapshot" to value.snapshot), output)
        is MetadataEntry -> encode(mapOf("selector" to value.selector, "raw" to value.raw, "value" to value.value, "owner" to value.owner, "location" to value.location, "origin" to value.origin), output)
        is ColorFacts -> encode(mapOf("primaries" to value.primaries, "transfer" to value.transfer, "matrix" to value.matrix, "range" to value.range, "bitDepth" to value.bitDepth, "icc" to value.icc, "hdr" to value.hdr), output)
        is TrackFacts -> encode(mapOf("id" to value.id, "kind" to value.kind, "videoCodec" to value.videoCodec, "audioCodec" to value.audioCodec, "codecString" to value.codecString, "codecConfiguration" to value.codecConfiguration, "profile" to value.profile, "level" to value.level, "duration" to value.duration, "frameCount" to value.frameCount, "width" to value.width, "height" to value.height, "transform" to value.transform, "color" to value.color, "origin" to value.origin), output)
        is MediaFacts -> encode(mapOf("imageFormat" to value.imageFormat, "videoContainer" to value.videoContainer, "mime" to value.mime, "width" to value.width, "height" to value.height, "orientation" to value.orientation, "duration" to value.duration, "tracks" to value.tracks, "auxiliary" to value.auxiliary, "color" to value.color, "coverage" to value.coverage, "issues" to value.issues), output)
        is RawKeyField -> encode(mapOf("selector" to value.selector, "rawValue" to value.rawValue, "unit" to value.unit, "location" to value.location), output)
        is KeyPhotoResult -> encode(mapOf("position" to value.position, "frameIndex" to value.frameIndex, "trackId" to value.trackId, "source" to value.source, "rawFields" to value.rawFields, "issues" to value.issues), output)
        is PairingFacts -> encode(mapOf("imageIdentifier" to value.imageIdentifier, "videoIdentifier" to value.videoIdentifier, "matches" to value.matches, "evidence" to value.evidence), output)
        is InspectionResult -> encode(mapOf("snapshot" to value.snapshot, "detection" to value.detection, "layout" to value.layout, "media" to value.media, "metadata" to value.metadata, "keyPhoto" to value.keyPhoto, "pairing" to value.pairing, "issues" to value.issues), output)
        is CheckResult -> encode(mapOf("id" to value.id, "layer" to value.layer, "verdict" to value.verdict, "coverage" to value.coverage, "issues" to value.issues), output)
        is ValidationReport -> encode(mapOf("verdict" to value.verdict, "coverage" to value.coverage, "checks" to value.checks, "issues" to value.issues, "snapshot" to value.snapshot), output)
        is MediaOutput -> encode(mapOf("receipt" to value.receipt, "assets" to value.assets), output)
        is ExecutionRecord -> encode(mapOf("stage" to value.stage, "backendId" to value.backendId, "reason" to value.reason, "transcoded" to value.transcoded, "remuxed" to value.remuxed, "hardwareUsed" to value.hardwareUsed, "inputFacts" to value.inputFacts, "outputFacts" to value.outputFacts), output)
        is OperationResult -> encode(mapOf("output" to value.output, "validation" to value.validation, "preservation" to value.preservation, "execution" to value.execution, "keyPhoto" to value.keyPhoto, "issues" to value.issues), output)
        is RepairResult -> encode(mapOf("issuesBefore" to value.issuesBefore, "proposedChanges" to value.proposedChanges, "changesApplied" to value.changesApplied, "issuesAfter" to value.issuesAfter, "operation" to value.operation, "blocked" to value.blocked), output)
        is FrameResult -> encode(mapOf("operation" to value.operation, "requested" to value.requested, "actualTime" to value.actualTime, "actualFrameIndex" to value.actualFrameIndex, "trackId" to value.trackId), output)
        is TrackTrim -> encode(mapOf("trackId" to value.trackId, "presented" to value.presented, "encoded" to value.encoded, "timelineMap" to value.timelineMap, "transcoded" to value.transcoded, "samplesPreserved" to value.samplesPreserved), output)
        is TrimResult -> encode(mapOf("operation" to value.operation, "requestedStart" to value.requestedStart, "requestedEnd" to value.requestedEnd, "actualStart" to value.actualStart, "actualEnd" to value.actualEnd, "tracks" to value.tracks, "wasTranscoded" to value.wasTranscoded, "wasRemuxed" to value.wasRemuxed, "wasBitstreamPreserved" to value.wasBitstreamPreserved, "retainedHiddenContent" to value.retainedHiddenContent, "exactTolerance" to value.exactTolerance, "timelineMap" to value.timelineMap), output)
        is PlanStep -> encode(mapOf("stage" to value.stage, "required" to value.required, "inputs" to value.inputs, "reason" to value.reason), output)
        is ExecutionPlan -> encode(mapOf("snapshot" to value.snapshot, "target" to value.target, "steps" to value.steps, "predictedPreservation" to value.predictedPreservation, "capabilities" to value.capabilities, "issues" to value.issues), output)
        is Change -> encode(mapOf("selector" to value.selector, "before" to value.before, "after" to value.after, "reason" to value.reason, "requested" to value.requested), output)
        is GuaranteeRecord -> encode(mapOf("assetId" to value.assetId, "guarantee" to value.guarantee, "outcome" to value.outcome, "sourceDigest" to value.sourceDigest, "outputDigest" to value.outputDigest, "proof" to value.proof), output)
        is PreservationReport -> encode(mapOf("records" to value.records, "changes" to value.changes, "issues" to value.issues), output)
        is TimeRange -> encode(mapOf("start" to value.start, "end" to value.end), output)
        is TimelineSegment -> encode(mapOf("input" to value.input, "output" to value.output), output)
        is RationalRate -> encode(mapOf("numerator" to value.numerator, "denominator" to value.denominator), output)
        else -> error("Not a supported Core result DTO")
    }
    private fun quote(text: String): String = buildString {
        append('"')
        for (char in text) when (char) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (char.code < 32 || char.isSurrogate()) append("\\u${char.code.toString(16).padStart(4, '0')}") else append(char)
        }
        append('"')
    }
}
