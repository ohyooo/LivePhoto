package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.orThrow
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
    @Test fun mutationFallbackIsAllowedOnlyBeforeAnyStagingAttempt(): Unit = runImmediate {
        for (operation in listOf(Operation.Trim, Operation.Remux, Operation.Transcode, Operation.ExtractFrame)) for (touch in listOf("", "create", "read")) {
            var attempts = 0
            val staging = object : StagingArea {
                override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> { attempts++; return CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "Synthetic failed staging create")) }
                override suspend fun openForRead(id: AssetId): CoreResult<BinarySource> { attempts++; return CoreResult.Failure(CoreError(IssueCode("IO_READ_FAILED"), Stage.Read, "Synthetic failed staging read")) }
            }
            val first = JobBackend(touch); val second = JobBackend(code = null)
            val backend = FallbackMediaBackend(listOf(first, second))
            val job = BackendJob(operation, listOf(request().media),
                trim = if (operation == Operation.Trim) TrimSpec(TimeRange(Time.Zero, Time(80, 1000u))) else null,
                remuxContainer = if (operation == Operation.Remux) VideoContainer.Mp4 else null,
                videoEncoding = if (operation == Operation.Transcode) VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4) else null,
                position = if (operation == Operation.ExtractFrame) CoverPosition.FrameIndex(0uL) else null,
                imageEncoding = if (operation == Operation.ExtractFrame) ImageEncoding(ImageFormat.Jpeg) else null,
                policy = MutationPolicy(transcode = TranscodePolicy.Explicit), context = context, destination = staging)
            val result = when (operation) {
                Operation.Trim -> backend.trim(job); Operation.Remux -> backend.remux(job)
                Operation.Transcode -> backend.transcode(job); else -> backend.extractFrame(job)
            }
            assertEquals(1, first.calls); assertEquals(if (touch.isEmpty()) 1 else 0, second.calls)
            assertEquals(if (touch.isEmpty()) 0 else 1, attempts)
            if (touch.isEmpty()) assertIs<CoreResult.Success<BackendResult>>(result)
            else assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(result).error.code.value)
        }
    }
    @Test fun unsupportedAfterPartialOutputAbortsCoreTransactionWithoutRetry(): Unit = runImmediate {
        val first = JobBackend("partial"); val second = JobBackend(code = null)
        val backend = FallbackMediaBackend(listOf(first, second))
        val source = MemoryBinarySource(Bytes(GoogleFixtures.video().bytes), SourceId("fallback-partial-video"))
        val output = livephoto.core.memory.MemoryOutputTransaction(context, "fallback-partial")
        val result = DefaultLivePhotoCore(backend).remux(RemuxRequest(ResourceRef(SourceSet.Single(source)), VideoContainer.Mp4, output = output, context = context))
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(result).error.code.value)
        assertEquals(0, second.calls); assertEquals(TransactionState.Aborted, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
    }
    @Test fun cancellationBeforeOrBetweenFallbacksPreventsAdditionalBackendWork(): Unit = runImmediate {
        for (alreadyCancelled in listOf(false, true)) {
            var cancelled = alreadyCancelled
            val first = JobBackend(onCall = { cancelled = true }); val second = JobBackend(code = null)
            val staging = object : StagingArea {
                override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = error("Cancellation cannot write")
                override suspend fun openForRead(id: AssetId): CoreResult<BinarySource> = error("Cancellation cannot read")
            }
            val job = BackendJob(Operation.Remux, listOf(request().media), remuxContainer = VideoContainer.Mp4,
                context = context.copy(cancellation = Cancellation { cancelled }), destination = staging)
            assertEquals("CANCELLED", assertIs<CoreResult.Failure>(FallbackMediaBackend(listOf(first, second)).remux(job)).error.code.value)
            assertEquals(if (alreadyCancelled) 0 else 1, first.calls); assertEquals(0, second.calls)
        }
    }

    private class JobBackend(val touch: String = "", val code: String? = "CAPABILITY_UNSUPPORTED", val onCall: () -> Unit = {}) : MediaBackend {
        var calls = 0
        override fun capabilities(): MediaCapabilities = MediaCapabilities(listOf("synthetic-job"), listOf(Operation.Trim, Operation.Remux, Operation.Transcode, Operation.ExtractFrame).map { CapabilityEntry(it, Implementation.Experimental) })
        private suspend fun run(job: BackendJob): CoreResult<BackendResult> {
            job.validate().orThrow(); calls++; onCall()
            when (touch) {
                "create" -> job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/mp4"))
                "read" -> job.destination.openForRead(AssetId("missing"))
                "partial" -> {
                    val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/mp4")).orThrow()
                    handle.sink.write(Bytes(byteArrayOf(0, 1, 2))).orThrow(); handle.sink.close().orThrow()
                }
            }
            return code?.let { CoreResult.Failure(CoreError(IssueCode(it), Stage.Plan, "Synthetic job failure")) } ?: CoreResult.Success(BackendResult(emptyList(), emptyList(), emptyList()))
        }
        override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = error("Unexpected probe")
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = run(job)
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = run(job)
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = run(job)
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = run(job)
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
