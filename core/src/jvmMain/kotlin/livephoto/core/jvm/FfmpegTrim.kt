package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.planTrim
import livephoto.core.implementation.microseconds
import livephoto.core.implementation.videoFacts
import livephoto.core.implementation.verifyTrimDurationHeaders
import java.nio.file.Path

/** Lossless-first trim; exact encoding only with independently selected and authorized source boundaries. */
internal object FfmpegTrim {
    suspend fun run(executable: Path, job: BackendJob): CoreResult<BackendResult> = attempt {
        if (job.operation != Operation.Trim) fail("INVALID_ARGUMENT", "Backend method and operation differ", Stage.Plan)
        job.validate().orThrow()
        val ref = job.inputs.single()
        val source = (ref.input as? SourceSet.Single)?.source ?: fail("INVALID_ARGUMENT", "Trim requires one isolated video")
        if (ref.resourceId != null || ref.snapshot != null) fail("INVALID_ARGUMENT", "Core must resolve trim input references")
        val reader = BinaryReader(source, job.context)
        val identity = reader.identity().orThrow()
        val video = BmffVideoProbe(reader).probe(ByteRange(0uL, identity.size)).orThrow()
        val selected = planTrim(reader, video, job.trim ?: fail("INVALID_ARGUMENT", "Missing trim specification"), job.policy)
        if (selected.encoded) return@attempt FfmpegTranscode.run(executable, job).orThrow()
        val plan = selected.boundaries
        val metadata = RemuxVerification.metadata(reader, video, trimDurationsVerifiedSeparately = true)
        if (video.container != VideoContainer.Mp4 || video.movieTimescale > Int.MAX_VALUE.toUInt() || plan.track.timescale > Int.MAX_VALUE.toUInt())
            fail("LOSSLESS_TRIM_UNAVAILABLE", "FFmpeg trim currently requires a bounded MP4 source", Stage.Plan)
        val start = seconds(microseconds(plan.start)); val duration = seconds(microseconds(plan.duration))
        FfmpegWorkspace.use(reader, Stage.Trim, "Trim") {
            run(listOf(executable.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
                "-protocol_whitelist", "file", "-f", "mov", "-ignore_editlist", "1", "-ss", start, "-noaccurate_seek", "-i", input.toString(),
                "-t", duration, "-map", "0", "-c", "copy", "-copytb", "1", "-avoid_negative_ts", "disabled", "-use_editlist", "0", "-map_metadata", "0", "-map_chapters", "0",
                "-fflags", "+bitexact", "-write_btrt", "0", "-video_track_timescale", plan.track.timescale.toString(), "-movie_timescale", video.movieTimescale.toString(),
                "-streamid", "0:${plan.track.trackId}", "-use_stream_ids_as_track_ids", "1", "-fs", outputLimit.toString(), "-f", "mp4", output.toString()), "REMUX_FAILED")
            val size = outputSize("REMUX_FAILED")
            val staged = FileBinarySource(output)
            try {
                val outputReader = BinaryReader(staged, job.context)
                val actual = BmffVideoProbe(outputReader).probe(ByteRange(0uL, size)).orThrow()
                verifyTrimDurationHeaders(outputReader, actual, plan)
                RemuxVerification.verify(reader, plan.expected(), outputReader, actual)
                RemuxVerification.verifyMetadata(metadata, RemuxVerification.metadata(outputReader, actual, trimDurationsVerifiedSeparately = true))
                val facts = videoFacts(actual)
                val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = facts.mime!!)).orThrow()
                try { copyRange(outputReader, handle.sink, ByteRange(0uL, size), job.context).orThrow(); handle.sink.flush().orThrow() }
                finally { handle.sink.close().orThrow() }
                reader.validateIdentity().orThrow()
                BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, facts.mime, size)), listOf(facts), listOf(
                    ExecutionRecord(Stage.Trim, "ffmpeg-external", "Closed-IDR lossless streamcopy; no encoder, no hidden preroll; samples and rebased timeline independently verified", false, true, false, videoFacts(video), facts)),
                    tracks = listOf(plan.trackTrim), timelineMap = plan.mapping)
            } finally { staged.close() }
        }
    }
    private fun seconds(value: Long): String = "${value / 1_000_000}.${(value % 1_000_000).toString().padStart(6, '0')}"
}
