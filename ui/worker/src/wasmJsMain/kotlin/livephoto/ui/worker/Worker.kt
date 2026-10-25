@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package livephoto.ui.worker

import kotlin.js.*
import kotlinx.coroutines.*
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import livephoto.core.*
import livephoto.core.memory.MemoryOutputTransaction
import livephoto.core.ffmpeg.*
import livephoto.ui.*

private external interface Request : JsAny {
    val task: Int
    val options: Options
    val sizes: JsArray<JsNumber>
    val files: JsArray<JsAny>
}
private external interface Options : JsAny {
    val kind: String
    val reference: String
    val protocol: String
    val profile: String
    val time: String
    val scale: String
    val end: String
    val endScale: String
    val review: String
    val imageName: String
    val videoName: String
    val publication: String
}
private external interface Reply : JsAny
private fun reply(report: String, failed: Boolean = false, bytes: ArrayBuffer? = null, mime: String = "", name: String = "",
    second: ArrayBuffer? = null, secondMime: String = "", secondName: String = "", review: String = "",
    time: String = "", scale: String = "", start: String = "", end: String = "", windowScale: String = ""): Reply =
    js("({report,failed,bytes,mime,name,second,secondMime,secondName,review,time,scale,start,end,windowScale})")

private fun install(run: (Request) -> Unit): Unit = js("""{
    const reads=new Map(); let next=0;
    self.readUiSource=(task,source,offset,length)=>new Promise(resolve=>{
        const id=++next; reads.set(id,resolve); self.postMessage({type:'read',task,id,source,offset,length});
    });
    self.onmessage=({data})=>{
        if(data.type==='read'){const resolve=reads.get(data.id);reads.delete(data.id);resolve?.(data.bytes);}
        else if(data.type==='run')run(data);
    };
    self.postMessage({type:'ready'});
}""")
private fun read(task: Int, source: Int, offset: Double, length: Int): Promise<ArrayBuffer?> = js("self.readUiSource(task,source,offset,length)")
private fun send(task: Int, result: Reply): Unit = js("{ self.postMessage({type:'done',task,result},[result.bytes,result.second].filter(Boolean)); }")
private fun number(values: JsArray<JsNumber>, index: Int): Double = js("values[index]")
private fun file(values: JsArray<JsAny>, index: Int): JsAny = js("values[index]")
private fun byte(values: Int8Array, index: Int): Byte = js("values[index]")
private fun put(values: Int8Array, index: Int, value: Byte): Unit = js("{ values[index]=value; }")
private fun token(): String = js("crypto.randomUUID()")
private fun buffer(bytes: Bytes): ArrayBuffer {
    val array = Int8Array(bytes.size)
    for (index in 0 until bytes.size) put(array, index, bytes[index])
    return array.buffer
}

private class Source(private val request: Request, private val index: Int, id: String) : BinarySource {
    private val length = number(request.sizes, index).also {
        val limit = if (CoreMediaAction.entries.any { action -> action.name == request.options.kind }) BROWSER_MEDIA_INPUT_LIMIT else ANALYSIS_INPUT_LIMIT
        require(it.isFinite() && it >= 0 && it <= limit)
    }.toULong()
    private val identity = SourceIdentity(SourceId(id), GenerationToken("immutable-browser-blob-v1"), length)
    override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(identity)
    override suspend fun size(): CoreResult<ULong> = CoreResult.Success(length)
    override suspend fun close() {}
    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> {
        if (offset > this.length) return CoreResult.Failure(CoreError(IssueCode("OFFSET_OUT_OF_BOUNDS"), Stage.Read,
            "Read exceeds browser source", Location(source = identity.id)))
        val count = minOf(length.toULong(), this.length - offset, 65536uL).toInt()
        if (count == 0) return CoreResult.Success(Bytes(byteArrayOf()))
        val data = read(request.task, index, offset.toDouble(), count).await()
            ?: return CoreResult.Failure(CoreError(IssueCode("IO_READ_FAILED"), Stage.Read,
                "Unable to read the selected browser file; select it again", Location(source = identity.id)))
        val array = Int8Array(data)
        if (array.length != count) return CoreResult.Failure(CoreError(IssueCode("IO_READ_FAILED"), Stage.Read,
            "Incomplete browser slice", Location(source = identity.id)))
        return CoreResult.Success(Bytes(ByteArray(count) { byte(array, it) }))
    }
}

