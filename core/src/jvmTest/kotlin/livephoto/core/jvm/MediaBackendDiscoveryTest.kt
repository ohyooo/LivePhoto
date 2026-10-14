package livephoto.core.jvm

import livephoto.core.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MediaBackendDiscoveryTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val executableName = if (windows) "ffmpeg.exe" else "ffmpeg"
    private fun executable(directory: Path, name: String): Path = Files.write(directory.resolve(name), byteArrayOf(0)).also { it.toFile().setExecutable(true) }
    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("livephoto-discovery-test-")
        try { block(directory) } finally { Files.list(directory).use { paths -> paths.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun desktopDefaultDiscoveryNeverAdvertisesRetainedSystemAdapters() {
        val discovery = JvmMediaBackends.discover(Path.of("missing-desktop-ffmpeg-for-default-discovery"))
        assertTrue(discovery.backend?.capabilities()?.backendIds.orEmpty().all { it == "ffmpeg-external" })
        assertTrue(discovery.issues.any { it.code.value == "FFMPEG_EXPLICIT_PATH_UNAVAILABLE" })
    }

    @Test fun executablePreflightHasBoundedColdStartBudgetAndNeverUsesAShell() {
        val path = Path.of("chosen ffmpeg & literal.exe")
        var calls = 0
        assertTrue(JvmMediaBackends.verifyFfmpeg(path) { arguments, timeout ->
            calls++
            assertEquals(listOf(path.toString(), "-version"), arguments)
            assertEquals(15_000L, timeout)
            ProcessResult(0, "ffmpeg version synthetic-test\n")
        })
        assertEquals(1, calls)
    }

    @Test fun executablePreflightNeverAcceptsIncompleteOrFailedVersionEvidence() {
        val success = ProcessResult(0, "ffmpeg version synthetic-test\n")
        for (result in listOf(success.copy(code = 1), success.copy(code = null), success.copy(output = "other program\nffmpeg version shadow"),
            success.copy(outputLimited = true), success.copy(ioFailed = true), success.copy(timedOut = true), success.copy(cancelled = true))) {
            var calls = 0
            assertFalse(JvmMediaBackends.verifyFfmpeg(Path.of("synthetic-executable")) { _, _ -> calls++; result })
            assertEquals(1, calls) // Discovery must not silently retry a failed process.
        }
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
