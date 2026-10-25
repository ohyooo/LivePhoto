package livephoto.core

import livephoto.core.memory.MemoryOutputTransaction
import kotlin.test.*

class StagedOutputTransactionTest {
    private val spec = OutputAssetSpec(AssetRole.Composite, mime = "application/octet-stream")
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
    private fun failure(code: String, result: CoreResult<*>) { assertEquals(code, assertIs<CoreResult.Failure>(result).error.code.value) }
    private fun files(failSecond: Boolean = false): StagedFileFactory {
        var count = 0
        return StagedFileFactory { _, spec ->
            val index = count++
            val memory = MemoryOutputTransaction(Context(Limits(4096uL, 4096uL)), "file-$index")
            val handle = value(memory.create(spec))
            CoreResult.Success(object : StagedFile {
                override val sink: BinarySink = handle.sink
                override suspend fun freeze(): CoreResult<Unit> = if (failSecond && index == 1)
                    CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.Verify, "Synthetic freeze failure")) else memory.prepare()
                override suspend fun openSource(): CoreResult<BinarySource> = memory.openStaged(handle.id)
            })
        }
    }
    @Test fun preparedFilesFreezeWritersAndProvideIndependentReadersBeforeAtomicCommit(): Unit = runImmediate {
        val output = StagedOutputTransaction(Context(Limits(64uL, 64uL)), "test", files())
        val first = value(output.create(spec)); val second = value(output.create(spec))
        value(first.sink.write(Bytes(byteArrayOf(1, 2, 3))))
        value(first.sink.seek(1uL)); value(first.sink.write(Bytes(byteArrayOf(9))))
        value(second.sink.write(Bytes(byteArrayOf(4))))
        failure("INVALID_ARGUMENT", output.openStaged(first.id))
        value(output.prepare())
        failure("INVALID_ARGUMENT", first.sink.write(Bytes(byteArrayOf(8))))
        assertFalse(output.isCommitted(first.id))
        val reader = value(output.openStaged(first.id)); val other = value(output.openStaged(first.id))
        assertEquals(Bytes(byteArrayOf(1, 9, 3)), value(reader.readAt(0uL, 64u)))
        reader.close()
        assertEquals(Bytes(byteArrayOf(9, 3)), value(other.readAt(1uL, 64u)))
        val receipt = value(output.commit())
        assertEquals(listOf(first.id, second.id), receipt.assetIds)
        assertEquals(Atomicity.AssetSetRequired, receipt.atomicity)
        assertTrue(output.isCommitted(first.id)); assertTrue(output.isCommitted(second.id))
        failure("INVALID_ARGUMENT", output.abort())
        other.close()
    }
    @Test fun aggregateBudgetTruncateAndCancellationDoNotPublishPartialFiles(): Unit = runImmediate {
        var cancelled = false
        val output = StagedOutputTransaction(Context(Limits(64uL, 4uL), cancellation = Cancellation { cancelled }), "budget", files())
        val first = value(output.create(spec)); val second = value(output.create(spec))
        value(first.sink.write(Bytes(byteArrayOf(1, 2, 3))))
        failure("RESOURCE_LIMIT_EXCEEDED", second.sink.write(Bytes(byteArrayOf(4, 5))))
        value(first.sink.truncate(2uL)); value(second.sink.write(Bytes(byteArrayOf(4, 5))))
        value(output.prepare()); cancelled = true
        failure("CANCELLED", output.commit())
        assertFalse(output.isCommitted(first.id))
        value(output.abort()); assertEquals(TransactionState.Aborted, value(output.query()).state)
    }
    @Test fun failedSecondFreezeBlocksReadbackAndCommitOfTheWholeSet(): Unit = runImmediate {
        val output = StagedOutputTransaction(Context(Limits(64uL, 64uL)), "failed-freeze", files(failSecond = true))
        val first = value(output.create(spec)); value(output.create(spec))
        value(first.sink.write(Bytes(byteArrayOf(1))))
        failure("IO_WRITE_FAILED", output.prepare())
        failure("INVALID_ARGUMENT", output.openStaged(first.id)); failure("INVALID_ARGUMENT", output.commit())
        assertFalse(output.isCommitted(first.id)); value(output.abort())
        failure("INVALID_ARGUMENT", output.create(spec))
    }
}
