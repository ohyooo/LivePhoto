package livephoto.core.jvm

import livephoto.core.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real subprocess tests using the already-running JDK, never a downloaded Java runtime. */
class ExternalProcessTest {
    private fun command(vararg arguments: String): List<String> {
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val classes = listOf(ProcessFixture::class.java, Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        return listOf(java.toString(), "-cp", classes.joinToString(File.pathSeparator), ProcessFixture::class.java.name) + arguments
    }
    @Test fun argumentVectorPreservesSpacesAndShellMetacharacters() {
        val argument = "space & ; literal dollar \$HOME"
        val result = ExternalProcess.run(command("echo", argument), 10_000)
        assertEquals(0, result.code); assertEquals(argument, result.output.trim()); assertFalse(result.outputLimited)
        assertFalse(result.ioFailed)
    }
    @Test fun unsuccessfulExitRetainsDiagnosticsAfterProcessStops() {
        val result = ExternalProcess.run(command("failure"), 10_000)
        assertEquals(7, result.code)
        assertEquals("fixture failure", result.output.trim())
        assertFalse(result.ioFailed)
    }
    @Test fun noisyProcessIsBoundedWithoutPipeDeadlock() {
        val result = ExternalProcess.run(command("flood"), 10_000)
        assertTrue(result.outputLimited); assertEquals(65_536, result.output.toByteArray().size)
    }
    @Test fun deadlineTerminatesProcess() {
        val result = ExternalProcess.run(command("sleep"), 150)
        assertTrue(result.timedOut)
    }
    @Test fun cancellationIsObservedWhileProcessRuns() {
        val polls = AtomicInteger()
        val context = Context(Limits(1uL, 1uL), Cancellation { polls.incrementAndGet() > 3 })
        assertTrue(ExternalProcess.run(command("sleep"), 10_000, context).cancelled)
    }
    @Test fun missingExecutableIsStructuredAndDoesNotInvokeAShell() {
        val result = ExternalProcess.run(listOf("livephoto-definitely-nonexistent-binary", "&&", "echo"), 100)
        assertNull(result.code); assertEquals("", result.output)
    }
}

internal object ProcessFixture {
    @JvmStatic fun main(args: Array<String>) {
        when (args[0]) {
            "echo" -> println(args[1])
            "flood" -> repeat(100_000) { System.out.print("0123456789") }
            "sleep" -> Thread.sleep(30_000)
            "failure" -> { System.err.println("fixture failure"); kotlin.system.exitProcess(7) }
        }
    }
}
