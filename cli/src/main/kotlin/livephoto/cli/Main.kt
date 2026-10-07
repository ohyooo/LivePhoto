package livephoto.cli

import livephoto.core.*
import livephoto.core.jvm.*
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.*
import kotlin.system.exitProcess

private const val HELP = """LivePhoto Core CLI
Usage: LivePhoto COMMAND [--option value]
Read: detect | inspect | analyze | validate | get-key | probe
Write: create | convert | extract | split | repair | set-key
Media: extract-frame | replace-cover | trim | remux | transcode
Capabilities: capabilities --target PROTOCOL [--profile PROFILE] | media-capabilities

Read/source: --input FILE [--pair-video FILE]
Create: --image FILE --video FILE --target PROTOCOL
Write: --output-dir NEW_DIRECTORY (assets published together under assets/)
Convert: --target PROTOCOL [--profile PROFILE]
Extract: [--resources ID,ID] [--raw-carrier]
Repair: preview by default; --apply --output-dir NEW_DIRECTORY to write
Key/frame: exactly one of --frame-index N or --time-us N [--track-id ID for frame index]
Validate: [--layers Structure,Protocol,Media]
Media: --format Jpeg|Png; trim --start-us N --end-us N [--mode LosslessPreferred]
Backend: [--ffmpeg EXECUTABLE]; otherwise PATH, then available system adapters, otherwise disabled
Probe: [--resource ID] [--decode-check] (never downloads media tools)
Remux/transcode: --container Mp4|Mov; transcode --codec Avc|Hevc --allow-transcode
Common: --strict, --max-bytes N (default 1 GiB), --help, --version

Output is JSON. Exit: 0 success, 2 arguments, 3 Core/IO failure, 4 invalid validation/blocked repair.
Unsupported/Planned operations return errors, never simulated media output.
"""

fun main(args: Array<String>) { exitProcess(blocking { Cli().run(args.toList(), ::println) }) }

internal fun <T> blocking(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    var completion: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { completion = result; latch.countDown() }
    })
    latch.await()
    return completion!!.getOrThrow()
}

