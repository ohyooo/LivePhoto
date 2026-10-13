@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package livephoto.cli.nativeapp

import kotlinx.coroutines.runBlocking
import livephoto.cli.shared.*
import livephoto.core.*
import platform.posix.fputs
import platform.posix.stderr
import kotlin.system.exitProcess
import kotlin.time.TimeSource

internal expect fun hostEnvironment(name: String): String?
internal expect fun hostArguments(fallback: Array<String>): CoreResult<List<String>>

private class NativePlatform : FileCliPlatform({ hostEnvironment("LIVEPHOTO_WORKSPACE") ?: "" }) {
    private val start = TimeSource.Monotonic.markNow()
    override fun environment(name: String): String? = hostEnvironment(name)
    override fun nanoTime(): Long = start.elapsedNow().inWholeNanoseconds
    override fun error(message: String) { fputs(message + "\n", stderr) }
    override fun exceptionType(error: Throwable): String = error::class.simpleName ?: "Exception"
    // Native textual stack dumps can include paths and messages. Do not expose them as safe frames.
    override fun exceptionFrames(error: Throwable): List<DiagnosticFrame> = emptyList()
}

fun main(args: Array<String>) {
    val decoded = hostArguments(args)
    if (decoded is CoreResult.Failure) {
        println(CliJson.encode(decoded))
        exitProcess(3)
    }
    val status = runBlocking { CliEngine(NativePlatform()).run((decoded as CoreResult.Success).value, ::println) }
    exitProcess(status)
}
