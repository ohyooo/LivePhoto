package livephoto.web

import kotlin.js.JSON
import livephoto.core.*

/** Transport mapping only. All protocol interpretation remains in the common Core. */
internal fun record(vararg fields: Pair<String, Any?>): dynamic {
    val result = js("Object.create(null)")
    fields.forEach { (key, value) -> result[key] = value }
    return result
}
internal fun reportJson(value: dynamic): String = JSON.stringify(value)
private fun value(v: Value?): dynamic = when (v) {
    null, Value.Null -> null
    is Value.Text -> record("kind" to "Text", "value" to v.value)
    is Value.BooleanValue -> record("kind" to "Boolean", "value" to v.value)
    // Never send an arbitrary precision decimal through a JS Number.
    is Value.Number -> record("kind" to "Number", "decimal" to v.decimal)
    is Value.ArrayValue -> record("kind" to "Array", "values" to v.values.map { value(it) }.toTypedArray())
    is Value.ObjectValue -> record("kind" to "Object", "entries" to record(*v.entries.map { it.key to value(it.value) }.toTypedArray()))
}
internal fun time(v: Time?): dynamic = v?.let { record("value" to it.value.toString(), "timescale" to it.timescale.toDouble()) }
private fun range(v: ByteRange?): dynamic = v?.let { record("offset" to it.offset.toString(), "length" to it.length.toString()) }
private fun location(v: Location?): dynamic = v?.let { record("source" to it.source?.value, "resource" to it.resource?.value,
    "range" to range(it.range), "selector" to it.selector, "track" to it.track?.value) }
internal fun issue(v: Issue): dynamic = record("code" to v.code.value, "severity" to v.severity.name,
    "layer" to v.layer.name, "location" to location(v.location), "expected" to value(v.expected), "observed" to value(v.observed),
    "evidenceIds" to v.evidenceIds.map { it.value }.toTypedArray(), "repairability" to v.repairability.name)
private fun evidence(v: Evidence): dynamic = record("id" to v.id.value, "kind" to v.kind.name,
    "description" to v.description, "location" to location(v.location), "reference" to v.reference)
internal fun errorReport(v: CoreError): dynamic = record("kind" to "Error", "location" to location(v.location),
    "recoverability" to v.recoverability.name, "details" to record(*v.details.map { it.key to value(it.value) }.toTypedArray()))
internal fun detection(v: DetectionResult): dynamic = record("disposition" to v.disposition.name,
    "primaryProtocol" to v.primaryProtocol?.protocol?.value, "primaryProfile" to v.primaryProtocol?.profile?.value,
    "matches" to v.matches.map { m -> record("protocol" to m.target.protocol.value, "profile" to m.target.profile?.value,
        "strength" to m.strength.name, "resourceIds" to m.resourceIds.map { it.value }.toTypedArray(),
        "evidence" to m.evidence.map { evidence(it) }.toTypedArray(), "issues" to m.issues.map { issue(it) }.toTypedArray()) }.toTypedArray(),
    "issues" to v.issues.map { issue(it) }.toTypedArray())
internal fun key(v: KeyPhotoResult): dynamic = record("kind" to "KeyPhoto", "position" to time(v.position),
    "frameIndex" to v.frameIndex?.toString(), "trackId" to v.trackId?.value, "source" to v.source.name,
    "rawFields" to v.rawFields.map { record("selector" to it.selector, "rawValue" to value(it.rawValue),
        "unit" to it.unit, "location" to location(it.location)) }.toTypedArray(), "issues" to v.issues.map { issue(it) }.toTypedArray())
private fun metadata(v: MetadataEntry): dynamic = record("selector" to v.selector, "rawResource" to v.raw?.value,
    "value" to value(v.value), "owner" to v.owner.name, "origin" to v.origin.name, "location" to location(v.location))
private fun color(v: ColorFacts?): dynamic = v?.let { record("primaries" to it.primaries, "transfer" to it.transfer,
    "matrix" to it.matrix, "range" to it.range, "bitDepth" to it.bitDepth?.toDouble(), "icc" to it.icc?.value,
    "hdr" to it.hdr.map { m -> metadata(m) }.toTypedArray()) }
internal fun media(v: MediaFacts): dynamic = record("imageFormat" to v.imageFormat?.name, "videoContainer" to v.videoContainer?.name,
    "mime" to v.mime, "width" to v.width?.toDouble(), "height" to v.height?.toDouble(), "duration" to time(v.duration),
    "coverage" to v.coverage.name, "orientation" to value(v.orientation), "color" to color(v.color),
    "auxiliary" to v.auxiliary.map { it.value }.toTypedArray(),
    "tracks" to v.tracks.map { record("id" to it.id.value, "kind" to it.kind, "videoCodec" to it.videoCodec?.name,
        "audioCodec" to it.audioCodec?.name, "codecString" to it.codecString, "profile" to it.profile, "level" to it.level,
        "duration" to time(it.duration), "frameCount" to it.frameCount?.toString(), "width" to it.width?.toDouble(),
        "height" to it.height?.toDouble(), "origin" to it.origin.name, "color" to color(it.color), "transform" to value(it.transform),
        "codecConfigurationByteLength" to it.codecConfiguration?.size) }.toTypedArray(),
    "issues" to v.issues.map { issue(it) }.toTypedArray())
