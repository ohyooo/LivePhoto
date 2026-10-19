package livephoto.core.ffmpeg

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.jpeg.JpegParser

/** Finite FFmpeg orchestration; injected runtime supplies an external executable or private Worker IO.
 * This class does not install, download or bundle an engine. */
public class FfmpegMediaBackend(public val executable: String, private val runtime: FfmpegRuntime) : MediaBackend {
    public override fun capabilities(): MediaCapabilities = MediaCapabilities(listOf("ffmpeg-external"),
        listOf(CapabilityEntry(Operation.Probe, Implementation.Experimental, conditions = listOf(
            Condition(ConditionOperator.Equals, "decodeScope", Value.Text("bounded-jpeg-or-validated-bmff-video-and-audio-or-closed-single-hvc1-heic-no-private-properties-sei-metadata-one-decoded-frame-at-most-4096x4096-and-64MiB-single-allocation"))))) +
            listOf(CapabilityEntry(Operation.Remux, Implementation.Experimental, conditions = listOf(
                Condition(ConditionOperator.Equals, "input", Value.Text("one-video-classified-mp4-mov-no-audio-or-mp4-to-mp4-at-most-two-aac-lc-base-asc-canonical-roll-map-all-track-independent-sample-and-metadata-proof"))))) +
            listOf(CapabilityEntry(Operation.ExtractFrame, Implementation.Experimental, conditions = listOf(
                Condition(ConditionOperator.Equals, "frameProfile", Value.Text("progressive-square-pixel-identity-transform-eight-bit-bt709-limited-sdr-to-standard-srgb-jpeg")),
                Condition(ConditionOperator.Equals, "defaultImageQuality", Value.Text("ffmpeg-mjpeg-qscale-2-no-backend-quality-equivalence-claim")),
                Condition(ConditionOperator.Equals, "selectionProof", Value.Text("unique-exact-integer-media-pts-decoder-showinfo-and-separate-ffmpeg-jpeg-decode-core-structure-dimensions-no-cross-decoder-claim"))))) +
            listOf(CapabilityEntry(Operation.Trim, Implementation.Experimental, conditions = listOf(
                Condition(ConditionOperator.Equals, "trimProfile", Value.Text("lossless-first-bounded-mp4-one-avc1-at-most-two-copied-aac-lc-canonical-roll-whole-packet-gaps-disclosed-no-reorder-no-hidden-content-exact-authorized-video-only-sdr-encoding-at-most-64-frames")),
                Condition(ConditionOperator.Equals, "boundaryRepresentation", Value.Text("exact-integer-microseconds-and-source-movie-timescale"))))) +
            listOf(CapabilityEntry(Operation.Transcode, Implementation.Experimental, conditions = listOf(
                Condition(ConditionOperator.Equals, "transcodeProfile", Value.Text("explicit-software-libx264-medium-crf18-bf0-avc-mp4-at-most-two-copied-aac-lc-no-edit-reorder-at-most-64-frames-no-hdr-resize-cfr")),
                Condition(ConditionOperator.Equals, "verification", Value.Text("whole-source-output-sdr-decode-and-every-vfr-sample-pts-duration-classified-metadata"))))))

    public override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = attempt {
        val source = (request.media.input as? SourceSet.Single)?.source
            ?: fail("INVALID_ARGUMENT", "FFmpeg requires one resolved media source", Stage.Validate)
        if (request.media.resourceId != null || request.media.snapshot != null)
            fail("INVALID_ARGUMENT", "Resolve resource references through Core before backend probing", Stage.Validate)
        val reader = BinaryReader(source, request.context)
        val identity = reader.identity().orThrow()
        val facts = DefaultLivePhotoCore().probe(request.copy(decodeCheck = false)).orThrow()
        if (!request.decodeCheck) return@attempt facts
        val heic = facts.imageFormat == ImageFormat.Heic
        if (facts.imageFormat != ImageFormat.Jpeg && !heic && facts.videoContainer !in setOf(VideoContainer.Mp4, VideoContainer.Mov))
            fail("CAPABILITY_UNSUPPORTED", "FFmpeg input demuxer is not authorized for this resource", Stage.Validate)
        if (facts.issues.any { it.severity == Severity.Error })
            fail("CORRUPTED_CONTAINER", "Invalid media must not reach the decoder", Stage.Validate)
        if (heic && (facts.width == null || facts.height == null || facts.width > 4096u || facts.height > 4096u))
            fail("CAPABILITY_UNSUPPORTED", "Finite HEIF decoding currently requires declared dimensions at most 4096x4096", Stage.Validate)
        if (facts.imageFormat == ImageFormat.Jpeg && JpegParser.parse(reader, ParseBudget(request.context)).orThrow().primary.length != identity.size)
            fail("INVALID_ARGUMENT", "FFmpeg accepts resolved JPEG resources, not composite carriers", Stage.Validate)
        FfmpegWorkspace.use(runtime, source, reader, Stage.Validate, "Media decode", producesOutput = false, ioStage = Stage.Read, cleanupStage = Stage.Read) {
            val result = run(listOf(executable.toString()) + (if (heic) listOf("-max_alloc", "67108864") else emptyList()) +
                listOf("-nostdin", "-hide_banner", "-loglevel", if (heic) "info" else "error", "-xerror",
                "-abort_on", "empty_output+empty_output_stream", "-protocol_whitelist", "file", "-err_detect", "explode",
                "-f", if (facts.imageFormat == ImageFormat.Jpeg) "mjpeg" else "mov", "-threads", "1", "-noautorotate") +
                (if (heic) listOf("-max_pixels", "16777216", "-codec_whitelist", "hevc") else emptyList()) +
                listOf("-i", input.toString(), "-map", "0:v", "-map", "0:a?", "-sn", "-dn") +
                (if (heic) listOf("-vf", "showinfo=checksum=0", "-fps_mode", "passthrough") else emptyList()) + listOf("-f", "null", "-"),
                errorCode = "DECODE_FAILED")
            if (heic) {
                val frames = Regex("^.*Parsed_showinfo[^\\r\\n]*\\bn:\\s*\\d+[^\\r\\n]*$", RegexOption.MULTILINE).findAll(result.output).map { it.value }.toList()
                val dimensions = frames.singleOrNull()?.let { Regex("\\bs:(\\d+)x(\\d+)\\b").find(it) }
                if (dimensions == null || dimensions.groupValues[1].toUIntOrNull() != facts.width || dimensions.groupValues[2].toUIntOrNull() != facts.height)
                    fail("POSTCONDITION_FAILED", "HEIF decode must prove exactly one frame with the declared primary dimensions", Stage.Validate)
            }
            // Full A/V decode is not full metadata/color conformance; do not promote all facts to Complete.
            facts.copy(coverage = Coverage.Partial, issues = facts.issues + Issue(IssueCode("MEDIA_DECODE_COMPLETED"), Severity.Info, Layer.Media))
        }
    }
    public override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = FfmpegTrim.run(executable, runtime, job)
    public override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = FfmpegRemux.run(executable, runtime, job)
    public override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = FfmpegTranscode.run(executable, runtime, job)
    public override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = FfmpegFrame.run(executable, runtime, job)
}
