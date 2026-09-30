package livephoto.core.memory

import livephoto.core.*
import kotlin.test.*

class MemoryIoTest {
    private fun context(output: ULong = 1024uL, cancellation: Cancellation? = null) =
        Context(Limits(1024uL, output), cancellation = cancellation)
    private fun spec(role: AssetRole = AssetRole.PrimaryImage) = OutputAssetSpec(role, mime = "application/octet-stream")

    @Test
    fun sourceCopiesCallerBytesAndSupportsShortEofReadsAndExplicitClose(): Unit = runImmediate {
        val caller = byteArrayOf(1, 2, 3)
        val source = MemoryBinarySource(Bytes(caller), SourceId("memory"), GenerationToken("g1"))
        caller.fill(9)
        val identity = value(source.identity())
        assertEquals(SourceId("memory"), identity.id)
        assertEquals(GenerationToken("g1"), identity.generation)
        assertEquals(3uL, identity.size)
        assertEquals(Bytes(byteArrayOf(2, 3)), value(source.readAt(1uL, UInt.MAX_VALUE)))
        assertEquals(Bytes(byteArrayOf()), value(source.readAt(3uL, 10u)))
        failure("OFFSET_OUT_OF_BOUNDS", source.readAt(4uL, 0u))
        val returned = value(source.readAt(0uL, 3u)).toByteArray()
        returned.fill(8)
        assertEquals(Bytes(byteArrayOf(1, 2, 3)), value(source.readAt(0uL, 3u)))
        source.close()
        failure("IO_READ_FAILED", source.size())
        failure("IO_READ_FAILED", source.identity())
        failure("IO_READ_FAILED", source.readAt(0uL, 0u))
    }

    @Test
    fun defaultMemoryIdentityDistinguishesSameIdAndSizeWithDifferentContent(): Unit = runImmediate {
        val id = SourceId("stable-provider-id")
        val first = value(MemoryBinarySource(Bytes(byteArrayOf(1, 2, 3)), id).identity())
        val different = value(MemoryBinarySource(Bytes(byteArrayOf(1, 2, 4)), id).identity())
        val same = value(MemoryBinarySource(Bytes(byteArrayOf(1, 2, 3)), id).identity())
        assertEquals(first, same)
        assertEquals(first.id, different.id)
        assertEquals(first.size, different.size)
        assertTrue(first.generation != different.generation || first.digest != different.digest)
        val explicitFirst = value(MemoryBinarySource(Bytes(byteArrayOf(1, 2, 3)), id, GenerationToken("same-provided-generation")).identity())
        val explicitDifferent = value(MemoryBinarySource(Bytes(byteArrayOf(1, 2, 4)), id, GenerationToken("same-provided-generation")).identity())
        assertEquals(explicitFirst.generation, explicitDifferent.generation)
        assertNotEquals(explicitFirst.digest, explicitDifferent.digest)
    }

    @Test
    fun assetSetRemainsInvisibleUntilAtomicCommitAndPrepareFreezesWriters(): Unit = runImmediate {
        val transaction = MemoryOutputTransaction(context(), "atomic-two")
        val first = value(transaction.create(spec()))
        val second = value(transaction.create(spec(AssetRole.MotionVideo)))
        value(first.sink.write(Bytes(byteArrayOf(1, 2))))
        value(second.sink.write(Bytes(byteArrayOf(3, 4, 5))))
        assertTrue(transaction.committedAssets().isEmpty())
        assertEquals(TransactionState.Open, value(transaction.query()).state)
        failure("INVALID_ARGUMENT", transaction.openStaged(first.id))
        value(transaction.prepare())
        assertTrue(transaction.committedAssets().isEmpty())
        failure("INVALID_ARGUMENT", first.sink.write(Bytes(byteArrayOf(9))))
        failure("INVALID_ARGUMENT", first.sink.seek(0uL))
        failure("INVALID_ARGUMENT", transaction.create(spec()))
        val stagedFirst = value(transaction.openStaged(first.id))
        val stagedSecond = value(transaction.openStaged(second.id))
        assertEquals(Bytes(byteArrayOf(1, 2)), value(stagedFirst.readAt(0uL, 10u)))
        assertEquals(Bytes(byteArrayOf(3, 4, 5)), value(stagedSecond.readAt(0uL, 10u)))
        val receipt = value(transaction.commit())
        assertEquals(TransactionState.Committed, receipt.state)
        assertEquals(setOf(first.id, second.id), receipt.assetIds.toSet())
        assertEquals(Atomicity.AssetSetRequired, receipt.atomicity)
        assertEquals("volatile-process-memory", receipt.durability)
        assertEquals(mapOf(first.id to Bytes(byteArrayOf(1, 2)), second.id to Bytes(byteArrayOf(3, 4, 5))), transaction.committedAssets())
        assertEquals(TransactionState.Committed, value(transaction.query()).state)
        failure("INVALID_ARGUMENT", transaction.abort())
        failure("INVALID_ARGUMENT", transaction.commit())
        stagedFirst.close(); stagedSecond.close()
        assertEquals(Bytes(byteArrayOf(1, 2)), transaction.committedAssets()[first.id])
    }

