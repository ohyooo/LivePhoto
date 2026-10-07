package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.StagedAsset
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.*
import java.nio.file.Files
import java.nio.file.Path

/** Explicit software AVC profile. No automatic install, hidden stream loss, HDR/CFR/resize or fallback. */
internal object FfmpegTranscode {
    suspend fun run(executable: Path, job: BackendJob): CoreResult<BackendResult> = attempt {
        if (job.operation != Operation.Transcode) fail("INVALID_ARGUMENT", "Backend method and operation differ", Stage.Plan)
        job.validate().orThrow()
        val encoding = job.videoEncoding ?: fail("INVALID_ARGUMENT", "Missing video encoding", Stage.Plan)
        val ref = job.inputs.single(); val source = (ref.input as? SourceSet.Single)?.source ?: fail("INVALID_ARGUMENT", "Transcode requires one isolated video")
        if (ref.resourceId != null || ref.snapshot != null) fail("INVALID_ARGUMENT", "Core must resolve the transcode resource")
        val reader = BinaryReader(source, job.context); val identity = reader.identity().orThrow()
        val video = BmffVideoProbe(reader).probe(ByteRange(0uL, identity.size)).orThrow(); val track = transcodeProfile(video, encoding)
        RemuxVerification.metadata(reader, video, transcodeAvcConfiguration = true)
        if (checkedMultiply(checkedMultiply(track.width!!.toULong(), track.height!!.toULong()), 4uL) > job.context.limits.maxMetadataBytes)
            fail("RESOURCE_LIMIT_EXCEEDED", "Decoded frame exceeds bounded verification budget", Stage.Plan)
        if (identity.size >= job.context.limits.maxSpoolBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Transcode input leaves no temporary output budget", Stage.Plan)
        val outputLimit = minOf(job.context.limits.maxOutputBytes, job.context.limits.maxSpoolBytes - identity.size, Long.MAX_VALUE.toULong())
        if (outputLimit == 0uL) fail("RESOURCE_LIMIT_EXCEEDED", "No temporary encoded output budget", Stage.Plan)
        var directory: Path? = null; var input: Path? = null; var output: Path? = null
        try {
            directory = Files.createTempDirectory("livephoto-ffmpeg-transcode-"); input = directory.resolve("input.bin"); output = directory.resolve("output.bin")
            Files.newOutputStream(input).use { stream ->
                var offset = 0uL
                while (offset < identity.size) {
                    val bytes = reader.readBuffer(offset, minOf(65536uL, identity.size - offset).toUInt()).orThrow()
                    stream.write(bytes.toByteArray()); offset += bytes.size.toULong()
                }
            }
            reader.validateIdentity().orThrow()
            fun decode(path: Path): List<String> = listOf(executable.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "info", "-nostats", "-xerror", "-copyts",
                "-protocol_whitelist", "file", "-err_detect", "explode", "-f", "mov", "-ignore_editlist", "1", "-threads", "1", "-noautorotate", "-i", path.toString(), "-map", "0", "-sn", "-dn")
            val filter = "settb=expr=1/${track.timescale},showinfo=checksum=0"
            // Refuse unknown source pixel semantics BEFORE invoking any encoder.
            val sourceDecode = ExternalProcess.run(decode(input) + listOf("-vf", filter, "-fps_mode", "passthrough", "-f", "null", "-"), 600_000L, job.context)
            checkProcess(sourceDecode, reader, "DECODE_FAILED"); verifyTrace(sourceDecode.output, track)
            val encoded = ExternalProcess.run(decode(input) + listOf("-vf", "settb=expr=1/${track.timescale},setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709",
                "-fps_mode", "passthrough", "-enc_time_base:v", "1:${track.timescale}", "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-bf", "0", "-g", "30", "-threads:v", "1", "-pix_fmt", "+yuv420p",
                "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-avoid_negative_ts", "disabled", "-use_editlist", "0",
                "-map_metadata", "0", "-map_chapters", "0", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0",
                "-video_track_timescale", track.timescale.toString(), "-movie_timescale", video.movieTimescale.toString(), "-streamid", "0:${track.trackId}", "-use_stream_ids_as_track_ids", "1",
                "-fs", outputLimit.toString(), "-f", "mp4", output.toString()), 600_000L, job.context)
            checkProcess(encoded, reader, "ENCODE_FAILED")
            if (!Files.isRegularFile(output)) fail("ENCODE_FAILED", "Encoder produced no video", Stage.Transcode)
            val size = Files.size(output).toULong()
            if (size > outputLimit) fail("RESOURCE_LIMIT_EXCEEDED", "Encoded output exceeded temporary storage budget", Stage.Transcode)
            val staged = FileBinarySource(output)
            try {
                val outputReader = BinaryReader(staged, job.context); val actual = BmffVideoProbe(outputReader).probe(ByteRange(0uL, size)).orThrow()
                verifyTranscode(reader, video, outputReader, actual, encoding)
                val decodedOutput = ExternalProcess.run(decode(output) + listOf("-vf", filter, "-fps_mode", "passthrough", "-f", "null", "-"), 600_000L, job.context)
                checkProcess(decodedOutput, reader, "DECODE_FAILED"); verifyTrace(decodedOutput.output, actual.tracks.single())
                val facts = videoFacts(actual); reader.validateIdentity().orThrow()
                val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/mp4")).orThrow()
                try { copyRange(outputReader, handle.sink, ByteRange(0uL, size), job.context).orThrow(); handle.sink.flush().orThrow() }
                finally { handle.sink.close().orThrow() }
                reader.validateIdentity().orThrow()
                BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, "video/mp4", size)), listOf(facts), listOf(
                    ExecutionRecord(Stage.Transcode, "ffmpeg-external", "Explicit software libx264 medium/CRF18/bf0; whole source/output progressive square-pixel eight-bit BT.709 limited SDR decode and exact VFR sample timeline verified; not bitstream preserving", true, false, false, videoFacts(video), facts)))
            } finally { staged.close() }
        } catch (fault: CoreFault) { throw fault }
        catch (_: Exception) { fail("IO_READ_FAILED", "Transcode temporary media IO failed", Stage.Transcode) }
        finally {
            var failed = false
            for (owned in listOfNotNull(output, input, directory)) try { Files.deleteIfExists(owned) } catch (_: Exception) { failed = true }
            if (failed) fail("IO_WRITE_FAILED", "Transcode temporary cleanup failed", Stage.Transcode)
        }
    }
    private suspend fun checkProcess(process: ProcessResult, reader: BinaryReader, code: String) {
        reader.validateIdentity().orThrow()
        when {
            process.cancelled -> fail("CANCELLED", "Transcode cancelled", Stage.Transcode)
            process.timedOut -> fail("BACKEND_TIMEOUT", "Transcode exceeded runtime limit", Stage.Transcode)
            process.outputLimited -> fail("RESOURCE_LIMIT_EXCEEDED", "Transcode diagnostic budget exceeded", Stage.Transcode)
            process.ioFailed -> fail("IO_READ_FAILED", "Transcode process failed to exit with drained diagnostics", Stage.Transcode)
            process.code != 0 -> fail(code, "Transcode decode/encode failed without fallback", Stage.Transcode)
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
