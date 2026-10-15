package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.xmp.*
import kotlin.test.*

/** Directory arithmetic only; the HEIF adapter separately establishes actual item association and box authority. */
class GoogleHeicBindingTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL))
    private fun packet(mime: String = "image/heic", padding: String = "8", length: String = "200", key: String? = "0"): XmpPacket {
        val time = key?.let { " c:MotionPhotoPresentationTimestampUs=\"$it\"" } ?: ""
        val xml = "<r:RDF xmlns:r=\"$RDF_URI\"><r:Description xmlns:c=\"$CAMERA_URI\" xmlns:k=\"$CONTAINER_URI\" xmlns:i=\"$ITEM_URI\" c:MotionPhoto=\"1\" c:MotionPhotoVersion=\"1\"$time>" +
            "<k:Directory><r:Seq><r:li r:parseType=\"Resource\" i:Semantic=\"Primary\" i:Mime=\"$mime\" i:Padding=\"$padding\"/>" +
            "<r:li r:parseType=\"Resource\" i:Semantic=\"MotionPhoto\" i:Mime=\"video/mp4\" i:Length=\"$length\"/></r:Seq></k:Directory></r:Description></r:RDF>"
        return XmpReader.parse(Bytes(xml.encodeToByteArray()), context).orThrow()
    }
    private fun read(packets: List<XmpPacket>, mime: String = "image/heic"): CoreResult<List<GoogleBinding>> =
        GoogleCarrierReader.read(XmpCollection(packets, emptyList()), ByteRange(0uL, 200uL), mime,
            SourceIdentity(SourceId("heic-binding"), GenerationToken("fixed"), 408uL), ParseBudget(context))
    @Test fun heicPrimaryPaddingAndVideoBytesAreSeparateAndProfileIsNotJpeg() {
        val binding = read(listOf(packet())).orThrow().single()
        assertEquals(ProfileId("heic"), binding.profile)
        assertEquals(ByteRange(200uL, 8uL), binding.padding)
        assertEquals(ByteRange(208uL, 200uL), binding.video)
        assertEquals("image/heic", binding.items.first().mime)
        assertEquals(Time(0, 1_000_000u), binding.key.position)
        assertTrue(binding.issues.isEmpty())
    }
    @Test fun unsupportedPaddingWrongMimeAndWrongLengthStayMachineReadableCandidates() {
        for ((packet, code) in listOf(packet(padding = "0") to "CAPABILITY_UNSUPPORTED", packet(padding = "16") to "CAPABILITY_UNSUPPORTED",
            packet(mime = "image/jpeg") to "MALFORMED_XMP", packet(length = "199") to "MOTION_VIDEO_LENGTH_MISMATCH")) {
            val binding = read(listOf(packet)).orThrow().single()
            assertNull(binding.video)
            assertEquals(IssueCode(code), binding.issues.single().code)
            assertEquals(ProfileId("heic"), binding.profile)
        }
    }
    @Test fun missingMinusOneAndZeroKeyAreDistinctWithoutDerivedDefaults() {
        for (key in listOf(null, "-1", "0")) {
            val result = read(listOf(packet(key = key))).orThrow().single().key
            assertEquals(if (key == "0") Time(0, 1_000_000u) else null, result.position)
            assertEquals(if (key == null) 0 else 1, result.rawFields.size)
            assertTrue(result.issues.isEmpty())
        }
    }
    @Test fun repeatedAuthoritiesDoNotSelectOnePacketAndAvifIsNotEnabledByAMimeString() {
        val binding = read(listOf(packet(), packet())).orThrow().single()
        assertNull(binding.video)
        assertEquals(IssueCode("CONFLICTING_METADATA"), binding.issues.single().code)
        assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), assertIs<CoreResult.Failure>(read(listOf(packet(mime = "image/avif")), "image/avif")).error.code)
    }
}
