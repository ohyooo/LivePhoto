package livephoto.cli.shared

import livephoto.core.*
import kotlin.test.*

class CliLifecycleTest {
    private val createArgs = listOf("create", "--image", "image", "--video", "video",
        "--target", "google.motionphoto.v2", "--output-dir", "output", "--max-bytes", "12345")
    private val failingCore = object : LivePhotoCore by DefaultLivePhotoCore() {
        override suspend fun create(request: CreateRequest): CoreResult<OperationResult> =
            CoreResult.Failure(CoreError(IssueCode("SENTINEL"), Stage.Plan, "test"))
    }

    @Test fun allInputsReceiveTheSameOperationBudgetAndFinishPrecedesEmission(): Unit = synchronous {
        val contexts = mutableListOf<Context>()
        var finishes = 0
        val platform = object : TestPlatform() {
            override fun openSource(path: String, context: Context): BinarySource {
                contexts += context
                return super.openSource(path, context)
            }
            override fun finish(): CoreResult<Unit> {
                finishes++
                opened.forEach { source -> assertIs<CoreResult.Failure>(synchronous { source.size() }) }
                return CoreResult.Success(Unit)
            }
        }
        assertEquals(3, CliEngine(platform, failingCore).run(createArgs) { assertEquals(1, finishes) })
        assertEquals(2, contexts.size)
        assertSame(contexts[0], contexts[1])
        assertEquals(12345uL, contexts[0].limits.maxSpoolBytes)
        assertEquals(1, finishes)
    }

    @Test fun failedCloseStillClosesRemainingInputsAbortsOutputAndFinishes(): Unit = synchronous {
        var finishes = 0
        val platform = object : TestPlatform() {
            override fun openSource(path: String, context: Context): BinarySource {
                val source = super.openSource(path, context)
                return if (path == "image") object : BinarySource by source {
                    override suspend fun close() { source.close(); error("private-path") }
                } else source
            }
            override fun finish(): CoreResult<Unit> { finishes++; return CoreResult.Success(Unit) }
        }
        var result = ""
        assertEquals(3, CliEngine(platform, failingCore).run(createArgs) { result = it })
        assertEquals(1, finishes)
        platform.opened.forEach { assertIs<CoreResult.Failure>(it.size()) }
        assertEquals(TransactionState.Aborted, assertIs<CoreResult.Success<Receipt>>(platform.transactions.single().query()).value.state)
        assertTrue(result.contains("IO_READ_FAILED"))
        assertFalse(result.contains("private-path"))
    }

    @Test fun failedAcquisitionStillFinishesAndClosesPreviouslyBorrowedSource(): Unit = synchronous {
        var finishes = 0
        val platform = object : TestPlatform() {
            override fun openSource(path: String, context: Context): BinarySource {
                if (path == "video") error("private-path")
                return super.openSource(path, context)
            }
            override fun finish(): CoreResult<Unit> { finishes++; return CoreResult.Success(Unit) }
        }
        assertEquals(3, CliEngine(platform, failingCore).run(createArgs) {})
        assertEquals(1, finishes)
        assertIs<CoreResult.Failure>(platform.opened.single().size())
    }

    @Test fun failedSessionCleanupCannotEmitSuccessAndEngineRemainsReusable(): Unit = synchronous {
        var finishes = 0
        val platform = object : TestPlatform() {
            override fun finish(): CoreResult<Unit> {
                finishes++
                return if (finishes == 1) CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.Read,
                    "Snapshot cleanup failed")) else CoreResult.Success(Unit)
            }
        }
        val engine = CliEngine(platform)
        val messages = mutableListOf<String>()
        assertEquals(3, engine.run(listOf("--version"), messages::add))
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("IO_WRITE_FAILED"))
        assertFalse(messages.single().contains("LivePhoto 0.1.0"))
        messages.clear()
        assertEquals(0, engine.run(listOf("--version"), messages::add))
        assertEquals(listOf("LivePhoto 0.1.0"), messages)
        assertEquals(2, finishes)
    }
}
