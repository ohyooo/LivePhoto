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
    @Test fun callerMutationCannotChangeMemorySnapshot() = runBlocking {
        val original = byteArrayOf(1, 2, 3)
        val source = MemoryBinarySource(Bytes(original), SourceId("android-host-test"))
        try {
            original.fill(9)
            assertContentEquals(byteArrayOf(1, 2, 3), assertIs<CoreResult.Success<Bytes>>(source.readAt(0u, 3u)).value.toByteArray())
        } finally { source.close() }
    }
    @Test fun boundedReadAcceptsExactlyTheLimit() = runBlocking {
        val bytes = ByteArray(100) { it.toByte() }
        assertContentEquals(bytes, readBounded(ByteArrayInputStream(bytes), bytes.size) {})
    }
    @Test fun boundedReadRejectsOverflowBeforeStoringIt() = runBlocking {
        assertFailsWith<IllegalArgumentException> { readBounded(ByteArrayInputStream(ByteArray(100)), 99) {} }
    }
    @Test fun boundedReadRejectsZeroProgress() = runBlocking {
        val stream = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertFailsWith<IllegalStateException> { readBounded(stream, 100) {} }
    }
    @Test fun boundedReadHonorsCancellation() = runBlocking {
        assertFailsWith<CancellationException> {
            readBounded(ByteArrayInputStream(ByteArray(100)), 100) { throw CancellationException("test") }
        }
    }
    @Test fun memoryPublicationBudgetDoesNotExposePartialAssets() = runBlocking {
        val context = Context(Limits(3u, 2u))
        val transaction = MemoryOutputTransaction(context, "android-budget-test")
        val handle = assertIs<CoreResult.Success<OutputHandle>>(transaction.create(OutputAssetSpec(AssetRole.Composite, mime = "image/jpeg"))).value
        val failed = assertIs<CoreResult.Failure>(handle.sink.write(Bytes(byteArrayOf(1, 2, 3))))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", failed.error.code.value)
        assertTrue(transaction.committedAssets().isEmpty())
        assertIs<CoreResult.Success<Unit>>(transaction.abort())
    }
}
