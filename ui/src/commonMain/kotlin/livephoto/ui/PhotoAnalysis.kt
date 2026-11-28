package livephoto.ui

import livephoto.core.*
import livephoto.core.memory.MemoryBinarySource

public const val ANALYSIS_INPUT_LIMIT: Int = 32 * 1024 * 1024

/** Shared application orchestration, not a second parser or a mutation path. */
public suspend fun analyzeSelectedPhoto(
    core: LivePhotoCore,
    bytes: ByteArray,
    cancellation: Cancellation? = null,
): CoreResult<AnalysisResult> {
    if (bytes.size > ANALYSIS_INPUT_LIMIT) return CoreResult.Failure(CoreError(
        IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Photo analysis input exceeds 32 MiB"))
    val source = MemoryBinarySource(Bytes(bytes), SourceId("ui-analysis"))
    try {
        return analyzeSelectedPhoto(core, source, cancellation)
    } finally { source.close() }
}

/** Borrowed random-access input; the adapter retains ownership and closes it. */
public suspend fun analyzeSelectedPhoto(
    core: LivePhotoCore,
    source: BinarySource,
    cancellation: Cancellation? = null,
): CoreResult<AnalysisResult> {
    when (val size = source.size()) {
        is CoreResult.Failure -> return size
        is CoreResult.Success -> if (size.value > ANALYSIS_INPUT_LIMIT.toULong()) return CoreResult.Failure(CoreError(
            IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Photo analysis input exceeds 32 MiB"))
    }
    return core.analyze(AnalyzeRequest(SourceSet.Single(source),
        layers = listOf(Layer.Structure, Layer.Protocol, Layer.Media),
        context = Context(Limits(ANALYSIS_INPUT_LIMIT.toULong(), 0u), cancellation = cancellation)))
}

/** UI presentation only. Keep unknown/zero, coverage and conditional capability distinct. */
public fun formatPhotoAnalysis(result: AnalysisResult): String = buildString {
    val inspection = result.inspection
    val detection = inspection.detection
    append("只读检查完成；未修改输入、未生成文件。\n")
    append("识别结果：").append(detection.disposition)
    append("\n首选协议：").append(detection.primaryProtocol?.let {
        it.protocol.value + (it.profile?.let { profile -> " / ${profile.value}" } ?: "")
    } ?: "无（不猜测未知协议）")
    append("\n全部匹配：")
    if (detection.matches.isEmpty()) append("无")
    detection.matches.take(12).forEach { append("\n  ").append(it.target.protocol.value).append(" / ").append(it.strength) }
    if (detection.matches.size > 12) append("\n  …仅显示前 12 个匹配")
    append("\n验证总览：").append(result.validation.verdict).append("；覆盖：").append(result.validation.coverage)
    append("\n分层检查（结论 / 覆盖）：")
    result.validation.checks.take(48).forEach {
        append("\n  ").append(it.layer).append(" / ").append(it.id.take(120))
            .append("：").append(it.verdict).append(" / ").append(it.coverage)
    }
    if (result.validation.checks.size > 48) append("\n  …仅显示前 48 个检查")
    val key = inspection.keyPhoto
    append("\n关键照片位置：").append(key.position?.let { "${it.value}/${it.timescale} 秒" } ?: "未知（不等于 0）")
    append("\n关键照片来源：").append(key.source)
    key.frameIndex?.let { append("\n关键帧索引（从 0 开始）：").append(it) }
    append("\n媒体信息：")
    if (inspection.media.isEmpty()) append("未知")
    inspection.media.take(8).forEach { media ->
        append("\n  ").append(media.mime ?: media.imageFormat?.name ?: media.videoContainer?.name ?: "未知格式")
        if (media.width != null && media.height != null) append(" / ${media.width}×${media.height}")
        append(" / 覆盖：").append(media.coverage)
        media.tracks.take(8).forEach { track ->
            append("\n    ").append(track.kind.take(40)).append(" / ")
                .append(track.videoCodec?.name ?: track.audioCodec?.name ?: "未知编码")
        }
    }
    append("\n内嵌资源：").append(inspection.layout.resources.take(24).joinToString { it.kind.name }.ifEmpty { "无" })
    if (inspection.layout.resources.size > 24) append("（仅显示前 24 项）")
    append("\n元数据：").append(inspection.metadata.size).append(" 项（不展开私人元数据值）")
    append("\n能力可用性：").append(result.capabilities.availability)
    append("（带条件的建议，不代表操作必定成功）")
    val issues = (detection.issues + inspection.issues + key.issues + result.validation.issues +
        result.validation.checks.flatMap { it.issues } + inspection.media.flatMap { it.issues } + result.capabilities.issues).distinct()
    append("\n问题报告：").append(issues.size).append(" 项")
    issues.take(16).forEach { issue ->
        append("\n  ").append(issue.severity).append(" / ").append(issue.code.value.take(128))
            .append(" / ").append(issue.layer)
        issue.location?.selector?.let { append(" / ").append(it.take(240)) }
    }
    if (issues.size > 16) append("\n  …仅显示前 16 项")
    append("\n未执行的检查会保留 NotRun/Partial；没有调用媒体解码后端。")
    append("\n本报告不是完整播放或设备相册兼容证明；未知结果不等于普通照片，单文件检查不能代替 Apple 配对检查。")
}

public fun formatPhotoAnalysisFailure(error: CoreError): String = buildString {
    append("检查失败\n错误码：").append(error.code.value.take(128))
    append("\n阶段：").append(error.stage)
    append("\n具体原因：").append(error.message.take(2000))
    error.location?.let { location ->
        location.range?.let { append("\n字节范围：${it.offset} + ${it.length}") }
        location.selector?.let { append("\n位置：").append(it.take(256)) }
    }
    append("\n未修改输入、未生成文件；检查失败不等于普通照片。")
}

/** A small view of structured Core results; no file details are passed through this summary. */
public enum class PhotoInspectionStatus { Motion, InvalidMotion, NonLive, Candidate, Ambiguous, Unknown }

public data class PhotoInspectionSummary(val status: PhotoInspectionStatus, val openExtraction: Boolean)

public fun summarizePhotoInspection(result: AnalysisResult): PhotoInspectionSummary {
    val status = when (result.inspection.detection.disposition) {
        Disposition.Live -> if (result.validation.verdict == Verdict.Invalid) PhotoInspectionStatus.InvalidMotion else PhotoInspectionStatus.Motion
        Disposition.NonLive -> PhotoInspectionStatus.NonLive
        Disposition.Candidate -> PhotoInspectionStatus.Candidate
        Disposition.Ambiguous -> PhotoInspectionStatus.Ambiguous
        Disposition.Unknown -> PhotoInspectionStatus.Unknown
    }
    val layout = result.inspection.layout
    val videos = layout.resources.filter { it.kind == ResourceKind.Video }
    // A vendor entry and a generic compatibility entry can describe the same bytes.
    val oneVideo = videos.all { it.standalone && it.extents.isNotEmpty() } &&
        videos.distinctBy { resource -> resource.extents.map { it.source to it.range } }.size == 1
    // Android permits a bigger read-only photo, but export still has the common input budget.
    val canExtract = status == PhotoInspectionStatus.Motion &&
        layout.sources.singleOrNull()?.size?.let { it <= ANALYSIS_INPUT_LIMIT.toULong() } == true &&
        oneVideo &&
        result.capabilities.availability != Availability.Unsupported &&
        result.capabilities.operations.any { entry ->
            entry.operation == Operation.ExtractRaw && entry.exposure == Exposure.Public &&
                entry.implementation in setOf(Implementation.Supported, Implementation.Experimental)
        }
    return PhotoInspectionSummary(status, canExtract)
}
