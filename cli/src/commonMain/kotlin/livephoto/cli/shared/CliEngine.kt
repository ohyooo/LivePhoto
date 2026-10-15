package livephoto.cli.shared

import livephoto.core.*

private const val HELP = """LivePhoto Core CLI
Usage: LivePhoto COMMAND [--option value]
Read: detect | inspect | analyze | validate | get-key | probe
Write: create | convert | extract | split | repair | set-key
Media: extract-frame | replace-cover | trim | remux | transcode
Capabilities: capabilities --target PROTOCOL [--profile PROFILE] | media-capabilities

Read/source: --input FILE [--pair-video FILE]
Create: --image FILE --video FILE --target PROTOCOL
Create Oplus: [--replace-oplus-user-comment] authorizes supported camera comments -> oplus_10485792
Write: --output-dir NEW_DIRECTORY (assets published together under assets/)
Convert: --target PROTOCOL [--profile PROFILE]
Create/convert edits: [--start-us N --end-us N --mode LosslessPreferred] [--frame-index N | --time-us N]
Derived image: [--replacement-frame-index N | --replacement-time-us N] [--replacement-track-id ID]; independent of key
Convert: [--same-target PreserveAsIs|Normalize]; trim [--key-outside Reject|ClampExplicitly|ClearIfSupported]
Extract: [--resources ID,ID] [--raw-carrier]
Repair: preview by default; --apply --output-dir NEW_DIRECTORY to write
Repair modes: [--mode SafeMetadataOnly|ExplicitRePair|ExplicitRemux]; re-pair needs --pair-video and --authority EVIDENCE_ID from inspect of a selected single asset
Key/frame: exactly one of --frame-index N or --time-us N [--track-id ID for frame index]
Extract-frame: [--resource ID] (select an embedded video in a live-photo carrier); extract-frame/replace-cover [--quality 0..100] (backend-dependent)
Validate: [--layers Structure,Protocol,Media]
Media: --format Jpeg|Png; trim --start-us N --end-us N [--mode LosslessPreferred|LosslessOnly|Exact] [--allow-transcode]
Backend: [--ffmpeg EXECUTABLE]; otherwise PATH, otherwise disabled (no desktop system API fallback)
Probe: [--resource ID] [--decode-check] (never downloads media tools)
Remux/transcode: --container Mp4|Mov; remux [--resource ID]; transcode --codec Avc|Hevc --allow-transcode
Common: --strict, --max-bytes N (default 1 GiB), --log-level off|error|debug|trace, --help, --version
Diagnostics: stderr only; LIVEPHOTO_LOG_LEVEL is the default; arguments and exception messages are redacted

Output is JSON. Exit: 0 success, 2 arguments, 3 Core/IO failure, 4 invalid validation/blocked repair.
Unsupported/Planned operations return errors, never simulated media output.
"""

