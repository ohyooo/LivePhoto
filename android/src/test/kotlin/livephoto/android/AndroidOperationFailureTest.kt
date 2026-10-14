package livephoto.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AndroidOperationFailureTest {
    @Test fun adapterExceptionKeepsSpecificInnerStepAndOriginalCause() = runBlocking<Unit> {
        val cause = SecurityException("permission revoked")
        val failure = assertFailsWith<AndroidOperationFailure> {
            androidOperation("invoke-core", "request") {
                androidOperation("read-video", "video") { throw cause }
            }
        }
        assertEquals("read-video", failure.step)
        assertEquals("video", failure.inputRole)
        assertSame(cause, failure.cause)
    }

    @Test fun cancellationIsNeverRewrittenAsAFileFailure() = runBlocking<Unit> {
        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            androidOperation("save-output", "output") { throw cancelled }
        })
    }
}
