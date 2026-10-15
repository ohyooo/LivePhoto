package livephoto.core.binary

import livephoto.core.*
import kotlin.test.*

class Sha256Test {
    private val context = Context(Limits(1024uL, 1024uL, maxMetadataBytes = 8uL))

    @Test
    fun nistEmptyAndAbcKnownAnswersAreIndependentOfProductionWriter() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256().finish().value)
        val hash = Sha256()
        for (character in "abc") hash.update(Bytes(byteArrayOf(character.code.toByte())))
        val result = hash.finish()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.value)
        assertEquals(result, hash.finish())
        assertFailsWith<CoreFault> { hash.update(Bytes(byteArrayOf())) }
    }

    @Test
    fun nistMultiBlockAndMillionAStreamingKnownAnswers() {
        val message = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        val hash = Sha256()
        message.chunked(7).forEach { hash.update(Bytes(it.encodeToByteArray())) }
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", hash.finish().value)
        val million = Sha256()
        val chunk = Bytes(ByteArray(1000) { 'a'.code.toByte() })
        repeat(1000) { million.update(chunk) }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", million.finish().value)
    }

    @Test
    fun rangeHashStreamsShortReadsWithoutBorrowedSourceClose(): Unit = runImmediate {
        val source = TestSource("--abc++".encodeToByteArray(), maxChunk = 1)
        val result = value(sha256Range(BinaryReader(source, context), ByteRange(2uL, 3uL)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.value)
        assertFalse(source.closed)
        assertEquals(3, source.readCalls)
    }

    @Test
    fun hashFailsOnChangingSourceAndOutOfBoundsRatherThanPublishingDigest(): Unit = runImmediate {
        val source = TestSource("abc".encodeToByteArray())
        source.onRead = { source.currentIdentity = source.currentIdentity.copy(generation = GenerationToken("changed")) }
        assertFailure("SOURCE_CHANGED", sha256Range(BinaryReader(source, context), ByteRange(0uL, 3uL)))
        val fresh = TestSource("abc".encodeToByteArray())
        assertFailure("OFFSET_OUT_OF_BOUNDS", sha256Range(BinaryReader(fresh, context), ByteRange(2uL, 2uL)))
        assertEquals(0, fresh.readCalls)
    }

    @Test
    fun cancelledHashDoesNotConsumeSource(): Unit = runImmediate {
        val source = TestSource("abc".encodeToByteArray())
        val cancelled = context.copy(cancellation = Cancellation { true })
        assertFailure("CANCELLED", sha256Range(BinaryReader(source, cancelled), ByteRange(0uL, 3uL)))
        assertEquals(0, source.readCalls)
    }

    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
    private fun assertFailure(code: String, result: CoreResult<*>) {
        assertEquals(IssueCode(code), assertIs<CoreResult.Failure>(result).error.code)
    }
}
