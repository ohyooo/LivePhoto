package livephoto.cli

import livephoto.core.*
import livephoto.core.jvm.BackendDiscovery
import java.nio.file.Path
import kotlin.test.*

class CliTest {
    @Test fun outputFormattingIncludesCoreFormatsWithoutSerializingHandles() {
        val asset = OutputAsset(AssetId("output"), AssetRole.MotionVideo, accessReference = "private-adapter-reference", byteLength = 1uL,
            mime = "video/quicktime", videoContainer = VideoContainer.Mov, digest = Digest("0".repeat(64)))
        val json = Json.encode(asset)
        assertTrue(json.contains("\"videoContainer\":\"Mov\""))
        assertTrue(json.contains("\"imageFormat\":null"))
        assertFalse(json.contains("private-adapter-reference"))
    }
    private val failure = CoreResult.Failure(CoreError(IssueCode("TEST_SENTINEL"), Stage.Plan, "test"))
    @Test fun helpVersionAndCapabilitiesWorkWithoutFiles() = blocking {
        for (args in listOf(listOf("--help"), listOf("--version"), listOf("capabilities", "--target", "google.microvideo.v1"), listOf("media-capabilities"))) {
            val lines = mutableListOf<String>()
            assertEquals(0, Cli().run(args, lines::add))
            assertTrue(lines.single().isNotBlank())
        }
    }
    @Test fun invalidOptionsNeverReachCore() = blocking {
        for (args in listOf(listOf("unknown"), listOf("detect", "--oops", "x"), listOf("detect", "--input"), listOf("detect", "--input", "a", "--input", "b"), listOf("set-key", "--input", "x", "--frame-index", "0", "--time-us", "0", "--output-dir", "unused"))) {
            val lines = mutableListOf<String>()
            assertEquals(2, Cli().run(args, lines::add))
            assertTrue(lines.single().contains("INVALID_ARGUMENT"))
        }
    }
    @Test fun repairIsReadOnlyByDefaultAndEmptyAllowlistReachesCore() = blocking {
        var received: RepairRequest? = null
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun repair(request: RepairRequest): CoreResult<RepairResult> { received = request; return failure }
        }
        assertEquals(3, Cli(core).run(listOf("repair", "--input", "not-opened")) {})
        val request = assertNotNull(received)
        assertTrue(request.dryRun)
        assertNull(request.output)
        assertTrue(request.allowedIssueCodes.isEmpty())
    }
    @Test fun defaultsDoNotAuthorizeTranscoding() = blocking {
        var received: TranscodeRequest? = null
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun transcode(request: TranscodeRequest): CoreResult<OperationResult> { received = request; return failure }
        }
        val args = listOf("transcode", "--input", "not-opened", "--codec", "Avc", "--container", "Mp4", "--output-dir", "not-created")
        assertEquals(3, Cli(core).run(args) {})
        assertEquals(TranscodePolicy.Forbid, assertNotNull(received).policy.transcode)
        assertEquals(3, Cli(core).run(args + "--allow-transcode") {})
        assertEquals(TranscodePolicy.Explicit, assertNotNull(received).policy.transcode)
    }
    @Test fun jsonEscapesControlsAndPreservesExactUnsignedRanges() {
        assertEquals("\"a\\n\\\"\\\\\"", Json.encode("a\n\"\\"))
        assertTrue(Json.encode(ByteRange(ULong.MAX_VALUE, 0uL)).contains("18446744073709551615"))
        assertFalse(Json.encode(GenerationToken("secret")).contains("secret"))
    }
    @Test fun backendPathIsForwardedAndOrdinaryCommandsDoNotDiscoverTools() = blocking {
        var path: Path? = null
        var calls = 0
        val cli = Cli(discover = { path = it; calls++; BackendDiscovery(null, null, emptyList()) })
        assertEquals(0, cli.run(listOf("--help")) {})
        assertEquals(0, cli.run(listOf("capabilities", "--target", "google.microvideo.v1")) {})
        assertEquals(0, calls)
        assertEquals(0, cli.run(listOf("media-capabilities", "--ffmpeg", "tools with spaces/ffmpeg.exe")) {})
        assertEquals(Path.of("tools with spaces/ffmpeg.exe"), path)
        assertEquals(1, calls)
    }
    @Test fun decodeCheckIsAnExplicitProbeRequestFlag() = blocking {
        var decode = false
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> { decode = request.decodeCheck; return failure }
        }
        assertEquals(3, Cli(core).run(listOf("probe", "--input", "not-opened", "--decode-check")) {})
        assertTrue(decode)
    }
    @Test fun extractFrameForwardsResourceIndexAndEncodingAsRequestOnly() = blocking {
        var received: ExtractFrameRequest? = null
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun extractFrame(request: ExtractFrameRequest): CoreResult<FrameResult> { received = request; return failure }
        }
        val args = listOf("extract-frame", "--input", "not-opened", "--resource", "embedded-video", "--frame-index", "0", "--track-id", "2", "--format", "Jpeg", "--output-dir", "not-created")
        assertEquals(3, Cli(core).run(args) {})
        val request = assertNotNull(received)
        assertEquals(ResourceId("embedded-video"), request.video.resourceId)
        assertEquals(CoverPosition.FrameIndex(0uL, TrackId("2")), request.position)
        assertEquals(ImageEncoding(ImageFormat.Jpeg), request.encoding)
    }
    @Test fun int64AndUint64UseDecimalStringsForJavaScriptSafeJson() {
        assertEquals("\"9223372036854775807\"", Json.encode(Long.MAX_VALUE))
        assertEquals("\"18446744073709551615\"", Json.encode(ULong.MAX_VALUE))
        assertEquals("{\"value\":\"1\",\"timescale\":90000}", Json.encode(Time(1, 90000u)))
        assertEquals("{\"offset\":\"18446744073709551615\",\"length\":\"0\"}", Json.encode(ByteRange(ULong.MAX_VALUE, 0uL)))
    }
    @Test fun coverPositionJsonUsesExplicitKindAndExactIntegerWidth() {
        assertEquals("{\"kind\":\"FrameIndex\",\"index\":\"18446744073709551615\",\"trackId\":{\"value\":\"2\"}}",
            Json.encode(CoverPosition.FrameIndex(ULong.MAX_VALUE, TrackId("2"))))
        assertEquals("{\"kind\":\"Timestamp\",\"time\":{\"value\":\"9223372036854775807\",\"timescale\":90000},\"selection\":\"Exact\",\"tolerance\":{\"value\":\"0\",\"timescale\":1}}",
            Json.encode(CoverPosition.Timestamp(Time(Long.MAX_VALUE, 90000u), Selection.Exact)))
    }
    @Test fun futureCoreIssueCodesArePassedThroughWithoutCliInterpretation() = blocking {
        val code = "FUTURE_MEDIA_RULE_2030"
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun detect(request: ReadRequest): CoreResult<DetectionResult> =
                CoreResult.Failure(CoreError(IssueCode(code), Stage.Detect, "Future structured error", details = mapOf("tick" to Value.Text(ULong.MAX_VALUE.toString()))))
        }
        val lines = mutableListOf<String>()
        assertEquals(3, Cli(core).run(listOf("detect", "--input", "not-opened"), lines::add))
        assertTrue(lines.single().contains("\"value\":\"$code\""))
        assertTrue(lines.single().contains("18446744073709551615"))
    }
    @Test fun replacementKeyUpdateAndStrictPolicyAreOnlyForwarded() = blocking {
        var received: ReplaceRequest? = null
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun replacePrimaryImageFromFrame(request: ReplaceRequest): CoreResult<OperationResult> { received = request; return failure }
        }
        val args = listOf("replace-cover", "--input", "not-opened", "--frame-index", "0", "--format", "Jpeg", "--output-dir", "not-created")
        assertEquals(3, Cli(core).run(args) {})
        assertFalse(assertNotNull(received).updateKeyPosition)
        assertEquals(3, Cli(core).run(args + listOf("--update-key", "--strict")) {})
        assertTrue(assertNotNull(received).updateKeyPosition)
        assertEquals(PreservationPolicy.Strict, assertNotNull(received).policy.preservation)
    }
}
