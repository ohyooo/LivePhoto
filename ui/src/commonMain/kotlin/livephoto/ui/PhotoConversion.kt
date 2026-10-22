package livephoto.ui

import livephoto.core.*
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.memory.MemoryOutputTransaction

public enum class ConversionTarget(public val displayName: String, public val selector: ProtocolSelector) {
    Oplus("OPPO / OnePlus", ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail"))),
    GoogleV2("Google V2", ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg"))),
    GoogleV1("Google V1", ProtocolSelector(ProtocolIds.GoogleV1, ProfileId("jpeg"))),
}

public data class ConvertedPhoto(
    public val target: ProtocolSelector,
    public val byteLength: ULong,
    public val digest: Digest,
    public val carrierExact: Boolean,
    public val preservation: PreservationReport,
    public val issues: List<Issue>,
)

public data class PreparedConversion(public val bytes: Bytes, public val report: ConvertedPhoto)

/** Application orchestration only; Core owns conversion, validation and preservation evidence. */
public suspend fun prepareConvertedPhoto(
    core: LivePhotoCore, bytes: ByteArray, target: ProtocolSelector, cancellation: Cancellation? = null,
): CoreResult<PreparedConversion> {
    if (ConversionTarget.entries.none { it.selector == target }) return CoreResult.Failure(CoreError(
        IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Plan, "This screen supports only the three displayed JPEG conversion targets"))
    if (bytes.size > ANALYSIS_INPUT_LIMIT) return CoreResult.Failure(CoreError(
        IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Conversion input exceeds 32 MiB"))
    val source = MemoryBinarySource(Bytes(bytes), SourceId("ui-conversion"))
    try { return prepareConvertedPhoto(core, source, target, cancellation) }
    finally { source.close() }
}

/** Borrowed input; the same Core conversion and preservation checks serve all adapters. */
public suspend fun prepareConvertedPhoto(
    core: LivePhotoCore, source: BinarySource, target: ProtocolSelector, cancellation: Cancellation? = null,
): CoreResult<PreparedConversion> {
    if (ConversionTarget.entries.none { it.selector == target }) return CoreResult.Failure(CoreError(
        IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Plan, "This screen supports only the three displayed JPEG conversion targets"))
    when (val size = source.size()) {
        is CoreResult.Failure -> return size
        is CoreResult.Success -> if (size.value > ANALYSIS_INPUT_LIMIT.toULong()) return CoreResult.Failure(CoreError(
            IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Conversion input exceeds 32 MiB"))
    }
    val context = Context(Limits(ANALYSIS_INPUT_LIMIT.toULong(), 48uL * 1024u * 1024u), cancellation = cancellation)
    val output = MemoryOutputTransaction(context, "ui-conversion")
    var operation: OperationResult? = null
    try {
        return when (val result = core.convert(ConvertRequest(SourceSet.Single(source), target, output = output, context = context))) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> {
                operation = result.value
                val asset = result.value.output.assets.singleOrNull()?.takeIf { it.role == AssetRole.Composite && it.mime == "image/jpeg" }
                    ?: return CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Verify,
                        "This screen requires exactly one composite JPEG; extra assets will not be discarded"))
                val records = result.value.preservation.records.filter { it.assetId == asset.id }
                val exact = records.any { it.guarantee == Guarantee.ExactExtraction && it.outcome == GuaranteeOutcome.Verified &&
                    it.sourceDigest == asset.digest && it.outputDigest == asset.digest }
                fun verified(guarantee: Guarantee) = records.any { it.guarantee == guarantee && it.outcome == GuaranteeOutcome.Verified &&
                    it.sourceDigest != null && it.sourceDigest == it.outputDigest }
                if (!exact && !(verified(Guarantee.ImageDataPreserving) && verified(Guarantee.BitstreamPreserving)))
                    return CoreResult.Failure(CoreError(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), Stage.Verify,
                        "Core did not prove whole-carrier copying or image/video preservation"))
                CoreResult.Success(PreparedConversion(output.committedAssets().getValue(asset.id), ConvertedPhoto(
                    target, asset.byteLength, asset.digest, exact, result.value.preservation,
                    (result.value.issues + result.value.validation.issues + result.value.preservation.issues).distinct())))
            }
        }
    } finally {
        operation?.output?.assets?.forEach { it.readableSource?.close() }
        if (operation == null) output.abort()
    }
}

public fun formatConvertedPhoto(photo: ConvertedPhoto, publication: String): String = buildString {
    append(publication)
    append("\n目标：").append(photo.target.protocol.value).append(" / ").append(photo.target.profile?.value ?: "默认")
    append("\n大小：").append(formatMediaSize(photo.byteLength.toLong())).append("；SHA-256：").append(photo.digest.value)
    append(if (photo.carrierExact) "\n同目标原样保留：ExactExtraction Verified（整个载体字节一致）。"
        else "\n跨协议重新装配：图片 ImageDataPreserving / 视频 BitstreamPreserving Verified，不宣称整个文件字节一致。")
    append("\nCore 分项保留结果：")
    photo.preservation.records.forEach { append("\n").append(it.guarantee).append(": ").append(it.outcome) }
    append("\n协议字段变更：").append(photo.preservation.changes.size).append(" 项；问题报告：").append(photo.issues.size).append(" 项。")
    if (photo.issues.isNotEmpty()) append("\n报告代码：").append(photo.issues.take(12).joinToString { it.code.value })
    append("\n不修改原片、不自动修复、不转码或编码音频；Unknown 不代表已保证保留，不代表设备相册兼容认证。")
}

public fun formatConversionFailure(error: CoreError): String =
    "转换失败\n错误码：${error.code.value.take(128)}\n阶段：${error.stage}\n具体原因：${error.message.take(2000)}\n未修改原片、未提交新的转换文件。"
