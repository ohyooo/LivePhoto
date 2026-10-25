package livephoto.ui

import livephoto.core.*
import livephoto.core.memory.MemoryBinarySource

/** Decimal seconds, not a floating-point timestamp or a frame-rate estimate. */
public fun parseKeySeconds(text: String): Time? {
    if (!Regex("[0-9]{1,6}(\\.[0-9]{1,6})?").matches(text)) return null
    val parts = text.split('.')
    return Time(parts[0].toLong() * 1_000_000 + parts.getOrNull(1).orEmpty().padEnd(6, '0').toLong(), 1_000_000u)
}

public data class PhotoKeyReport(
    public val requested: Time, public val actual: Time, public val digest: Digest,
    public val byteLength: ULong, public val preservation: PreservationReport, public val issues: List<Issue>,
)
public data class PreparedPhotoKey<out T>(public val bytes: T, public val report: PhotoKeyReport)

/** Only application orchestration: Core selects/encodes the key and proves preservation. */
public suspend fun preparePhotoKey(core: LivePhotoCore, bytes: ByteArray, requested: Time,
    cancellation: Cancellation? = null): CoreResult<PreparedPhotoKey<Bytes>> {
    if (requested < Time.Zero) return CoreResult.Failure(CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Plan, "Key time must be nonnegative"))
    if (bytes.size > ANALYSIS_INPUT_LIMIT) return CoreResult.Failure(CoreError(IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Key input exceeds 32 MiB"))
    val source = MemoryBinarySource(Bytes(bytes), SourceId("ui-key"))
    try { return preparePhotoKey(core, source, requested, cancellation) }
    finally { source.close() }
}

/** Borrowed input; changing key metadata still requires the original preservation proofs. */
public suspend fun preparePhotoKey(core: LivePhotoCore, source: BinarySource, requested: Time,
    cancellation: Cancellation? = null): CoreResult<PreparedPhotoKey<Bytes>> =
    preparePhotoKey(core, source, requested, cancellation, ::MemoryUiOutput)

public suspend fun <T> preparePhotoKey(core: LivePhotoCore, source: BinarySource, requested: Time,
    cancellation: Cancellation? = null, outputFactory: UiOutputFactory<T>): CoreResult<PreparedPhotoKey<T>> {
    if (requested < Time.Zero) return CoreResult.Failure(CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Plan, "Key time must be nonnegative"))
    when (val size = source.size()) {
        is CoreResult.Failure -> return size
        is CoreResult.Success -> if (size.value > ANALYSIS_INPUT_LIMIT.toULong()) return CoreResult.Failure(CoreError(
            IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Key input exceeds 32 MiB"))
    }
    val context = Context(Limits(ANALYSIS_INPUT_LIMIT.toULong(), 48uL * 1024u * 1024u), cancellation = cancellation)
    val output = outputFactory(context, "ui-key")
    var operation: OperationResult? = null
    try {
        return when (val result = core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Single(source),
            CoverPosition.Timestamp(requested, Selection.Nearest, Time(100, 1000u)), output = output, context = context))) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> {
                operation = result.value
                val asset = result.value.output.assets.singleOrNull()?.takeIf { it.role == AssetRole.Composite && it.mime == "image/jpeg" }
                    ?: return CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Verify, "Only one JPEG carrier can be saved; extra assets will not be discarded"))
                val records = result.value.preservation.records.filter { it.assetId == asset.id }
                fun verified(guarantee: Guarantee) = records.any { it.guarantee == guarantee && it.outcome == GuaranteeOutcome.Verified && it.sourceDigest != null && it.sourceDigest == it.outputDigest }
                val actual = result.value.keyPhoto?.position
                if (actual == null || !verified(Guarantee.ImageDataPreserving) || !verified(Guarantee.BitstreamPreserving))
                    return CoreResult.Failure(CoreError(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), Stage.Verify, "Core did not prove key position and image/video preservation"))
                CoreResult.Success(PreparedPhotoKey(output.asset(asset.id), PhotoKeyReport(requested, actual,
                    asset.digest, asset.byteLength, result.value.preservation,
                    (result.value.issues + result.value.keyPhoto.orEmptyIssues() + result.value.validation.issues + result.value.preservation.issues).distinct())))
            }
        }
    } finally {
        operation?.output?.assets?.forEach { it.readableSource?.close() }
        if (operation == null) output.abort()
    }
}

private fun KeyPhotoResult?.orEmptyIssues(): List<Issue> = this?.issues.orEmpty()

public fun formatPhotoKey(report: PhotoKeyReport, publication: String): String = buildString {
    append(publication)
    append("\n请求时间：${report.requested.value}/${report.requested.timescale} 秒；实际时间：${report.actual.value}/${report.actual.timescale} 秒。")
    append("\n大小：${formatMediaSize(report.byteLength.toLong())}；SHA-256：${report.digest.value}")
    report.preservation.records.forEach { append("\n${it.guarantee}: ${it.outcome}") }
    if (report.issues.isNotEmpty()) append("\n报告代码：${report.issues.take(12).joinToString { it.code.value }}")
    append("\n仅修改关键照片 metadata；最近画面容差 100 毫秒。不抽帧、不替换封面、不转码或编码音频、不修改原片；Unknown 不等于已保证保留。")
}

public fun formatPhotoKeyFailure(error: CoreError): String =
    "关键照片修改失败\n错误码：${error.code.value.take(128)}\n阶段：${error.stage}\n具体原因：${error.message.take(2000)}\n原片未修改、未提交新的关键照片文件。"