internal class Cli(private val providedCore: LivePhotoCore? = null,
    private val discover: (Path?) -> BackendDiscovery = { JvmMediaBackends.discover(it) }) {
    suspend fun run(args: List<String>, emit: (String) -> Unit): Int {
        if (args.isEmpty() || args == listOf("--help") || args == listOf("help")) { emit(HELP); return 0 }
        if (args == listOf("--version")) { emit("LivePhoto 0.1.0"); return 0 }
        val sources = mutableListOf<FileBinarySource>()
        var output: DirectoryOutputTransaction? = null
        try {
            val command = args.first()
            val read = setOf("detect", "inspect", "analyze", "validate", "get-key", "probe")
            val writes = setOf("create", "convert", "extract", "split", "repair", "set-key", "extract-frame", "replace-cover", "trim", "remux", "transcode")
            require(command in read + writes + setOf("capabilities", "media-capabilities")) { "Unknown command: $command" }
            val options = linkedMapOf<String, String>()
            val flags = setOf("apply", "strict", "raw-carrier", "allow-transcode", "decode-check")
            var i = 1
            while (i < args.size) {
                val key = args[i++].removePrefix("--")
                require(args[i - 1].startsWith("--") && key.isNotEmpty() && key !in options) { "Expected a unique --option" }
                options[key] = if (key in flags) "true" else { require(i < args.size && !args[i].startsWith("--")) { "Missing value for --$key" }; args[i++] }
            }
            val common = setOf("max-bytes")
            val inputKeys = setOf("input", "pair-video")
            val positionKeys = setOf("frame-index", "time-us", "track-id")
            val targetKeys = setOf("target", "profile")
            val mediaCommands = setOf("probe", "media-capabilities", "extract-frame", "replace-cover", "trim", "remux", "transcode")
            val allowed = common + (if (command in mediaCommands) setOf("ffmpeg") else emptySet()) + when (command) {
                "capabilities" -> targetKeys
                "media-capabilities" -> emptySet()
                "create" -> setOf("image", "video", "output-dir", "strict") + targetKeys
                "convert" -> inputKeys + targetKeys + setOf("output-dir", "strict")
                "extract" -> inputKeys + setOf("output-dir", "resources", "raw-carrier")
                "split" -> inputKeys + setOf("output-dir", "strict")
                "repair" -> inputKeys + setOf("output-dir", "strict", "apply", "issues")
                "set-key" -> inputKeys + positionKeys + setOf("output-dir", "strict")
                "extract-frame", "replace-cover" -> inputKeys + positionKeys + setOf("output-dir", "format")
                "trim" -> inputKeys + setOf("output-dir", "strict", "start-us", "end-us", "mode")
                "remux" -> inputKeys + setOf("output-dir", "strict", "container")
                "transcode" -> inputKeys + setOf("output-dir", "strict", "container", "codec", "allow-transcode")
                "validate" -> inputKeys + setOf("layers")
                "probe" -> inputKeys + setOf("resource", "decode-check")
                else -> inputKeys
            }
            require(options.keys.all { it in allowed }) { "Unknown or inapplicable option: ${options.keys.first { it !in allowed }}" }
            val needsBackend = command in mediaCommands && (command != "probe" || "decode-check" in options || "ffmpeg" in options)
            val discovery = if (providedCore == null && needsBackend) discover(options["ffmpeg"]?.let(Path::of)) else null
            val core = providedCore ?: DefaultLivePhotoCore(discovery?.backend)
            fun required(name: String): String = options[name] ?: errorArgument("Missing --$name")
            val maxBytes = options["max-bytes"]?.toULong() ?: 1_073_741_824uL
            require(maxBytes in 1uL..Long.MAX_VALUE.toULong()) { "Invalid byte budget" }
            val context = Context(Limits(maxBytes, maxBytes))
            fun file(name: String): BinarySource = FileBinarySource(Path.of(required(name))).also { sources += it }
            fun source(): SourceSet = if (options.containsKey("pair-video")) SourceSet.Pair(file("input"), file("pair-video")) else SourceSet.Single(file("input"))
            fun target() = ProtocolSelector(ProtocolId(required("target")), options["profile"]?.let(::ProfileId))
            fun destination(): OutputTransaction = DirectoryOutputTransaction(Path.of(required("output-dir")), context).also { output = it }
            fun position(): CoverPosition {
                require(options.containsKey("frame-index") xor options.containsKey("time-us")) { "Choose exactly one of --frame-index or --time-us" }
                require(!options.containsKey("track-id") || options.containsKey("frame-index")) { "--track-id requires --frame-index" }
                return options["frame-index"]?.let { CoverPosition.FrameIndex(it.toULong(), options["track-id"]?.let(::TrackId)) }
                    ?: CoverPosition.Timestamp(Time(required("time-us").toLong(), 1_000_000u))
            }
            val policy = MutationPolicy(preservation = if (options.containsKey("strict")) PreservationPolicy.Strict else PreservationPolicy.BestEffortWithReport,
                transcode = if (options.containsKey("allow-transcode")) TranscodePolicy.Explicit else TranscodePolicy.Forbid)
            val result: CoreResult<*> = when (command) {
                "capabilities" -> CoreResult.Success(core.getProtocolCapabilities(target()))
                "media-capabilities" -> CoreResult.Success(mapOf("capabilities" to core.getMediaCapabilities(),
                    "ffmpegPath" to discovery?.ffmpegPath?.toString(), "discoveryIssues" to (discovery?.issues ?: emptyList<Issue>())))
                "detect" -> core.detect(ReadRequest(source(), context))
                "inspect" -> core.inspect(ReadRequest(source(), context))
                "analyze" -> core.analyze(AnalyzeRequest(source(), context = context))
                "validate" -> core.validate(ValidationRequest(source(), layers = options["layers"]?.split(',')?.map { Layer.valueOf(it) } ?: listOf(Layer.Structure, Layer.Protocol, Layer.Media), context = context))
                "get-key" -> core.getKeyPhotoPosition(ReadRequest(source(), context))
                "probe" -> core.probe(ProbeRequest(ResourceRef(source(), options["resource"]?.let(::ResourceId)), decodeCheck = "decode-check" in options, context = context))
                "create" -> core.create(CreateRequest(file("image"), file("video"), target(), policy = policy, output = destination(), context = context))
                "convert" -> core.convert(ConvertRequest(source(), target(), policy = policy, output = destination(), context = context))
                "extract" -> core.extract(ExtractRequest(source(), options["resources"]?.split(',')?.map(::ResourceId) ?: emptyList(), includeRawCarrier = options.containsKey("raw-carrier"), output = destination(), context = context))
                "split" -> core.split(SplitRequest(source(), policy = policy, output = destination(), context = context))
                "repair" -> {
                    require(options.containsKey("apply") || !options.containsKey("output-dir")) { "Repair preview does not accept --output-dir; use --apply" }
                    core.repair(RepairRequest(source(), allowedIssueCodes = options["issues"]?.split(',')?.map(::IssueCode) ?: emptyList(), dryRun = !options.containsKey("apply"), policy = policy, output = if (options.containsKey("apply")) destination() else null, context = context))
                }
                "set-key" -> core.setKeyPhotoPosition(SetKeyRequest(source(), position(), policy, destination(), context))
                "extract-frame" -> core.extractFrame(ExtractFrameRequest(ResourceRef(source()), position(), ImageEncoding(ImageFormat.valueOf(required("format"))), destination(), context))
                "replace-cover" -> core.replacePrimaryImageFromFrame(ReplaceRequest(source(), position(), ImageEncoding(ImageFormat.valueOf(required("format"))), output = destination(), context = context))
                "trim" -> core.trim(TrimRequest(ResourceRef(source()), TrimSpec(TimeRange(Time(required("start-us").toLong(), 1_000_000u), Time(required("end-us").toLong(), 1_000_000u)), mode = options["mode"]?.let(TrimMode::valueOf) ?: TrimMode.LosslessPreferred), policy, destination(), context))
                "remux" -> core.remux(RemuxRequest(ResourceRef(source()), VideoContainer.valueOf(required("container")), policy, destination(), context))
                else -> core.transcode(TranscodeRequest(ResourceRef(source()), VideoEncoding(VideoCodec.valueOf(required("codec")), VideoContainer.valueOf(required("container"))), policy, destination(), context))
            }
            emit(Json.encode(result, output))
            return when (result) {
                is CoreResult.Failure -> 3
                is CoreResult.Success -> when (val value = result.value) {
                    is ValidationReport -> if (value.verdict == Verdict.Invalid) 4 else 0
                    is AnalysisResult -> if (value.validation.verdict == Verdict.Invalid) 4 else 0
                    is RepairResult -> if (value.blocked.isNotEmpty()) 4 else 0
                    else -> 0
                }
            }
        } catch (e: IllegalArgumentException) {
            emit(Json.encode(mapOf("error" to mapOf("code" to "INVALID_ARGUMENT", "message" to e.message))))
            return 2
        } finally {
            sources.forEach { it.close() }
            output?.let { transaction ->
                val state = (transaction.query() as? CoreResult.Success)?.value?.state
                if (state in setOf(TransactionState.Open, TransactionState.Prepared)) transaction.abort()
            }
        }
    }
}

private fun errorArgument(message: String): Nothing = throw IllegalArgumentException(message)
