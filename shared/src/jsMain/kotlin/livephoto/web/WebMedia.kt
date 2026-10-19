@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package livephoto.web

import kotlin.js.*
import kotlin.coroutines.*
import livephoto.core.*
import livephoto.core.ffmpeg.*
import livephoto.core.memory.*

private suspend fun awaitMedia(value: Promise<dynamic>): dynamic = suspendCoroutine { continuation ->
    value.then<Unit>({ result -> continuation.resume(result) }, { error -> continuation.resumeWithException(error) })
}

/** Private JS execution adapter; Core verifies every output before exposure. */
@JsExport
public fun processMedia(input: ByteArray, action: String, executor: dynamic, startUs: Double = 0.0,
    endUs: Double = 0.0, authorizeTranscode: Boolean = false, cancelled: () -> Boolean = { false }): Promise<WebResult> = promise {
    if (input.size > 32 * 1024 * 1024) return@promise WebResult(false, "RESOURCE_LIMIT_EXCEEDED", "Read", "Media input exceeds 32 MiB")
    if (!startUs.isFinite() || !endUs.isFinite() || startUs < 0 || endUs < 0 || startUs > 60_000_000 || endUs > 60_000_000 ||
        startUs % 1 != 0.0 || endUs % 1 != 0.0 || action !in setOf("probe", "remux", "trim", "transcode", "frame"))
        return@promise WebResult(false, "INVALID_ARGUMENT", "Plan", "Unknown action or invalid integer microsecond range (maximum 60 seconds)")
    if (action == "trim" && endUs <= startUs) return@promise WebResult(false, "INVALID_ARGUMENT", "Plan", "Trim range must be nonempty")
    val ctx = Context(Limits(32uL * 1024u * 1024u, 48uL * 1024u * 1024u), cancellation = Cancellation(cancelled))
    val backend = BrowserFfmpegBackend(MemoryFfmpegExecutor { args, data, previous, name, timeout, context ->
        val response = awaitMedia(executor(JSON.stringify(args.toTypedArray()), data.toByteArray(), previous?.toByteArray(), name, timeout.toDouble(), { context.cancellation?.isCancelled() == true }) as Promise<dynamic>)
        MemoryFfmpegResult(FfmpegProcessResult((response.code as? Int), response.output as String,
            response.outputLimited as Boolean, response.cancelled as Boolean, response.timedOut as Boolean, response.ioFailed as Boolean),
            (response.bytes as? ByteArray)?.let(::Bytes))
    })
    val core = DefaultLivePhotoCore(backend)
    val source = MemoryBinarySource(Bytes(input), SourceId("web-media"))
    val ref = ResourceRef(SourceSet.Single(source))
    val output = MemoryOutputTransaction(ctx, "web-media")
    var applied: OperationResult? = null
    try {
        if (action == "probe") return@promise when (val result = core.probe(ProbeRequest(ref, decodeCheck = true, context = ctx))) {
            is CoreResult.Failure -> failure(result.error)
            is CoreResult.Success -> WebResult(true, mime = result.value.mime.orEmpty(), coverage = result.value.coverage.name,
                issues = result.value.issues.map { it.code.value }.toTypedArray(),
                reportJson = reportJson(record("kind" to "MediaProbe", "backendId" to "ffmpeg-wasm", "media" to media(result.value))))
        }
        val policy = MutationPolicy(transcode = if (authorizeTranscode) TranscodePolicy.Explicit else TranscodePolicy.Forbid)
        var extra: dynamic = null
        val result = when (action) {
            "remux" -> core.remux(RemuxRequest(ref, VideoContainer.Mp4, output = output, context = ctx))
            "transcode" -> core.transcode(TranscodeRequest(ref, VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4), policy, output, ctx))
            "trim" -> when (val trimmed = core.trim(TrimRequest(ref, TrimSpec(TimeRange(Time(startUs.toLong(), 1_000_000u), Time(endUs.toLong(), 1_000_000u))), output = output, context = ctx))) {
                is CoreResult.Failure -> trimmed
                is CoreResult.Success -> {
                    val v = trimmed.value
                    extra = record("requestedStart" to time(v.requestedStart), "requestedEnd" to time(v.requestedEnd),
                        "actualStart" to time(v.actualStart), "actualEnd" to time(v.actualEnd), "wasTranscoded" to v.wasTranscoded, "retainedHiddenContent" to v.retainedHiddenContent)
                    CoreResult.Success(v.operation)
                }
            }
            else -> when (val frame = core.extractFrame(ExtractFrameRequest(ref,
                CoverPosition.Timestamp(Time(startUs.toLong(), 1_000_000u), Selection.Nearest, Time(100, 1000u)), ImageEncoding(ImageFormat.Jpeg), output, ctx))) {
                is CoreResult.Failure -> frame
                is CoreResult.Success -> { extra = record("actualTime" to time(frame.value.actualTime), "actualFrameIndex" to frame.value.actualFrameIndex?.toString()); CoreResult.Success(frame.value.operation) }
            }
        }
        when (result) {
            is CoreResult.Failure -> failure(result.error)
            is CoreResult.Success -> {
                applied = result.value
                val report = operationReport(result.value); report.mediaAction = action; report.actual = extra
                operation(result.value, output, report = reportJson(report))
            }
        }
    } finally { applied?.output?.assets?.forEach { it.readableSource?.close() }; if (applied == null) output.abort(); source.close() }
}
