package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class EmptyMovieMetadataTest {
    private val context = Context(Limits(1_000_000uL, 1_000_000uL))
    private fun handler() = GoogleFixtures.box("hdlr", ByteArray(8) + "mdirappl".encodeToByteArray() + ByteArray(9))
    private fun udta(nodes: ByteArray) = GoogleFixtures.box("udta", GoogleFixtures.fullBox("meta", nodes))
    private suspend fun matches(bytes: ByteArray): Boolean {
        val reader = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("empty-meta")), context)
        val boxes = BmffReader(reader)
        return EmptyMovieMetadata.matches(reader, boxes, boxes.readBoxes(ByteRange(0uL, bytes.size.toULong())).orThrow().single(), 0u)
    }
    @Test fun onlyTheExactEmptyEnvelopeCanProveAbsenceOfSecondaryAuthority(): Unit = runImmediate {
        assertTrue(matches(udta(handler() + GoogleFixtures.box("ilst", byteArrayOf()))))
        for (bytes in listOf(
            udta(handler()),
            udta(handler() + handler() + GoogleFixtures.box("ilst", byteArrayOf())),
            udta(handler() + GoogleFixtures.box("ilst", GoogleFixtures.box("data", byteArrayOf(0)))),
            udta(handler() + GoogleFixtures.box("ilst", byteArrayOf()) + GoogleFixtures.fullBox("keys", GoogleFixtures.u32(0u))),
            udta(GoogleFixtures.box("hdlr", ByteArray(25)) + GoogleFixtures.box("ilst", byteArrayOf())),
            GoogleFixtures.box("udta", GoogleFixtures.fullBox("meta", handler() + GoogleFixtures.box("ilst", byteArrayOf())) + GoogleFixtures.box("free", byteArrayOf()))))
            assertFalse(matches(bytes), "Nonempty, unknown, duplicate or additional metadata must not borrow the empty-envelope proof")
    }
}
