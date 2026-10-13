package livephoto.cli

import livephoto.core.*
import livephoto.core.jvm.*
import livephoto.cli.shared.*
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.*
import kotlin.system.exitProcess

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

/** JVM-specific IO and diagnostics only; command behavior is shared with Native. */
internal class Cli(
    providedCore: LivePhotoCore? = null,
    discover: (Path?) -> BackendDiscovery = { JvmMediaBackends.discover(it) },
    diagnostic: (String) -> Unit = { System.err.println(it) },
) {
    private val discoverBackend = discover
    private val engine = CliEngine(object : CliPlatform {
        override fun openSource(path: String, context: Context): BinarySource = FileBinarySource(Path.of(path))
        override fun output(path: String, context: Context): CliOutput {
            val transaction = DirectoryOutputTransaction(Path.of(path), context)
            return CliOutput(transaction) { transaction.assetPath(it).toString() }
        }
        override suspend fun discover(ffmpegPath: String?): CliDiscovery {
            val result = discoverBackend(ffmpegPath?.let(Path::of))
            return CliDiscovery(result.backend, result.ffmpegPath?.toString(), result.issues)
        }
        override fun environment(name: String): String? = System.getenv(name)
        override fun nanoTime(): Long = System.nanoTime()
        override fun error(message: String) { System.err.println(message) }
        override fun exceptionType(error: Throwable): String = error.javaClass.name
        override fun exceptionFrames(error: Throwable): List<DiagnosticFrame> =
            error.stackTrace.take(32).map { DiagnosticFrame(it.className, it.methodName, it.lineNumber) }
    }, providedCore, diagnostic)

    suspend fun run(args: List<String>, emit: (String) -> Unit): Int = engine.run(args, emit)
}
