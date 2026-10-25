package livephoto.core.xmp

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.google.GoogleJpegWriter
import livephoto.core.jpeg.*
import livephoto.core.xml.ExpandedName
import kotlin.test.*

class XmpFixedSlotTest {
    private val context = Context(Limits(1_000_000uL, 1_000_000uL))
    private val body = "<r:RDF xmlns:r=\"$RDF_URI\" xmlns:g=\"http://ns.google.com/photos/1.0/camera/\" xmlns:o=\"urn:ordinary\">" +
        "<r:Description o:Title=\"中文 📷 &amp; retained\" g:MotionPhoto=\"1\" g:MotionPhotoPresentationTimestampUs=\"40000\"/></r:RDF>"
    private fun bytes(text: String) = Bytes(text.encodeToByteArray())
    private fun packet(end: String) = bytes("<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>$body<?xpacket end=\"$end\"?>")

    @Test fun utf8PaddingPrecedesWritableTrailerWithoutChangingOrdinaryProperties() {
        for (input in listOf(packet("w"), bytes(body))) {
            val result = XmpWriter.fitPacket(input, input.size + 257, context).orThrow()
            assertEquals(input.size + 257, result.size)
            val parsed = XmpReader.parse(result, context).orThrow()
            assertEquals("中文 📷 & retained", parsed.scalar("urn:ordinary", "Title").orThrow())
            assertEquals("40000", parsed.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhotoPresentationTimestampUs").orThrow())
            if (input == packet("w")) assertTrue(result.toByteArray().decodeToString().endsWith("<?xpacket end=\"w\"?>"))
            assertEquals(input, XmpWriter.fitPacket(input, input.size - 1, context).orThrow())
        }
        val readOnly = packet("r")
        assertEquals(readOnly, XmpWriter.fitPacket(readOnly, readOnly.size + 257, context).orThrow())
    }

    @Test fun aFittingXmpRewriteKeepsAnUnparsedExifBlockAtItsExactOriginalRange(): Unit = runImmediate {
        // Reserve the slot independently of the writer under test.
        val originalPacket = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>$body" + " ".repeat(2048) + "<?xpacket end=\"w\"?>"
        val exif = GoogleFixtures.segment(0xe1, "Exif\u0000\u0000".encodeToByteArray() + ByteArray(8))
        val image = GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(originalPacket) + exif)
        val reader = BinaryReader(TestSource(image), context)
        val original = JpegParser.parse(reader).orThrow()
        val updated = XmpWriter.merge(XmpReader.parse(bytes(originalPacket), context).orThrow(),
            mapOf(ExpandedName("http://ns.google.com/photos/1.0/camera/", "MotionPhotoPresentationTimestampUs") to "0"), context).orThrow()
        val plan = GoogleJpegWriter.patch(original, updated, context)
        assertEquals(original.primary.length, plan.outputLength)
        assertEquals(plan.patches.single().range.length, plan.patches.single().replacement.size.toULong())
        val sink = TestSink()
        JpegRewrite.write(reader, BinaryWriter(sink, context), original, plan, context).orThrow()
        val output = sink.written.toByteArray()
        val after = JpegParser.parse(BinaryReader(TestSource(output), context)).orThrow()
        val range = original.segments.single { it.payloadKind == AppPayloadKind.Exif }.range
        assertEquals(range, after.segments.single { it.payloadKind == AppPayloadKind.Exif }.range)
        assertContentEquals(exif, output.copyOfRange(range.offset.toInt(), range.endExclusive.toInt()))
        val app = plan.patches.single().replacement
        val parsed = XmpReader.parse(app.slice(4 + XMP_HEADER.length), context).orThrow()
        assertEquals("0", parsed.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhotoPresentationTimestampUs").orThrow())
        val tooLarge = bytes(body.replace("40000", "1".repeat(8192)))
        assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(
            attemptNow { GoogleJpegWriter.patch(original, tooLarge, context) }).error.code.value)
    }
}
