package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MediaBackendDiscoveryTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val executableName = if (windows) "ffmpeg.exe" else "ffmpeg"
    private val context = Context(Limits(4_000_000uL, 4_000_000uL))
    private fun request() = ProbeRequest(ResourceRef(SourceSet.Single(MemoryBinarySource(Bytes(GoogleFixtures.jpeg()), SourceId("backend-test")))), true, context)
    private fun executable(directory: Path, name: String): Path = Files.write(directory.resolve(name), byteArrayOf(0)).also { it.toFile().setExecutable(true) }
    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("livephoto-discovery-test-")
        try { block(directory) } finally { Files.list(directory).use { paths -> paths.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun explicitExecutablePrecedesPathAndPathPrecedesSystem() = withDirectory { directory ->
        val explicit = executable(directory, "chosen tool.exe")
        executable(directory, executableName)
        val visited = mutableListOf<Path>()
        val selection = JvmMediaBackends.discover(explicit, directory.toString(), windows, listOf(FakeBackend("system"))) { visited.add(it); true }
        assertEquals(explicit.toRealPath(), selection.ffmpegPath)
        assertEquals(listOf(explicit.toRealPath()), visited)
        assertEquals(listOf("ffmpeg-external", "system"), selection.backend!!.capabilities().backendIds)
    }

    @Test fun invalidExplicitPathFallsBackToPathWithVisibleWarning() = withDirectory { directory ->
        val candidate = executable(directory, executableName)
        val selection = JvmMediaBackends.discover(directory.resolve("missing"), directory.toString(), windows, emptyList()) { true }
        assertEquals(candidate.toRealPath(), selection.ffmpegPath)
        assertTrue(selection.issues.any { it.code.value == "FFMPEG_EXPLICIT_PATH_UNAVAILABLE" })
    }

    @Test fun unavailablePathFallsBackToSystemOrDisablesAllMediaOperations() {
        val selection = JvmMediaBackends.discover(null, ":.:relative:", false, listOf(FakeBackend("system"))) { error("Must not search relative PATH entries") }
        assertNull(selection.ffmpegPath)
        assertEquals(listOf("system"), selection.backend!!.capabilities().backendIds)
        val absent = JvmMediaBackends.discover(null, "", false, emptyList()) { error("No executable") }
        assertNull(absent.backend)
        assertTrue(absent.issues.any { it.code.value == "MEDIA_BACKEND_UNAVAILABLE" })
    }

    @Test fun nonFfmpegExecutableIsNotSelectedAndWindowsSearchUsesExe() = withDirectory { directory ->
        val candidate = executable(directory, "ffmpeg.exe")
        val visited = mutableListOf<Path>()
        assertNull(JvmMediaBackends.discover(null, directory.toString(), true, emptyList()) { visited.add(it); false }.backend)
        assertEquals(listOf(candidate.toRealPath()), visited)
    }

    @Test fun onlyUnsupportedFallsThroughNotDecodeFailureOrCancellation(): Unit = runImmediate {
        for (code in listOf("CAPABILITY_UNSUPPORTED", "DECODE_FAILED", "CANCELLED", "SOURCE_CHANGED", "TRANSCODE_NOT_AUTHORIZED")) {
            val first = FakeBackend("first", code)
            val second = FakeBackend("second")
            val result = FallbackMediaBackend(listOf(first, second)).probe(request())
            assertEquals(1, first.calls)
            assertEquals(if (code == "CAPABILITY_UNSUPPORTED") 1 else 0, second.calls)
            if (code == "CAPABILITY_UNSUPPORTED") assertIs<CoreResult.Success<MediaFacts>>(result)
            else assertEquals(code, assertIs<CoreResult.Failure>(result).error.code.value)
        }
    }

    @Test fun unsupportedCapabilitiesAreNotInvoked(): Unit = runImmediate {
        val backend = FakeBackend("disabled", implemented = false)
        assertIs<CoreResult.Failure>(FallbackMediaBackend(listOf(backend)).probe(request()))
        assertEquals(0, backend.calls)
    }

    @Test fun fallbackPreflightKeepsPolicyErrorsAndRejectsMethodMismatch(): Unit = runImmediate {
        val staging = object : StagingArea {
            override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = error("Preflight cannot write")
            override suspend fun openForRead(id: AssetId): CoreResult<BinarySource> = error("Preflight cannot read")
        }
        val backend = FallbackMediaBackend(emptyList())
        val transcode = BackendJob(Operation.Transcode, listOf(request().media), videoEncoding = VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4),
            context = context, destination = staging)
        assertEquals("TRANSCODE_NOT_AUTHORIZED", assertIs<CoreResult.Failure>(backend.transcode(transcode)).error.code.value)
        assertEquals("INVALID_ARGUMENT", assertIs<CoreResult.Failure>(backend.remux(transcode)).error.code.value)
    }

    private class FakeBackend(val id: String, val code: String? = null, val implemented: Boolean = true) : MediaBackend {
        var calls = 0
        override fun capabilities() = MediaCapabilities(listOf(id), listOf(CapabilityEntry(Operation.Probe, if (implemented) Implementation.Experimental else Implementation.Unsupported)))
        override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> {
            calls++
            return code?.let { CoreResult.Failure(CoreError(IssueCode(it), Stage.Validate, "test failure")) }
                ?: CoreResult.Success(MediaFacts(imageFormat = ImageFormat.Jpeg, coverage = Coverage.Partial))
        }
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = error("Unexpected mutation")
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = error("Unexpected mutation")
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = error("Unexpected mutation")
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = error("Unexpected mutation")
    }
}
