package livephoto.cli.shared

import livephoto.core.*
import kotlin.test.*

/** Exercise every command's nested output graph on all Native hosts. */
class JsonGraphTest {
    private val source = SourceId("private-source")
    private val resourceId = ResourceId("resource")
    private val trackId = TrackId("track")
    private val assetId = AssetId("asset")
    private val identity = SourceIdentity(source, GenerationToken("private-generation"), 100uL, Digest("digest"))
    private val snapshot = Snapshot(listOf(identity), GenerationToken("private-snapshot"))
    private val target = ProtocolSelector(ProtocolIds.GoogleV2)
    private val location = Location(source, ByteRange(0uL, 10uL), resourceId, "metadata.key", trackId)
    private val evidence = Evidence(EvidenceId("evidence"), EvidenceKind.Inspection, "test", location)
    private val issue = Issue(IssueCode("TEST_RULE"), Severity.Warning, Layer.Protocol, location,
        Value.Text("expected"), Value.Number("1"), listOf(evidence.id))
    private val match = Match(target, MatchStrength.entries.first(), listOf(evidence), listOf(issue), listOf(resourceId))
    private val detection = DetectionResult(Disposition.Live, target, listOf(match), listOf(issue), snapshot)
    private val region = Region(resourceId, source, ByteRange(0uL, 10uL), ResourceKind.PrimaryImage, target.protocol)
    private val resource = Resource(resourceId, ResourceKind.PrimaryImage, listOf(region), true)
    private val layout = Layout(listOf(identity), listOf(region), listOf(resource),
        listOf(Relationship(RelationshipKind.Describes, resourceId, resourceId, mapOf("value" to Value.Text("test")))))
    private val metadata = MetadataEntry("metadata.key", resourceId, Value.Text("value"),
        Ownership.Ordinary, location, FactOrigin.Parsed)
    private val color = ColorFacts("bt709", "bt709", "bt709", "full", 8u, resourceId, listOf(metadata))
    private val facts = MediaFacts(ImageFormat.Jpeg, VideoContainer.Mp4, "image/jpeg", 2u, 2u,
        Value.Number("1"), Time(1, 1u), listOf(TrackFacts(trackId, "video", VideoCodec.Avc,
            codecConfiguration = Bytes(byteArrayOf(1, 2)), frameCount = ULong.MAX_VALUE, color = color)),
        listOf(resourceId), color, Coverage.Complete, listOf(issue))
    private val key = KeyPhotoResult(Time.Zero, ULong.MAX_VALUE, trackId, rawFields =
        listOf(RawKeyField("key", Value.Number("0"), "us", location)), issues = listOf(issue))
    private val inspection = InspectionResult(snapshot, detection, layout, listOf(facts), listOf(metadata), key,
        PairingFacts("identifier", "identifier", true, listOf(evidence)), listOf(issue))
    private val validation = ValidationReport(Verdict.Warning, Coverage.Complete,
        listOf(CheckResult("check", Layer.Protocol, Verdict.Warning, Coverage.Complete, listOf(issue))), listOf(issue), snapshot)
    private val capability = CapabilityEntry(Operation.Detect, Implementation.Supported,
        conditions = listOf(Condition(ConditionOperator.entries.first(), "key", Value.BooleanValue(true))),
        reasons = listOf(issue.code), evidence = listOf(evidence))
    private val capabilities = CapabilitySet(Availability.Supported, listOf(capability), listOf(issue))
    private val change = Change("key", Value.Number("0"), Value.Number("1"), "test", true)
    private val preservation = PreservationReport(listOf(GuaranteeRecord(assetId, Guarantee.ExactExtraction,
        GuaranteeOutcome.Verified, Digest("input"), Digest("output"), "test")), listOf(change), listOf(issue))
    private val receipt = Receipt("private-receipt", TransactionState.Committed, listOf(assetId), Atomicity.AssetSetRequired, "volatile")
    private val asset = OutputAsset(assetId, AssetRole.PrimaryImage, accessReference = "private-reference",
        byteLength = 10uL, mime = "image/jpeg", imageFormat = ImageFormat.Jpeg, digest = Digest("output"))
    private val operation = OperationResult(MediaOutput(listOf(asset), receipt), validation, preservation,
        listOf(ExecutionRecord(Stage.WriteProtocol, "backend", "test", false, false, false, facts, facts)), key, listOf(issue))
    private val range = TimeRange(Time.Zero, Time(1, 1u))
    private val timeline = TimelineSegment(range, range)

    @Test fun readCapabilityAndPlanGraphsHaveAllNestedDtosWithoutReflection() {
        val values = listOf(detection, inspection, validation, AnalysisResult(inspection, validation, capabilities),
            ProtocolCapabilities(target, listOf(capability)), MediaCapabilities(listOf("backend"), listOf(capability)),
            ExecutionPlan(snapshot, target, listOf(PlanStep(Stage.Plan, listOf(Operation.Detect), listOf(resourceId), "test")),
                preservation, capabilities, listOf(issue)), key, facts, RationalRate(30u, 1u))
        values.forEach {
            val json = CliJson.encode(CoreResult.Success(it))
            assertTrue(json.startsWith("{\"result\":"))
            assertFalse(json.contains("private-generation"))
            assertFalse(json.contains("private-snapshot"))
        }
        assertTrue(CliJson.encode(inspection).contains("\"frameCount\":\"18446744073709551615\""))
    }

    @Test fun writeRepairFrameAndTrimGraphsDoNotExposeRuntimeHandles() {
        val values = listOf(operation, RepairResult(listOf(issue), listOf(change), listOf(change), emptyList(), operation),
            FrameResult(operation, CoverPosition.FrameIndex(0uL), Time.Zero, 0uL, trackId),
            TrimResult(operation, range.start, range.end, range.start, range.end,
                listOf(TrackTrim(trackId, range, range, listOf(timeline), false, true)),
                false, true, true, false, Time.Zero, listOf(timeline)))
        values.forEach {
            val json = CliJson.encode(CoreResult.Success(it)) { "published/${it.value}" }
            assertTrue(json.contains("\"path\":\"published/asset\""))
            assertFalse(json.contains("private-receipt"))
            assertFalse(json.contains("private-reference"))
        }
    }
}