public class CliEngine(private val platform: CliPlatform, private val providedCore: LivePhotoCore? = null,
    private val diagnostic: (String) -> Unit = platform::error) {
    suspend fun run(args: List<String>, emit: (String) -> Unit): Int {
        val sources = mutableListOf<BinarySource>()
        val log = Diagnostics(diagnostic, platform)
        val started = platform.nanoTime()
        var output: CliOutput? = null
        var message: String? = null
        var cleanupError: CoreError? = null
        fun failed(error: CoreError) { if (cleanupError == null) cleanupError = error }
        fun unexpectedCleanup() = failed(CoreError(IssueCode("IO_READ_FAILED"), Stage.Read,
            "CLI adapter cleanup failed"))
        val status = try {
            execute(args, { message = it }, sources, log) { output = it }
        } finally {
            // A failed close must not prevent releasing the remaining sources or snapshot session.
            sources.forEach { source ->
                try { source.close() } catch (_: Exception) { unexpectedCleanup() }
            }
            try {
                output?.let {
                    when (val receipt = it.transaction.query()) {
                        is CoreResult.Failure -> failed(receipt.error)
                        is CoreResult.Success -> if (receipt.value.state in setOf(TransactionState.Open, TransactionState.Prepared)) {
                            val aborted = it.transaction.abort()
                            if (aborted is CoreResult.Failure) failed(aborted.error)
                        }
                    }
                }
            } catch (_: Exception) { unexpectedCleanup() }
            try {
                val finished = platform.finish()
                if (finished is CoreResult.Failure) failed(finished.error)
            } catch (_: Exception) { unexpectedCleanup() }
            log.trace("event=finished elapsed-ms=${(platform.nanoTime() - started) / 1_000_000}")
        }
        val failure = cleanupError
        if (failure != null) {
            log.failure(failure.code.value, failure.stage.name)
            emit(CliJson.encode(CoreResult.Failure(failure)))
            return 3
        }
        message?.let(emit)
        return status
    }

    private suspend fun execute(args: List<String>, emit: (String) -> Unit,
        sources: MutableList<BinarySource>, log: Diagnostics, onOutput: (CliOutput) -> Unit): Int {
        if (args.isEmpty() || args == listOf("--help") || args == listOf("help")) { emit(HELP); return 0 }
        if (args == listOf("--version")) { emit("LivePhoto 0.1.0"); return 0 }
        var output: CliOutput? = null
        try {
            log.configure(platform.environment("LIVEPHOTO_LOG_LEVEL"))
            val command = args.first()
            val read = setOf("detect", "inspect", "analyze", "validate", "get-key", "probe")
            val writes = setOf("create", "convert", "extract", "split", "repair", "set-key", "extract-frame", "replace-cover", "trim", "remux", "transcode")
            require(command in read + writes + setOf("capabilities", "media-capabilities")) { "Unknown command: $command" }
            val options = linkedMapOf<String, String>()
            val flags = setOf("apply", "strict", "raw-carrier", "allow-transcode", "decode-check", "update-key", "replace-oplus-user-comment")
            var i = 1
            while (i < args.size) {
                val key = args[i++].removePrefix("--")
                require(args[i - 1].startsWith("--") && key.isNotEmpty() && key !in options) { "Expected a unique --option" }
                options[key] = if (key in flags) "true" else { require(i < args.size && !args[i].startsWith("--")) { "Missing value for --$key" }; args[i++] }
            }
            val common = setOf("max-bytes", "log-level")
            val inputKeys = setOf("input", "pair-video")
            val positionKeys = setOf("frame-index", "time-us", "track-id")
            val replacementKeys = positionKeys.map { "replacement-$it" }.toSet()
            val targetKeys = setOf("target", "profile")
            val trimKeys = setOf("start-us", "end-us", "mode", "key-outside")
            val mediaCommands = setOf("probe", "media-capabilities", "extract-frame", "replace-cover", "trim", "remux", "transcode")
            val allowed = common + (if (command in mediaCommands + setOf("create", "convert", "repair")) setOf("ffmpeg") else emptySet()) + when (command) {
                "capabilities" -> targetKeys
                "media-capabilities" -> emptySet()
                "create" -> setOf("image", "video", "output-dir", "strict", "allow-transcode", "replace-oplus-user-comment") + targetKeys + trimKeys + positionKeys + replacementKeys
                "convert" -> inputKeys + targetKeys + setOf("output-dir", "strict", "same-target", "allow-transcode") + trimKeys + positionKeys + replacementKeys
                "extract" -> inputKeys + setOf("output-dir", "resources", "raw-carrier")
                "split" -> inputKeys + setOf("output-dir", "strict")
                "repair" -> inputKeys + setOf("output-dir", "strict", "apply", "issues", "mode", "authority")
                "set-key" -> inputKeys + positionKeys + setOf("output-dir", "strict")
                "extract-frame" -> inputKeys + positionKeys + setOf("output-dir", "format", "resource", "quality")
                "replace-cover" -> inputKeys + positionKeys + setOf("output-dir", "format", "update-key", "strict", "quality")
                "trim" -> inputKeys + setOf("output-dir", "strict", "start-us", "end-us", "mode", "resource", "allow-transcode")
                "remux" -> inputKeys + setOf("output-dir", "strict", "container", "resource")
                "transcode" -> inputKeys + setOf("output-dir", "strict", "container", "codec", "allow-transcode")
                "validate" -> inputKeys + setOf("layers")
                "probe" -> inputKeys + setOf("resource", "decode-check")
                else -> inputKeys
            }
            require(options.keys.all { it in allowed }) { "Unknown or inapplicable option: ${options.keys.first { it !in allowed }}" }
            val imageQuality = options["quality"]?.toUInt()?.also { require(it <= 100u) { "Image quality must be in 0..100" } }
            options["log-level"]?.let(log::configure)
            log.debug("operation=$command event=start")
            val needsBackend = command in mediaCommands && (command != "probe" || "decode-check" in options || "ffmpeg" in options) ||
                command in setOf("create", "convert") && (options.keys.any { it in trimKeys + replacementKeys } || "ffmpeg" in options) ||
                command == "repair" && (options["mode"] == RepairMode.ExplicitRemux.name || "ffmpeg" in options)
            val discovery = if (providedCore == null && needsBackend) platform.discover(options["ffmpeg"]) else null
            log.trace("event=backend-discovery requested=$needsBackend ffmpeg-found=${discovery?.ffmpegPath != null}")
            val core = providedCore ?: DefaultLivePhotoCore(discovery?.backend)
            fun required(name: String): String = options[name] ?: errorArgument("Missing --$name")
            val maxBytes = options["max-bytes"]?.toULong() ?: 1_073_741_824uL
            require(maxBytes in 1uL..Long.MAX_VALUE.toULong()) { "Invalid byte budget" }
            val context = Context(Limits(maxBytes, maxBytes), progress = ProgressReceiver { log.progress(it.stage.name, it.completed, it.total) })
            fun file(name: String): BinarySource = platform.openSource(required(name), context).also { sources += it }
            fun source(): SourceSet = if (options.containsKey("pair-video")) SourceSet.Pair(file("input"), file("pair-video")) else SourceSet.Single(file("input"))
            fun target() = ProtocolSelector(ProtocolId(required("target")), options["profile"]?.let(::ProfileId))
            fun destination(): OutputTransaction = platform.output(required("output-dir"), context).also { output = it; onOutput(it) }.transaction
            fun position(prefix: String = ""): CoverPosition {
                require(options.containsKey("${prefix}frame-index") xor options.containsKey("${prefix}time-us")) { "Choose exactly one of --${prefix}frame-index or --${prefix}time-us" }
                require(!options.containsKey("${prefix}track-id") || options.containsKey("${prefix}frame-index")) { "--${prefix}track-id requires --${prefix}frame-index" }
                return options["${prefix}frame-index"]?.let { CoverPosition.FrameIndex(it.toULong(), options["${prefix}track-id"]?.let(::TrackId)) }
                    ?: CoverPosition.Timestamp(Time(required("${prefix}time-us").toLong(), 1_000_000u))
            }
            fun edits(): EditSpec? {
                val trim = if (options.keys.any { it in trimKeys }) TrimSpec(TimeRange(Time(required("start-us").toLong(), 1_000_000u), Time(required("end-us").toLong(), 1_000_000u)),
                    mode = options["mode"]?.let(TrimMode::valueOf) ?: TrimMode.LosslessPreferred,
                    keyOutside = options["key-outside"]?.let(KeyOutsidePolicy::valueOf) ?: KeyOutsidePolicy.Reject) else null
                val key = if (options.keys.any { it in positionKeys }) position() else null
                val replacement = if (options.keys.any { it in replacementKeys }) position("replacement-") else null
                return if (trim == null && key == null && replacement == null) null else EditSpec(trim = trim, keyPosition = key, replacementFrame = replacement)
            }
            val policy = MutationPolicy(preservation = if (options.containsKey("strict")) PreservationPolicy.Strict else PreservationPolicy.BestEffortWithReport,
                loss = if ("replace-oplus-user-comment" in options) LossPolicy.AllowListed else LossPolicy.RejectUnrequested,
                allowedLosses = if ("replace-oplus-user-comment" in options) listOf(MetadataLoss.OplusCameraUserComment, MetadataLoss.OplusAsciiCameraUserComment) else emptyList(),
                transcode = if (options.containsKey("allow-transcode")) TranscodePolicy.Explicit else TranscodePolicy.Forbid,
                conflicts = if (command == "repair" && "authority" in options) ConflictPolicy.ExplicitAuthority else ConflictPolicy.Reject,
                authority = if (command == "repair") options["authority"]?.let(::EvidenceId) else null)
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
                "create" -> core.create(CreateRequest(file("image"), file("video"), target(), edits = edits(), policy = policy, output = destination(), context = context))
                "convert" -> core.convert(ConvertRequest(source(), target(), edits = edits(), sameTarget = options["same-target"]?.let(SameTargetPolicy::valueOf) ?: SameTargetPolicy.PreserveAsIs, policy = policy, output = destination(), context = context))
                "extract" -> core.extract(ExtractRequest(source(), options["resources"]?.split(',')?.map(::ResourceId) ?: emptyList(), includeRawCarrier = options.containsKey("raw-carrier"), output = destination(), context = context))
                "split" -> core.split(SplitRequest(source(), policy = policy, output = destination(), context = context))
                "repair" -> {
                    require(options.containsKey("apply") || !options.containsKey("output-dir")) { "Repair preview does not accept --output-dir; use --apply" }
                    core.repair(RepairRequest(source(), mode = options["mode"]?.let(RepairMode::valueOf) ?: RepairMode.SafeMetadataOnly,
                        allowedIssueCodes = options["issues"]?.split(',')?.map(::IssueCode) ?: emptyList(), authority = options["authority"]?.let(::EvidenceId),
                        dryRun = !options.containsKey("apply"), policy = policy, output = if (options.containsKey("apply")) destination() else null, context = context))
                }
                "set-key" -> core.setKeyPhotoPosition(SetKeyRequest(source(), position(), policy, destination(), context))
                "extract-frame" -> core.extractFrame(ExtractFrameRequest(ResourceRef(source(), options["resource"]?.let(::ResourceId)), position(), ImageEncoding(ImageFormat.valueOf(required("format")), quality = imageQuality), destination(), context))
                "replace-cover" -> core.replacePrimaryImageFromFrame(ReplaceRequest(source(), position(), ImageEncoding(ImageFormat.valueOf(required("format")), quality = imageQuality), updateKeyPosition = "update-key" in options, policy = policy, output = destination(), context = context))
                "trim" -> core.trim(TrimRequest(ResourceRef(source(), options["resource"]?.let(::ResourceId)), TrimSpec(TimeRange(Time(required("start-us").toLong(), 1_000_000u), Time(required("end-us").toLong(), 1_000_000u)), mode = options["mode"]?.let(TrimMode::valueOf) ?: TrimMode.LosslessPreferred), policy, destination(), context))
                "remux" -> core.remux(RemuxRequest(ResourceRef(source(), options["resource"]?.let(::ResourceId)), VideoContainer.valueOf(required("container")), policy, destination(), context))
                else -> core.transcode(TranscodeRequest(ResourceRef(source()), VideoEncoding(VideoCodec.valueOf(required("codec")), VideoContainer.valueOf(required("container"))), policy, destination(), context))
            }
            emit(CliJson.encode(result, output?.assetPath))
            if (result is CoreResult.Failure) log.failure(result.error.code.value, result.error.stage.name)
            log.debug("operation=$command event=result status=${if (result is CoreResult.Failure) "failure" else "success"}")
            return when (result) {
                is CoreResult.Failure -> 3
                is CoreResult.Success -> when (val value = result.value) {
                    is ValidationReport -> if (value.verdict == Verdict.Invalid) 4 else 0
                    is AnalysisResult -> if (value.validation.verdict == Verdict.Invalid) 4 else 0
                    is RepairResult -> if (value.blocked.isNotEmpty()) 4 else 0
                    else -> 0
                }
            }
        } catch (e: CliAdapterFailure) {
            log.failure(e.error.code.value, e.error.stage.name)
            emit(CliJson.encode(CoreResult.Failure(e.error)))
            return 3
        } catch (e: IllegalArgumentException) {
            log.exception(e)
            emit(CliJson.encode(mapOf("error" to mapOf("code" to "INVALID_ARGUMENT", "message" to e.message))))
            return 2
        } catch (e: Exception) {
            log.exception(e)
            emit(CliJson.encode(mapOf("error" to mapOf("code" to "UNEXPECTED_ERROR", "message" to "Unexpected failure; enable trace diagnostics for redacted call frames"))))
            return 3
        }
    }
}

private fun errorArgument(message: String): Nothing = throw IllegalArgumentException(message)
