package livephoto.core.oplus

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.SourceSession
import livephoto.core.jpeg.*
import livephoto.core.memory.*
import kotlin.test.*

/** Reachable upstream template/writer facts; not a device compatibility claim. */
class OplusUpstreamWireProfileTest {
    private val context = Context(Limits(4_000_000uL, 4_000_000uL, maxMetadataBytes = 2_000_000uL))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))

    @Test fun freshPacketHasUpstreamZeroFieldsWrapperPrefixesAndFirstAppPosition(): Unit = runImmediate {
        val image = source(GoogleFixtures.jpeg(), "image")
        val videoBytes = GoogleFixtures.video().bytes
        val video = source(videoBytes, "video")
        val result = DefaultLivePhotoCore().create(CreateRequest(image, video,
            ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail")),
            edits = EditSpec(keyPosition = CoverPosition.FrameIndex(0uL)),
            output = MemoryOutputTransaction(context, "wire-profile"), context = context)).orThrow()
        val output = result.output.assets.single().readableSource!!
        val session = SourceSession.open(SourceSet.Single(output), context, ParseBudget(context)).orThrow()
        val jpeg = session.jpeg!!
        val segment = jpeg.segments.first { it.marker in 0xe0..0xef }
        assertEquals(AppPayloadKind.Xmp, segment.payloadKind)
        assertEquals(2uL, segment.range.offset)
        val payload = session.reader.readExactly(segment.payload!!.offset, segment.payload.length.toUInt()).orThrow().toByteArray().decodeToString()
        assertTrue(payload.startsWith("http://ns.adobe.com/xap/1.0/\u0000<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"))
        assertTrue(payload.contains("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"))
        assertTrue(payload.endsWith("<?xpacket end=\"w\"?>"))
        assertTrue(payload.contains("<Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/>"))
        assertTrue(payload.contains("<Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"${videoBytes.size}\" Item:Padding=\"0\"/>"))
        assertTrue(payload.contains("GCamera:MotionPhotoPresentationTimestampUs=\"0\""))
        assertTrue(payload.contains("OpCamera:MotionPhotoOwner=\"oplus\""))
        assertFalse(payload.contains("LivePhotoBox:Action"))
        assertEquals(ProtocolIds.Oplus, session.inspection.detection.primaryProtocol?.protocol)
        assertEquals(videoBytes.toList(), session.reader.readExactly(jpeg.primary.endExclusive, videoBytes.size.toUInt()).orThrow().toByteArray().toList())
        assertTrue(result.execution.none { it.transcoded || it.remuxed })
        val marker = session.exifComments.single().document.ifds.flatMap { it.entries }.single { it.tag == 0x9286u.toUShort() }
        assertEquals(Bytes("ASCII\u0000\u0000\u0000oplus_10485792".encodeToByteArray()), marker.value)
        assertEquals(22u, marker.count)
        assertEquals(Endian.Big, session.exifComments.single().document.endian)
    }
}
