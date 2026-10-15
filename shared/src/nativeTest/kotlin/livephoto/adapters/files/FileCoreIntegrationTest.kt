package livephoto.adapters.files

import livephoto.core.*
import okio.*
import okio.Path.Companion.toPath
import kotlin.test.*

class FileCoreIntegrationTest {
    private fun fixture(name: String): Path = listOf("reference/$name", "../reference/$name")
        .map { it.toPath() }.firstOrNull { FileSystem.SYSTEM.exists(it) }
        ?: error("Required reference fixture missing")

    @Test fun referenceCreateInspectValidateAndByteExactExtractUseRealNativeFileAdapters() = sandbox { box ->
        val context = Context(Limits(128uL * 1024uL * 1024uL, 128uL * 1024uL * 1024uL))
        val session = FileInputSession((box.root / "snapshots").toString(), context)
        try {
            val image = result(session.capture(fixture("video.jpg").toString()))
            val video = result(session.capture(fixture("video.mp4").toString()))
            val imageIdentity = result(image.identity()); val videoIdentity = result(video.identity())
            val core = DefaultLivePhotoCore()
            val transaction = FileOutputTransaction((box.root / "created").toString(), context)
            val created = result(core.create(CreateRequest(image, video,
                ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail")), output = transaction, context = context)))
            assertEquals(TransactionState.Committed, created.output.receipt.state)
            val carrier = assertNotNull(created.output.assets.single().readableSource)
            val input = SourceSet.Single(carrier)
            val inspection = result(core.inspect(ReadRequest(input, context)))
            assertEquals(ProtocolIds.Oplus, inspection.detection.primaryProtocol!!.protocol)
            val validation = result(core.validate(ValidationRequest(input, layers = listOf(Layer.Structure, Layer.Protocol), context = context)))
            assertNotEquals(Verdict.Invalid, validation.verdict)
            // Multi-match detection has separate Google/Oplus aliases for the same video range.
            // Select the declared primary match, not an arbitrary first resource.
            val selected = inspection.detection.matches.single { it.target == inspection.detection.primaryProtocol }.resourceIds
            val videoResource = inspection.layout.resources.single { it.kind == ResourceKind.Video && it.id in selected }.id
            val extracted = result(core.extract(ExtractRequest(input, listOf(videoResource),
                output = FileOutputTransaction((box.root / "extracted").toString(), context), context = context)))
            assertEquals(videoIdentity.digest, extracted.output.assets.single().digest)
            assertEquals(videoIdentity.size, extracted.output.assets.single().byteLength)
            assertEquals(imageIdentity, result(image.identity()))
            assertEquals(videoIdentity, result(video.identity()))
            val originalImage = box.fs.openReadOnly(fixture("video.jpg")).use { digestFile(it, it.size(), context) }
            val originalVideo = box.fs.openReadOnly(fixture("video.mp4")).use { digestFile(it, it.size(), context) }
            assertEquals(imageIdentity.digest, originalImage)
            assertEquals(videoIdentity.digest, originalVideo)
            carrier.close()
            extracted.output.assets.forEach { it.readableSource?.close() }
        } finally { result(session.close()) }
    }

    @Test fun realSystemSymlinkFixtureIsRejectedOnEveryHost() = sandbox { box ->
        val link = listOf("shared/build/io-link-fixtures/link.bin", "build/io-link-fixtures/link.bin")
            .map { it.toPath() }.firstOrNull { FileSystem.SYSTEM.exists(it) }
            ?: error("Required real system symlink fixture missing")
        val session = FileInputSession((box.root / "snapshots").toString(), testContext)
        failure(session.capture(link.toString()), "INVALID_ARGUMENT")
        assertFalse(box.fs.exists(box.root / "snapshots"))
        result(session.close())
    }
}
