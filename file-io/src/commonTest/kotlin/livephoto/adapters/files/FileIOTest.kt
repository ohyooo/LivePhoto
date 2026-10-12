package livephoto.adapters.files

import livephoto.core.*
import okio.*
import okio.Path.Companion.toPath
import kotlin.coroutines.*
import kotlin.test.*

internal val testContext = Context(Limits(16uL * 1024uL * 1024uL, 16uL * 1024uL * 1024uL))
internal fun <T> result(value: CoreResult<T>): T = when (value) {
    is CoreResult.Success -> value.value
    is CoreResult.Failure -> fail("Unexpected Core failure: ${value.error.code.value} ${value.error.message}")
}
internal fun failure(value: CoreResult<*>, code: String) { assertEquals(code, assertIs<CoreResult.Failure>(value).error.code.value) }
internal fun <T> immediate(block: suspend () -> T): T {
    var completion: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { completion = result }
    })
    return checkNotNull(completion) { "File adapter test unexpectedly suspended" }.getOrThrow()
}
internal class Sandbox {
    val fs = FileSystem.SYSTEM
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "livephoto-file-test-${newToken()}"
    init { fs.createDirectory(root, mustCreate = true) }
    fun file(name: String, bytes: ByteArray): Path = (root / name).also { path ->
        fs.openReadWrite(path, mustCreate = true).use { it.write(0, bytes, 0, bytes.size) }
    }
    fun contents(path: Path): ByteArray = fs.read(path) { readByteArray() }
    fun close() {
        check(root.name.startsWith("livephoto-file-test-"))
        // Only the exclusively-created test sandbox, never a project/user directory.
        fs.deleteRecursively(root, mustExist = true)
    }
}
internal fun sandbox(block: suspend (Sandbox) -> Unit): Unit = immediate {
    val box = Sandbox()
    try { block(box) } finally { box.close() }
}

