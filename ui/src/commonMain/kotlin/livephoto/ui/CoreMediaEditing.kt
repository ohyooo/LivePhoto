package livephoto.ui

import livephoto.core.*
import livephoto.core.ffmpeg.BROWSER_MEDIA_INPUT_LIMIT
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.memory.MemoryOutputTransaction

public enum class CoreMediaAction { Probe, Remux, Trim, ExtractFrame, Transcode }
public data class EditedVideo(val reference: String, val summary: String)
public data class PreparedCoreMedia(val bytes: Bytes?, val mime: String?, val actualTime: Time?, val summary: String,
    val retainedVideoWindow: TimeRange? = null)
public data class CoreMediaEdit(val summary: String, val imageReference: String? = null, val video: EditedVideo? = null)

/** Only a verified, single, unit-rate video translation can reuse the player's cover clock.
 * Audio packet boundaries are deliberately not used as the video origin. */
public fun retainedCoreVideoWindow(start: Time, end: Time, mapping: List<TimelineSegment>,
    bitstreamPreserved: Boolean, hiddenContent: Boolean): TimeRange? {
    if (!bitstreamPreserved || hiddenContent || start < Time.Zero || end > Time(60, 1u) || end <= start) return null
    val segment = mapping.singleOrNull() ?: return null
    if (segment.input.start != start || segment.input.end != end || segment.output.start.compareTo(Time.Zero) != 0) return null
    // Fail closed on a mixed-scale representation instead of guessing or floating-point arithmetic.
    if (start.timescale != end.timescale) return null
    val duration = Time(end.value - start.value, start.timescale)
    return segment.input.takeIf { segment.output.end.compareTo(duration) == 0 }
}

