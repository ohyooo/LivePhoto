package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.jpeg.JpegParser
import java.nio.file.Files
import java.nio.file.Path

/** Optional user-installed executable. No FFmpeg binaries or libraries are bundled. */
internal class FfmpegMediaBackend(private val executable: Path) : MediaBackend {
    override fun capabilities(): MediaCapabilities = MediaCapabilities(listOf("ffmpeg-external"),
        listOf(CapabilityEntry(Operation.Probe, Implementation.Experimental, conditions = listOf(
            Condition(ConditionOperator.Equals, "decodeScope", Value.Text("bounded-jpeg-or-validated-bmff-video-and-audio"))))) +
            listOf(CapabilityEntry(Operation.Remux, Implementation.Experimental, conditions = listOf(
                Condition(ConditionOperator.Equals, "input", Value.Text("one-video-classified-mp4-mov-no-audio-or-mp4-to-mp4-one-aac-lc-base-asc-canonical-roll-map-all-track-independent-sample-and-metadata-proof"))))) +
            listOf(CapabilityEntry(Operation.ExtractFrame, Implementation.Experimental, conditions = listOf(
                Condition(ConditionOperator.Equals, "frameProfile", Value.Text("progressive-square-pixel-identity-transform-eight-bit-bt709-limited-sdr-to-standard-srgb-jpeg")),
                Condition(ConditionOperator.Equals, "defaultImageQuality", Value.Text("ffmpeg-mjpeg-qscale-2-no-backend-quality-equivalence-claim")),
                Condition(ConditionOperator.Equals, "selectionProof", Value.Text("unique-exact-integer-media-pts-decoder-showinfo-and-independent-jpeg-decode"))))) +
            listOf(CapabilityEntry(Operation.Trim, Implementation.Experimental, conditions = listOf(
                Condition(ConditionOperator.Equals, "trimProfile", Value.Text("lossless-first-bounded-mp4-one-avc1-track-no-audio-no-reorder-no-hidden-content-exact-authorized-sdr-encoding-at-most-64-frames")),
                Condition(ConditionOperator.Equals, "boundaryRepresentation", Value.Text("exact-integer-microseconds-and-source-movie-timescale"))))) +
            listOf(CapabilityEntry(Operation.Transcode, Implementation.Experimental, conditions = listOf(
                Condition(ConditionOperator.Equals, "transcodeProfile", Value.Text("explicit-software-libx264-medium-crf18-bf0-avc-mp4-no-audio-edit-reorder-at-most-64-frames-no-hdr-resize-cfr")),
                Condition(ConditionOperator.Equals, "verification", Value.Text("whole-source-output-sdr-decode-and-every-vfr-sample-pts-duration-classified-metadata"))))))

    override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = attempt {
        val source = (request.media.input as? SourceSet.Single)?.source
            ?: fail("INVALID_ARGUMENT", "FFmpeg requires one resolved media source", Stage.Validate)
        if (request.media.resourceId != null || request.media.snapshot != null)
            fail("INVALID_ARGUMENT", "Resolve resource references through Core before backend probing", Stage.Validate)
        val reader = BinaryReader(source, request.context)
        val identity = reader.identity().orThrow()
        val facts = DefaultLivePhotoCore().probe(request.copy(decodeCheck = false)).orThrow()
        if (!request.decodeCheck) return@attempt facts
        if (facts.imageFormat != ImageFormat.Jpeg && facts.videoContainer !in setOf(VideoContainer.Mp4, VideoContainer.Mov))
            fail("CAPABILITY_UNSUPPORTED", "FFmpeg input demuxer is not authorized for this resource", Stage.Validate)
        if (facts.issues.any { it.severity == Severity.Error })
            fail("CORRUPTED_CONTAINER", "Invalid media must not reach the decoder", Stage.Validate)
        if (facts.imageFormat == ImageFormat.Jpeg && JpegParser.parse(reader, ParseBudget(request.context)).orThrow().primary.length != identity.size)
            fail("INVALID_ARGUMENT", "FFmpeg accepts resolved JPEG resources, not composite carriers", Stage.Validate)
        if (identity.size > request.context.limits.maxSpoolBytes)
            fail("RESOURCE_LIMIT_EXCEEDED", "Resolved media exceeds the temporary storage budget", Stage.Read)
        var directory: Path? = null
        var file: Path? = null
        try {
            directory = Files.createTempDirectory("livephoto-ffmpeg-")
            file = directory.resolve("media.bin")
            Files.newOutputStream(file).use { stream ->
                var offset = 0uL
                while (offset < identity.size) {
                    val bytes = reader.readBuffer(offset, minOf(65_536uL, identity.size - offset).toUInt()).orThrow()
                    stream.write(bytes.toByteArray()); offset += bytes.size.toULong()
                }
            }
            reader.validateIdentity().orThrow()
            val result = ExternalProcess.run(listOf(executable.toString(), "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror",
                "-abort_on", "empty_output+empty_output_stream", "-protocol_whitelist", "file", "-err_detect", "explode",
                "-f", if (facts.imageFormat == ImageFormat.Jpeg) "mjpeg" else "mov", "-threads", "1", "-noautorotate",
                "-i", file.toString(), "-map", "0:v", "-map", "0:a?", "-sn", "-dn", "-f", "null", "-"),
                timeoutMillis = 600_000L, context = request.context)
            reader.validateIdentity().orThrow()
            when {
                result.cancelled -> fail("CANCELLED", "Media decode was cancelled", Stage.Validate)
                result.timedOut -> fail("BACKEND_TIMEOUT", "Media decode exceeded its runtime limit", Stage.Validate)
                result.outputLimited -> fail("RESOURCE_LIMIT_EXCEEDED", "Media backend exceeded its diagnostic output budget", Stage.Validate)
                result.ioFailed -> fail("IO_READ_FAILED", "Media backend did not exit with completely drained diagnostics", Stage.Validate)
                result.code != 0 -> fail("DECODE_FAILED", "FFmpeg could not decode the entire selected media resource", Stage.Validate)
            }
            // Full A/V decode is not full metadata/color conformance; do not promote all facts to Complete.
            facts.copy(coverage = Coverage.Partial, issues = facts.issues + Issue(IssueCode("MEDIA_DECODE_COMPLETED"), Severity.Info, Layer.Media))
        } catch (fault: CoreFault) { throw fault }
        catch (_: Exception) { fail("IO_READ_FAILED", "FFmpeg temporary media IO failed", Stage.Read) }
        finally {
            // Exact paths created by this invocation only; never recursive cleanup or user paths.
            var cleanupFailed = false
            for (owned in listOfNotNull(file, directory)) try { Files.deleteIfExists(owned) } catch (_: Exception) { cleanupFailed = true }
            if (cleanupFailed) fail("IO_WRITE_FAILED", "FFmpeg temporary media cleanup failed", Stage.Read)
        }
    }
    private fun unsupported(job: BackendJob): CoreResult<BackendResult> = when (val validation = job.validate()) {
        is CoreResult.Failure -> validation
        is CoreResult.Success -> CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Plan, "This FFmpeg operation is not yet implemented"))
    }
    override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = FfmpegTrim.run(executable, job)
    override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = FfmpegRemux.run(executable, job)
    override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = FfmpegTranscode.run(executable, job)
    override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = FfmpegFrame.run(executable, job)
}
