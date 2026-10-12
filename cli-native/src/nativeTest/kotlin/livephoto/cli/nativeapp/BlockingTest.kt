package livephoto.cli.nativeapp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class BlockingTest {
    @Test fun nativeRuntimeWaitsForAnActuallySuspendingOperation() {
        assertEquals(42, runBlocking { delay(10); 42 })
    }
}
