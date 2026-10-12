package livephoto.adapters.files

import livephoto.core.*
import okio.*
import kotlin.test.*

class FileInputEdgeTest {
    private fun provider(box: Sandbox, input: Path, read: (FileHandle, Long, ByteArray, Int, Int) -> Int): FileSystem =
        object : ForwardingFileSystem(box.fs) {
            override fun openReadOnly(file: Path): FileHandle {
                val delegate = super.openReadOnly(file)
                if (file != input) return delegate
                return object : FileHandle(false) {
                    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int =
                        read(delegate, fileOffset, array, arrayOffset, byteCount)
                    override fun protectedSize(): Long = delegate.size()
                    override fun protectedClose() { delegate.close() }
                    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) { error("read only") }
                    override fun protectedResize(size: Long) { error("read only") }
                    override fun protectedFlush() { error("read only") }
                }
            }
        }

    @Test fun shortReadsAreLoopedUntilAllBytesAreCaptured() = sandbox { box ->
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val input = box.file("input", bytes)
        val fs = provider(box, input) { handle, offset, array, start, count ->
            handle.read(offset, array, start, minOf(1, count))
        }
        val session = FileInputSession((box.root / "snapshot").toString(), testContext, fs)
        val source = result(session.capture(input.toString()))
        assertContentEquals(bytes, result(source.readAt(0uL, bytes.size.toUInt())).toByteArray())
        result(session.close())
    }

    @Test fun zeroProgressReadFailsRatherThanLoopingOrLeavingPublishedData() = sandbox { box ->
        val input = box.file("input", byteArrayOf(1))
        val fs = provider(box, input) { _, _, _, _, _ -> 0 }
        val sessionRoot = box.root / "snapshot"
        val session = FileInputSession(sessionRoot.toString(), testContext, fs)
        failure(session.capture(input.toString()), "SOURCE_CHANGED")
        assertTrue(box.fs.list(sessionRoot).isEmpty())
        result(session.close())
        assertContentEquals(byteArrayOf(1), box.contents(input))
    }

    @Test fun sameSizeContentChangeBetweenPassesIsRejectedAndCopyIsCleaned() = sandbox { box ->
        val input = box.file("input", byteArrayOf(1, 2))
        var passes = 0
        val fs = provider(box, input) { handle, offset, array, start, count ->
            if (offset == 0L) passes++
            val n = handle.read(offset, array, start, count)
            if (passes == 2 && n > 0) array[start] = 9
            n
        }
        val sessionRoot = box.root / "snapshot"
        val session = FileInputSession(sessionRoot.toString(), testContext, fs)
        failure(session.capture(input.toString()), "SOURCE_CHANGED")
        assertTrue(box.fs.list(sessionRoot).isEmpty())
        result(session.close())
    }
}
