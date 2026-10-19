package livephoto.android

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.memory.MemoryOutputTransaction
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

/** Android runtime/IO evidence, not vendor gallery or physical device certification. */
@RunWith(AndroidJUnit4::class)
class AndroidIntegrationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LivePhotoApplication
    private val context = Context(Limits(32_000_000u, 48_000_000u))
    private fun fixture(name: String): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open("reference/$name").use { it.readBytes() }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun <T> success(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result, result.toString()).value

    @Test fun applicationUsesSharedCoreWithoutBackend() {
        assertIs<DefaultLivePhotoCore>(app.core)
        assertTrue(app.core.getMediaCapabilities().backendIds.isEmpty())
    }

    @Test fun controllerAnalyzesLiveAndOrdinaryPhotosWithoutWritingOrChangingInputs() = runBlocking {
        val file = app.getFileStreamPath("analysis-${UUID.randomUUID()}.jpg")
        try {
            for ((name, disposition) in listOf("livephoto.jpg" to Disposition.Live, "video.jpg" to Disposition.NonLive)) {
                val bytes = fixture(name)
                file.writeBytes(bytes)
                val filesBefore = app.filesDir.list()!!.toSet()
                val modified = file.lastModified()
                val result = success(AndroidCreateController(app, app.core).analyze(Uri.fromFile(file)))
                assertEquals(disposition, result.inspection.detection.disposition)
                assertEquals(filesBefore, app.filesDir.list()!!.toSet())
                assertEquals(modified, file.lastModified())
                assertContentEquals(bytes, file.readBytes())
                assertTrue(result.validation.checks.any { it.layer == Layer.Structure })
                assertTrue(result.validation.checks.any { it.layer == Layer.Protocol })
            }
        } finally { file.delete() }
    }

    @Test fun controllerReportsMalformedPhotoWithoutChangingItsBytes() = runBlocking {
        val file = app.getFileStreamPath("analysis-bad-${UUID.randomUUID()}.jpg")
        val bytes = byteArrayOf(-1, -40, -1, -31, 0, 16, 0, 0)
        try {
            file.writeBytes(bytes)
            val result = assertIs<CoreResult.Failure>(AndroidCreateController(app, app.core).analyze(Uri.fromFile(file)))
            assertTrue(result.error.code.value.isNotBlank())
            assertContentEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun packagedOriginalFixturesAreByteIdentical() {
        assertEquals("18d7fe28bff459d41fb75fe8358bb6df0f7c5164c04158e8b120789af7da76a9", sha(fixture("video.jpg")))
        assertEquals("d334d2c36a821dfa82c2d0f1bb09948125596bf77fd8eff7034ae6c53b739b64", sha(fixture("video.mp4")))
        assertEquals("fc4be53da351b60fa22086d9816871ab3fcec01ec9e166a72fbf82a3f6ba96cb", sha(fixture("livephoto.jpg")))
    }

    @Test fun oplusCreateExtractPreservesOriginalVideo() = runBlocking { roundTrip(ProtocolIds.Oplus, "jpeg-no-tail") }
    @Test fun googleV2CreateExtractPreservesOriginalVideo() = runBlocking { roundTrip(ProtocolIds.GoogleV2, "jpeg") }
    @Test fun googleV1CreateExtractPreservesOriginalVideo() = runBlocking { roundTrip(ProtocolIds.GoogleV1, "jpeg") }
    @Test fun cameraStartOffsetRoundTripsWithoutRewritingMovieHeaders() = runBlocking {
        roundTrip(ProtocolIds.Oplus, "jpeg-no-tail", cameraOffsetVideo(), ByteArray(435) { ((it * 37 + 11) and 255).toByte() })
    }
    @Test fun fractionalAudioEditRoundTripsWithoutRoundingOrEncoding() = runBlocking {
        for ((protocol, profile) in listOf(ProtocolIds.Oplus to "jpeg-no-tail", ProtocolIds.GoogleV2 to "jpeg", ProtocolIds.GoogleV1 to "jpeg")) {
            roundTrip(protocol, profile, fractionalAudioVideo())
        }
    }
    @Test fun boundedVideoDurationVarianceIsReportedAndVideoBytesStayExact() = runBlocking {
        for ((protocol, profile) in listOf(ProtocolIds.Oplus to "jpeg-no-tail", ProtocolIds.GoogleV2 to "jpeg", ProtocolIds.GoogleV1 to "jpeg")) {
            roundTrip(protocol, profile, durationVarianceVideo(), timingWarning = true)
        }
    }

    @Test fun controllerReplacesOnlyAuthorizedCameraCommentAndPreservesOpaqueExif() = runBlocking {
        controllerCommentRoundTrip(false)
    }
    @Test fun controllerReplacesTiffAsciiCommentWithoutMovingOtherExifValues() = runBlocking {
        controllerCommentRoundTrip(true)
    }
    private suspend fun controllerCommentRoundTrip(ascii: Boolean) {
        val token = UUID.randomUUID().toString()
        val original = fixture("video.jpg")
        val exif = if (ascii) asciiCameraCommentExif() else cameraCommentExif()
        val suffix = ByteArray(435) { ((it * 37 + 11) and 255).toByte() }
        val imageBytes = original.copyOfRange(0, 2) + exif + original.copyOfRange(2, original.size) + suffix
        val videoBytes = durationVarianceVideo()
        val imageFile = app.getFileStreamPath("comment-$token.jpg")
        val videoFile = app.getFileStreamPath("comment-$token.mp4")
        var output: Uri? = null
        try {
            imageFile.writeBytes(imageBytes); videoFile.writeBytes(videoBytes)
            val result = success(AndroidCreateController(app, app.core).create(Uri.fromFile(imageFile), Uri.fromFile(videoFile),
                ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail"))))
            output = result.uri
            val saved = app.contentResolver.openInputStream(result.uri)!!.use { it.readBytes() }
            if (ascii) {
                for (i in exif.indices) if (i !in 2..3 && i !in 40..49 && i !in 90..103) assertEquals(exif[i], saved[2 + i], "EXIF byte $i changed")
                assertContentEquals("ASCII\u0000\u0000\u0000oplus_10485792".encodeToByteArray(), saved.copyOfRange(172, 194))
            } else {
                for (i in exif.indices) if (i !in 42..45 && i !in 90..111) assertEquals(exif[i], saved[2 + i], "EXIF byte $i changed")
                assertContentEquals("ASCII\u0000\u0000\u0000oplus_10485792".encodeToByteArray(), saved.copyOfRange(92, 114))
            }
            assertContentEquals(suffix, saved.copyOfRange(saved.size - videoBytes.size - suffix.size, saved.size - videoBytes.size))
            assertContentEquals(videoBytes, saved.takeLast(videoBytes.size).toByteArray())
            val change = result.operation.preservation.changes.single { it.selector == "exif:UserComment" }
            assertEquals(Value.Text(if (ascii) "oplus_1056768" else "oplus_1048576"), change.before)
            assertEquals(Value.Text("oplus_10485792"), change.after)
            assertEquals(GuaranteeOutcome.Changed, result.operation.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
            assertTrue(result.operation.execution.none { it.transcoded || it.remuxed })
            assertContentEquals(imageBytes, imageFile.readBytes())
            assertContentEquals(videoBytes, videoFile.readBytes())
        } finally {
            output?.let { app.contentResolver.delete(it, null, null) }
            if (imageFile.exists()) assertTrue(imageFile.delete())
            if (videoFile.exists()) assertTrue(videoFile.delete())
        }
    }

    private suspend fun roundTrip(protocol: ProtocolId, profile: String, videoBytes: ByteArray = fixture("video.mp4"), suffix: ByteArray = byteArrayOf(), timingWarning: Boolean = false) {
        val imageBytes = fixture("video.jpg") + suffix
        val imageHash = sha(imageBytes)
        val videoHash = sha(videoBytes)
        val image = MemoryBinarySource(Bytes(imageBytes), SourceId("android-image"))
        val video = MemoryBinarySource(Bytes(videoBytes), SourceId("android-video"))
        var created: OperationResult? = null
        var extracted: OperationResult? = null
        try {
            val output = MemoryOutputTransaction(context, "android-create-$profile-${protocol.value}")
            val result = success(app.core.create(CreateRequest(image, video, ProtocolSelector(protocol, ProfileId(profile)), output = output, context = context)))
            created = result
            if (timingWarning) {
                assertEquals(Verdict.Warning, result.validation.verdict)
                assertTrue(result.validation.issues.any { it.code.value == "VIDEO_DURATION_HEADER_SAMPLE_VARIANCE" })
                assertEquals(Verdict.Valid, result.validation.checks.single { it.id.endsWith(":jpeg.frame") }.verdict)
            }
            if (suffix.isNotEmpty()) {
                val bytes = output.committedAssets().values.single().toByteArray()
                val start = bytes.size - videoBytes.size - suffix.size
                assertContentEquals(suffix, bytes.copyOfRange(start, start + suffix.size))
                assertEquals(GuaranteeOutcome.Unknown, result.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
            }
            assertTrue(result.execution.none { it.transcoded || it.remuxed })
            val source = assertNotNull(result.output.assets.single().readableSource)
            val input = SourceSet.Single(source)
            assertEquals(protocol, success(app.core.detect(ReadRequest(input, context))).primaryProtocol?.protocol)
            val validation = success(app.core.validate(ValidationRequest(input, layers = listOf(Layer.Structure, Layer.Protocol), context = context)))
            assertNotEquals(Verdict.Invalid, validation.verdict)
            assertEquals(Coverage.Complete, validation.coverage)
            val extractedOutput = MemoryOutputTransaction(context, "android-extract-$profile-${protocol.value}")
            val extraction = success(app.core.extract(ExtractRequest(input, emptyList(), output = extractedOutput, context = context)))
            extracted = extraction
            assertEquals(videoHash, extraction.output.assets.single().digest.value)
            assertContentEquals(videoBytes, extractedOutput.committedAssets().getValue(extraction.output.assets.single().id).toByteArray())
            assertEquals(imageHash, sha(imageBytes))
            assertEquals(videoHash, sha(videoBytes))
        } finally {
            extracted?.output?.assets?.forEach { it.readableSource?.close() }
            created?.output?.assets?.forEach { it.readableSource?.close() }
            image.close(); video.close()
        }
    }

    @Test fun originalReferenceKeepsNonCanonicalProtocolDiagnostic() = runBlocking {
        val source = MemoryBinarySource(Bytes(fixture("livephoto.jpg")), SourceId("android-reference"))
        try {
            val input = SourceSet.Single(source)
            val inspected = success(app.core.inspect(ReadRequest(input, context)))
            assertEquals(ByteRange(294_932u, 8_121_451u), inspected.layout.resources.single { it.kind == ResourceKind.Video }.extents.single().range)
            assertEquals(Verdict.Invalid, success(app.core.validateProtocol(ValidationRequest(input,
                target = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg")), context = context))).verdict)
        } finally { source.close() }
    }

    @Test fun videoAsPrimaryImageNeverPublishes() = runBlocking {
        val first = MemoryBinarySource(Bytes(fixture("video.mp4")), SourceId("wrong-image"))
        val second = MemoryBinarySource(Bytes(fixture("video.mp4")), SourceId("video"))
        val output = MemoryOutputTransaction(context, "must-not-publish")
        try {
            val failure = assertIs<CoreResult.Failure>(app.core.create(CreateRequest(first, second, ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail")), output = output, context = context)))
            assertEquals(Value.Text("image"), failure.error.details["livephoto.inputRole"])
            assertEquals(Value.Text("inspect-image"), failure.error.details["livephoto.step"])
            assertTrue(output.committedAssets().isEmpty())
        } finally { first.close(); second.close() }
    }

    @Test fun decoderRequestIsUnsupportedWithoutSystemMediaBackend() = runBlocking {
        val source = MemoryBinarySource(Bytes(fixture("video.jpg")), SourceId("image"))
        try {
            val result = assertIs<CoreResult.Failure>(app.core.probe(ProbeRequest(ResourceRef(SourceSet.Single(source)), decodeCheck = true, context = context)))
            assertEquals("CAPABILITY_UNSUPPORTED", result.error.code.value)
        } finally { source.close() }
    }

    @Test fun malformedVideoReportsVideoParsingAndPublishesNothing() = runBlocking {
        val image = MemoryBinarySource(Bytes(fixture("video.jpg")), SourceId("diagnostic-image"))
        val video = MemoryBinarySource(Bytes(byteArrayOf(0, 1, 2)), SourceId("diagnostic-video"))
        val output = MemoryOutputTransaction(context, "bad-video")
        try {
            val error = assertIs<CoreResult.Failure>(app.core.create(CreateRequest(image, video,
                ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail")), output = output, context = context))).error
            assertEquals(Value.Text("video"), error.details["livephoto.inputRole"])
            assertEquals(Value.Text("inspect-video"), error.details["livephoto.step"])
            assertEquals(SourceId("diagnostic-video"), error.location?.source)
            assertTrue(formatCoreFailure(error, "cover.jpg", "clip.mp4").contains("视频输入（clip.mp4）"))
            assertTrue(output.committedAssets().isEmpty())
        } finally { image.close(); video.close() }
    }

    @Test fun controllerMissingVideoIdentifiesReadFailureBeforeMerge() = runBlocking {
        val token = UUID.randomUUID().toString()
        val imageName = "diagnostic-$token.jpg"
        val imageFile = app.getFileStreamPath(imageName)
        val videoFile = app.getFileStreamPath("missing-$token.mp4")
        try {
            app.openFileOutput(imageName, 0).use { it.write(fixture("video.jpg")) }
            assertFalse(videoFile.exists())
            val failure = assertFailsWith<AndroidOperationFailure> {
                AndroidCreateController(app, app.core).create(Uri.fromFile(imageFile), Uri.fromFile(videoFile),
                    ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail")))
            }
            assertEquals("read-video", failure.step)
            assertEquals("video", failure.inputRole)
            assertIs<java.io.FileNotFoundException>(failure.cause)
            assertTrue(formatAndroidFailure(failure, "cover.jpg", "clip.mp4").contains("读取所选视频"))
        } finally { if (imageFile.exists()) assertTrue(imageFile.delete()) }
    }

    @Test fun controllerFreezesSelectedInputsAndPublishesExactGalleryCopy() = runBlocking {
        val token = UUID.randomUUID().toString()
        val imageName = "android-test-$token.jpg"
        val videoName = "android-test-$token.mp4"
        val imageBytes = fixture("video.jpg")
        val videoBytes = fixture("video.mp4")
        val imageFile = app.getFileStreamPath(imageName)
        val videoFile = app.getFileStreamPath(videoName)
        var output: Uri? = null
        try {
            app.openFileOutput(imageName, 0).use { it.write(imageBytes) }
            app.openFileOutput(videoName, 0).use { it.write(videoBytes) }
            val result = success(AndroidCreateController(app, app.core).create(Uri.fromFile(imageFile), Uri.fromFile(videoFile),
                ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail"))))
            output = result.uri
            assertEquals(1, result.operation.output.assets.size)
            assertNull(result.operation.output.assets.single().readableSource)
            assertEquals(result.uri.toString(), result.operation.output.assets.single().accessReference)
            assertTrue(result.operation.execution.none { it.transcoded || it.remuxed })
            val saved = app.contentResolver.openInputStream(result.uri)!!.use { readBounded(it, AndroidCreateController.MAX_OUTPUT_BYTES) {} }
            val selectedInfo = readSelectedMediaInfo(app.contentResolver, result.uri)
            assertEquals(saved.size.toLong(), selectedInfo.sizeBytes)
            assertTrue(assertNotNull(selectedInfo.displayName).endsWith(".jpg"))
            assertEquals(result.operation.output.assets.single().digest.value, sha(saved))
            val source = MemoryBinarySource(Bytes(saved), SourceId("android-gallery-readback"))
            try {
                assertEquals(ProtocolIds.Oplus, success(app.core.detect(ReadRequest(SourceSet.Single(source), context))).primaryProtocol?.protocol)
            } finally { source.close() }
            app.contentResolver.query(result.uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            assertContentEquals(imageBytes, imageFile.readBytes())
            assertContentEquals(videoBytes, videoFile.readBytes())
        } finally {
            output?.let { app.contentResolver.delete(it, null, null) }
            if (imageFile.exists()) assertTrue(imageFile.delete())
            if (videoFile.exists()) assertTrue(videoFile.delete())
        }
    }
}
