package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.selectFrame
import livephoto.core.implementation.videoFacts
import livephoto.core.implementation.jpegFacts
import livephoto.core.jpeg.*
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Finite SDR profile. No keyframe seek, average FPS, autorotation, HDR downgrade, or video encoder. */
internal object FfmpegFrame {
    suspend fun run(executable: Path, job: BackendJob): CoreResult<BackendResult> = attempt {
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
        val emptyTicks = if (edit == null) 0uL else {
            val product = checkedMultiply(edit.emptyDuration, track.timescale.toULong())
            if (product % edit.movieTimescale.toULong() != 0uL) fail("VALUE_NOT_REPRESENTABLE", "Empty edit cannot be expressed in media ticks", Stage.Plan)
            product / edit.movieTimescale.toULong()
        }
        if (emptyTicks > Long.MAX_VALUE.toULong() || selected.sample.presentationTime < emptyTicks.toLong())
            fail("CAPABILITY_UNSUPPORTED", "Frame is outside the visible nonempty edit", Stage.Plan)
        val shifted = selected.sample.presentationTime - emptyTicks.toLong()
        if (shifted > Long.MAX_VALUE - (edit?.mediaStart ?: 0L)) fail("INTEGER_OVERFLOW", "Frame media PTS exceeds signed time domain", Stage.Plan)
        val mediaPts = shifted + (edit?.mediaStart ?: 0L)
        // FFmpeg filter expressions use doubles: integers up to 2^53 are exactly representable.
        if (mediaPts > 9_007_199_254_740_992L || track.timescale > Int.MAX_VALUE.toUInt())
            fail("VALUE_NOT_REPRESENTABLE", "Frame PTS cannot be selected exactly by this filter profile", Stage.Plan)
        val pixels = checkedMultiply(track.width.toULong(), track.height.toULong())
        if (checkedMultiply(pixels, 4uL) > job.context.limits.maxMetadataBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Decoded frame exceeds the bounded image verification budget", Stage.Plan)
        if (identity.size >= job.context.limits.maxSpoolBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Frame input leaves no temporary output budget", Stage.Plan)
        val outputLimit = minOf(job.context.limits.maxOutputBytes, job.context.limits.maxSpoolBytes - identity.size, Long.MAX_VALUE.toULong())
        if (outputLimit == 0uL) fail("RESOURCE_LIMIT_EXCEEDED", "No frame output storage budget", Stage.Plan)
        var directory: Path? = null
        var input: Path? = null
        var output: Path? = null
        try {
            directory = Files.createTempDirectory("livephoto-ffmpeg-frame-")
            input = directory.resolve("input.bin"); output = directory.resolve("frame.jpg")
            Files.newOutputStream(input).use { stream ->
                var offset = 0uL
                while (offset < identity.size) {
                    val bytes = reader.readBuffer(offset, minOf(65536uL, identity.size - offset).toUInt()).orThrow()
                    stream.write(bytes.toByteArray()); offset += bytes.size.toULong()
                }
            }
            reader.validateIdentity().orThrow()
            val common = listOf(executable.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "info", "-nostats", "-xerror", "-copyts",
                "-protocol_whitelist", "file", "-err_detect", "explode", "-f", "mov", "-ignore_editlist", "1", "-threads", "1", "-noautorotate", "-i", input.toString(),
                "-map", "0:i:${track.trackId}", "-an", "-sn", "-dn")
            val select = "settb=expr=1/${track.timescale},select='eq(pts,$mediaPts)'"
            val decoded = ExternalProcess.run(common + listOf("-vf", "$select,showinfo=checksum=0", "-frames:v", "1", "-fps_mode", "passthrough", "-f", "null", "-"), 600_000L, job.context)
            checkProcess(decoded, reader)
            verifyTrace(decoded.output, mediaPts, track, encoded = false)
            // A documented standard-sRGB JPEG transform, only after proving an 8-bit BT.709 SDR frame.
            val converted = "$select,colorspace=space=bt470bg:primaries=bt709:trc=iec61966-2-1:range=pc:format=yuv420p,format=yuvj420p,showinfo=checksum=0"
            val encoded = ExternalProcess.run(common + listOf("-vf", converted, "-frames:v", "1", "-fps_mode", "passthrough", "-c:v", "mjpeg", "-q:v", "2",
                "-threads:v", "1", "-map_metadata", "-1", "-map_chapters", "-1", "-fflags", "+bitexact", "-flags:v", "+bitexact",
                "-fs", outputLimit.toString(), "-f", "image2", "-update", "1", output.toString()), 600_000L, job.context)
            checkProcess(encoded, reader)
            verifyTrace(encoded.output, mediaPts, track, encoded = true)
            if (!Files.isRegularFile(output)) fail("ENCODE_FAILED", "Frame encoder produced no image", Stage.EncodeImage)
            val size = Files.size(output).toULong()
            if (size > outputLimit) fail("RESOURCE_LIMIT_EXCEEDED", "Frame exceeded temporary/output storage budget", Stage.EncodeImage)
            val staged = FileBinarySource(output)
            try {
                val outputReader = BinaryReader(staged, job.context)
                val jpeg = JpegParser.parse(outputReader).orThrow()
                val facts = jpegFacts(outputReader, jpeg)
                if (jpeg.trailing.length != 0uL || facts.width != track.width || facts.height != track.height || facts.issues.any { it.severity == Severity.Error })
                    fail("POSTCONDITION_FAILED", "Frame encoder changed dimensions or produced malformed JPEG", Stage.Verify)
                // Independent existing-JDK image decoder, not a second invocation of the producing encoder.
                val image = ImageIO.read(output.toFile()) ?: fail("DECODE_FAILED", "Encoded frame failed independent JPEG decoding", Stage.Verify)
                if (image.width.toUInt() != track.width || image.height.toUInt() != track.height) fail("POSTCONDITION_FAILED", "Independent frame dimensions differ", Stage.Verify)
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
        } catch (fault: CoreFault) { throw fault }
        catch (_: Exception) { fail("IO_READ_FAILED", "Frame temporary media IO or independent image decoding failed", Stage.DecodeFrame) }
        finally {
            var failed = false
            for (owned in listOfNotNull(output, input, directory)) try { Files.deleteIfExists(owned) } catch (_: Exception) { failed = true }
            if (failed) fail("IO_WRITE_FAILED", "Frame temporary cleanup failed", Stage.EncodeImage)
        }
    }

    private suspend fun checkProcess(process: ProcessResult, reader: BinaryReader) {
        reader.validateIdentity().orThrow()
        when {
            process.cancelled -> fail("CANCELLED", "Frame extraction cancelled", Stage.DecodeFrame)
            process.timedOut -> fail("BACKEND_TIMEOUT", "Frame processing exceeded runtime limit", Stage.DecodeFrame)
            process.outputLimited -> fail("RESOURCE_LIMIT_EXCEEDED", "Frame diagnostic budget exceeded", Stage.DecodeFrame)
            process.ioFailed -> fail("IO_READ_FAILED", "Frame process failed to exit with drained diagnostics", Stage.DecodeFrame)
            process.code != 0 -> fail("DECODE_FAILED", "Frame decoder/encoder failed", Stage.DecodeFrame)
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
