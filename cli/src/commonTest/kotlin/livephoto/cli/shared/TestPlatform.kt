package livephoto.cli.shared

import livephoto.core.*
import livephoto.core.memory.*
import kotlin.coroutines.*

/** Test doubles only, deliberately no OS files or media tools. */
internal open class TestPlatform : CliPlatform {
    val opened = mutableListOf<BinarySource>()
    val transactions = mutableListOf<MemoryOutputTransaction>()
    var environmentLevel: String? = null
    override fun openSource(path: String, context: Context): BinarySource =
        MemoryBinarySource(Bytes(byteArrayOf()), SourceId(path), GenerationToken("test")).also { opened += it }
    override fun output(path: String, context: Context): CliOutput {
        val transaction = MemoryOutputTransaction(context, "test-output")
        transactions += transaction
        return CliOutput(transaction) { "$path/${it.value}" }
    }
    override suspend fun discover(ffmpegPath: String?): CliDiscovery = CliDiscovery(null, null, emptyList())
    override fun environment(name: String): String? = environmentLevel
    override fun nanoTime(): Long = 0L
    override fun error(message: String) {}
    override fun exceptionType(error: Throwable): String = when (error) {
        is IllegalArgumentException -> "IllegalArgumentException"
        is IllegalStateException -> "IllegalStateException"
        else -> "Exception"
    }
    override fun exceptionFrames(error: Throwable): List<DiagnosticFrame> =
        listOf(DiagnosticFrame("test.Adapter", "call", 1))
}

internal class TestCli(
    providedCore: LivePhotoCore? = null,
    discover: (String?) -> CliDiscovery = { CliDiscovery(null, null, emptyList()) },
    diagnostic: (String) -> Unit = {},
) {
    private val discoverBackend = discover
    private val engine = CliEngine(object : TestPlatform() {
        override suspend fun discover(ffmpegPath: String?): CliDiscovery = discoverBackend(ffmpegPath)
    }, providedCore, diagnostic)
    suspend fun run(args: List<String>, emit: (String) -> Unit): Int = engine.run(args, emit)
}

/** All test adapters complete synchronously; never fake an async blocking runtime. */
internal fun <T> synchronous(block: suspend () -> T): T {
    var completed: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { completed = result }
    })
    return checkNotNull(completed) { "Test unexpectedly suspended" }.getOrThrow()
}
