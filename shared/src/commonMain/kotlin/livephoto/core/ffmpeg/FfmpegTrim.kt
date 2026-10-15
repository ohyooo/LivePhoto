package livephoto.core.ffmpeg

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.planTrim
import livephoto.core.implementation.microseconds
import livephoto.core.implementation.videoFacts
import livephoto.core.implementation.verifyTrimDurationHeaders
import livephoto.core.implementation.trimTicks

/** Lossless-first trim; exact encoding only with independently selected and authorized source boundaries. */
internal object FfmpegTrim {
    suspend fun run(executable: String, runtime: FfmpegRuntime, job: BackendJob): CoreResult<BackendResult> = attempt {
        if (job.operation != Operation.Trim) fail("INVALID_ARGUMENT", "Backend method and operation differ", Stage.Plan)
        job.validate().orThrow()
        val ref = job.inputs.single()
        val source = (ref.input as? SourceSet.Single)?.source ?: fail("INVALID_ARGUMENT", "Trim requires one isolated video")
        if (ref.resourceId != null || ref.snapshot != null) fail("INVALID_ARGUMENT", "Core must resolve trim input references")
        val reader = BinaryReader(source, job.context)
        val identity = reader.identity().orThrow()
        val video = BmffVideoProbe(reader).probe(ByteRange(0uL, identity.size)).orThrow()
        val selected = planTrim(reader, video, job.trim ?: fail("INVALID_ARGUMENT", "Missing trim specification"), job.policy)
        if (selected.encoded) return@attempt FfmpegTranscode.run(executable, runtime, job).orThrow()
        val plan = selected.boundaries
        val metadata = RemuxVerification.metadata(reader, video, trimDurationsVerifiedSeparately = true, trimAudioSampleCountsVerifiedSeparately = plan.audio.isNotEmpty())
        if (video.container != VideoContainer.Mp4 || plan.movieTimescale > Int.MAX_VALUE.toUInt() || video.tracks.any { it.timescale > Int.MAX_VALUE.toUInt() || it.trackId > Int.MAX_VALUE.toUInt() })
            fail("LOSSLESS_TRIM_UNAVAILABLE", "FFmpeg trim currently requires a bounded MP4 source", Stage.Plan)
        val start = seconds(microseconds(plan.start)); val duration = seconds(microseconds(plan.duration))
        FfmpegWorkspace.use(runtime, source, reader, Stage.Trim, "Trim") {
            run(listOf(executable.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
                "-protocol_whitelist", "file", "-f", "mov", "-ignore_editlist", "1", "-ss", start, "-noaccurate_seek", "-i", input.toString(),
                "-t", duration, "-map", "0", "-c", "copy", "-copytb", "1", "-avoid_negative_ts", "disabled", "-use_editlist", if (plan.audio.isEmpty()) "0" else "1", "-map_metadata", "0", "-map_chapters", "0",
                "-fflags", "+bitexact", "-write_btrt", "0", "-video_track_timescale", plan.track.timescale.toString(), "-movie_timescale", plan.movieTimescale.toString()) +
                video.tracks.flatMapIndexed { index, track -> listOf("-streamid", "$index:${track.trackId}") } +
                plan.audio.flatMap { selection ->
                    val index = video.tracks.indexOfFirst { it.trackId == selection.track.trackId }
                    val end = trimTicks(plan.end, selection.track.timescale) - selection.originTicks
                    // Packet filtering is FFmpeg's own bitstream filter, with byte modification explicitly disabled.
                    // Keep only whole AAC packets inside the disclosed video range; no hidden preroll/tail.
                    listOf("-bsf:$index", "noise=amount=0:drop='lt(pts,0)+gt(pts+duration,$end)'")
                } + listOf("-use_stream_ids_as_track_ids", "1", "-fs", outputLimit.toString(), "-f", "mp4", output.toString()), "REMUX_FAILED")
            val size = outputSize("REMUX_FAILED")
            AacRemuxMetadata.restore(video, this, job.context)
            reader.validateIdentity().orThrow()
            val staged = openOutput()
            try {
                val outputReader = BinaryReader(staged, job.context)
                val actual = BmffVideoProbe(outputReader).probe(ByteRange(0uL, size)).orThrow()
                verifyTrimDurationHeaders(outputReader, actual, plan)
                RemuxVerification.verify(reader, plan.expected(), outputReader, actual)
                RemuxVerification.verifyMetadata(metadata, RemuxVerification.metadata(outputReader, actual, trimDurationsVerifiedSeparately = true,
                    trimAudioSampleCountsVerifiedSeparately = plan.audio.isNotEmpty()))
                if (plan.audio.isNotEmpty()) run(listOf(executable.toString(), "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror",
                    "-protocol_whitelist", "file", "-err_detect", "explode", "-f", "mov", "-i", output.toString(), "-map", "0", "-f", "null", "-"), "DECODE_FAILED")
                val facts = videoFacts(actual)
                val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = facts.mime!!)).orThrow()
                try { copyRange(outputReader, handle.sink, ByteRange(0uL, size), job.context).orThrow(); handle.sink.flush().orThrow() }
                finally { handle.sink.close().orThrow() }
                reader.validateIdentity().orThrow()
                BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, facts.mime, size)), listOf(facts), listOf(
                    ExecutionRecord(Stage.Trim, "ffmpeg-external", "Closed-IDR all-track streamcopy; AAC whole-packet selection by FFmpeg with exact gap edits; no encoder or hidden packets; samples/configuration and every track timeline independently verified; no PCM startup equality claim", false, true, false, videoFacts(video), facts)),
                    tracks = selected.trackTrims, timelineMap = plan.mapping)
            } finally { staged.close() }
        }
    }
    private fun seconds(value: Long): String = "${value / 1_000_000}.${(value % 1_000_000).toString().padStart(6, '0')}"
}
