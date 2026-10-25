package livephoto.ui

import livephoto.core.*
import livephoto.core.memory.MemoryBinarySource

/** The explicit UI confirmation is bound to these exact input bytes and proposed changes. */
public data class RepairReview(public val sourceDigest: Digest, public val changes: List<Change>)

public data class PhotoRepairReport(
    public val sourceDigest: Digest,
    public val review: RepairReview?,
    public val issuesBefore: List<Issue>,
    public val proposedChanges: List<Change>,
    public val changesApplied: List<Change>,
    public val issuesAfter: List<Issue>,
    public val blocked: List<Issue>,
    public val outputDigest: Digest? = null,
    public val byteLength: ULong? = null,
    public val preservation: PreservationReport? = null,
    public val issues: List<Issue> = emptyList(),
)

public data class PreparedPhotoRepair<out T>(public val bytes: T?, public val report: PhotoRepairReport)

/** Preview has no transaction; applying first rechecks the reviewed source and Core proposal. */
public suspend fun preparePhotoRepair(
    core: LivePhotoCore, bytes: ByteArray, review: RepairReview? = null, cancellation: Cancellation? = null,
): CoreResult<PreparedPhotoRepair<Bytes>> {
    if (bytes.size > ANALYSIS_INPUT_LIMIT) return CoreResult.Failure(CoreError(
        IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Repair input exceeds 32 MiB"))
    val source = MemoryBinarySource(Bytes(bytes), SourceId("ui-repair"))
    try { return preparePhotoRepair(core, source, review, cancellation) }
    finally { source.close() }
}

/** Borrowed input; both preview and apply independently hash all bytes using bounded reads. */
public suspend fun preparePhotoRepair(
    core: LivePhotoCore, source: BinarySource, review: RepairReview? = null, cancellation: Cancellation? = null,
): CoreResult<PreparedPhotoRepair<Bytes>> =
    preparePhotoRepair(core, source, review, cancellation, ::MemoryUiOutput)

public suspend fun <T> preparePhotoRepair(
    core: LivePhotoCore, source: BinarySource, review: RepairReview? = null, cancellation: Cancellation? = null, outputFactory: UiOutputFactory<T>): CoreResult<PreparedPhotoRepair<T>> {
    val context = Context(Limits(ANALYSIS_INPUT_LIMIT.toULong(), 48uL * 1024u * 1024u), cancellation = cancellation)
    var output: UiOutput<T>? = null
    var operation: OperationResult? = null
    fun failure(code: String, stage: Stage, message: String) = CoreResult.Failure(CoreError(IssueCode(code), stage, message))
    try {
        val digest = when (val result = sourceDigest(source, context)) {
            is CoreResult.Failure -> return result
            is CoreResult.Success -> result.value
        }
        if (review != null && review.sourceDigest != digest)
            return failure("SOURCE_CHANGED", Stage.Read, "Selected input differs from the reviewed bytes; preview it again")
        val preview = when (val result = core.repair(RepairRequest(SourceSet.Single(source), mode = RepairMode.SafeMetadataOnly, dryRun = true, context = context))) {
            is CoreResult.Failure -> return result
            is CoreResult.Success -> result.value
        }
        // A dry-run must not produce handles or applied changes, even from another injected Core.
        preview.operation?.let { unexpected ->
            unexpected.output.assets.forEach { it.readableSource?.close() }
            return failure("POSTCONDITION_FAILED", Stage.Verify, "Preview unexpectedly produced output")
        }
        if (preview.changesApplied.isNotEmpty()) return failure("POSTCONDITION_FAILED", Stage.Verify, "Preview unexpectedly applied changes")
        if (review != null && (review.changes.isEmpty() || review.changes != preview.proposedChanges || preview.blocked.isNotEmpty()))
            return failure("REPAIR_AMBIGUOUS", Stage.Plan, "The safe repair proposal is not the reviewed proposal; preview it again")
        fun report(result: RepairResult, authorization: RepairReview? = null) = PhotoRepairReport(
            digest, authorization, result.issuesBefore, result.proposedChanges, result.changesApplied, result.issuesAfter, result.blocked)
        if (review == null || preview.proposedChanges.isEmpty()) {
            val authorization = if (preview.blocked.isEmpty() && preview.proposedChanges.isNotEmpty())
                RepairReview(digest, preview.proposedChanges.toList()) else null
            return CoreResult.Success(PreparedPhotoRepair(null, report(preview, authorization)))
        }
        val transaction = outputFactory(context, "ui-repair")
        output = transaction
        return when (val result = core.repair(RepairRequest(SourceSet.Single(source), mode = RepairMode.SafeMetadataOnly, dryRun = false, output = transaction, context = context))) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> {
                operation = result.value.operation
                val applied = operation ?: return failure("POSTCONDITION_FAILED", Stage.Verify, "Confirmed repair did not produce its planned output")
                if (result.value.blocked.isNotEmpty() || result.value.changesApplied != review.changes)
                    return failure("POSTCONDITION_FAILED", Stage.Verify, "Repair applied a different proposal")
                val asset = applied.output.assets.singleOrNull()?.takeIf { it.role == AssetRole.Composite && it.mime == "image/jpeg" }
                    ?: return failure("CAPABILITY_UNSUPPORTED", Stage.Verify, "This screen saves only one repaired JPEG; extra assets will not be discarded")
                val records = applied.preservation.records.filter { it.assetId == asset.id }
                fun verified(guarantee: Guarantee) = records.any { it.guarantee == guarantee && it.outcome == GuaranteeOutcome.Verified &&
                    it.sourceDigest != null && it.sourceDigest == it.outputDigest }
                if (!verified(Guarantee.ImageDataPreserving) || !verified(Guarantee.BitstreamPreserving))
                    return failure("PRESERVATION_REQUIREMENT_FAILED", Stage.Verify, "Core did not prove image and video preservation")
                CoreResult.Success(PreparedPhotoRepair(transaction.asset(asset.id), report(result.value).copy(
                    outputDigest = asset.digest, byteLength = asset.byteLength, preservation = applied.preservation,
                    issues = (applied.issues + applied.validation.issues + applied.preservation.issues).distinct())))
            }
        }
    } finally {
        operation?.output?.assets?.forEach { it.readableSource?.close() }
        if (operation == null) output?.abort()
    }
}

public fun formatPhotoRepair(report: PhotoRepairReport, publication: String): String = buildString {
    append(when {
        report.outputDigest != null -> publication
        report.blocked.isNotEmpty() -> "修复被阻止（未写入）。"
        report.proposedChanges.isEmpty() -> "无需修改（未写入）。"
        else -> "修复预览完成（未写入）；请检查方案后显式应用。"
    })
    append("\n源 SHA-256：").append(report.sourceDigest.value)
    append("\n修复前问题：").append(report.issuesBefore.size).append("；计划修改：").append(report.proposedChanges.size)
    append("；已应用：").append(report.changesApplied.size)
    append(if (report.outputDigest == null) "；当前问题（未写入）：" else "；修复后问题：").append(report.issuesAfter.size)
    append("；阻止项：").append(report.blocked.size)
    for (change in report.proposedChanges.take(12)) append("\n拟修改字段：").append(change.selector.take(240))
    val issues = (report.issuesBefore + report.issuesAfter + report.blocked + report.issues).distinct()
    if (issues.isNotEmpty()) append("\n报告代码：").append(issues.take(12).joinToString { it.code.value })
    report.outputDigest?.let { append("\n输出 SHA-256：").append(it.value).append("；大小：").append(formatMediaSize(report.byteLength?.toLong())) }
    report.preservation?.records?.forEach { append("\n").append(it.guarantee).append(": ").append(it.outcome) }
    append("\n仅 SafeMetadataOnly；不改原片、不 remux、不转码、不编码音频。只显示字段名/代码，不展开私人 metadata 值。")
    append("\n有问题不代表一定可修复；无需修改、预览或被阻止均不会保存文件。Unknown 不等于已保证保留，不代表相册兼容认证。")
}

public fun formatRepairFailure(error: CoreError): String =
    "修复失败\n错误码：${error.code.value.take(128)}\n阶段：${error.stage}\n具体原因：${error.message.take(2000)}\n未修改原片、未提交新的修复文件。"
