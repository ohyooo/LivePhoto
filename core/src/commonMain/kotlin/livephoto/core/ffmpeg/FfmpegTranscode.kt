package livephoto.core.ffmpeg

import livephoto.core.*
import livephoto.core.StagedAsset
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.*

/** Explicit software AVC profile. No automatic install, hidden stream loss, HDR/CFR/resize or fallback. */
internal object FfmpegTranscode {
    suspend fun run(executable: String, runtime: FfmpegRuntime, job: BackendJob): CoreResult<BackendResult> = attempt {
        if (job.operation !in setOf(Operation.Transcode, Operation.Trim)) fail("INVALID_ARGUMENT", "Backend method and operation differ", Stage.Plan)
        job.validate().orThrow()
        val encoding = if (job.operation == Operation.Trim) VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4)
            else job.videoEncoding ?: fail("INVALID_ARGUMENT", "Missing video encoding", Stage.Plan)
        val ref = job.inputs.single(); val source = (ref.input as? SourceSet.Single)?.source ?: fail("INVALID_ARGUMENT", "Transcode requires one isolated video")
        if (ref.resourceId != null || ref.snapshot != null) fail("INVALID_ARGUMENT", "Core must resolve the transcode resource")
        val reader = BinaryReader(source, job.context); val identity = reader.identity().orThrow()
        val video = BmffVideoProbe(reader).probe(ByteRange(0uL, identity.size)).orThrow()
        val track = transcodeProfile(video, encoding, allowCopiedAudio = job.operation == Operation.Transcode)
        val trim = if (job.operation == Operation.Trim) planTrim(reader, video, job.trim!!, job.policy).also {
            if (!it.encoded) fail("INVALID_ARGUMENT", "Lossless trim must not invoke an encoder", Stage.Plan)
        } else null
        val metadata = RemuxVerification.metadata(reader, video, trimDurationsVerifiedSeparately = trim != null, transcodeAvcConfiguration = true)
        FfmpegAacRemuxProfile.validate(video, metadata)
        for (audio in video.tracks.filter { it.handler == "soun" }) if (validateEsds(audio.codecConfiguration, ParseBudget(job.context)).esDescriptorFlags != 0)
            fail("CAPABILITY_UNSUPPORTED", "Audio copy cannot rewrite referenced/URL/OCR ES descriptors", Stage.Plan)
        if (checkedMultiply(checkedMultiply(track.width!!.toULong(), track.height!!.toULong()), 4uL) > job.context.limits.maxMetadataBytes)
            fail("RESOURCE_LIMIT_EXCEEDED", "Decoded frame exceeds bounded verification budget", Stage.Plan)
        FfmpegWorkspace.use(runtime, source, reader, Stage.Transcode, "Transcode") {
            fun decode(path: String): List<String> = listOf(executable.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "info", "-nostats", "-xerror", "-copyts",
                "-protocol_whitelist", "file", "-err_detect", "explode", "-f", "mov", "-ignore_editlist", "1", "-threads", "1", "-noautorotate", "-i", path.toString(), "-map", "0", "-sn", "-dn")
            val filter = "settb=expr=1/${track.timescale},showinfo=checksum=0"
            // Refuse unknown source pixel semantics BEFORE invoking any encoder.
            val sourceDecode = run(decode(input) + listOf("-vf", filter, "-fps_mode", "passthrough", "-f", "null", "-"), "DECODE_FAILED")
            verifyTrace(sourceDecode.output, track)
            val trimFilter = trim?.boundaries?.let { "trim=start_pts=${it.startTicks}:end_pts=${it.startTicks + it.durationTicks},setpts=PTS-${it.startTicks}," } ?: ""
            run(decode(input) + listOf("-vf", "settb=expr=1/${track.timescale},${trimFilter}setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709",
                "-fps_mode", "passthrough", "-enc_time_base:v", "1:${track.timescale}", "-c:a", "copy", "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-bf", "0", "-g", "30", "-threads:v", "1", "-pix_fmt", "+yuv420p",
                "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-avoid_negative_ts", "disabled", "-use_editlist", "0",
                "-map_metadata", "0", "-map_chapters", "0", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0",
                "-video_track_timescale", track.timescale.toString(), "-movie_timescale", video.movieTimescale.toString()) +
                video.tracks.flatMapIndexed { index, entry -> listOf("-streamid", "$index:${entry.trackId}") } + listOf("-use_stream_ids_as_track_ids", "1",
                "-fs", outputLimit.toString(), "-f", "mp4", output.toString()), "ENCODE_FAILED")
            val size = outputSize("ENCODE_FAILED")
            AacRemuxMetadata.restore(video, this, job.context)
            reader.validateIdentity().orThrow()
            val staged = openOutput()
            try {
                val outputReader = BinaryReader(staged, job.context); val actual = BmffVideoProbe(outputReader).probe(ByteRange(0uL, size)).orThrow()
                if (trim == null) verifyTranscode(reader, video, outputReader, actual, encoding)
                else verifyEncodedTrim(reader, trim.boundaries, outputReader, actual)
                val decodedOutput = run(decode(output) + listOf("-vf", filter, "-fps_mode", "passthrough", "-f", "null", "-"), "DECODE_FAILED")
                verifyTrace(decodedOutput.output, actual.tracks.single { it.handler == "vide" })
                val facts = videoFacts(actual); reader.validateIdentity().orThrow()
                val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/mp4")).orThrow()
                try { copyRange(outputReader, handle.sink, ByteRange(0uL, size), job.context).orThrow(); handle.sink.flush().orThrow() }
                finally { handle.sink.close().orThrow() }
                reader.validateIdentity().orThrow()
                BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, "video/mp4", size)), listOf(facts), listOf(
                    ExecutionRecord(if (trim == null) Stage.Transcode else Stage.Trim, "ffmpeg-external", "Authorized software libx264 medium/CRF18/bf0; whole source/output SDR decode and exact selected sample timeline verified; any AAC tracks copied and independently verified, not whole-asset bitstream preserving", true, false, false, videoFacts(video), facts)),
                    tracks = trim?.let { listOf(it.trackTrim) } ?: emptyList(), timelineMap = trim?.boundaries?.mapping ?: emptyList())
            } finally { staged.close() }
        }
    }
    private fun verifyTrace(trace: String, track: VideoTrack) {
        val frames = Regex("(?m)^.*Parsed_showinfo[^\\r\\n]*\\bn:\\s*\\d+[^\\r\\n]*$").findAll(trace).map { it.value }.toList()
        if (frames.size != track.samples.size) fail("POSTCONDITION_FAILED", "Decoder did not return every source presentation frame", Stage.Transcode)
        for ((line, sample) in frames.zip(track.samples)) {
            if (Regex("\\bpts:\\s*(-?\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() != sample.presentationTime ||
                !line.contains("s:${track.width}x${track.height}") || !line.contains("sar:1/1") || !line.contains("i:P") || !line.contains("fmt:yuv420p"))
                fail("HDR_PRESERVATION_UNAVAILABLE", "Decoded frame time/transform/depth is outside the authorized profile", Stage.Transcode)
        }
        val colors = Regex("(?m)^.*Parsed_showinfo[^\\r\\n]*color_range:[^\\r\\n]*$").findAll(trace).map { it.value }.toList()
        if (colors.size != frames.size || colors.any { !it.contains("color_range:tv color_space:bt709 color_primaries:bt709 color_trc:bt709") })
            fail("HDR_PRESERVATION_UNAVAILABLE", "Every decoded frame must prove known preserved BT.709 limited SDR", Stage.Transcode)
        // x264's own version/options SEI is encoded-data annotation, rewritten by the explicit encoder.
        val side = Regex("(?m)^.*Parsed_showinfo.*side data[^\\r\\n]*$").findAll(trace).map { it.value }.toList()
        val uuids = Regex("(?m)^.*Parsed_showinfo.*UUID=[^\\r\\n]*$").findAll(trace).map { it.value.substringAfter("UUID=").trim().replace("-", "").lowercase() }.toList()
        val data = Regex("(?m)^.*Parsed_showinfo.*User Data=[^\\r\\n]*$").findAll(trace).map { it.value.substringAfter("User Data=").trim() }.toList()
        if (side.any { !it.contains("User Data Unregistered") } || uuids.size != side.size || data.size != side.size ||
            uuids.any { it != "dc45e9bde6d948b7962cd820d923eeef" } || data.any { !it.startsWith("7832363420", ignoreCase = true) })
            fail("UNSAFE_METADATA_REWRITE", "Unclassified decoded side data cannot be silently removed by encoding", Stage.Transcode)
    }
}
