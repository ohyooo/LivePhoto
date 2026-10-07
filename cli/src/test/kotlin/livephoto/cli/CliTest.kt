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
}