private var reviewed: Triple<String, String, RepairReview>? = null
private val core = DefaultLivePhotoCore()

private fun failure(kind: String, error: CoreError, options: Options): Reply = reply(when (kind) {
    "Create" -> formatCoreFailure(error, options.imageName, options.videoName)
    "Analyze" -> formatPhotoAnalysisFailure(error)
    "Extract" -> formatVideoExtractionFailure(error)
    "Split" -> formatCleanSplitFailure(error)
    "Convert" -> formatConversionFailure(error)
    "Repair" -> formatRepairFailure(error)
    "Key" -> formatPhotoKeyFailure(error)
    else -> "${error.code.value} / ${error.stage}：${error.message.take(1600)}"
}, true)

private suspend fun execute(request: Request): Reply {
    val options = request.options
    val kind = options.kind
    val input = Source(request, 0, if (kind == "Create") "browser-image" else "browser-${kind.lowercase()}:${options.reference}")
    val target by lazy { ProtocolSelector(ProtocolId(options.protocol), ProfileId(options.profile)) }
    val time by lazy { if (options.time.isEmpty()) Time.Zero else Time(options.time.toLong(), options.scale.toUInt()) }
    fun failed(error: CoreError) = failure(kind, error, options)
    try {
        return when (kind) {
            "Analyze" -> when (val result = analyzeSelectedPhoto(core, input)) {
                is CoreResult.Failure -> failed(result.error)
                is CoreResult.Success -> reply(formatPhotoAnalysis(result.value))
            }
            "Extract" -> when (val result = prepareExtractedVideo(core, input)) {
                is CoreResult.Failure -> failed(result.error)
                is CoreResult.Success -> reply(formatExtractedVideo(result.value.report, options.publication),
                    bytes = buffer(result.value.bytes), mime = result.value.report.mime, name = "LivePhoto-video.${result.value.report.extension}")
            }
            "Split" -> when (val result = prepareCleanSplit(core, input)) {
                is CoreResult.Failure -> failed(result.error)
                is CoreResult.Success -> reply(formatCleanSplit(result.value),
                    bytes = buffer(result.value.image.bytes), mime = result.value.image.mime, name = "LivePhoto-clean.${result.value.image.extension}",
                    second = buffer(result.value.video.bytes), secondMime = result.value.video.mime, secondName = "LivePhoto-clean.${result.value.video.extension}")
            }
            "Convert" -> when (val result = prepareConvertedPhoto(core, input, target)) {
                is CoreResult.Failure -> failed(result.error)
                is CoreResult.Success -> reply(formatConvertedPhoto(result.value.report, options.publication),
                    bytes = buffer(result.value.bytes), mime = "image/jpeg", name = "LivePhoto-converted-${target.protocol.value}.jpg")
            }
            "Key" -> when (val result = preparePhotoKey(core, input, time)) {
                is CoreResult.Failure -> failed(result.error)
                is CoreResult.Success -> reply(formatPhotoKey(result.value.report, options.publication),
                    bytes = buffer(result.value.bytes), mime = "image/jpeg", name = "LivePhoto-key.jpg")
            }
            "Repair" -> {
                val previous = reviewed
                reviewed = null
                if (options.review.isNotEmpty() && (previous == null || previous.first != options.review || previous.second != options.reference))
                    return failed(CoreError(IssueCode("SOURCE_CHANGED"), Stage.Read, "Review is no longer available; preview the selected input again"))
                when (val result = preparePhotoRepair(core, input, if (options.review.isEmpty()) null else previous!!.third)) {
                    is CoreResult.Failure -> failed(result.error)
                    is CoreResult.Success -> {
                        val review = result.value.report.review?.let { approved -> token().also { reviewed = Triple(it, options.reference, approved) } } ?: ""
                        reply(formatPhotoRepair(result.value.report, options.publication), result.value.report.blocked.isNotEmpty(),
                            bytes = result.value.bytes?.let(::buffer), mime = "image/jpeg", name = "LivePhoto-repaired.jpg", review = review)
                    }
                }
            }
            "Create" -> {
                val video = Source(request, 1, "browser-video")
                val context = Context(Limits(32uL * 1024u * 1024u, 48uL * 1024u * 1024u))
                val output = MemoryOutputTransaction(context, "browser-create")
                var operation: OperationResult? = null
                try {
                    val policy = if (target.protocol == ProtocolIds.Oplus) MutationPolicy(loss = LossPolicy.AllowListed,
                        allowedLosses = listOf(MetadataLoss.OplusCameraUserComment, MetadataLoss.OplusAsciiCameraUserComment)) else MutationPolicy()
                    val edits = if (options.time.isEmpty()) null else EditSpec(keyPosition = CoverPosition.Timestamp(time, Selection.Nearest, Time(1, 1u)))
                    when (val result = core.create(CreateRequest(input, video, target, edits = edits, policy = policy, output = output, context = context))) {
                        is CoreResult.Failure -> failed(result.error)
                        is CoreResult.Success -> {
                            operation = result.value
                            val asset = result.value.output.assets.single()
                            val name = "LivePhoto-${target.protocol.value}.jpg"
                            val displayed = result.value.copy(output = MediaOutput(listOf(asset.copy(recommendedName = name)), result.value.output.receipt))
                            reply(formatCreatedPhoto(displayed, options.publication), bytes = buffer(output.committedAssets().getValue(asset.id)), mime = asset.mime, name = name)
                        }
                    }
                } finally {
                    operation?.output?.assets?.forEach { it.readableSource?.close() }
                    if (operation == null) output.abort()
                    video.close()
                }
            }
            else -> media(request, input)
        }
    } finally { input.close() }
}

