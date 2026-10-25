package livephoto.ui

import livephoto.core.*
import livephoto.core.memory.MemoryBinarySource

public data class ExtractedVideo(
    public val byteLength: ULong,
    public val mime: String,
    public val extension: String,
    public val digest: Digest,
    public val preservation: PreservationReport,
    public val issues: List<Issue>,
)

/** No borrowed Core handles escape this bounded application session. */
public data class PreparedVideo<out T>(public val bytes: T, public val report: ExtractedVideo)

public suspend fun prepareExtractedVideo(
    core: LivePhotoCore, bytes: ByteArray, cancellation: Cancellation? = null,
): CoreResult<PreparedVideo<Bytes>> {
    if (bytes.size > ANALYSIS_INPUT_LIMIT) return CoreResult.Failure(CoreError(
        IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Video extraction input exceeds 32 MiB"))
    val source = MemoryBinarySource(Bytes(bytes), SourceId("ui-video-extraction"))
    try { return prepareExtractedVideo(core, source, cancellation) }
    finally { source.close() }
}

/** Borrowed input; output verification and staging are identical for all adapters. */
public suspend fun prepareExtractedVideo(
    core: LivePhotoCore, source: BinarySource, cancellation: Cancellation? = null,
): CoreResult<PreparedVideo<Bytes>> =
    prepareExtractedVideo(core, source, cancellation, ::MemoryUiOutput)

public suspend fun <T> prepareExtractedVideo(
    core: LivePhotoCore, source: BinarySource, cancellation: Cancellation? = null, outputFactory: UiOutputFactory<T>): CoreResult<PreparedVideo<T>> {
    when (val size = source.size()) {
        is CoreResult.Failure -> return size
        is CoreResult.Success -> if (size.value > ANALYSIS_INPUT_LIMIT.toULong()) return CoreResult.Failure(CoreError(
            IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Video extraction input exceeds 32 MiB"))
    }
    val context = Context(Limits(ANALYSIS_INPUT_LIMIT.toULong(), 48uL * 1024u * 1024u), cancellation = cancellation)
    val output = outputFactory(context, "ui-video-extraction")
    var operation: OperationResult? = null
    try {
        return when (val result = core.extract(ExtractRequest(SourceSet.Single(source), emptyList(), output = output, context = context))) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> {
                operation = result.value
                val asset = result.value.output.assets.singleOrNull()?.takeIf { it.role == AssetRole.MotionVideo }
                    ?: return CoreResult.Failure(CoreError(IssueCode("AMBIGUOUS_LAYOUT"), Stage.Extract,
                        "This screen requires exactly one embedded motion video"))
                val extension = when (asset.mime) {
                    "video/mp4" -> "mp4"
                    "video/quicktime" -> "mov"
                    else -> return CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Extract,
                        "Video output container is not identified as MP4 or MOV; no conversion will be attempted"))
                }
                val exact = result.value.preservation.records.any {
                    it.assetId == asset.id && it.guarantee == Guarantee.ExactExtraction &&
                        it.outcome == GuaranteeOutcome.Verified && it.sourceDigest == asset.digest && it.outputDigest == asset.digest
                }
                if (!exact) return CoreResult.Failure(CoreError(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), Stage.Verify,
                    "Core did not provide a verified exact-extraction digest"))
                val extracted = output.asset(asset.id)
                CoreResult.Success(PreparedVideo(extracted, ExtractedVideo(asset.byteLength, asset.mime, extension,
                    asset.digest, result.value.preservation,
                    (result.value.issues + result.value.validation.issues + result.value.preservation.issues).distinct())))
            }
        }
    } finally {
        operation?.output?.assets?.forEach { it.readableSource?.close() }
        if (operation == null) output.abort()
    }
}

public fun formatExtractedVideo(video: ExtractedVideo, publication: String): String = buildString {
    append(publication)
    append("\n格式：").append(video.mime).append("；大小：").append(formatMediaSize(video.byteLength.toLong()))
    append("\nExactExtraction：Verified（Core 已核对所选视频资源的原始字节）")
    append("\nSHA-256：").append(video.digest.value)
    append("\n未修改原片；没有裁剪、清理、remux 或重新编码，音频字节也保持原样。")
    append("\n问题报告：").append(video.issues.size).append(" 项")
    if (video.issues.isNotEmpty()) append("\n报告代码：").append(video.issues.take(12).joinToString { it.code.value })
    append("\n字节一致不代表视频可播放或设备相册兼容；这不是 Clean Split。")
}

public fun formatVideoExtractionFailure(error: CoreError): String =
    "提取失败\n错误码：${error.code.value.take(128)}\n阶段：${error.stage}\n具体原因：${error.message.take(2000)}\n未修改原片、未提交新的视频文件。"
