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
    @Test fun controllerSetsOnlyKeyMetadataAndPublishesVerifiedJpegForGoogleProfiles() = runBlocking {
        val file = app.getFileStreamPath("key-${UUID.randomUUID()}.jpg")
        try {
            for (target in listOf(livephoto.ui.ConversionTarget.GoogleV1, livephoto.ui.ConversionTarget.GoogleV2)) {
                val original = syntheticRepairCarrier(target)
                file.writeBytes(original)
                val requested = Time(667, 1000u)
                val result = success(AndroidCreateController(app, app.core).setPhotoKey(Uri.fromFile(file), requested))
                try {
                    val bytes = app.contentResolver.openInputStream(result.uri)!!.use { readBounded(it, AndroidCreateController.MAX_OUTPUT_BYTES) {} }
                    assertEquals(sha(bytes), result.report.digest.value)
                    assertTrue(result.report.actual >= Time(567, 1000u) && result.report.actual <= Time(767, 1000u))
                    assertTrue(result.report.preservation.records.filter { it.guarantee in setOf(Guarantee.ImageDataPreserving, Guarantee.BitstreamPreserving) }.all { it.outcome == GuaranteeOutcome.Verified && it.sourceDigest == it.outputDigest })
                    val source = MemoryBinarySource(Bytes(bytes), SourceId("key-output"))
                    val transaction = MemoryOutputTransaction(context, "key-extract")
                    var extracted: OperationResult? = null
                    try {
                        assertEquals(0, success(app.core.getKeyPhotoPosition(ReadRequest(SourceSet.Single(source), context))).position!!.compareTo(result.report.actual))
                        extracted = success(app.core.extract(ExtractRequest(SourceSet.Single(source), emptyList(), output = transaction, context = context)))
                        assertContentEquals(fractionalAudioVideo(), transaction.committedAssets().getValue(extracted.output.assets.single().id).toByteArray())
                    } finally { extracted?.output?.assets?.forEach { it.readableSource?.close() }; source.close() }
                    app.contentResolver.query(result.uri, arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                        assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals("Pictures/LivePhoto/", it.getString(1))
                    }
                    assertContentEquals(original, file.readBytes())
                } finally { app.contentResolver.delete(result.uri, null, null) }
            }
        } finally { file.delete() }
    }
    @Test fun controllerRejectsInvalidKeyRequestsWithoutPublication() = runBlocking {
        val file = app.getFileStreamPath("key-invalid-${UUID.randomUUID()}.jpg")
        val count = { app.contentResolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("LivePhoto-key-%"), null)!!.use { it.count } }
        val before = count()
        try {
            val oplus = syntheticRepairCarrier(livephoto.ui.ConversionTarget.Oplus)
            file.writeBytes(oplus)
            val refused = assertIs<CoreResult.Failure>(AndroidCreateController(app, app.core).setPhotoKey(Uri.fromFile(file), Time(667, 1000u)))
            assertEquals("UNSAFE_METADATA_REWRITE", refused.error.code.value)
            assertContentEquals(oplus, file.readBytes()); assertEquals(before, count())
            for (bytes in listOf(syntheticRepairCarrier(livephoto.ui.ConversionTarget.GoogleV1), conversionImage(), byteArrayOf(0, 1, 2, 3))) {
                file.writeBytes(bytes)
                assertIs<CoreResult.Failure>(AndroidCreateController(app, app.core).setPhotoKey(Uri.fromFile(file), Time(999, 1u)))
                assertContentEquals(bytes, file.readBytes()); assertEquals(before, count())
            }
        } finally { file.delete() }
    }
    private fun <T> success(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result, result.toString()).value

    @Test fun applicationUsesSharedCoreWithoutBackend() {
        assertIs<DefaultLivePhotoCore>(app.core)
        assertTrue(app.core.getMediaCapabilities().backendIds.isEmpty())
    }

    /** Only a generated fixture's owned XMP length/key digits are changed, never a private photo. */
    private fun corruptRepairField(bytes: ByteArray, protocol: ProtocolId, key: Boolean = false): ByteArray {
        val field = if (key) "MicroVideoPresentationTimestampUs" else if (protocol == ProtocolIds.GoogleV1) "MicroVideoOffset" else "Length"
        val expression = Regex("(?:[A-Za-z_][\\w.-]*:)?$field=[\"']([1-9][0-9]*)[\"']")
        val match = checkNotNull(expression.find(bytes.toString(Charsets.ISO_8859_1))) { "Missing synthetic repair field" }
        val range = match.groups[1]!!.range
        return bytes.copyOf().also { result ->
            for (index in range) result[index] = (if (key) '9' else '0').code.toByte()
            if (key) result[range.first] = '-'.code.toByte() // Invalid negative key, not the reserved -1; no width/unit assumptions.
        }
    }

    private suspend fun syntheticRepairCarrier(target: livephoto.ui.ConversionTarget): ByteArray {
        val image = MemoryBinarySource(Bytes(conversionImage()), SourceId("repair-image"))
        val video = MemoryBinarySource(Bytes(fractionalAudioVideo()), SourceId("repair-video"))
        val output = MemoryOutputTransaction(context, "repair-setup")
        var operation: OperationResult? = null
        try {
            operation = success(app.core.create(CreateRequest(image, video, target.selector, output = output, context = context)))
            return output.committedAssets().getValue(operation.output.assets.single().id).toByteArray()
        } finally { operation?.output?.assets?.forEach { it.readableSource?.close() }; image.close(); video.close() }
    }

    private fun countRepairedPhotos(): Int = app.contentResolver.query(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("LivePhoto-repaired-%"), null)!!.use { it.count }

    @Test fun controllerRepairPreviewsWithoutPublishingAndAppliesOnlyReviewedGoogleRepairs() = runBlocking {
        val file = app.getFileStreamPath("repair-${UUID.randomUUID()}.jpg")
        val controller = AndroidCreateController(app, app.core)
        try {
            for (target in listOf(livephoto.ui.ConversionTarget.GoogleV1, livephoto.ui.ConversionTarget.GoogleV2)) {
                val original = syntheticRepairCarrier(target)
                file.writeBytes(original)
                val before = countRepairedPhotos()
                val noop = success(controller.repairPhoto(Uri.fromFile(file)))
                assertNull(noop.uri); assertNull(noop.report.review); assertTrue(noop.report.proposedChanges.isEmpty())
                assertEquals(before, countRepairedPhotos())
                val damaged = corruptRepairField(original, target.selector.protocol)
                file.writeBytes(damaged)
                val preview = success(controller.repairPhoto(Uri.fromFile(file)))
                assertNull(preview.uri); assertNull(preview.report.outputDigest)
                val review = assertNotNull(preview.report.review)
                assertTrue(preview.report.changesApplied.isEmpty()); assertEquals(sha(damaged), review.sourceDigest.value)
                assertEquals(before, countRepairedPhotos()); assertContentEquals(damaged, file.readBytes())
                val applied = success(controller.repairPhoto(Uri.fromFile(file), review))
                val uri = assertNotNull(applied.uri)
                try {
                    val bytes = app.contentResolver.openInputStream(uri)!!.use { readBounded(it, AndroidCreateController.MAX_OUTPUT_BYTES) {} }
                    assertEquals(sha(bytes), applied.report.outputDigest?.value)
                    assertEquals(review.changes, applied.report.changesApplied); assertNull(applied.report.review)
                    assertContentEquals(damaged, file.readBytes())
                    val source = MemoryBinarySource(Bytes(bytes), SourceId("repaired-readback"))
                    var extracted: OperationResult? = null
                    try {
                        val validated = success(app.core.validate(ValidationRequest(SourceSet.Single(source), layers = listOf(Layer.Structure, Layer.Protocol), context = context)))
                        assertTrue(validated.verdict != Verdict.Invalid); assertEquals(Coverage.Complete, validated.coverage)
                        val output = MemoryOutputTransaction(context, "repair-video")
                        extracted = success(app.core.extract(ExtractRequest(SourceSet.Single(source), emptyList(), output = output, context = context)))
                        assertContentEquals(fractionalAudioVideo(), output.committedAssets().getValue(extracted.output.assets.single().id).toByteArray())
                    } finally { extracted?.output?.assets?.forEach { it.readableSource?.close() }; source.close() }
                    file.writeBytes(bytes)
                    val again = success(controller.repairPhoto(Uri.fromFile(file)))
                    assertNull(again.uri); assertNull(again.report.review); assertTrue(again.report.proposedChanges.isEmpty())
                    assertEquals(before + 1, countRepairedPhotos())
                    app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                        assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals("Pictures/LivePhoto/", it.getString(1))
                    }
                } finally { app.contentResolver.delete(uri, null, null) }
            }
        } finally { file.delete() }
    }

    @Test fun controllerRepairRejectsStaleReviewUnsafeLayoutsAndBlockedKeysWithoutPublication() = runBlocking {
        val file = app.getFileStreamPath("repair-rejected-${UUID.randomUUID()}.jpg")
        val controller = AndroidCreateController(app, app.core)
        try {
            val original = syntheticRepairCarrier(livephoto.ui.ConversionTarget.GoogleV1)
            val damaged = corruptRepairField(original, ProtocolIds.GoogleV1)
            file.writeBytes(damaged)
            val before = countRepairedPhotos()
            val review = assertNotNull(success(controller.repairPhoto(Uri.fromFile(file))).report.review)
            val wrongPlan = assertIs<CoreResult.Failure>(controller.repairPhoto(Uri.fromFile(file), review.copy(changes = emptyList())))
            assertEquals("REPAIR_AMBIGUOUS", wrongPlan.error.code.value)
            file.writeBytes(original)
            assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(controller.repairPhoto(Uri.fromFile(file), review)).error.code.value)
            val invalidKey = corruptRepairField(original, ProtocolIds.GoogleV1, key = true)
            file.writeBytes(invalidKey)
            val blocked = success(controller.repairPhoto(Uri.fromFile(file)))
            assertNull(blocked.uri); assertNull(blocked.report.review); assertTrue(blocked.report.blocked.isNotEmpty())
            val oplus = syntheticRepairCarrier(livephoto.ui.ConversionTarget.Oplus)
            file.writeBytes(oplus)
            assertEquals("REPAIR_AMBIGUOUS", assertIs<CoreResult.Failure>(controller.repairPhoto(Uri.fromFile(file))).error.code.value)
            for (bytes in listOf(conversionImage(), byteArrayOf(0, 1, 2, 3), byteArrayOf(-1, -40, -1, -31, 0, 16, 0, 0))) {
                file.writeBytes(bytes)
                assertIs<CoreResult.Failure>(controller.repairPhoto(Uri.fromFile(file)))
                assertContentEquals(bytes, file.readBytes())
            }
            assertEquals(before, countRepairedPhotos())
        } finally { file.delete() }
    }

    /** Independently encoded synthetic JPEG; not a private phone/reference derivative. */
    private fun conversionImage(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.BLUE)
            return java.io.ByteArrayOutputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)); it.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun countConvertedPhotos(): Int = app.contentResolver.query(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("LivePhoto-converted-%"), null)!!.use { it.count }

    @Test fun controllerConvertsLimitedMatrixWithExactCopiesAndSafeRefusals() = runBlocking {
        val imageBytes = conversionImage()
        val videoBytes = fractionalAudioVideo() // Synthetic protocol/timing, not a decoder/playability claim.
        val file = app.getFileStreamPath("convert-${UUID.randomUUID()}.jpg")
        val controller = AndroidCreateController(app, app.core)
        try {
            for (sourceTarget in livephoto.ui.ConversionTarget.entries) {
                val image = MemoryBinarySource(Bytes(imageBytes), SourceId("convert-image"))
                val video = MemoryBinarySource(Bytes(videoBytes), SourceId("convert-video"))
                val setup = MemoryOutputTransaction(context, "convert-setup")
                var created: OperationResult? = null
                try {
                    created = success(app.core.create(CreateRequest(image, video, sourceTarget.selector, output = setup, context = context)))
                    val original = setup.committedAssets().getValue(created.output.assets.single().id).toByteArray()
                    file.writeBytes(original)
                    for (target in livephoto.ui.ConversionTarget.entries) {
                        val before = countConvertedPhotos()
                        val converted = controller.convertPhoto(Uri.fromFile(file), target.selector)
                        assertContentEquals(original, file.readBytes())
                        if (sourceTarget == livephoto.ui.ConversionTarget.Oplus && target != sourceTarget) {
                            assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(converted).error.code.value)
                            assertEquals(before, countConvertedPhotos())
                            continue
                        }
                        val saved = success(converted)
                        try {
                            val bytes = app.contentResolver.openInputStream(saved.uri)!!.use { readBounded(it, AndroidCreateController.MAX_OUTPUT_BYTES) {} }
                            assertEquals(sha(bytes), saved.report.digest.value)
                            assertEquals(target.selector, saved.report.target)
                            assertEquals(target == sourceTarget, saved.report.carrierExact)
                            if (target == sourceTarget) assertContentEquals(original, bytes)
                            val source = MemoryBinarySource(Bytes(bytes), SourceId("converted-readback"))
                            var extraction: OperationResult? = null
                            try {
                                val detection = success(app.core.detect(ReadRequest(SourceSet.Single(source), context)))
                                assertEquals(target.selector, detection.primaryProtocol)
                                val output = MemoryOutputTransaction(context, "converted-video")
                                extraction = success(app.core.extract(ExtractRequest(SourceSet.Single(source), emptyList(), output = output, context = context)))
                                assertContentEquals(videoBytes, output.committedAssets().getValue(extraction.output.assets.single().id).toByteArray())
                            } finally { extraction?.output?.assets?.forEach { it.readableSource?.close() }; source.close() }
                            app.contentResolver.query(saved.uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH,
                                MediaStore.MediaColumns.MIME_TYPE), null, null, null)!!.use {
                                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals("Pictures/LivePhoto/", it.getString(1)); assertEquals("image/jpeg", it.getString(2))
                            }
                        } finally { app.contentResolver.delete(saved.uri, null, null) }
                    }
                } finally { created?.output?.assets?.forEach { it.readableSource?.close() }; image.close(); video.close() }
            }
        } finally { file.delete() }
    }

    @Test fun controllerConversionRejectsNonLiveAndMalformedWithoutPublication() = runBlocking {
        val file = app.getFileStreamPath("convert-rejected-${UUID.randomUUID()}.jpg")
        try {
            for (bytes in listOf(conversionImage(), byteArrayOf(0, 1, 2, 3), byteArrayOf(-1, -40, -1, -31, 0, 16, 0, 0))) {
                file.writeBytes(bytes)
                val before = countConvertedPhotos()
                assertIs<CoreResult.Failure>(AndroidCreateController(app, app.core).convertPhoto(Uri.fromFile(file), livephoto.ui.ConversionTarget.GoogleV2.selector))
                assertEquals(before, countConvertedPhotos()); assertContentEquals(bytes, file.readBytes())
            }
        } finally { file.delete() }
    }

    @Test fun cleanSplitPreparesWithoutPublicationAndSavesEachVerifiedAsset() = runBlocking {
        val imageBytes = fixture("video.jpg")
        val videoBytes = fixture("video.mp4")
        val carrier = app.getFileStreamPath("split-${UUID.randomUUID()}.jpg")
        val controller = AndroidCreateController(app, app.core)
        try {
            for ((protocol, profile) in listOf(ProtocolIds.Oplus to "jpeg-no-tail", ProtocolIds.GoogleV2 to "jpeg", ProtocolIds.GoogleV1 to "jpeg")) {
                val image = MemoryBinarySource(Bytes(imageBytes), SourceId("split-image"))
                val video = MemoryBinarySource(Bytes(videoBytes), SourceId("split-video"))
                val output = MemoryOutputTransaction(context, "split-setup")
                var created: OperationResult? = null
                val saved = mutableListOf<Uri>()
                try {
                    created = success(app.core.create(CreateRequest(image, video, ProtocolSelector(protocol, ProfileId(profile)), output = output, context = context)))
                    val original = output.committedAssets().getValue(created.output.assets.single().id).toByteArray()
                    carrier.writeBytes(original)
                    val imageCount = countSplitRows(false); val videoCount = countSplitRows(true)
                    val split = success(controller.cleanSplit(Uri.fromFile(carrier)))
                    assertEquals(imageCount, countSplitRows(false)); assertEquals(videoCount, countSplitRows(true))
                    assertContentEquals(original, carrier.readBytes())
                    assertContentEquals(videoBytes, split.video.bytes.toByteArray())
                    assertEquals(sha(videoBytes), split.video.digest.value)
                    val source = MemoryBinarySource(split.image.bytes, SourceId("clean-image"))
                    try {
                        val analysis = success(app.core.analyze(AnalyzeRequest(SourceSet.Single(source), context = context)))
                        assertEquals(Disposition.NonLive, analysis.inspection.detection.disposition)
                        assertTrue(analysis.inspection.layout.resources.none { it.kind == ResourceKind.Video })
                        val again = MemoryOutputTransaction(context, "clean-idempotence")
                        val operation = success(app.core.split(SplitRequest(SourceSet.Single(source), output = again, context = context)))
                        try { assertEquals(split.image.bytes, again.committedAssets().getValue(operation.output.assets.single().id)) }
                        finally { operation.output.assets.forEach { it.readableSource?.close() } }
                    } finally { source.close() }
                    for ((file, folder) in listOf(split.image to "Pictures/LivePhoto/", split.video to "Movies/LivePhoto/")) {
                        val uri = controller.saveSplitFile(file); saved += uri
                        val readback = app.contentResolver.openInputStream(uri)!!.use { readBounded(it, file.bytes.size) {} }
                        assertContentEquals(file.bytes.toByteArray(), readback)
                        assertEquals(file.digest.value, sha(readback))
                        app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH,
                            MediaStore.MediaColumns.MIME_TYPE), null, null, null)!!.use {
                            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(folder, it.getString(1)); assertEquals(file.mime, it.getString(2))
                        }
                    }
                } finally {
                    saved.forEach { app.contentResolver.delete(it, null, null) }
                    created?.output?.assets?.forEach { it.readableSource?.close() }
                    image.close(); video.close()
                }
            }
        } finally { carrier.delete() }
    }

    private fun countSplitRows(video: Boolean): Int = app.contentResolver.query(
        if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("LivePhoto-clean-%"), null)!!.use { it.count }

    @Test fun cleanSplitRejectsOrdinaryAndMalformedInputsWithoutPublishing() = runBlocking {
        val file = app.getFileStreamPath("split-rejected-${UUID.randomUUID()}.jpg")
        try {
            for (bytes in listOf(fixture("video.jpg"), byteArrayOf(-1, -40, -1, -31, 0, 16, 0, 0))) {
                file.writeBytes(bytes)
                val images = countSplitRows(false); val videos = countSplitRows(true)
                assertIs<CoreResult.Failure>(AndroidCreateController(app, app.core).cleanSplit(Uri.fromFile(file)))
                assertContentEquals(bytes, file.readBytes())
                assertEquals(images, countSplitRows(false)); assertEquals(videos, countSplitRows(true))
            }
        } finally { file.delete() }
    }

    @Test fun controllerExportsExactVideoToMediaStoreForAllThreeProfiles() = runBlocking {
        val imageBytes = fixture("video.jpg")
        val videoBytes = fixture("video.mp4")
        val carrier = app.getFileStreamPath("extract-${UUID.randomUUID()}.jpg")
        try {
            for ((protocol, profile) in listOf(ProtocolIds.Oplus to "jpeg-no-tail", ProtocolIds.GoogleV2 to "jpeg", ProtocolIds.GoogleV1 to "jpeg")) {
                val image = MemoryBinarySource(Bytes(imageBytes), SourceId("extract-image"))
                val video = MemoryBinarySource(Bytes(videoBytes), SourceId("extract-video"))
                val output = MemoryOutputTransaction(context, "extract-setup")
                var created: OperationResult? = null
                var published: Uri? = null
                try {
                    created = success(app.core.create(CreateRequest(image, video, ProtocolSelector(protocol, ProfileId(profile)), output = output, context = context)))
                    val sourceBytes = output.committedAssets().getValue(created.output.assets.single().id).toByteArray()
                    carrier.writeBytes(sourceBytes)
                    val extracted = success(AndroidCreateController(app, app.core).extractVideo(Uri.fromFile(carrier)))
                    published = extracted.uri
                    val saved = app.contentResolver.openInputStream(extracted.uri)!!.use { readBounded(it, videoBytes.size) {} }
                    assertContentEquals(videoBytes, saved)
                    assertContentEquals(sourceBytes, carrier.readBytes())
                    assertEquals(sha(videoBytes), extracted.report.digest.value)
                    assertEquals("video/mp4", extracted.report.mime)
                    assertTrue(extracted.report.preservation.records.any {
                        it.guarantee == Guarantee.ExactExtraction && it.outcome == GuaranteeOutcome.Verified
                    })
                    app.contentResolver.query(extracted.uri, arrayOf(MediaStore.MediaColumns.IS_PENDING,
                        MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE), null, null, null)!!.use {
                        assertTrue(it.moveToFirst())
                        assertEquals(0, it.getInt(0))
                        assertEquals("Movies/LivePhoto/", it.getString(1))
                        assertEquals("video/mp4", it.getString(2))
                    }
                } finally {
                    published?.let { app.contentResolver.delete(it, null, null) }
                    created?.output?.assets?.forEach { it.readableSource?.close() }
                    image.close(); video.close()
                }
            }
        } finally { carrier.delete() }
    }

    @Test fun controllerExtractionRejectsNonLiveAndMalformedInputsWithoutPublication() = runBlocking {
        val file = app.getFileStreamPath("extract-rejected-${UUID.randomUUID()}.jpg")
        fun countVideos(): Int = app.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?", arrayOf("LivePhoto-video-%"), null)!!.use { it.count }
        try {
            for (bytes in listOf(fixture("video.jpg"), byteArrayOf(-1, -40, -1, -31, 0, 16, 0, 0))) {
                file.writeBytes(bytes)
                val count = countVideos()
                assertIs<CoreResult.Failure>(AndroidCreateController(app, app.core).extractVideo(Uri.fromFile(file)))
                assertEquals(count, countVideos())
                assertContentEquals(bytes, file.readBytes())
            }
        } finally { file.delete() }
    }

    @Test fun controllerKeepsNonCanonicalCandidateAndOrdinaryPhotoInputsReadOnly() = runBlocking {
        val file = app.getFileStreamPath("analysis-${UUID.randomUUID()}.jpg")
        try {
            // The existing sample is intentionally noncanonical. Preserve its
            // Candidate/Invalid diagnostics, as the protocol regression below does.
            for ((name, disposition) in listOf("livephoto.jpg" to Disposition.Candidate, "video.jpg" to Disposition.NonLive)) {
                val bytes = fixture(name)
                file.writeBytes(bytes)
                val filesBefore = app.filesDir.list()!!.toSet()
                val modified = file.lastModified()
                val result = success(AndroidCreateController(app, app.core).analyze(Uri.fromFile(file)))
                assertEquals(disposition, result.inspection.detection.disposition)
                if (name == "livephoto.jpg") {
                    assertEquals(Verdict.Invalid, result.validation.verdict)
                    assertEquals(ByteRange(294_932u, 8_121_451u),
                        result.inspection.layout.resources.single { it.kind == ResourceKind.Video }.extents.single().range)
                }
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
