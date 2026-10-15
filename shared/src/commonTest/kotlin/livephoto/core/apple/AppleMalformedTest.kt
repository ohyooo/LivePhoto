package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.memory.*
import livephoto.core.google.GoogleFixtures
import kotlin.test.*

class AppleMalformedTest {
    private val context = Context(Limits(4_000_000uL, 4_000_000uL, maxMetadataBytes = 4_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun pair(image: ByteArray, movie: ByteArray = AppleFixtures.movie()) = SourceSet.Pair(source(image, "image"), source(movie, "movie"))

    @Test fun truncatedAndOversizedMakerNoteTablesFailWithStructuredErrors(): Unit = runImmediate {
        val image = AppleFixtures.image()
        // Fixture APP1 payload starts at 6, TIFF at 12, MakerNote at TIFF+44.
        val note = 56
        val hugeCount = image.copyOf().also { it[note + 14] = -1; it[note + 15] = -1 }
        val hugePointer = image.copyOf().also { GoogleFixtures.u32(UInt.MAX_VALUE).copyInto(it, note + 24) }
        val truncatedCount = image.copyOf().also { GoogleFixtures.u32(15u).copyInto(it, 12 + 32) }
        for (bytes in listOf(hugeCount, hugePointer, truncatedCount, image.copyOfRange(0, note + 12))) {
            assertIs<CoreResult.Failure>(core.inspect(ReadRequest(pair(bytes), context)))
        }
    }

    @Test fun makerNoteOutsideFormalExifOwnerCannotEstablishAPair(): Unit = runImmediate {
        val image = AppleFixtures.image()
        // Replace the IFD0 ExifIFD pointer with a direct MakerNote entry.
        image[22] = 0x92.toByte(); image[23] = 0x7c; image[24] = 0; image[25] = 7
        GoogleFixtures.u32(65u).copyInto(image, 26)
        GoogleFixtures.u32(44u).copyInto(image, 30)
        assertEquals(IssueCode("CONFLICTING_METADATA"), assertIs<CoreResult.Failure>(core.inspect(ReadRequest(pair(image), context))).error.code)
    }

    @Test fun idsAreNotCaseFoldedAndMarkerCountIsBudgeted(): Unit = runImmediate {
        assertEquals(IssueCode("INVALID_PAIR_IDENTIFIER"), assertIs<CoreResult.Failure>(core.inspect(ReadRequest(pair(AppleFixtures.image(AppleFixtures.ID.uppercase())), context))).error.code)
        val limited = Context(Limits(4_000_000uL, 4_000_000uL, maxMetadataBytes = 128uL))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(core.inspect(ReadRequest(pair(AppleFixtures.image()), limited))).error.code)
    }
}
