package livephoto.cli.shared

import livephoto.core.*
import kotlin.test.*

class SharedDiagnosticsTest {
    @Test fun traceUnexpectedFailureIsRedactedAndDoesNotPolluteJson(): Unit = synchronous {
        val secret = "/private/user/GPS-token-secret.mp4"
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun detect(request: ReadRequest): CoreResult<DetectionResult> = throw IllegalStateException(secret, IllegalArgumentException(secret))
        }
        val logs = mutableListOf<String>(); val output = mutableListOf<String>()
        assertEquals(3, TestCli(core, diagnostic = logs::add).run(listOf("detect", "--input", secret, "--log-level", "trace"), output::add))
        assertEquals(1, output.size); assertTrue(output.single().contains("UNEXPECTED_ERROR"))
        assertTrue(logs.any { it.startsWith("DEBUG operation=detect event=start") })
        assertTrue(logs.any { it.startsWith("TRACE at ") })
        assertTrue(logs.any { it.contains("IllegalStateException") })
        assertTrue(logs.any { it.contains("IllegalArgumentException") })
        assertFalse((output + logs).joinToString().contains(secret))
        assertFalse(logs.joinToString().contains("GPS-token-secret"))
    }
    @Test fun structuredFailureLogsOnlyCodeAndStage(): Unit = synchronous {
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun detect(request: ReadRequest): CoreResult<DetectionResult> = CoreResult.Failure(CoreError(IssueCode("TEST_FAILURE"), Stage.Read, "private exception text"))
        }
        val logs = mutableListOf<String>()
        assertEquals(3, TestCli(core, diagnostic = logs::add).run(listOf("detect", "--input", "not-opened", "--log-level", "error")) {})
        assertEquals(listOf("ERROR code=TEST_FAILURE stage=Read"), logs)
    }
    @Test fun explicitOffAndInvalidLevelsAreHandled(): Unit = synchronous {
        val logs = mutableListOf<String>()
        assertEquals(0, TestCli(diagnostic = logs::add).run(listOf("capabilities", "--target", "google.microvideo.v1", "--log-level", "off")) {})
        assertTrue(logs.isEmpty())
        assertEquals(2, TestCli(diagnostic = logs::add).run(listOf("capabilities", "--target", "google.microvideo.v1", "--log-level", "verbose")) {})
    }
}