/** Bounded application orchestration only. Commands, media proofs and publication stay in Core. */
public suspend fun prepareCoreMedia(core: LivePhotoCore, bytes: ByteArray, action: CoreMediaAction,
    start: Time = Time.Zero, end: Time = Time.Zero, cancellation: Cancellation? = null,
    inputLimit: Int = ANALYSIS_INPUT_LIMIT): CoreResult<PreparedCoreMedia> {
    if (inputLimit !in 1..BROWSER_MEDIA_INPUT_LIMIT || bytes.size > inputLimit)
        return CoreResult.Failure(CoreError(IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Core media input exceeds its memory budget"))
    val source = MemoryBinarySource(Bytes(bytes), SourceId("ui-core-media"))
    return try { prepareCoreMedia(core, source, action, start, end, cancellation, inputLimit) }
    finally { source.close() }
}

/** Borrows an immutable random-access source; its owner controls its lifetime. */
public suspend fun prepareCoreMedia(core: LivePhotoCore, source: BinarySource, action: CoreMediaAction,
    start: Time = Time.Zero, end: Time = Time.Zero, cancellation: Cancellation? = null,
    inputLimit: Int = ANALYSIS_INPUT_LIMIT): CoreResult<PreparedCoreMedia> {
    if (inputLimit !in 1..BROWSER_MEDIA_INPUT_LIMIT)
        return CoreResult.Failure(CoreError(IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Core media input exceeds its memory budget"))
    when (val size = source.size()) {
        is CoreResult.Failure -> return size
        is CoreResult.Success -> if (size.value > inputLimit.toULong())
            return CoreResult.Failure(CoreError(IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Core media input exceeds its memory budget"))
    }
    if (start < Time.Zero || end < Time.Zero || start > Time(60, 1u) || end > Time(60, 1u) || action == CoreMediaAction.Trim && end <= start)
        return CoreResult.Failure(CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Plan, "Invalid media range; maximum 60 seconds"))
    val ctx = Context(Limits(inputLimit.toULong(), 48uL * 1024u * 1024u), cancellation = cancellation)
    val input = ResourceRef(SourceSet.Single(source))
    val output = MemoryOutputTransaction(ctx, "ui-core-media")
    var applied: OperationResult? = null
    var actualTime: Time? = null
    var retainedWindow: TimeRange? = null
    var details = ""
    fun time(value: Time): String = "${value.value}/${value.timescale} 秒"
    try {
        if (action == CoreMediaAction.Probe) return when (val result = core.probe(ProbeRequest(input, decodeCheck = true, context = ctx))) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> CoreResult.Success(PreparedCoreMedia(null, null, null,
                "Core Probe：${result.value.coverage}；轨道 ${result.value.tracks.size}；" +
                    result.value.issues.joinToString { it.code.value } + "。只读检查，不替换输入。"))
        }
        val result = when (action) {
            CoreMediaAction.Remux -> core.remux(RemuxRequest(input, VideoContainer.Mp4, output = output, context = ctx))
            CoreMediaAction.Trim -> when (val trim = core.trim(TrimRequest(input, TrimSpec(TimeRange(start, end)), output = output, context = ctx))) {
                is CoreResult.Failure -> trim
                is CoreResult.Success -> {
                    val value = trim.value
                    retainedWindow = retainedCoreVideoWindow(value.actualStart, value.actualEnd, value.timelineMap,
                        value.wasBitstreamPreserved, value.retainedHiddenContent)
                    details = "请求 ${time(value.requestedStart)}～${time(value.requestedEnd)}；实际 ${time(value.actualStart)}～${time(value.actualEnd)}。" +
                        value.tracks.joinToString { "轨道 ${it.trackId.value}：${time(it.presented.start)}～${time(it.presented.end)}，samplesPreserved=${it.samplesPreserved}" }
                    CoreResult.Success(value.operation)
                }
            }
            CoreMediaAction.Transcode -> core.transcode(TranscodeRequest(input, VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4),
                MutationPolicy(transcode = TranscodePolicy.Explicit), output, ctx))
            CoreMediaAction.ExtractFrame -> when (val frame = core.extractFrame(ExtractFrameRequest(input,
                CoverPosition.Timestamp(start, Selection.Nearest, Time(100, 1000u)), ImageEncoding(ImageFormat.Jpeg), output, ctx))) {
                is CoreResult.Failure -> frame
                is CoreResult.Success -> {
                    actualTime = frame.value.actualTime
                    details = "实际 ${time(frame.value.actualTime)}；帧 ${frame.value.actualFrameIndex}；轨道 ${frame.value.trackId.value}。"
                    CoreResult.Success(frame.value.operation)
                }
            }
            CoreMediaAction.Probe -> error("Probe was handled without an output")
        }
        return when (result) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> {
                applied = result.value
                val asset = result.value.output.assets.singleOrNull()
                val mime = if (action == CoreMediaAction.ExtractFrame) "image/jpeg" else "video/mp4"
                if (asset == null || asset.mime != mime) return CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Verify, "Only one identified JPEG/MP4 output can be used; extra assets are not discarded"))
                val records = result.value.preservation.records.filter { it.assetId == asset.id }
                val summary = "Core $action：" + result.value.execution.mapNotNull { it.backendId }.distinct().joinToString() + "。$details\n" +
                    records.joinToString { "${it.guarantee}: ${it.outcome}" } + "\nSHA-256：${asset.digest.value}；${asset.byteLength} bytes。" +
                    "\n不修改原文件、不编码音频；Unknown 不等于保留已验证。" +
                    if (action == CoreMediaAction.ExtractFrame) "封面使用 Core 返回的实际帧时间。"
                    else if (retainedWindow != null) "已验证视频时间平移；保留范围内的旧封面可沿用，范围外拒绝替换输入。"
                    else "请在处理后的视频上重新选封面，旧封面时间不自动沿用。"
                CoreResult.Success(PreparedCoreMedia(output.committedAssets().getValue(asset.id), mime, actualTime, summary, retainedWindow))
            }
        }
    } finally {
        applied?.output?.assets?.forEach { it.readableSource?.close() }
        if (applied == null) output.abort()
    }
}
