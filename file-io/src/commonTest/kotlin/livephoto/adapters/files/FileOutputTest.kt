package livephoto.adapters.files

import livephoto.core.*
import okio.*
import kotlin.test.*

class FileOutputTest {
    private val image = OutputAssetSpec(AssetRole.PrimaryImage, "../../unsafe.jpg", "image/jpeg")
    private val video = OutputAssetSpec(AssetRole.MotionVideo, "ignored.mp4", "video/mp4")
    @Test fun twoAssetsBecomeVisibleTogetherAndPreparedReadersSurviveRename() = sandbox { box ->
        val root = box.root / "输出 portable"
        val transaction = FileOutputTransaction(root.toString(), testContext)
        val a = result(transaction.create(image)); val b = result(transaction.create(video))
        result(a.sink.write(Bytes(byteArrayOf(1, 2)))); result(b.sink.write(Bytes(byteArrayOf(3, 4))))
        assertFalse(box.fs.exists(root / "assets"))
        result(transaction.prepare())
        val reader = result(transaction.openStaged(a.id))
        val identity = result(reader.identity())
        failure(a.sink.write(Bytes(byteArrayOf(9))), "INVALID_ARGUMENT")
        val receipt = result(transaction.commit())
        assertEquals(TransactionState.Committed, receipt.state)
        assertEquals(setOf(a.id, b.id), receipt.assetIds.toSet())
        assertFalse(box.fs.exists(root / ".staging"))
        assertContentEquals(byteArrayOf(1, 2), box.contents(root / "assets" / "asset-1.jpg"))
        assertContentEquals(byteArrayOf(3, 4), box.contents(root / "assets" / "asset-2.mp4"))
        assertEquals(identity, result(reader.identity()))
        assertContentEquals(byteArrayOf(1, 2), result(reader.readAt(0uL, 2u)).toByteArray())
        assertFalse(box.fs.exists(box.root / "unsafe.jpg"))
        failure(transaction.abort(), "INVALID_ARGUMENT")
        reader.close()
    }
    @Test fun existingEmptyDirectoryAndFileAreNeverReplacedOrCleaned() = sandbox { box ->
        val input = box.file("original", byteArrayOf(1))
        for (root in listOf(box.root, input)) {
            val transaction = FileOutputTransaction(root.toString(), testContext)
            failure(transaction.create(image), "OUTPUT_EXISTS")
            result(transaction.abort())
            assertTrue(box.fs.exists(root))
        }
        assertContentEquals(byteArrayOf(1), box.contents(input))
    }
    @Test fun aggregateOutputBudgetIsCheckedBeforeSecondAssetWrite() = sandbox { box ->
        val transaction = FileOutputTransaction((box.root / "output").toString(), Context(Limits(100uL, 3uL)))
        val a = result(transaction.create(image)); val b = result(transaction.create(video))
        result(a.sink.write(Bytes(byteArrayOf(1, 2))))
        failure(b.sink.write(Bytes(byteArrayOf(3, 4))), "RESOURCE_LIMIT_EXCEEDED")
        assertEquals(0L, box.fs.metadata(box.root / "output" / ".staging" / "asset-2.mp4").size)
        result(transaction.abort())
        assertFalse(box.fs.exists(box.root / "output"))
    }
    @Test fun seekOverwriteAndShrinkReclaimOnlyActualAccountedBytes() = sandbox { box ->
        val transaction = FileOutputTransaction((box.root / "output").toString(), Context(Limits(100uL, 4uL)))
        val a = result(transaction.create(image))
        result(a.sink.write(Bytes(byteArrayOf(1, 2, 3, 4))))
        result(a.sink.seek(1uL)); result(a.sink.write(Bytes(byteArrayOf(9))))
        result(a.sink.truncate(2uL))
        val b = result(transaction.create(video))
        result(b.sink.write(Bytes(byteArrayOf(5, 6))))
        result(transaction.prepare()); result(transaction.commit())
        assertContentEquals(byteArrayOf(1, 9), box.contents(box.root / "output" / "assets" / "asset-1.jpg"))
        assertContentEquals(byteArrayOf(5, 6), box.contents(box.root / "output" / "assets" / "asset-2.mp4"))
    }
    @Test fun hugeSeekDoesNotAllocateAndOverflowingWriteFails() = sandbox { box ->
        val transaction = FileOutputTransaction((box.root / "output").toString(), testContext)
        val a = result(transaction.create(image))
        failure(a.sink.seek(ULong.MAX_VALUE), "OFFSET_OUT_OF_BOUNDS")
        result(a.sink.seek(Long.MAX_VALUE.toULong()))
        assertEquals(0u, result(a.sink.write(Bytes(byteArrayOf()))))
        failure(a.sink.write(Bytes(byteArrayOf(1))), "INTEGER_OVERFLOW")
        assertEquals(0L, box.fs.metadata(box.root / "output" / ".staging" / "asset-1.jpg").size)
        failure(a.sink.truncate(1uL), "INVALID_ARGUMENT")
        result(transaction.abort())
    }
    @Test fun sparseGapIsIncludedInOutputBudgetAndFilledByFilesystem() = sandbox { box ->
        val transaction = FileOutputTransaction((box.root / "output").toString(), Context(Limits(100uL, 5uL)))
        val a = result(transaction.create(image))
        result(a.sink.seek(4uL)); result(a.sink.write(Bytes(byteArrayOf(7))))
        failure(a.sink.write(Bytes(byteArrayOf(8))), "RESOURCE_LIMIT_EXCEEDED")
        result(transaction.prepare()); result(transaction.commit())
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 7), box.contents(box.root / "output" / "assets" / "asset-1.jpg"))
    }
    @Test fun emptyPrepareUnknownAssetAndCommitBeforePrepareAreRejected() = sandbox { box ->
        val transaction = FileOutputTransaction((box.root / "output").toString(), testContext)
        failure(transaction.prepare(), "INVALID_ARGUMENT")
        failure(transaction.commit(), "INVALID_ARGUMENT")
        failure(transaction.openStaged(AssetId("unknown")), "INVALID_ARGUMENT")
        result(transaction.abort()); result(transaction.abort())
        assertFalse(box.fs.exists(box.root / "output"))
    }
    @Test fun assetCountAndCancellationPreventOutputCreation() = sandbox { box ->
        val root = box.root / "output"
        val transaction = FileOutputTransaction(root.toString(), testContext.copy(cancellation = Cancellation { true }))
        failure(transaction.create(image), "CANCELLED")
        result(transaction.abort())
        assertFalse(box.fs.exists(root))
        val limited = FileOutputTransaction(root.toString(), Context(Limits(100uL, 100uL, maxItems = 1uL)))
        result(limited.create(image))
        failure(limited.create(video), "RESOURCE_LIMIT_EXCEEDED")
        result(limited.abort())
    }
    @Test fun cancellationBeforeCommitLeavesNoPublishedAssetSet() = sandbox { box ->
        var cancelled = false
        val context = testContext.copy(cancellation = Cancellation { cancelled })
        val root = box.root / "output"
        val transaction = FileOutputTransaction(root.toString(), context)
        result(transaction.create(image))
        result(transaction.prepare())
        cancelled = true
        failure(transaction.commit(), "CANCELLED")
        result(transaction.abort())
        assertFalse(box.fs.exists(root))
    }
    @Test fun publicationConflictIsPreservedRatherThanOverwritten() = sandbox { box ->
        val root = box.root / "output"
        val transaction = FileOutputTransaction(root.toString(), testContext)
        result(transaction.create(image)); result(transaction.prepare())
        val reserved = root / "assets"
        box.fs.openReadWrite(reserved, mustCreate = true).use { it.write(0, byteArrayOf(9), 0, 1) }
        failure(transaction.commit(), "OUTPUT_EXISTS")
        failure(transaction.abort(), "IO_WRITE_FAILED")
        assertContentEquals(byteArrayOf(9), box.contents(reserved))
    }
    @Test fun unknownStagingFilesStopPrepareAndCleanupDoesNotDeleteThem() = sandbox { box ->
        val root = box.root / "output"
        val transaction = FileOutputTransaction(root.toString(), testContext)
        result(transaction.create(image))
        val unknown = root / ".staging" / "unknown"
        box.fs.openReadWrite(unknown, mustCreate = true).close()
        failure(transaction.prepare(), "IO_WRITE_FAILED")
        failure(transaction.abort(), "IO_WRITE_FAILED")
        assertTrue(box.fs.exists(unknown))
        assertFalse(box.fs.exists(root / "assets"))
        box.fs.delete(unknown)
        result(transaction.abort())
    }
    @Test fun moveFailureBeforeRenameCanBeQueriedAndAbortedWithoutPartialOutput() = sandbox { box ->
        val root = box.root / "output"
        val provider = object : ForwardingFileSystem(box.fs) {
            override fun atomicMove(source: Path, target: Path) { throw IOException("private provider failure") }
        }
        val transaction = FileOutputTransaction(root.toString(), testContext, provider)
        result(transaction.create(image)); result(transaction.create(video)); result(transaction.prepare())
        failure(transaction.commit(), "COMMIT_OUTCOME_UNKNOWN")
        assertEquals(TransactionState.Prepared, result(transaction.query()).state)
        assertFalse(box.fs.exists(root / "assets"))
        result(transaction.abort())
        assertFalse(box.fs.exists(root))
    }
    @Test fun lostResponseAfterRenameRecoversCommittedSetAndCannotAbortIt() = sandbox { box ->
        val root = box.root / "output"
        val provider = object : ForwardingFileSystem(box.fs) {
            override fun atomicMove(source: Path, target: Path) {
                super.atomicMove(source, target)
                throw IOException("response lost")
            }
        }
        val transaction = FileOutputTransaction(root.toString(), testContext, provider)
        val a = result(transaction.create(image)); val b = result(transaction.create(video))
        result(a.sink.write(Bytes(byteArrayOf(1)))); result(b.sink.write(Bytes(byteArrayOf(2))))
        result(transaction.prepare())
        failure(transaction.commit(), "COMMIT_OUTCOME_UNKNOWN")
        assertEquals(TransactionState.Committed, result(transaction.query()).state)
        failure(transaction.abort(), "INVALID_ARGUMENT")
        assertContentEquals(byteArrayOf(1), box.contents(root / "assets" / "asset-1.jpg"))
        assertContentEquals(byteArrayOf(2), box.contents(root / "assets" / "asset-2.mp4"))
    }
    @Test fun corruptedUncertainPublicationCannotBeReportedCommittedOrDeleted() = sandbox { box ->
        val root = box.root / "output"
        val provider = object : ForwardingFileSystem(box.fs) {
            override fun atomicMove(source: Path, target: Path) {
                super.atomicMove(source, target)
                box.fs.openReadWrite(target / "asset-1.jpg", mustExist = true).use { it.write(0, byteArrayOf(9), 0, 1) }
                throw IOException("response lost")
            }
        }
        val transaction = FileOutputTransaction(root.toString(), testContext, provider)
        val a = result(transaction.create(image)); result(a.sink.write(Bytes(byteArrayOf(1))))
        result(transaction.prepare())
        failure(transaction.commit(), "COMMIT_OUTCOME_UNKNOWN")
        failure(transaction.query(), "COMMIT_OUTCOME_UNKNOWN")
        failure(transaction.abort(), "COMMIT_OUTCOME_UNKNOWN")
        assertContentEquals(byteArrayOf(9), box.contents(root / "assets" / "asset-1.jpg"))
    }
    @Test fun capabilitiesDistinguishAtomicVisibilityFromReplacementAndDurability() = sandbox { box ->
        val capabilities = FileOutputTransaction((box.root / "output").toString(), testContext).capabilities()
        assertTrue(capabilities.assetSetAtomic && capabilities.seekable && capabilities.canReadStaged)
        assertFalse(capabilities.replacesAtomically || capabilities.durableFlush)
        assertFalse(box.fs.exists(box.root / "output"))
    }
}
