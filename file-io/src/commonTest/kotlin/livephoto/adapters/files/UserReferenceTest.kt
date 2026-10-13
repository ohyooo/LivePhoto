package livephoto.adapters.files

import livephoto.core.*
import okio.Path
import okio.Path.Companion.toPath
import okio.ByteString.Companion.toByteString
import okio.use
import kotlin.test.*

/** Fixed user-approved inputs, mandatory on every host; not device certification. */
class UserReferenceTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL, maxMetadataBytes = 8_000_000uL))
    private fun fixture(name: String): Path = listOf("reference/$name", "../reference/$name")
        .map { it.toPath() }.firstOrNull { localFileSystem.exists(it) }
        ?: error("Required reference fixture missing: $name")
    private fun digest(path: Path): Digest = localFileSystem.openReadOnly(path).use { digestFile(it, it.size(), context) }
    private fun bytes(path: Path): ByteArray = localFileSystem.read(path) { readByteArray() }
    private fun sha(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

    @Test fun generatedReferenceHasSafeExactMovExtractionWithNonCanonicalProtocolReported() = sandbox { box ->
        val originalVideo = bytes(fixture("video.mp4"))
        val cover = bytes(fixture("video.jpg"))
        val generated = bytes(fixture("livephoto.jpg"))
        assertEquals(8_121_397, originalVideo.size)
        assertEquals(293_848, cover.size)
        assertEquals(8_416_383, generated.size)
        assertEquals("d334d2c36a821dfa82c2d0f1bb09948125596bf77fd8eff7034ae6c53b739b64", sha(originalVideo))
        assertEquals("18d7fe28bff459d41fb75fe8358bb6df0f7c5164c04158e8b120789af7da76a9", sha(cover))
        assertEquals("fc4be53da351b60fa22086d9816871ab3fcec01ec9e166a72fbf82a3f6ba96cb", sha(generated))
        val expectedMov = generated.copyOfRange(294_932, 8_416_383)
        val movieHash = "233e954e8b53e8b707b6e38fcdea554ef987a0bfb7d4bc8413c68a4d3d69d0e7"
        assertEquals(movieHash, sha(expectedMov))
        assertFalse(originalVideo.contentEquals(expectedMov))
        assertContentEquals(cover.copyOfRange(2, cover.size), generated.copyOfRange(1086, 294_932))
        val session = FileInputSession((box.root / "snapshots").toString(), context)
        try {
            val source = result(session.capture(fixture("livephoto.jpg").toString()))
            val input = SourceSet.Single(source)
            val core = DefaultLivePhotoCore()
            val inspected = result(core.inspect(ReadRequest(input, context)))
            val resource = inspected.layout.resources.single { it.kind == ResourceKind.Video }
            assertEquals(ByteRange(294_932uL, 8_121_451uL), resource.extents.single().range)
            assertEquals(Time(0, 1_000_000u), inspected.keyPhoto.position)
            val validation = result(core.validateProtocol(ValidationRequest(input,
                target = ProtocolSelector(ProtocolId("google.motionphoto.v2"), ProfileId("jpeg")), context = context)))
            assertEquals(Verdict.Invalid, validation.verdict, "Secondary Padding=0 is outside canonical Google V2")
            val transaction = FileOutputTransaction((box.root / "extracted").toString(), context)
            val extracted = result(core.extract(ExtractRequest(input, listOf(resource.id), inspected.snapshot, output = transaction, context = context)))
            assertEquals(movieHash, extracted.output.assets.single().digest.value)
            assertContentEquals(expectedMov, bytes(transaction.assetPath(extracted.output.assets.single().id).toPath()))
            extracted.output.assets.forEach { it.readableSource?.close() }
            assertEquals("fc4be53da351b60fa22086d9816871ab3fcec01ec9e166a72fbf82a3f6ba96cb", digest(fixture("livephoto.jpg")).value)
            assertTrue(core.getProtocolCapabilities(ProtocolSelector(ProtocolId("google.motionphoto.v2"))).operations.none { Verification.DeviceTested in it.verification })
        } finally { result(session.close()) }
    }

    @Test fun jpegAndMp4CreateBothGoogleProfilesAndExtractOriginalVideoExactly() = sandbox { box ->
        val paths = listOf("video.jpg", "video.mp4", "livephoto.jpg").map(::fixture)
        val hashes = paths.associateWith(::digest)
        val session = FileInputSession((box.root / "snapshots").toString(), context)
        try {
            val image = result(session.capture(fixture("video.jpg").toString()))
            val video = result(session.capture(fixture("video.mp4").toString()))
            val core = DefaultLivePhotoCore()
            for ((index, protocol) in listOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2).withIndex()) {
                val created = result(core.create(CreateRequest(image, video, ProtocolSelector(protocol),
                    output = FileOutputTransaction((box.root / "create-$index").toString(), context), context = context)))
                val carrier = assertNotNull(created.output.assets.single().readableSource)
                try {
                    val live = SourceSet.Single(carrier)
                    assertEquals(protocol, result(core.detect(ReadRequest(live, context))).primaryProtocol?.protocol)
                    val validation = result(core.validate(ValidationRequest(live, layers = listOf(Layer.Structure, Layer.Protocol), context = context)))
                    assertEquals(Coverage.Complete, validation.coverage)
                    assertNotEquals(Verdict.Invalid, validation.verdict)
                    val extracted = result(core.extract(ExtractRequest(live, emptyList(),
                        output = FileOutputTransaction((box.root / "extract-$index").toString(), context), context = context)))
                    try {
                        val extractedSource = assertNotNull(extracted.output.assets.single().readableSource)
                        assertContentEquals(bytes(fixture("video.mp4")), result(extractedSource.readAt(0uL, 8_121_397u)).toByteArray())
                        assertEquals(hashes[fixture("video.mp4")], extracted.output.assets.single().digest)
                    } finally { extracted.output.assets.forEach { it.readableSource?.close() } }
                    assertTrue(created.execution.none { it.transcoded })
                } finally { carrier.close() }
            }
        } finally { result(session.close()) }
        paths.forEach { assertEquals(hashes[it], digest(it), "Reference input changed") }
    }

    @Test fun videoCannotBeSilentlyUsedAsThePrimaryImage() = sandbox { box ->
        val session = FileInputSession((box.root / "snapshots").toString(), context)
        try {
            val image = result(session.capture(fixture("video.mp4").toString()))
            val video = result(session.capture(fixture("video.mp4").toString()))
            val root = box.root / "must-not-publish"
            assertIs<CoreResult.Failure>(DefaultLivePhotoCore().create(CreateRequest(image, video, ProtocolSelector(ProtocolIds.GoogleV2),
                output = FileOutputTransaction(root.toString(), context), context = context)))
            assertFalse(box.fs.exists(root))
        } finally { result(session.close()) }
    }
}