private external interface MediaProcess : JsAny {
    val code: Int?; val output: String; val outputLimited: Boolean
    val cancelled: Boolean; val timedOut: Boolean; val ioFailed: Boolean; val bytes: Int8Array?
}
private fun arguments(): JsAny = js("[]")
private fun append(args: JsAny, value: String): Unit = js("{ args.push(value); }")
private fun executeMedia(args: JsAny, input: JsAny, previous: Int8Array?, name: String, timeout: Double): Promise<MediaProcess> =
    js("import(/* webpackIgnore: true */ new URL('../media/core-runtime.mjs', self.location.href).href).then(module => module.executeCoreFfmpeg(JSON.stringify(args),input,previous,name,timeout))")

private suspend fun media(request: Request, source: BinarySource): Reply {
    val options = request.options
    val action = CoreMediaAction.entries.find { it.name == options.kind }
        ?: return failure(options.kind, CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Plan, "Unknown task"), options)
    val backend = DefaultLivePhotoCore(BrowserFfmpegBackend(MemoryFfmpegExecutor { values, input, previous, name, timeout, _ ->
        require(input === source) { "Unbound browser media source" }
        val args = arguments(); values.forEach { append(args, it) }
        val result = executeMedia(args, file(request.files, 0), previous?.let { Int8Array(buffer(it)) }, name, timeout.toDouble()).await()
        MemoryFfmpegResult(FfmpegProcessResult(result.code, result.output, result.outputLimited, result.cancelled, result.timedOut, result.ioFailed),
            result.bytes?.let { array -> Bytes(ByteArray(array.length) { byte(array, it) }) })
    }))
    val start = Time(options.time.toLong(), options.scale.toUInt())
    val end = Time(options.end.toLong(), options.endScale.toUInt())
    return when (val result = prepareCoreMedia(backend, source, action, start, end, inputLimit = BROWSER_MEDIA_INPUT_LIMIT)) {
        is CoreResult.Failure -> failure(options.kind, result.error, options)
        is CoreResult.Success -> {
            val value = result.value
            reply(value.summary, bytes = value.bytes?.let(::buffer), mime = value.mime ?: "",
                time = value.actualTime?.value?.toString() ?: "", scale = value.actualTime?.timescale?.toString() ?: "",
                start = value.retainedVideoWindow?.start?.value?.toString() ?: "", end = value.retainedVideoWindow?.end?.value?.toString() ?: "",
                windowScale = value.retainedVideoWindow?.start?.timescale?.toString() ?: "")
        }
    }
}

fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    install { request -> scope.launch {
        val result = try { execute(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reply("后台处理失败\n错误码：WORKER_FAILED\n未发布输出，请重新选择文件。", true) }
        send(request.task, result)
    } }
}