internal fun inspection(v: InspectionResult): dynamic = record("kind" to "Inspection", "detection" to detection(v.detection),
    "layout" to record("sources" to v.layout.sources.map { record("id" to it.id.value, "size" to it.size.toString()) }.toTypedArray(),
        "regions" to v.layout.regions.map { record("id" to it.id.value, "source" to it.source.value, "range" to range(it.range),
            "kind" to it.kind.name, "owner" to it.owner?.value) }.toTypedArray(),
        "resources" to v.layout.resources.map { record("id" to it.id.value, "kind" to it.kind.name, "standalone" to it.standalone,
            "extents" to it.extents.map { r -> record("id" to r.id.value, "source" to r.source.value, "range" to range(r.range)) }.toTypedArray(),
            "sharedWith" to it.sharedWith.map { r -> r.value }.toTypedArray()) }.toTypedArray(),
        "relationships" to v.layout.relationships.map { record("kind" to it.kind.name, "from" to it.from.value,
            "to" to it.to.value, "details" to record(*it.details.map { d -> d.key to value(d.value) }.toTypedArray())) }.toTypedArray()),
    "media" to v.media.map { media(it) }.toTypedArray(),
    "metadata" to v.metadata.map { metadata(it) }.toTypedArray(),
    "keyPhoto" to key(v.keyPhoto), "pairing" to v.pairing?.let { record("imageIdentifier" to it.imageIdentifier,
        "videoIdentifier" to it.videoIdentifier, "matches" to it.matches, "evidence" to it.evidence.map { e -> evidence(e) }.toTypedArray()) },
    "issues" to v.issues.map { issue(it) }.toTypedArray())
internal fun validation(v: ValidationReport): dynamic = record("verdict" to v.verdict.name, "coverage" to v.coverage.name,
    "checks" to v.checks.map { record("id" to it.id, "layer" to it.layer.name, "verdict" to it.verdict.name,
        "coverage" to it.coverage.name, "issues" to it.issues.map { i -> issue(i) }.toTypedArray()) }.toTypedArray(),
    "issues" to v.issues.map { issue(it) }.toTypedArray())
private fun condition(v: Condition): dynamic = record("operator" to v.operator.name, "key" to v.key, "value" to value(v.value),
    "operands" to v.operands.map { condition(it) }.toTypedArray())
internal fun analysis(v: AnalysisResult, media: MediaCapabilities): dynamic = record("kind" to "Analysis", "inspection" to inspection(v.inspection),
    "validation" to validation(v.validation), "capabilities" to record("availability" to v.capabilities.availability.name,
        "operations" to v.capabilities.operations.map { record("operation" to it.operation.name, "implementation" to it.implementation.name,
            "lifecycle" to it.lifecycle.name, "exposure" to it.exposure.name, "reasons" to it.reasons.map { r -> r.value }.toTypedArray(),
            "conditions" to it.conditions.map { c -> condition(c) }.toTypedArray()) }.toTypedArray(),
        "issues" to v.capabilities.issues.map { issue(it) }.toTypedArray()),
    "mediaCapabilities" to record("backendIds" to media.backendIds.toTypedArray(),
        "operations" to media.operations.map { record("operation" to it.operation.name, "implementation" to it.implementation.name,
            "reasons" to it.reasons.map { r -> r.value }.toTypedArray(), "conditions" to it.conditions.map { c -> condition(c) }.toTypedArray()) }.toTypedArray()))
internal fun operationReport(v: OperationResult): dynamic = record("kind" to "Operation", "validation" to validation(v.validation),
    "preservation" to record("records" to v.preservation.records.map { record("assetId" to it.assetId.value,
        "guarantee" to it.guarantee.name, "outcome" to it.outcome.name, "sourceDigest" to it.sourceDigest?.value,
        "outputDigest" to it.outputDigest?.value, "proof" to it.proof) }.toTypedArray(),
        "changes" to v.preservation.changes.map { record("selector" to it.selector, "before" to value(it.before),
            "after" to value(it.after), "reason" to it.reason, "requested" to it.requested) }.toTypedArray(),
        "issues" to v.preservation.issues.map { issue(it) }.toTypedArray()),
    "keyPhoto" to v.keyPhoto?.let { key(it) }, "issues" to v.issues.map { issue(it) }.toTypedArray(),
    "execution" to v.execution.map { record("stage" to it.stage.name, "backendId" to it.backendId, "reason" to it.reason,
        "transcoded" to it.transcoded, "remuxed" to it.remuxed, "hardwareUsed" to it.hardwareUsed) }.toTypedArray(),
    "durability" to "volatile-process-memory")

private fun change(v: Change): dynamic = record("selector" to v.selector, "before" to value(v.before),
    "after" to value(v.after), "reason" to v.reason, "requested" to v.requested)
internal fun repairReport(v: RepairResult, dryRun: Boolean): dynamic = record("kind" to "Repair", "dryRun" to dryRun,
    "issuesBefore" to v.issuesBefore.map { issue(it) }.toTypedArray(),
    "proposedChanges" to v.proposedChanges.map { change(it) }.toTypedArray(),
    "changesApplied" to v.changesApplied.map { change(it) }.toTypedArray(),
    "issuesAfter" to v.issuesAfter.map { issue(it) }.toTypedArray(),
    "blocked" to v.blocked.map { issue(it) }.toTypedArray(),
    "operation" to v.operation?.let { operationReport(it) })
