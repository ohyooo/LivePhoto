package livephoto.android

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import livephoto.core.Bytes
import livephoto.core.CoreResult
import livephoto.core.SourceIdentity
import livephoto.core.SourceId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidInputSnapshotTest {
    @Test fun snapshotIsImmutableAndReadsAtMostOneBlockWithStableIdentity() = runBlocking {
        withDirectory { directory ->
            val block = AndroidInputSnapshot.BLOCK_BYTES
            val original = ByteArray(block + 3) { (it and 0xFF).toByte() }
            val expectedTail = original.copyOfRange(block - 2, original.size)
            val source = AndroidInputSnapshot.copyFrom(directory, original.size, SourceId("test")) {
                ByteArrayInputStream(original)
            }
            try {
                assertEquals(original.size, source.byteLength)
                assertEquals(original.size.toULong(), assertIs<CoreResult.Success<*>>(source.size()).value)
                val identity = assertIs<CoreResult.Success<SourceIdentity>>(source.identity()).value
                assertEquals("32beb7b57583420f652233c9c0b774fbe19e611c9374f26c5b4722e5c940cf05", identity.digest?.value)
                original.fill(127)
                val first = assertIs<CoreResult.Success<Bytes>>(source.readAt(0uL, UInt.MAX_VALUE)).value
                assertEquals(block, first.size)
                assertEquals(1, first.toByteArray()[1].toInt())
                val tail = assertIs<CoreResult.Success<Bytes>>(source.readAt((block - 2).toULong(), 20u)).value
                assertContentEquals(expectedTail, tail.toByteArray())
                val eof = assertIs<CoreResult.Success<Bytes>>(source.readAt(original.size.toULong(), UInt.MAX_VALUE)).value
                assertEquals(0, eof.size)
                assertEquals("OFFSET_OUT_OF_BOUNDS", assertIs<CoreResult.Failure>(source.readAt(ULong.MAX_VALUE, 1u)).error.code.value)
                assertEquals(identity, assertIs<CoreResult.Success<*>>(source.identity()).value)
                assertEquals(1, directory.listFiles()!!.size)
            } finally { source.close() }
            source.close()
            assertEquals("IO_READ_FAILED", assertIs<CoreResult.Failure>(source.readAt(0uL, 1u)).error.code.value)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun honorsExactBudgetAndRejectsOneExtraByteWithoutLeavingSnapshot() = runBlocking {
        withDirectory { directory ->
            val data = byteArrayOf(1, 2, 3, 4)
            val exact = AndroidInputSnapshot.copyFrom(directory, 4, SourceId("exact")) { ByteArrayInputStream(data) }
            try {
                assertEquals(4, exact.byteLength)
            } finally { exact.close() }
            assertFailsWith<InputBudgetExceededException> {
                AndroidInputSnapshot.copyFrom(directory, 3, SourceId("overflow")) { ByteArrayInputStream(data) }
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancelledCopyClosesProviderAndDeletesPartiallyWrittenSnapshot() = runBlocking {
        withDirectory { directory ->
            var closed = false
            var calls = 0
            val limit = AndroidInputSnapshot.BLOCK_BYTES + 1
            val task = async {
                val job = currentCoroutineContext().job
                AndroidInputSnapshot.copyFrom(directory, limit, SourceId("cancelled")) {
                    object : ByteArrayInputStream(ByteArray(limit)) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (++calls == 2) {
                                assertEquals(AndroidInputSnapshot.BLOCK_BYTES.toLong(), directory.listFiles()!!.single().length())
                                job.cancel()
                            }
                            return super.read(b, off, len)
                        }

                        override fun close() { closed = true; super.close() }
                    }
                }
            }
            assertFailsWith<CancellationException> { task.await() }
            assertEquals(2, calls)
            assertTrue(closed)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun noProgressAndProviderOpenFailureLeaveNoSnapshot() = runBlocking {
        withDirectory { directory ->
            assertFailsWith<SelectedDocumentUnavailableException> {
                AndroidInputSnapshot.copyFrom(directory, 16, SourceId("stalled")) {
                    object : InputStream() {
                        override fun read(): Int = error("Only buffered reads expected")
                        override fun read(b: ByteArray, off: Int, len: Int): Int = 0
                    }
                }
            }
            assertFailsWith<SelectedDocumentUnavailableException> {
                AndroidInputSnapshot.copyFrom(directory, 16, SourceId("unavailable")) { throw IOException("Cannot open") }
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun interruptedProviderReadOrCloseIsSanitizedAndDeletesPartialSnapshot() = runBlocking {
        withDirectory { directory ->
            val privateUri = "content://fixture/private/full-document-path"
            for (failOnClose in listOf(false, true)) {
                var reads = 0
                var closed = false
                val failure = assertFailsWith<SelectedDocumentUnavailableException> {
                    AndroidInputSnapshot.copyFrom(directory, 8, SourceId("provider-interrupted")) {
                        object : InputStream() {
                            override fun read(): Int = error("Only buffered reads expected")
                            override fun read(b: ByteArray, off: Int, len: Int): Int =
                                when (++reads) {
                                    1 -> { b[off] = 1; 1 }
                                    else -> {
                                        assertEquals(1L, directory.listFiles()!!.single().length())
                                        if (failOnClose) -1 else throw IOException(privateUri)
                                    }
                                }
                            override fun close() {
                                closed = true
                                if (failOnClose) throw IOException(privateUri)
                            }
                        }
                    }
                }
                assertTrue(closed)
                assertEquals(2, reads)
                assertNull(failure.cause)
                assertFalse(privateUri in failure.toString())
                assertTrue("重新选择" in failure.message.orEmpty())
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        }
    }

    private suspend fun withDirectory(action: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("livephoto-input-test-").toFile()
        try { action(directory) }
        finally { directory.deleteRecursively() }
    }
}
