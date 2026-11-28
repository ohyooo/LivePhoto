package livephoto.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import livephoto.core.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AndroidOutputStagingTest {
    private val spec = OutputAssetSpec(AssetRole.Composite, mime = "image/jpeg")
    private fun <T> CoreResult<T>.value(): T = assertIs<CoreResult.Success<T>>(this).value

    @Test fun frozenDiskOutputStreamsAndVerifiesWithoutSecondWholeAsset() = runBlocking<Unit> {
        val directory = createTempDirectory("android-stage").toFile()
        val input = ByteArray(2 * 65536 + 1) { it.toByte() }
        val staging = AndroidOutputStaging(directory, "disk-test")
        try {
            val output = staging.uiOutput(Context(Limits(1uL, input.size.toULong())), "disk-test")
            val asset = output.create(spec).value()
            asset.sink.write(Bytes(input)).value()
            output.prepare().value()
            assertIs<CoreResult.Failure>(asset.sink.write(Bytes(byteArrayOf(1))))
            val verifier = output.openStaged(asset.id).value()
            verifier.close()
            assertIs<CoreResult.Failure>(output.openAsset(asset.id))
            assertEquals(TransactionState.Committed, output.commit().value().state)
            assertIs<CoreResult.Failure>(output.openAsset(AssetId("other-asset")))
            val source = output.openAsset(output.asset(asset.id)).value()
            assertEquals(65536, source.readAt(0uL, input.size.toUInt()).value().size)
            val payload = MediaPayload.from(source, input.size.toULong())
            val copied = ByteArrayOutputStream()
            writeMediaChunks(copied, payload) {}
            assertContentEquals(input, copied.toByteArray())
            requireMatchingMedia(ByteArrayInputStream(input), payload) {}
            assertFailsWith<IllegalStateException> {
                requireMatchingMedia(ByteArrayInputStream(input.copyOf().also { it[it.lastIndex] = 1 }), payload) {}
            }
            assertFailsWith<CancellationException> { writeMediaChunks(ByteArrayOutputStream(), payload) { throw CancellationException("cancel") } }
            // Scope cleanup also owns every reader, including one abandoned by a cancelled caller.
        } finally {
            staging.close()
            assertTrue(directory.list().orEmpty().isEmpty())
            assertTrue(directory.delete())
        }
    }

    @Test fun anUnavailableCacheOrByteBudgetDoesNotLeaveStages() = runBlocking<Unit> {
        val directory = createTempDirectory("android-stage-budget").toFile()
        try {
            AndroidOutputStaging(File(directory, "missing"), "unavailable").use { staging ->
                val output = StagedOutputTransaction(Context(Limits(1uL, 2uL)), "unavailable", staging)
                val error = assertIs<CoreResult.Failure>(output.create(spec)).error
                assertEquals("IO_WRITE_FAILED", error.code.value)
            }
            AndroidOutputStaging(directory, "small").use { staging ->
                val output = StagedOutputTransaction(Context(Limits(1uL, 2uL)), "small", staging)
                val asset = output.create(spec).value()
                assertEquals("RESOURCE_LIMIT_EXCEEDED",
                    assertIs<CoreResult.Failure>(asset.sink.write(Bytes(byteArrayOf(0, 1, 2)))).error.code.value)
                assertEquals(0L, directory.listFiles()!!.single().length())
                output.abort().value()
            }
            assertTrue(directory.list().orEmpty().isEmpty())
        } finally { assertTrue(directory.delete()) }
    }
}
