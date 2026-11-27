package livephoto.android

import livephoto.core.AnalyzeRequest
import livephoto.core.AnalysisResult
import livephoto.core.BinarySource
import livephoto.core.Cancellation
import livephoto.core.Context
import livephoto.core.CoreError
import livephoto.core.CoreResult
import livephoto.core.IssueCode
import livephoto.core.Layer
import livephoto.core.Limits
import livephoto.core.LivePhotoCore
import livephoto.core.SourceSet
import livephoto.core.Stage
import livephoto.ui.ANALYSIS_INPUT_LIMIT
import livephoto.ui.analyzeSelectedPhoto

internal const val ANDROID_INSPECTION_INPUT_LIMIT: Int = 64 * 1024 * 1024

/** Only the Android inspection entry point can borrow a larger immutable snapshot. */
internal suspend fun inspectAndroidPhoto(
    core: LivePhotoCore, source: BinarySource, cancellation: Cancellation?,
): CoreResult<AnalysisResult> = when (val size = source.size()) {
    is CoreResult.Failure -> size
    is CoreResult.Success -> when {
        size.value <= ANALYSIS_INPUT_LIMIT.toULong() -> analyzeSelectedPhoto(core, source, cancellation)
        size.value > ANDROID_INSPECTION_INPUT_LIMIT.toULong() -> CoreResult.Failure(CoreError(
            IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Photo inspection input exceeds 64 MiB"))
        else -> core.analyze(AnalyzeRequest(SourceSet.Single(source),
            layers = listOf(Layer.Structure, Layer.Protocol, Layer.Media),
            // A larger carrier does not grant the parser a larger metadata allocation or any output.
            context = Context(Limits(ANDROID_INSPECTION_INPUT_LIMIT.toULong(), 0u,
                maxMetadataBytes = ANALYSIS_INPUT_LIMIT.toULong()), cancellation = cancellation)))
    }
}
