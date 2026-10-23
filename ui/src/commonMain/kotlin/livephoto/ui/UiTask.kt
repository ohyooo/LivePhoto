package livephoto.ui

import livephoto.core.*

/** Presentation boundary; actions carry no protocol logic or platform IO into Core. */
public enum class UiTaskKind { Create, Analyze, Extract, Split, Convert, Repair, Key }
public data class UiTask(
    val kind: UiTaskKind, val input: String, val video: String? = null,
    val target: ProtocolSelector? = null, val time: Time? = null,
    val imageName: String? = null, val videoName: String? = null,
)
public data class UiSaveAction(val label: String, val save: suspend () -> String)
public data class UiTaskResult(
    val report: String, val failed: Boolean = false, val name: String? = null,
    val applyRepair: (suspend () -> UiTaskResult)? = null,
    val saves: List<UiSaveAction> = emptyList(),
)

internal suspend fun LivePhotoUiPlatform.runHostTask(task: UiTask): UiTaskResult = when (task.kind) {
    UiTaskKind.Create -> when (val result = create(task.input, checkNotNull(task.video), checkNotNull(task.target))) {
        is CoreResult.Failure -> UiTaskResult(formatCoreFailure(result.error, task.imageName, task.videoName), true)
        is CoreResult.Success -> UiTaskResult(formatCreatedPhoto(result.value.operation, successMessage), name = result.value.operation.output.assets.singleOrNull()?.recommendedName)
    }
    UiTaskKind.Analyze -> when (val result = analyze(task.input)) {
        is CoreResult.Failure -> UiTaskResult(formatPhotoAnalysisFailure(result.error), true)
        is CoreResult.Success -> UiTaskResult(formatPhotoAnalysis(result.value))
    }
    UiTaskKind.Extract -> when (val result = extractVideo(task.input)) {
        is CoreResult.Failure -> UiTaskResult(formatVideoExtractionFailure(result.error), true)
        is CoreResult.Success -> UiTaskResult(formatExtractedVideo(result.value, videoExportSuccessMessage))
    }
    UiTaskKind.Split -> when (val result = cleanSplit(task.input)) {
        is CoreResult.Failure -> UiTaskResult(formatCleanSplitFailure(result.error), true)
        is CoreResult.Success -> UiTaskResult(formatCleanSplit(result.value), saves = listOf(
            UiSaveAction("image") { saveSplitFile(result.value.image) }, UiSaveAction("video") { saveSplitFile(result.value.video) }))
    }
    UiTaskKind.Convert -> when (val result = convertPhoto(task.input, checkNotNull(task.target))) {
        is CoreResult.Failure -> UiTaskResult(formatConversionFailure(result.error), true)
        is CoreResult.Success -> UiTaskResult(formatConvertedPhoto(result.value, conversionSuccessMessage))
    }
    UiTaskKind.Key -> when (val result = setPhotoKey(task.input, checkNotNull(task.time))) {
        is CoreResult.Failure -> UiTaskResult(formatPhotoKeyFailure(result.error), true)
        is CoreResult.Success -> UiTaskResult(formatPhotoKey(result.value, keySuccessMessage))
    }
    UiTaskKind.Repair -> runHostRepair(task.input, null)
}

private suspend fun LivePhotoUiPlatform.runHostRepair(input: String, review: RepairReview?): UiTaskResult =
    when (val result = repairPhoto(input, review)) {
        is CoreResult.Failure -> UiTaskResult(formatRepairFailure(result.error), true)
        is CoreResult.Success -> UiTaskResult(formatPhotoRepair(result.value, repairSuccessMessage), result.value.blocked.isNotEmpty(),
            applyRepair = result.value.review?.let { approved -> { runHostRepair(input, approved) } })
    }