    @Test
    fun abortedOrInvalidTransactionsNeverPublishPartialAssets(): Unit = runImmediate {
        val empty = MemoryOutputTransaction(context(), "empty")
        failure("INVALID_ARGUMENT", empty.prepare())
        failure("INVALID_ARGUMENT", empty.commit())
        val transaction = MemoryOutputTransaction(context(), "abort")
        val handle = value(transaction.create(spec()))
        value(handle.sink.write(Bytes(byteArrayOf(1))))
        value(transaction.prepare())
        value(transaction.abort())
        assertEquals(TransactionState.Aborted, value(transaction.query()).state)
        assertTrue(value(transaction.query()).assetIds.isEmpty())
        assertTrue(transaction.committedAssets().isEmpty())
        failure("INVALID_ARGUMENT", transaction.openStaged(handle.id))
        failure("INVALID_ARGUMENT", transaction.commit())
        failure("INVALID_ARGUMENT", handle.sink.write(Bytes(byteArrayOf(2))))
    }

    @Test
    fun stagedReadHandlesHaveIndependentLifetimeAndReturnedMapCannotChangePublication(): Unit = runImmediate {
        val transaction = MemoryOutputTransaction(context(), "isolated")
        val handle = value(transaction.create(spec()))
        value(handle.sink.write(Bytes(byteArrayOf(1, 2, 3))))
        value(handle.sink.close())
        value(transaction.prepare())
        val first = value(transaction.openStaged(handle.id))
        val second = value(transaction.openStaged(handle.id))
        first.close()
        assertEquals(Bytes(byteArrayOf(1, 2, 3)), value(second.readAt(0uL, 3u)))
        value(transaction.commit())
        val external = transaction.committedAssets()
        (external as? MutableMap<AssetId, Bytes>)?.let { runCatching { it.clear() } }
        assertEquals(Bytes(byteArrayOf(1, 2, 3)), transaction.committedAssets()[handle.id])
        second.close()
    }

    @Test
    fun seekAndTruncatePreserveContentAndZeroNewHoles(): Unit = runImmediate {
        val transaction = MemoryOutputTransaction(context(), "seek-truncate")
        val sink = value(transaction.create(spec())).sink
        value(sink.write(Bytes(byteArrayOf(1, 2, 3, 4))))
        value(sink.truncate(2uL))
        value(sink.seek(4uL))
        value(sink.write(Bytes(byteArrayOf(8))))
        value(sink.seek(1uL))
        value(sink.write(Bytes(byteArrayOf(9))))
        value(sink.truncate(7uL))
        value(transaction.prepare())
        value(transaction.commit())
        assertEquals(Bytes(byteArrayOf(1, 9, 0, 0, 8, 0, 0)), transaction.committedAssets().values.single())
    }

    @Test
    fun aggregateBudgetCoversAllAssetsAndFailedGrowthLeavesExistingBytesIntact(): Unit = runImmediate {
        val transaction = MemoryOutputTransaction(context(output = 4uL), "budget")
        val first = value(transaction.create(spec()))
        val second = value(transaction.create(spec()))
        value(first.sink.write(Bytes(byteArrayOf(1, 2, 3))))
        failure("RESOURCE_LIMIT_EXCEEDED", second.sink.write(Bytes(byteArrayOf(8, 9))))
        value(second.sink.write(Bytes(byteArrayOf(4))))
        failure("RESOURCE_LIMIT_EXCEEDED", first.sink.truncate(4uL))
        value(first.sink.truncate(2uL))
        value(second.sink.write(Bytes(byteArrayOf(5))))
        value(transaction.prepare()); value(transaction.commit())
        assertEquals(Bytes(byteArrayOf(1, 2)), transaction.committedAssets()[first.id])
        assertEquals(Bytes(byteArrayOf(4, 5)), transaction.committedAssets()[second.id])
    }

    @Test
    fun cancellationBeforeCommitDoesNotPublishAndAbortStillWorks(): Unit = runImmediate {
        var cancelled = false
        val transaction = MemoryOutputTransaction(context(cancellation = Cancellation { cancelled }), "cancel")
        val handle = value(transaction.create(spec()))
        value(handle.sink.write(Bytes(byteArrayOf(1))))
        value(transaction.prepare())
        cancelled = true
        failure("CANCELLED", transaction.commit())
        assertEquals(TransactionState.Prepared, value(transaction.query()).state)
        assertTrue(transaction.committedAssets().isEmpty())
        value(transaction.abort())
        assertEquals(TransactionState.Aborted, value(transaction.query()).state)
    }

    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
    private fun failure(code: String, result: CoreResult<*>) { assertEquals(IssueCode(code), assertIs<CoreResult.Failure>(result).error.code) }
}
