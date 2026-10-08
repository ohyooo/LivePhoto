package livephoto.core.jvm

import java.nio.file.Path
import java.io.File
import kotlin.test.*

class WindowsMediaApiPreflightTest {
    private fun run(args: List<String>, native: Boolean = false): ProcessResult {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val javaPath = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java")
        val classes = listOf(WindowsMediaApiWorker::class.java, Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
        return ExternalProcess.run(listOf(javaPath.toString()) + (if (native) listOf("--enable-native-access=ALL-UNNAMED") else emptyList()) +
            listOf("-cp", classes.joinToString(File.pathSeparator), "livephoto.core.jvm.WindowsMediaApiWorker") + args, timeoutMillis = 15_000L)
    }
    @Test fun invalidArgumentsNeverEnterNativeBootstrap() {
        val result = run(listOf("--not-an-operation"), true)
        assertEquals(2, result.code); assertTrue(result.output.contains("WINDOWS_MEDIA_API_PREFLIGHT=INVALID_ARGUMENT"))
        assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
    }
    @Test fun nativeAccessMustBeExplicitAndIsNotEnabledInTheParentProcess() {
        val result = run(listOf("--preflight"))
        assertEquals(3, result.code); assertTrue(result.output.contains("WINDOWS_MEDIA_API_PREFLIGHT=UNAVAILABLE"))
        assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
    }
    @Test fun existingWindowsApiStartsAndShutsDownInAnIsolatedExistingRuntime() {
        val result = run(listOf("--preflight"), true)
        assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
        if (System.getProperty("os.name").startsWith("Windows") && System.getenv("LIVEPHOTO_REQUIRE_WINDOWS_MEDIA") == "true") {
            assertEquals(0, result.code, result.output)
            assertTrue(result.output.contains("SUCCESS scope=runtime-bootstrap-not-media-decode"))
        } else {
            assertTrue(result.code in setOf(0, 3), result.output)
            assertTrue(result.output.contains(if (result.code == 0) "WINDOWS_MEDIA_API_PREFLIGHT=SUCCESS" else "WINDOWS_MEDIA_API_PREFLIGHT=UNAVAILABLE"))
        }
    }
    @Test fun decoderEnumerationIsSeparateFromRuntimeBootstrap() {
        val result = run(listOf("--decoder-preflight"), true)
        assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
        if (System.getenv("LIVEPHOTO_REQUIRE_WINDOWS_MEDIA") == "true") {
            assertEquals(0, result.code, result.output)
            assertTrue(result.output.contains("WINDOWS_MEDIA_API_DECODER=SUCCESS scope=registered-software-avc-to-nv12"))
        } else {
            assertTrue(result.code in setOf(0, 3), result.output)
            assertTrue(result.output.contains(if (result.code == 0) "WINDOWS_MEDIA_API_DECODER=SUCCESS" else "UNAVAILABLE"))
        }
    }
}
