@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.adapters.files

import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.core.ffmpeg.*
import livephoto.core.memory.MemoryOutputTransaction
import okio.Path.Companion.toPath
import kotlin.test.*

/** Mandatory real host FFmpeg tests, not device compatibility certification. */
class NativeFfmpegTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    private fun executable(): String = localFileSystem.canonicalize(assertNotNull(platform.posix.getenv("LIVEPHOTO_FFMPEG")?.toKString(), "CI must supply real FFmpeg").toPath()).toString()
    private suspend fun generate(runtime: NativeFfmpeg, arguments: List<String>) {
        val result = runtime.execute(listOf(executable(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror") + arguments, 60_000, context)
        assertEquals(0, result.code, result.output)
        assertFalse(result.ioFailed || result.outputLimited || result.timedOut || result.cancelled)
    }
    @Test fun discoveryChecksExplicitAndAbsolutePathButNeverRelativePathEntries(): Unit = runBlocking<Unit> {
        val box = Sandbox()
        try {
            val runtime = NativeFfmpeg({ box.root.toString() })
            assertEquals(executable(), runtime.discover(executable(), "").executable)
            val invalid = runtime.discover((box.root / "missing-ffmpeg").toString(), ".")
            assertNull(invalid.backend)
            assertTrue(invalid.issues.any { it.code.value == "FFMPEG_EXPLICIT_PATH_UNAVAILABLE" })
            val directory = executable().substringBeforeLast(if (nativeWindows) '\\' else '/')
            assertNotNull(runtime.discover(null, directory).backend)
            assertTrue(box.fs.list(box.root).isEmpty())
        } finally { box.close() }
    }
    @Test fun missingExecutableFailsAndOwnedWorkspaceCanStillBeReleased(): Unit = runBlocking<Unit> {
        val box = Sandbox()
        try {
            val source = livephoto.core.memory.MemoryBinarySource(Bytes(byteArrayOf(1, 2, 3)), SourceId("missing-executable"))
            val runtime = NativeFfmpeg({ box.root.toString() })
            val files = result(runtime.openFiles(source, context, "frame.jpg", true))
            try {
                // Okio's temporary root may be drive-relative on Windows. Resolve the
                // existing parent, not the deliberately nonexistent executable.
                val missing = localFileSystem.canonicalize(box.root) / "definitely-missing-ffmpeg.exe"
                val process = runtime.execute(listOf(missing.toString()), 1000, context)
                assertNotEquals(0, process.code)
                failure(files.outputSize(), "FFMPEG_OUTPUT_MISSING")
            } finally { result(files.close()) }
            assertTrue(box.fs.list(box.root).isEmpty())
            assertEquals(3uL, result(source.size()))
        } finally { box.close() }
    }
    @Test fun nativeVideoProbeFrameRemuxAndExplicitTranscodeUseActualFfmpeg(): Unit = runBlocking<Unit> {
        val box = Sandbox()
        val session = FileInputSession((box.root / "snapshots").toString(), context)
        try {
            val runtime = NativeFfmpeg({ box.root.toString() }, { session.spoolBytes })
            val file = box.root / "sdr video 输入.mp4"
            generate(runtime, listOf("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "8",
                "-vf", "setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709",
                "-c:v", "libx264", "-preset", "medium", "-bf", "0", "-g", "3", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
                "-use_editlist", "0", "-map_metadata", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", file.toString()))
            val before = box.contents(file)
            val source = result(session.capture(file.toString()))
            val ref = ResourceRef(SourceSet.Single(source))
            var lastTrace = ""
            val recording = object : FfmpegRuntime by runtime {
                override suspend fun execute(arguments: List<String>, timeoutMillis: Long, context: Context): FfmpegProcessResult {
                    val value = runtime.execute(arguments, timeoutMillis, context)
                    lastTrace = value.output
                    return value
                }
            }
            val core = DefaultLivePhotoCore(FfmpegMediaBackend(executable(), recording))
            assertTrue(result(core.probe(ProbeRequest(ref, true, context))).issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
            val frameRun = core.extractFrame(ExtractFrameRequest(ref, CoverPosition.FrameIndex(2uL), ImageEncoding(ImageFormat.Jpeg), MemoryOutputTransaction(context, "frame"), context))
            val frame = assertIs<CoreResult.Success<FrameResult>>(frameRun, "Native frame verification: $frameRun\nBounded synthetic-fixture FFmpeg trace:\n$lastTrace").value
            assertEquals(2uL, frame.actualFrameIndex)
            frame.operation.output.assets.forEach { it.readableSource?.close() }
            // MP4->MOV rewrites this fixture's nclx declaration. Keep the fidelity rejection.
            val movOutput = MemoryOutputTransaction(context, "color-mov-refused")
            failure(core.remux(RemuxRequest(ref, VideoContainer.Mov, output = movOutput, context = context)), "POSTCONDITION_FAILED")
            assertTrue(movOutput.committedAssets().isEmpty())
            val remux = result(core.remux(RemuxRequest(ref, VideoContainer.Mp4, output = MemoryOutputTransaction(context, "remux"), context = context)))
            assertTrue(remux.execution.none { it.transcoded })
            remux.output.assets.forEach { it.readableSource?.close() }
            val transcode = result(core.transcode(TranscodeRequest(ref, VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4),
                MutationPolicy(transcode = TranscodePolicy.Explicit), MemoryOutputTransaction(context, "encode"), context)))
            assertTrue(transcode.execution.any { it.transcoded })
            transcode.output.assets.forEach { it.readableSource?.close() }
            assertContentEquals(before, box.contents(file))
            assertEquals(setOf("snapshots", file.name), box.fs.list(box.root).map { it.name }.toSet())
        } finally { result(session.close()); box.close() }
    }
    @Test fun nativeTrimCopiesBothAacTracksAndPublishesOnlyVerifiedMedia(): Unit = runBlocking<Unit> {
        val box = Sandbox()
        val session = FileInputSession((box.root / "snapshots").toString(), context)
        try {
            val runtime = NativeFfmpeg({ box.root.toString() }, { session.spoolBytes })
            val video = box.root / "video.mp4"
            generate(runtime, listOf("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "40", "-c:v", "libx264",
                "-preset", "ultrafast", "-bf", "0", "-g", "3", "-sc_threshold", "0", "-pix_fmt", "yuv420p", "-use_editlist", "0",
                "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", video.toString()))
            val audio = listOf(box.root / "mono.aac", box.root / "stereo.aac")
            for ((index, path) in audio.withIndex()) generate(runtime, listOf("-f", "lavfi", "-i",
                "sine=frequency=${440 + 440 * index}:sample_rate=${if (index == 0) 44100 else 48000}:duration=1.6",
                "-c:a", "aac", "-b:a", "64k", "-ac", (index + 1).toString(), "-flags:a", "+bitexact", "-f", "adts", path.toString()))
            val file = box.root / "two audio.mp4"
            generate(runtime, listOf("-i", video.toString(), "-i", audio[0].toString(), "-i", audio[1].toString(),
                "-map", "1:a", "-map", "0:v", "-map", "2:a", "-c", "copy", "-bsf:a", "aac_adtstoasc", "-use_editlist", "0",
                "-map_metadata", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-write_btrt", "0", "-streamid", "0:4", "-streamid", "1:17", "-streamid", "2:29", "-use_stream_ids_as_track_ids", "1", file.toString()))
            val before = box.contents(file)
            val source = result(session.capture(file.toString()))
            val core = DefaultLivePhotoCore(assertNotNull(runtime.discover(executable(), "").backend))
            val trim = result(core.trim(TrimRequest(ResourceRef(SourceSet.Single(source)),
                TrimSpec(TimeRange(Time(130, 1000u), Time(270, 1000u))),
                output = MemoryOutputTransaction(context, "trim"), context = context)))
            assertFalse(trim.wasTranscoded); assertTrue(trim.wasBitstreamPreserved)
            assertEquals(3, trim.tracks.size)
            val copied = assertNotNull(trim.operation.output.assets.single().readableSource)
            assertTrue(result(core.probe(ProbeRequest(ResourceRef(SourceSet.Single(copied)), true, context))).issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
            copied.close()
            assertContentEquals(before, box.contents(file))
        } finally { result(session.close()); box.close() }
    }
    @Test fun unknownDecodedColorIsRefusedWithoutPublishingOrLeakingTemporaryFiles(): Unit = runBlocking<Unit> {
        val box = Sandbox()
        val session = FileInputSession((box.root / "snapshots").toString(), context)
        try {
            val runtime = NativeFfmpeg({ box.root.toString() }, { session.spoolBytes })
            val file = box.root / "unknown color.mp4"
            generate(runtime, listOf("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "8",
                "-c:v", "libx264", "-bf", "0", "-g", "3", "-pix_fmt", "yuv420p", "-use_editlist", "0",
                "-map_metadata", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", file.toString()))
            val before = box.contents(file)
            val source = result(session.capture(file.toString()))
            val transaction = MemoryOutputTransaction(context, "unknown-color")
            val core = DefaultLivePhotoCore(assertNotNull(runtime.discover(executable(), "").backend))
            val frame = core.extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(source)), CoverPosition.FrameIndex(2uL), ImageEncoding(ImageFormat.Jpeg), transaction, context))
            failure(frame, "HDR_PRESERVATION_UNAVAILABLE")
            assertTrue(transaction.committedAssets().isEmpty())
            // Unknown color is not permission to encode, but a plain streamcopy may preserve it.
            // This color-declaration-free finite fixture can round-trip through MOV without rewriting colr.
            val mov = result(core.remux(RemuxRequest(ResourceRef(SourceSet.Single(source)), VideoContainer.Mov,
                output = MemoryOutputTransaction(context, "plain-mov"), context = context)))
            val movSource = assertNotNull(mov.output.assets.single().readableSource)
            try {
                val back = result(core.remux(RemuxRequest(ResourceRef(SourceSet.Single(movSource)), VideoContainer.Mp4,
                    output = MemoryOutputTransaction(context, "plain-mp4"), context = context)))
                back.output.assets.forEach { it.readableSource?.close() }
            } finally { movSource.close() }
            assertContentEquals(before, box.contents(file))
            assertEquals(setOf("snapshots", file.name), box.fs.list(box.root).map { it.name }.toSet())
        } finally { result(session.close()); box.close() }
    }
}
