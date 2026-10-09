package livephoto.cli

/** Opt-in stderr diagnostics. Never include arguments, exception messages, paths or media fields. */
internal class Diagnostics(private val sink: (String) -> Unit) {
    private var level = 0
    private var progressEvents = 0
    private fun emit(value: String) { try { sink(value) } catch (_: Exception) { /* Logging cannot change an operation's result. */ } }
    fun progress(stage: String, completed: ULong, total: ULong?) {
        if (progressEvents++ < 128) trace("event=progress stage=${safe(stage)} completed=$completed total=${total ?: "unknown"}")
        else if (progressEvents == 129) trace("event=progress limit=128 suppressed=true")
    }
    fun configure(value: String?) {
        level = when (value?.lowercase() ?: "off") {
            "off" -> 0
            "error" -> 1
            "debug" -> 2
            "trace" -> 3
            else -> throw IllegalArgumentException("Log level must be off, error, debug or trace")
        }
    }
    fun debug(event: String) { if (level >= 2) emit("DEBUG $event") }
    fun trace(event: String) { if (level >= 3) emit("TRACE $event") }
    fun failure(code: String, stage: String) {
        if (level >= 1) emit("ERROR code=${safe(code)} stage=${safe(stage)}")
    }
    fun exception(error: Exception) {
        if (level == 0) return
        var cause: Throwable? = error
        repeat(4) { index ->
            val current = cause ?: return
            emit("ERROR exception[$index]=${safe(current.javaClass.name)} message=redacted")
            if (level >= 3) current.stackTrace.take(32).forEach {
                emit("TRACE at ${safe(it.className)}.${safe(it.methodName)} line=${it.lineNumber}")
            }
            cause = current.cause?.takeUnless { it === current }
        }
    }
    private fun safe(value: String): String = value.filter { it.isLetterOrDigit() || it in "._$-" }.take(160)
}
