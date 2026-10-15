package livephoto.adapters.process

import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.TimeSource

internal expect fun fixtureExecutable(): String

class ProcessTest {
    private suspend fun run(mode: String, vararg args: String): ProcessResult =
        NativeProcess.run(listOf(fixtureExecutable(), mode) + args, timeoutMillis = 10_000)

    @Test fun literalArgumentsNeverPassThroughShell() = runBlocking<Unit> {
        val args = listOf("", "中文 space", "a\"b", "trailing\\", "two\\\\\"quotes", "$(echo unexpected)", "; & | > < %PATH%")
        val result = run("echo", *args.toTypedArray())
        assertEquals(ProcessStatus.Exited, result.status)
        assertEquals(0, result.exitCode)
        assertEquals(args.joinToString("") { "${it.encodeToByteArray().size}:$it\n" }, result.diagnostics)
    }
    @Test fun stdoutAndStderrAreBothDrained() = runBlocking<Unit> {
        val result = run("streams")
        assertEquals(ProcessStatus.Exited, result.status)
        assertTrue(result.diagnostics.contains("stdout-marker"))
        assertTrue(result.diagnostics.contains("stderr-marker"))
    }
    @Test fun nonzeroExitIsNotStartFailure() = runBlocking<Unit> {
        val result = run("fail")
        assertEquals(ProcessStatus.Exited, result.status)
        assertEquals(7, result.exitCode)
    }
    @Test fun missingExecutableIsStructured() = runBlocking<Unit> {
        val result = NativeProcess.run(listOf(fixtureExecutable() + "-missing.exe"))
        assertEquals(ProcessStatus.StartFailed, result.status)
        assertEquals("", result.diagnostics)
    }
    @Test fun relativeExecutableRejected() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { NativeProcess.run(listOf("fixture.exe")) }
    }
    @Test fun nulArgumentRejected() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { run("echo", "bad\u0000argument") }
    }
    @Test fun invalidBudgetsRejected() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { NativeProcess.run(listOf(fixtureExecutable()), timeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { NativeProcess.run(listOf(fixtureExecutable()), maxDiagnosticBytes = 65_537) }
    }
    @Test fun stdinIsClosed() = runBlocking<Unit> {
        assertEquals(0, run("stdin").exitCode)
    }
    @Test fun timeoutStopsTheRealChild() = runBlocking<Unit> {
        val start = TimeSource.Monotonic.markNow()
        val result = NativeProcess.run(listOf(fixtureExecutable(), "hold"), timeoutMillis = 150)
        assertEquals(ProcessStatus.TimedOut, result.status)
        assertNotNull(result.exitCode)
        assertTrue(start.elapsedNow().inWholeMilliseconds < 3500)
    }
    @Test fun preCancelledDoesNotStart() = runBlocking<Unit> {
        val result = NativeProcess.run(listOf(fixtureExecutable(), "hold"), cancelled = { true })
        assertEquals(ProcessStatus.Cancelled, result.status)
        assertNull(result.exitCode)
    }
    @Test fun callbackCancellationReapsChild() = runBlocking<Unit> {
        var polls = 0
        val result = NativeProcess.run(listOf(fixtureExecutable(), "hold"), cancelled = { ++polls >= 5 })
        assertEquals(ProcessStatus.Cancelled, result.status)
        assertNotNull(result.exitCode)
    }
    @Test fun coroutineCancellationCleansBeforePropagation() = runBlocking<Unit> {
        var polls = 0
        val task = async { NativeProcess.run(listOf(fixtureExecutable(), "hold"), cancelled = { polls++; false }) }
        withTimeout(5000) { while (polls < 3) delay(10) }
        val start = TimeSource.Monotonic.markNow()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(start.elapsedNow().inWholeMilliseconds < 3500)
        assertEquals(ProcessStatus.Exited, run("streams").status)
    }
    @Test fun noisyProcessHasBoundedDiagnostics() = runBlocking<Unit> {
        val result = NativeProcess.run(listOf(fixtureExecutable(), "flood"), maxDiagnosticBytes = 1024)
        assertEquals(ProcessStatus.OutputLimit, result.status)
        assertEquals(1024, result.diagnostics.encodeToByteArray().size)
        assertNotNull(result.exitCode)
    }
    @Test fun repeatedRunsReleasePipeAndProcessResources() = runBlocking<Unit> {
        repeat(20) { assertEquals(ProcessStatus.Exited, run("streams").status) }
    }
    @Test fun descendantsCannotKeepPipeOpenAfterParentExit() = runBlocking<Unit> {
        val start = TimeSource.Monotonic.markNow()
        val result = run("descendant")
        assertEquals(ProcessStatus.Exited, result.status)
        assertEquals(0, result.exitCode)
        assertTrue(start.elapsedNow().inWholeMilliseconds < 3500)
    }
    @Test fun windowsQuotingHandlesEmptyQuoteAndTrailingBackslash() {
        assertEquals("\"\" \"a b\" \"a\\\"b\" \"c\\\\\"", windowsCommandLine(listOf("", "a b", "a\"b", "c\\")))
    }
}
