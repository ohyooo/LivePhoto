package livephoto.ui

import livephoto.core.*
import livephoto.core.memory.MemoryBinarySource

/** Prepared, bounded outputs only. Saving each file is an explicit, independent UI action. */
public data class SplitFile<out T>(
    public val bytes: T,
    public val mime: String,
    public val extension: String,
    public val digest: Digest,
    public val byteLength: ULong,
)

public data class PreparedCleanSplit<out T>(
    public val image: SplitFile<T>,
    public val video: SplitFile<T>,
    public val preservation: PreservationReport,
    public val issues: List<Issue>,
)

public suspend fun prepareCleanSplit(
    core: LivePhotoCore, bytes: ByteArray, cancellation: Cancellation? = null,
): CoreResult<PreparedCleanSplit<Bytes>> {
    if (bytes.size > ANALYSIS_INPUT_LIMIT) return CoreResult.Failure(CoreError(
        IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Clean split input exceeds 32 MiB"))
    val source = MemoryBinarySource(Bytes(bytes), SourceId("ui-clean-split"))
    try { return prepareCleanSplit(core, source, cancellation) }
    finally { source.close() }
}

/** Borrowed input; preparing outputs still does not publish either file. */
public suspend fun prepareCleanSplit(
    core: LivePhotoCore, source: BinarySource, cancellation: Cancellation? = null,
): CoreResult<PreparedCleanSplit<Bytes>> =
    prepareCleanSplit(core, source, cancellation, ::MemoryUiOutput)

public suspend fun <T> prepareCleanSplit(
    core: LivePhotoCore, source: BinarySource, cancellation: Cancellation? = null, outputFactory: UiOutputFactory<T>): CoreResult<PreparedCleanSplit<T>> {
    when (val size = source.size()) {
        is CoreResult.Failure -> return size
        is CoreResult.Success -> if (size.value > ANALYSIS_INPUT_LIMIT.toULong()) return CoreResult.Failure(CoreError(
            IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Clean split input exceeds 32 MiB"))
    }
    val context = Context(Limits(ANALYSIS_INPUT_LIMIT.toULong(), 48uL * 1024u * 1024u), cancellation = cancellation)
    val output = outputFactory(context, "ui-clean-split")
    var operation: OperationResult? = null
    fun failure(code: String, message: String) = CoreResult.Failure(CoreError(IssueCode(code), Stage.Verify, message))
    try {
        return when (val result = core.split(SplitRequest(SourceSet.Single(source), output = output, context = context))) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> {
                operation = result.value
                val assets = result.value.output.assets
                val image = assets.singleOrNull { it.role == AssetRole.PrimaryImage }
                val video = assets.singleOrNull { it.role == AssetRole.MotionVideo }
                if (assets.size != 2 || image == null || video == null)
                    return failure("CAPABILITY_UNSUPPORTED", "This screen needs exactly one clean JPEG and one motion video; no extra assets will be discarded")
                if (image.mime != "image/jpeg" || video.mime !in setOf("video/mp4", "video/quicktime"))
                    return failure("CAPABILITY_UNSUPPORTED", "This screen exports only identified JPEG plus MP4/MOV; no conversion will be attempted")
                val records = result.value.preservation.records
                val imageVerified = records.any { it.assetId == image.id && it.guarantee == Guarantee.ImageDataPreserving &&
                    it.outcome == GuaranteeOutcome.Verified && it.sourceDigest != null && it.sourceDigest == it.outputDigest }
                val videoVerified = records.any { it.assetId == video.id && it.guarantee == Guarantee.ExactExtraction &&
                    it.outcome == GuaranteeOutcome.Verified && it.sourceDigest == video.digest && it.outputDigest == video.digest }
                if (!imageVerified || !videoVerified)
                    return failure("PRESERVATION_REQUIREMENT_FAILED", "Core did not prove image coding preservation and exact video extraction")
                CoreResult.Success(PreparedCleanSplit(
                    SplitFile(output.asset(image.id), image.mime, "jpg", image.digest, image.byteLength),
                    SplitFile(output.asset(video.id), video.mime, if (video.mime == "video/mp4") "mp4" else "mov", video.digest, video.byteLength),
                    result.value.preservation,
                    (result.value.issues + result.value.validation.issues + result.value.preservation.issues).distinct(),
                ))
            }
        }
    } finally {
        operation?.output?.assets?.forEach { it.readableSource?.close() }
        if (operation == null) output.abort()
    }
}

public fun formatCleanSplit(split: PreparedCleanSplit<*>): String = buildString {
    append("清理拆分已准备；准备时尚未保存，后续状态见各保存按钮下方。\n已移除动态照片绑定；原片未修改，不转码、不重新编码音频。")
    append("\n图片：").append(split.image.mime).append("；").append(formatMediaSize(split.image.byteLength.toLong()))
    append("\n图片 SHA-256：").append(split.image.digest.value)
    append("\n视频：").append(split.video.mime).append("；").append(formatMediaSize(split.video.byteLength.toLong()))
    append("\n视频 SHA-256：").append(split.video.digest.value)
    append("\nImageDataPreserving：Verified；视频 ExactExtraction：Verified。")
    append("\n普通 metadata 保留结果（按资产）：")
    split.preservation.records.filter { it.guarantee == Guarantee.MetadataPreserving }.forEach {
        append("\n").append(it.assetId.value).append(": ").append(it.outcome)
    }
    append("\n协议字段变更：").append(split.preservation.changes.size).append(" 项；问题报告：").append(split.issues.size).append(" 项。")
    if (split.issues.isNotEmpty()) append("\n报告代码：").append(split.issues.take(12).joinToString { it.code.value })
    append("\n分别保存两个文件；不是外部原子成对保存。请检查每项下载或相册记录，不代表设备兼容认证。")
}

public fun formatCleanSplitFailure(error: CoreError): String =
    "清理拆分失败\n错误码：${error.code.value.take(128)}\n阶段：${error.stage}\n具体原因：${error.message.take(2000)}\n未修改原片、未发起文件保存。"