class FileIOTest {
    @Test fun captureUsesRealFilesystemAndFreezesOriginalBytes() = sandbox { box ->
        val bytes = ByteArray(150000) { (it % 251).toByte() }
        val original = box.file("原始 input.bin", bytes)
        val session = FileInputSession((box.root / "输入 snapshot").toString(), testContext)
        val source = result(session.capture(original.toString()))
        val identity = result(source.identity())
        assertEquals(bytes.size.toULong(), identity.size)
        assertFalse(identity.id.value.contains(original.toString()))
        box.fs.openReadWrite(original, mustExist = true).use { it.resize(1); it.write(0, byteArrayOf(9), 0, 1) }
        assertEquals(identity, result(source.identity()))
        assertContentEquals(bytes.sliceArray(65530..65570), result(source.readAt(65530uL, 41u)).toByteArray())
        assertEquals(0, result(source.readAt(bytes.size.toULong(), 1u)).size)
        result(session.close())
        failure(source.size(), "IO_READ_FAILED")
        assertContentEquals(byteArrayOf(9), box.contents(original))
    }
    @Test fun readBoundsAreCheckedBeforeNarrowingOrAllocating() = sandbox { box ->
        val session = FileInputSession((box.root / "snapshot").toString(), testContext)
        val source = result(session.capture(box.file("file", byteArrayOf(1, 2)).toString()))
        failure(source.readAt(ULong.MAX_VALUE, 1u), "OFFSET_OUT_OF_BOUNDS")
        failure(source.readAt(0uL, UInt.MAX_VALUE), "RESOURCE_LIMIT_EXCEEDED")
        assertEquals(0, result(source.readAt(0uL, 0u)).size)
        result(session.close())
    }
    @Test fun emptyFilesHaveARealDigestAndNoZeroProgressLoop() = sandbox { box ->
        val session = FileInputSession((box.root / "snapshot").toString(), testContext)
        val source = result(session.capture(box.file("empty", byteArrayOf()).toString()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            result(source.identity()).digest!!.value)
        assertEquals(0uL, result(source.size()))
        result(session.close())
    }
    @Test fun aggregateSpoolBudgetCannotBeBypassedByMultipleInputs() = sandbox { box ->
        val context = Context(Limits(3uL, 100uL))
        val input = box.file("input", byteArrayOf(1, 2))
        val session = FileInputSession((box.root / "snapshot").toString(), context)
        result(session.capture(input.toString()))
        failure(session.capture(input.toString()), "RESOURCE_LIMIT_EXCEEDED")
        result(session.close())
        assertContentEquals(byteArrayOf(1, 2), box.contents(input))
    }
    @Test fun sourceCountBudgetIncludesPreviouslyClosedSourcesUntilSessionCleanup() = sandbox { box ->
        val context = Context(Limits(100uL, 100uL, maxSources = 1u))
        val input = box.file("input", byteArrayOf(1))
        val session = FileInputSession((box.root / "snapshot").toString(), context)
        result(session.capture(input.toString())).close()
        failure(session.capture(input.toString()), "RESOURCE_LIMIT_EXCEEDED")
        result(session.close())
        result(session.close())
    }
    @Test fun existingSnapshotDirectoriesAndInputDirectoriesAreNeverOverwritten() = sandbox { box ->
        val input = box.file("input", byteArrayOf(1))
        val session = FileInputSession(box.root.toString(), testContext)
        failure(session.capture(input.toString()), "OUTPUT_EXISTS")
        result(session.close())
        assertContentEquals(byteArrayOf(1), box.contents(input))
        val other = FileInputSession((box.root / "snapshot").toString(), testContext)
        failure(other.capture(box.root.toString()), "INVALID_ARGUMENT")
        result(other.close())
    }
    @Test fun cancelledCaptureDoesNotCreateSpoolOrTouchOriginals() = sandbox { box ->
        val context = testContext.copy(cancellation = Cancellation { true })
        val input = box.file("input", byteArrayOf(1))
        val root = box.root / "snapshot"
        val session = FileInputSession(root.toString(), context)
        failure(session.capture(input.toString()), "CANCELLED")
        assertFalse(box.fs.exists(root))
        result(session.close())
        assertContentEquals(byteArrayOf(1), box.contents(input))
    }
    @Test fun changedPrivateSnapshotIsDetectedByDigestNotOnlyMtime() = sandbox { box ->
        val sessionRoot = box.root / "snapshot"
        val session = FileInputSession(sessionRoot.toString(), testContext)
        val source = result(session.capture(box.file("input", byteArrayOf(1, 2)).toString()))
        val copy = box.fs.list(sessionRoot).single()
        box.fs.openReadWrite(copy, mustExist = true).use { it.write(0, byteArrayOf(9, 8), 0, 2) }
        failure(source.identity(), "SOURCE_CHANGED")
        result(session.close())
    }
    @Test fun unknownSpoolChildrenStopCleanupWithoutDeletingUserData() = sandbox { box ->
        val sessionRoot = box.root / "snapshot"
        val session = FileInputSession(sessionRoot.toString(), testContext)
        result(session.capture(box.file("input", byteArrayOf(1)).toString()))
        val unknown = sessionRoot / "unknown"
        box.fs.openReadWrite(unknown, mustCreate = true).close()
        failure(session.close(), "IO_READ_FAILED")
        assertTrue(box.fs.exists(unknown))
        box.fs.delete(unknown)
        result(session.close())
    }
    @Test fun providerSymlinkMetadataIsRejectedBeforeReading() = sandbox { box ->
        val input = box.file("input", byteArrayOf(1))
        val provider = object : ForwardingFileSystem(box.fs) {
            override fun metadataOrNull(path: Path): FileMetadata? = if (path == input)
                FileMetadata(isRegularFile = true, size = 1L, symlinkTarget = input) else super.metadataOrNull(path)
        }
        val session = FileInputSession((box.root / "snapshot").toString(), testContext, provider)
        failure(session.capture(input.toString()), "INVALID_ARGUMENT")
        assertFalse(box.fs.exists(box.root / "snapshot"))
        result(session.close())
    }
    @Test fun missingInputReportsStructuredErrorWithoutExposingPath() = sandbox { box ->
        val session = FileInputSession((box.root / "snapshot").toString(), testContext)
        val error = assertIs<CoreResult.Failure>(session.capture((box.root / "secret-input").toString())).error
        assertEquals("IO_READ_FAILED", error.code.value)
        assertFalse(error.message.contains(box.root.toString()))
        result(session.close())
    }
}
