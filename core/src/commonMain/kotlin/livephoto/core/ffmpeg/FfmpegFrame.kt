package livephoto.core.ffmpeg

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.selectFrame
import livephoto.core.implementation.videoFacts
import livephoto.core.implementation.jpegFacts
import livephoto.core.jpeg.*

/** Finite SDR profile. No keyframe seek, average FPS, autorotation, HDR downgrade, or video encoder. */
internal object FfmpegFrame {
    suspend fun run(executable: String, runtime: FfmpegRuntime, job: BackendJob): CoreResult<BackendResult> = attempt {
        if (job.operation != Operation.ExtractFrame) fail("INVALID_ARGUMENT", "Backend method and operation differ", Stage.Plan)
        job.validate().orThrow()
        val encoding = job.imageEncoding ?: fail("INVALID_ARGUMENT", "Missing image encoding", Stage.Plan)
        if (encoding.format != ImageFormat.Jpeg || encoding.quality != null || encoding.dynamicRange != DynamicRangePolicy.Preserve)
            fail("CAPABILITY_UNSUPPORTED", "This frame profile supports default-quality SDR-preserving JPEG only", Stage.Plan)
        val ref = job.inputs.single()
        val source = (ref.input as? SourceSet.Single)?.source ?: fail("INVALID_ARGUMENT", "Frame backend requires an isolated video")
        if (ref.resourceId != null || ref.snapshot != null) fail("INVALID_ARGUMENT", "Core must resolve the frame resource")
        val reader = BinaryReader(source, job.context)
        val identity = reader.identity().orThrow()
        val video = BmffVideoProbe(reader).probe(ByteRange(0uL, identity.size)).orThrow()
        val selected = selectFrame(video, job.position ?: fail("INVALID_ARGUMENT", "Missing frame position"))
        val track = selected.track
        // Classification rejects unrecognized private/auxiliary metadata that might change image meaning.
        RemuxVerification.metadata(reader, video)
        val identityMatrix = Bytes(unsignedBytes(0x10000uL, 4, Endian.Big).toByteArray() + ByteArray(12) +
            unsignedBytes(0x10000uL, 4, Endian.Big).toByteArray() + ByteArray(12) + unsignedBytes(0x40000000uL, 4, Endian.Big).toByteArray())
        if (track.transform != identityMatrix || track.displayWidthFixed.toULong() != checkedMultiply(track.width!!.toULong(), 65536uL) ||
            track.displayHeightFixed.toULong() != checkedMultiply(track.height!!.toULong(), 65536uL))
            fail("CAPABILITY_UNSUPPORTED", "Rotated/scaled display transforms cannot be silently discarded", Stage.Plan)
        if (track.samples.map { it.presentationTime }.distinct().size != track.samples.size)
            fail("CAPABILITY_UNSUPPORTED", "Duplicate frame PTS requires a decoder ordinal proof not implemented by this backend", Stage.Plan)
        val edit = track.edit
        if (selected.time < (edit?.let { Time(it.emptyDuration.toLong(), it.movieTimescale) } ?: Time.Zero))
            fail("CAPABILITY_UNSUPPORTED", "Frame is outside the visible nonempty edit", Stage.Plan)
        val mediaPts = PresentationClock.create(track.timescale, edit).mediaTime(selected.sample.presentationTime)
        // FFmpeg filter expressions use doubles: integers up to 2^53 are exactly representable.
        if (mediaPts > 9_007_199_254_740_992L || track.timescale > Int.MAX_VALUE.toUInt())
            fail("VALUE_NOT_REPRESENTABLE", "Frame PTS cannot be selected exactly by this filter profile", Stage.Plan)
        val pixels = checkedMultiply(track.width.toULong(), track.height.toULong())
        if (checkedMultiply(pixels, 4uL) > job.context.limits.maxMetadataBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Decoded frame exceeds the bounded image verification budget", Stage.Plan)
        FfmpegWorkspace.use(runtime, source, reader, Stage.DecodeFrame, "Frame", "frame.jpg", cleanupStage = Stage.EncodeImage) {
            val common = listOf(executable.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "info", "-nostats", "-xerror", "-copyts",
                "-protocol_whitelist", "file", "-err_detect", "explode", "-f", "mov", "-ignore_editlist", "1", "-threads", "1", "-noautorotate", "-i", input.toString(),
                "-map", "0:i:${track.trackId}", "-an", "-sn", "-dn")
            val select = "settb=expr=1/${track.timescale},select='eq(pts,$mediaPts)'"
            val decoded = run(common + listOf("-vf", "$select,showinfo=checksum=0", "-frames:v", "1", "-fps_mode", "passthrough", "-f", "null", "-"), "DECODE_FAILED")
            verifyTrace(decoded.output, mediaPts, track, encoded = false)
            // A documented standard-sRGB JPEG transform, only after proving an 8-bit BT.709 SDR frame.
            val converted = "$select,colorspace=space=bt470bg:primaries=bt709:trc=iec61966-2-1:range=pc:format=yuv420p,format=yuvj420p,showinfo=checksum=0"
            val encoded = run(common + listOf("-vf", converted, "-frames:v", "1", "-fps_mode", "passthrough", "-c:v", "mjpeg", "-q:v", "2",
                "-threads:v", "1", "-map_metadata", "-1", "-map_chapters", "-1", "-fflags", "+bitexact", "-flags:v", "+bitexact",
                "-fs", outputLimit.toString(), "-f", "image2", "-update", "1", output.toString()), "ENCODE_FAILED", Stage.EncodeImage)
            verifyTrace(encoded.output, mediaPts, track, encoded = true)
            val size = outputSize("ENCODE_FAILED", Stage.EncodeImage)
            val staged = openOutput()
            try {
                val outputReader = BinaryReader(staged, job.context)
                val jpeg = JpegParser.parse(outputReader).orThrow()
                val facts = jpegFacts(outputReader, jpeg)
                if (jpeg.trailing.length != 0uL || facts.width != track.width || facts.height != track.height || facts.issues.any { it.severity == Severity.Error })
                    fail("POSTCONDITION_FAILED", "Frame encoder changed dimensions or produced malformed JPEG", Stage.Verify)
                // Separate FFmpeg JPEG decode plus Core structural/dimension proof.
                // This does not claim verification by a different decoder engine.
                val decodedJpeg = run(listOf(executable, "-nostdin", "-hide_banner", "-loglevel", "info", "-nostats", "-xerror",
                    "-abort_on", "empty_output+empty_output_stream", "-protocol_whitelist", "file", "-err_detect", "explode",
                    "-f", "mjpeg", "-threads", "1", "-noautorotate", "-i", output, "-map", "0:v",
                    "-vf", "showinfo=checksum=0", "-fps_mode", "passthrough", "-f", "null", "-"), "DECODE_FAILED", Stage.Verify)
                val frames = Regex("(?m)^.*Parsed_showinfo[^\\r\\n]*\\bn:\\s*\\d+[^\\r\\n]*$")
                    .findAll(decodedJpeg.output).map { it.value }.toList()
                if (frames.size != 1 || !frames.single().contains("s:${track.width}x${track.height}"))
                    fail("POSTCONDITION_FAILED", "Encoded JPEG must decode to exactly one frame with the selected dimensions", Stage.Verify)
                reader.validateIdentity().orThrow()
                val handle = job.destination.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/jpeg")).orThrow()
                try { copyRange(outputReader, handle.sink, ByteRange(0uL, size), job.context).orThrow(); handle.sink.flush().orThrow() }
                finally { handle.sink.close().orThrow() }
                reader.validateIdentity().orThrow()
                BackendResult(listOf(StagedAsset(handle.id, AssetRole.PrimaryImage, "image/jpeg", size)), listOf(facts),
                    listOf(Stage.DecodeFrame, Stage.EncodeImage).map { stage -> ExecutionRecord(stage, "ffmpeg-external",
                        if (stage == Stage.DecodeFrame) "Exact integer media PTS verified; presentation index resolved from sample/edit timeline" else "Explicit derived SDR JPEG encoding; source video is not transcoded",
                        false, false, false, videoFacts(video), facts) }, actualFrameTime = selected.time, actualFrameIndex = selected.index, actualFrameTrack = TrackId(track.trackId.toString()))
            } finally { staged.close() }
        }
    }

    private fun verifyTrace(trace: String, pts: Long, track: VideoTrack, encoded: Boolean) {
        val frames = Regex("(?m)^.*Parsed_showinfo[^\\r\\n]*\\bn:\\s*\\d+[^\\r\\n]*$").findAll(trace).map { it.value }.toList()
        if (frames.size != 1 || Regex("\\bpts:\\s*(-?\\d+)").find(frames.single())?.groupValues?.get(1)?.toLongOrNull() != pts ||
            !frames.single().contains("s:${track.width}x${track.height}") || !frames.single().contains("sar:1/1") || !frames.single().contains("i:P"))
            fail("POSTCONDITION_FAILED", "Decoder trace did not prove the exact progressive square-pixel frame", Stage.DecodeFrame)
        val format = if (encoded) "fmt:yuvj420p" else "fmt:yuv420p"
        val color = if (encoded) "color_range:pc color_space:bt470bg color_primaries:bt709 color_trc:iec61966-2-1" else "color_range:tv color_space:bt709 color_primaries:bt709 color_trc:bt709"
        if (!frames.single().contains(format)) fail("HDR_PRESERVATION_UNAVAILABLE", "Decoded pixel format is outside the authorized eight-bit profile", Stage.DecodeFrame)
        if (!trace.contains(color)) fail("HDR_PRESERVATION_UNAVAILABLE", "Decoded color properties do not match the authorized SDR profile", Stage.DecodeFrame)
        if (Regex("(?m)^.*Parsed_showinfo.*side data[^\\r\\n]*$").findAll(trace).any { !it.value.contains("User Data Unregistered") })
            fail("HDR_PRESERVATION_UNAVAILABLE", "Decoded frame has unclassified auxiliary side data", Stage.DecodeFrame)
    }
}
