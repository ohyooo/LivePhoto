package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.videoFacts
import java.nio.file.Files
import java.nio.file.Path

/** Restricted external streamcopy. Temporary outputs are verified before touching Core staging. */
internal object FfmpegRemux {
    suspend fun run(executable: Path, job: BackendJob): CoreResult<BackendResult> = attempt {
        if (job.operation != Operation.Remux) fail("INVALID_ARGUMENT", "Backend method and operation differ", Stage.Plan)
        job.validate().orThrow()
        if (job.remuxContainer !in setOf(VideoContainer.Mp4, VideoContainer.Mov)) fail("CAPABILITY_UNSUPPORTED", "FFmpeg remux target is not implemented", Stage.Plan)
        val ref = job.inputs.single()
        val source = (ref.input as? SourceSet.Single)?.source ?: fail("INVALID_ARGUMENT", "FFmpeg requires one resolved video resource")
        if (ref.resourceId != null || ref.snapshot != null) fail("INVALID_ARGUMENT", "Core must resolve video references before backend remux")
        val reader = BinaryReader(source, job.context)
        val identity = reader.identity().orThrow()
        val before = BmffVideoProbe(reader).probe(ByteRange(0uL, identity.size)).orThrow()
        val video = before.tracks.singleOrNull { it.handler == "vide" }
            ?: fail("CAPABILITY_UNSUPPORTED", "Remux requires exactly one verified video track; no track may be dropped", Stage.Plan)
        val audio = before.tracks.filter { it.handler == "soun" }
        if (audio.size > 1 || before.tracks.any { it.handler !in setOf("vide", "soun") } ||
            audio.any { it.audioCodec != AudioCodec.Aac || it.sampleEntry != "mp4a" } ||
            audio.isNotEmpty() && (before.container != VideoContainer.Mp4 || job.remuxContainer != VideoContainer.Mp4))
            fail("CAPABILITY_UNSUPPORTED", "AAC-bearing remux currently requires MP4 to MP4 with one verified AAC-LC track; no track may be dropped", Stage.Plan)
        if (before.movieTimescale > Int.MAX_VALUE.toUInt() || before.tracks.any { it.timescale > Int.MAX_VALUE.toUInt() || it.trackId > Int.MAX_VALUE.toUInt() })
            fail("CAPABILITY_UNSUPPORTED", "FFmpeg muxer timescale domain cannot represent the source", Stage.Plan)
        val metadata = RemuxVerification.metadata(reader, before)
        for (track in audio) if (validateEsds(track.codecConfiguration, ParseBudget(job.context)).esDescriptorFlags != 0)
            fail("CAPABILITY_UNSUPPORTED", "AAC remux cannot rewrite referenced/URL/OCR ES descriptors", Stage.Plan)
        if (identity.size >= job.context.limits.maxSpoolBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Remux input leaves no temporary output budget", Stage.Plan)
        val outputLimit = minOf(job.context.limits.maxOutputBytes, job.context.limits.maxSpoolBytes - identity.size, Long.MAX_VALUE.toULong())
        if (outputLimit == 0uL) fail("RESOURCE_LIMIT_EXCEEDED", "Remux has no output storage budget", Stage.Plan)
        var directory: Path? = null
        var input: Path? = null
        var output: Path? = null
        try {
            directory = Files.createTempDirectory("livephoto-ffmpeg-remux-")
            input = directory.resolve("input.bin"); output = directory.resolve("output.bin")
            Files.newOutputStream(input).use { stream ->
                var offset = 0uL
                while (offset < identity.size) {
                    val bytes = reader.readBuffer(offset, minOf(65_536uL, identity.size - offset).toUInt()).orThrow()
                    stream.write(bytes.toByteArray()); offset += bytes.size.toULong()
                }
            }
            reader.validateIdentity().orThrow()
            val process = ExternalProcess.run(listOf(executable.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
                "-copyts", "-protocol_whitelist", "file", "-f", "mov", "-i", input.toString(),
                "-map", "0", "-c", "copy", "-copytb", "1", "-avoid_negative_ts", "disabled", "-map_metadata", "0", "-map_chapters", "0",
                "-fflags", "+bitexact", "-write_btrt", "0", "-video_track_timescale", video.timescale.toString(),
                "-movie_timescale", before.movieTimescale.toString()) + before.tracks.flatMapIndexed { index, track -> listOf("-streamid", "$index:${track.trackId}") } + listOf("-use_stream_ids_as_track_ids", "1",
                "-fs", outputLimit.toString(), "-f", if (job.remuxContainer == VideoContainer.Mov) "mov" else "mp4", output.toString()), 600_000L, job.context)
            reader.validateIdentity().orThrow()
            when {
                process.cancelled -> fail("CANCELLED", "Remux cancelled", Stage.Remux)
                process.timedOut -> fail("BACKEND_TIMEOUT", "Remux exceeded its runtime limit", Stage.Remux)
                process.outputLimited -> fail("RESOURCE_LIMIT_EXCEEDED", "Remux diagnostic budget exceeded", Stage.Remux)
                process.ioFailed -> fail("IO_READ_FAILED", "Remux process did not exit with drained diagnostics", Stage.Remux)
                process.code != 0 -> fail("REMUX_FAILED", "FFmpeg streamcopy failed", Stage.Remux)
            }
            if (!Files.isRegularFile(output)) fail("REMUX_FAILED", "Remux produced no file", Stage.Remux)
            val size = Files.size(output).toULong()
            if (size > outputLimit) fail("RESOURCE_LIMIT_EXCEEDED", "Remux output exceeded its storage budget", Stage.Remux)
            reader.validateIdentity().orThrow()
            val hintsRestored = AacRemuxMetadata.restore(before, output, job.context)
            reader.validateIdentity().orThrow()
            val staged = FileBinarySource(output)
            try {
                val outputReader = BinaryReader(staged, job.context)
                val actual = BmffVideoProbe(outputReader).probe(ByteRange(0uL, size)).orThrow()
                if (actual.container != job.remuxContainer) fail("POSTCONDITION_FAILED", "Wrong remux container", Stage.Verify)
                RemuxVerification.verify(reader, before, outputReader, actual)
                RemuxVerification.verifyMetadata(metadata, RemuxVerification.metadata(outputReader, actual))
                val facts = videoFacts(actual)
                val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = facts.mime!!)).orThrow()
                try { copyRange(outputReader, handle.sink, ByteRange(0uL, size), job.context).orThrow(); handle.sink.flush().orThrow() }
                finally { handle.sink.close().orThrow() }
                reader.validateIdentity().orThrow()
                BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, facts.mime, size)), listOf(facts), listOf(
                    ExecutionRecord(Stage.Remux, "ffmpeg-external", "Explicit all-track streamcopy; no encoder; independently verified temporary output" +
                        if (hintsRestored) "; preserved original parsed AAC bitrate/buffer hints" else "", false, true, false, videoFacts(before), facts)))
            } finally { staged.close() }
        } catch (fault: CoreFault) { throw fault }
        catch (_: Exception) { fail("IO_READ_FAILED", "Remux temporary media IO failed", Stage.Remux) }
        finally {
            var failed = false
            for (owned in listOfNotNull(output, input, directory)) try { Files.deleteIfExists(owned) } catch (_: Exception) { failed = true }
            if (failed) fail("IO_WRITE_FAILED", "Remux temporary cleanup failed", Stage.Remux)
        }
    }
}
