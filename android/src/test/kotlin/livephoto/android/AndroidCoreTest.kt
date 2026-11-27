package livephoto.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.memory.MemoryOutputTransaction
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.*

class AndroidCoreTest {
    @Test fun appFactoryUsesSharedCore() {
        assertIs<DefaultLivePhotoCore>(AndroidCoreFactory.create())
        assertNotSame(AndroidCoreFactory.create(), AndroidCoreFactory.create())
    }
    @Test fun noDesktopOrMobileDecoderIsImplicitlyEnabled() {
        val capabilities = AndroidCoreFactory.create().getMediaCapabilities()
        assertTrue(capabilities.backendIds.isEmpty())
        for (operation in listOf(Operation.Trim, Operation.Remux, Operation.Transcode, Operation.ExtractFrame)) {
            assertEquals(Implementation.Unsupported, capabilities.operations.single { it.operation == operation }.implementation)
        }
    }
    @Test fun oplusCapabilityDoesNotClaimDeviceCertification() {
        val capabilities = AndroidCoreFactory.create().getProtocolCapabilities(ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail")))
        assertEquals(Implementation.Experimental, capabilities.operations.single { it.operation == Operation.Create }.implementation)
        assertTrue(capabilities.operations.none { Verification.DeviceTested in it.verification })
    }
    @Test fun callerMutationCannotChangeMemorySnapshot() = runBlocking<Unit> {
        val original = byteArrayOf(1, 2, 3)
        val source = MemoryBinarySource(Bytes(original), SourceId("android-host-test"))
        try {
            original.fill(9)
            assertContentEquals(byteArrayOf(1, 2, 3), assertIs<CoreResult.Success<Bytes>>(source.readAt(0u, 3u)).value.toByteArray())
        } finally { source.close() }
    }
    @Test fun pendingReadbackUsesOneBoundedBufferAndAcceptsShortReads() = runBlocking<Unit> {
        val bytes = ByteArray(2 * 64 * 1024 + 17) { (it % 251).toByte() }
        var bufferSeen: ByteArray? = null
        var readCalls = 0
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length in 1..64 * 1024)
                if (bufferSeen == null) bufferSeen = buffer else assertSame(bufferSeen, buffer)
                readCalls++
                return super.read(buffer, offset, minOf(length, 9001))
            }
        }
        requireMatchingMedia(stream, bytes) {}
        assertTrue(readCalls > 3)
    }
    @Test fun pendingReadbackRejectsTruncationExtraBytesAndChangedBytes() = runBlocking<Unit> {
        val expected = ByteArray(64 * 1024 + 1) { it.toByte() }
        for (received in listOf(expected.copyOf(expected.size - 1), expected + 0,
            expected.copyOf().apply { this[lastIndex] = 1 })) {
            assertFailsWith<IllegalStateException> { requireMatchingMedia(ByteArrayInputStream(received), expected) {} }
        }
    }
    @Test fun pendingReadbackRejectsZeroProgress() = runBlocking<Unit> {
        val stream = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertFailsWith<IllegalStateException> { requireMatchingMedia(stream, ByteArray(100)) {} }
    }
    @Test fun pendingReadbackHonorsCancellationBetweenChunksAndBeforeEof() = runBlocking<Unit> {
        val expected = ByteArray(64 * 1024 + 1)
        val stream = ByteArrayInputStream(expected)
        var polls = 0
        assertFailsWith<CancellationException> {
            requireMatchingMedia(stream, expected) { if (++polls == 2) throw CancellationException("test") }
        }
        assertEquals(1, stream.available())
        assertFailsWith<CancellationException> {
            requireMatchingMedia(ByteArrayInputStream(byteArrayOf(7)), byteArrayOf(7)) {
                if (++polls == 4) throw CancellationException("test")
            }
        }
        assertEquals(4, polls)
    }
    @Test fun memoryPublicationBudgetDoesNotExposePartialAssets() = runBlocking<Unit> {
        val context = Context(Limits(3u, 2u))
        val transaction = MemoryOutputTransaction(context, "android-budget-test")
        val handle = assertIs<CoreResult.Success<OutputHandle>>(transaction.create(OutputAssetSpec(AssetRole.Composite, mime = "image/jpeg"))).value
        val failed = assertIs<CoreResult.Failure>(handle.sink.write(Bytes(byteArrayOf(1, 2, 3))))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", failed.error.code.value)
        assertTrue(transaction.committedAssets().isEmpty())
        assertIs<CoreResult.Success<Unit>>(transaction.abort())
    }
}
