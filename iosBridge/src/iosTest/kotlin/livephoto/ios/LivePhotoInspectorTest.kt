@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package livephoto.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import livephoto.core.Bytes
import livephoto.core.CoreResult
import livephoto.core.SourceId
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.posix.mkfifo
import platform.posix.mkdir
import platform.posix.symlink
import platform.posix.truncate

class LivePhotoInspectorTest {
    private val inspector = LivePhotoInspector()

    @Test
    fun ordinaryImageIsNotLive() {
        val report = inspector.inspect(ORDINARY_JPEG.fixtureData())
        assertTrue(report.successful)
        assertEquals("NonLive", report.disposition)
        assertNull(report.protocolId)
        assertNull(report.errorCode)
    }

    @Test
    fun aSingleAppleCarrierIsOnlyACandidate() {
        val report = inspector.inspect(APPLE_JPEG.fixtureData())
        assertTrue(report.successful)
        assertEquals("Candidate", report.disposition)
        assertEquals(false, report.pairingMatches)
        assertContains(report.issueCodes, "PAIR_ASSET_MISSING")
    }

    @Test
    fun matchingApplePairUsesTheSharedCore() {
        val report = inspector.inspectPair(APPLE_JPEG.fixtureData(), APPLE_MOV.fixtureData())
        assertTrue(report.successful)
        assertEquals("Live", report.disposition)
        assertEquals("apple.livephoto", report.protocolId)
        assertEquals(true, report.pairingMatches)
    }

    @Test
    fun mismatchedApplePairExposesStructuredCoreError() {
        val original = "00112233-4455-6677-8899-aabbccddeeff".encodeToByteArray()
        val modifiedMovie = Base64.decode(APPLE_MOV).also { movie ->
            val start = (0..(movie.size - original.size)).single { offset ->
                original.indices.all { movie[offset + it] == original[it] }
            }
            movie[start] = '1'.code.toByte()
        }
        val report = inspector.inspectPair(APPLE_JPEG.fixtureData(), modifiedMovie.toNSData())
        assertFalse(report.successful)
        assertEquals("INVALID_PAIR_IDENTIFIER", report.errorCode)
        assertNull(report.disposition)
    }

    @Test
    fun emptyInputsAreRejected() {
        val empty = NSData()
        assertEquals("INVALID_ARGUMENT", inspector.inspect(empty).errorCode)
        assertEquals("INVALID_ARGUMENT", inspector.inspectPair(APPLE_JPEG.fixtureData(), empty).errorCode)
    }

    @Test
    fun combinedLimitIsEnforcedBeforeParsing() {
        val halfPlusOne = ByteArray(inspector.inputLimitBytes / 2 + 1).toNSData()
        val report = inspector.inspectPair(halfPlusOne, halfPlusOne)
        assertFalse(report.successful)
        assertEquals("RESOURCE_LIMIT_EXCEEDED", report.errorCode)
        assertEquals("Read", report.errorStage)
    }

    @Test
    fun fileBridgeInspectsSingleAndExplicitApplePair() = withTemporaryDirectory { directory ->
        val ordinary = directory.write("普通照片 no-extension", ORDINARY_JPEG.fixtureData())
        val image = directory.write("still.txt", APPLE_JPEG.fixtureData())
        val motion = directory.write("motion.again", APPLE_MOV.fixtureData())
        val ordinaryReport = inspector.inspectFile(ordinary)
        assertTrue(ordinaryReport.successful)
        assertEquals("NonLive", ordinaryReport.disposition)
        assertEquals("Candidate", inspector.inspectFile(image).disposition)
        val report = inspector.inspectFilePair(image, motion)
        assertTrue(report.successful)
        assertEquals("apple.livephoto", report.protocolId)
        assertEquals(true, report.pairingMatches)
    }

    @Test
    fun fileBridgeRefusesNonRegularNonLocalAndOverBudgetInputs() = withTemporaryDirectory { directory ->
        val image = directory.write("still", APPLE_JPEG.fixtureData())
        val large = directory.write("sparse", ORDINARY_JPEG.fixtureData())
        val budget = inspector.inputLimitBytes.toLong()
        assertEquals(0, truncate(requireNotNull(large.path), budget))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", inspector.inspectFilePair(image, large).errorCode)
        assertEquals(0, truncate(requireNotNull(large.path), budget + 1))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", inspector.inspectFile(large).errorCode)
        assertEquals(0, truncate(requireNotNull(large.path), 0L))
        assertEquals("INVALID_ARGUMENT", inspector.inspectFile(large).errorCode)

        assertEquals("INVALID_ARGUMENT", inspector.inspectFile(NSURL.fileURLWithPath(directory)).errorCode)
        assertEquals("IO_READ_FAILED", inspector.inspectFile(NSURL.fileURLWithPath("$directory/missing")).errorCode)
        assertEquals("INVALID_ARGUMENT", inspector.inspectFile(requireNotNull(NSURL.URLWithString("https://example.invalid/photo"))).errorCode)

        val alias = "$directory/link"
        assertEquals(0, symlink(requireNotNull(image.path), alias))
        assertEquals("INVALID_ARGUMENT", inspector.inspectFile(NSURL.fileURLWithPath(alias)).errorCode)
        // Opening a FIFO must not hang awaiting a producer.
        val fifo = "$directory/fifo"
        assertEquals(0, mkfifo(fifo, 384u))
        assertEquals("INVALID_ARGUMENT", inspector.inspectFile(NSURL.fileURLWithPath(fifo)).errorCode)
    }

    @Test
    fun fileSourceShortReadsChangesAndCloseHaveExplicitSemantics() = runBlocking {
        withTemporaryDirectory { directory ->
            val bytes = ByteArray(96 * 1024) { (it % 251).toByte() }
            val url = directory.write("chunk-input", bytes.toNSData())
            val source = IosReadOnlyFileSource.openFile(url, SourceId("test-file"))
            try {
                val first = assertIs<CoreResult.Success<Bytes>>(source.readAt(7uL, UInt.MAX_VALUE)).value
                assertEquals(64 * 1024, first.size)
                assertEquals(bytes[7], first.toByteArray()[0])
                val last = assertIs<CoreResult.Success<Bytes>>(source.readAt((bytes.size - 1).toULong(), 1024u)).value
                assertEquals(listOf(bytes.last()), last.toByteArray().toList())
                assertEquals(0, truncate(requireNotNull(url.path), 1L))
                assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(source.readAt(0uL, 1u)).error.code.value)
            } finally {
                source.close()
                source.close()
            }
            assertEquals("IO_READ_FAILED", assertIs<CoreResult.Failure>(source.identity()).error.code.value)
        }
    }

    private fun String.fixtureData(): NSData = Base64.decode(this).toNSData()
    private fun ByteArray.toNSData(): NSData =
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    private fun String.write(name: String, data: NSData): NSURL =
        NSURL.fileURLWithPath("$this/$name").also { assertTrue(data.writeToURL(it, atomically = false)) }

    private inline fun <T> withTemporaryDirectory(block: (String) -> T): T {
        val directory = NSTemporaryDirectory().trimEnd('/') + "/livephoto-" + NSUUID().UUIDString
        assertEquals(0, mkdir(directory, 448u))
        try {
            return block(directory)
        } finally {
            assertTrue(NSFileManager.defaultManager.removeItemAtPath(directory, error = null))
        }
    }
}

// Synthetic bytes generated by shared commonTest's GoogleFixtures.jpeg(),
// AppleFixtures.image() and AppleFixtures.movie(). Not camera captures.
private const val ORDINARY_JPEG =
    "/9j/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB" +
    "AQEBAQH/wAALCAABAAEBAREA/8QAJgABAAAAAAAAAAAAAAAAAAAAABABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAA" +
    "PwA//9k="
private const val APPLE_JPEG =
    "/9j/4QB1RXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAAZJ8AAcAAABBAAAALAAAAABBcHBsZSBpT1MA" +
    "AAFNTQABABEAAgAAACUAAAAcMDAxMTIyMzMtNDQ1NS02Njc3LTg4OTktYWFiYmNjZGRlZWZmAP/bAEMAAQEBAQEB" +
    "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAf/AAAsIAAEA" +
    "AQEBEQD/xAAmAAEAAAAAAAAAAAAAAAAAAAAAEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAA/AD//2Q=="
private const val APPLE_MOV =
    "AAAAFGZ0eXBxdCAgAAAAAHF0ICAAAAAVbWRhdAAAAAJliAAAAANBmiIAAAARbWRhdAAAAAkAAAAB/wAABUFtb292" +
    "AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAAUAABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAA" +
    "AAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAB8XRyYWsAAABcdGtoZAAAAAEAAAAA" +
    "AAAAAAAAAAEAAAAAAAAAUAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAA" +
    "AAAAAQAAAAEAAAAAAY1tZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAAAPoAAAAUFXEAAAAAAAmaGRscgAAAAAAAAAA" +
    "dmlkZQAAAAAAAAAAAAAAAFZpZGVvAAAAAT9taW5mAAAAFHZtaGQAAAABAAAAAAAAAAAAAAAkZGluZgAAABxkcmVm" +
    "AAAAAAAAAAEAAAAMdXJsIAAAAAEAAAD/c3RibAAAAH9zdHNkAAAAAAAAAAEAAABvYXZjMQAAAAAAAAABAAAAAAAA" +
    "AAAAAAAAAAAAAAABAAEASAAAAEgAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABj//wAA" +
    "ABlhdmNDAUIAHv/hAARnQgAeAQACaM4AAAAYc3R0cwAAAAAAAAABAAAAAgAAACgAAAAcc3RzYwAAAAAAAAABAAAA" +
    "AQAAAAIAAAABAAAAHHN0c3oAAAAAAAAAAAAAAAIAAAAGAAAABwAAABRzdGNvAAAAAAAAAAEAAAAcAAAAFHN0c3MA" +
    "AAAAAAAAAQAAAAEAAAIydHJhawAAAFx0a2hkAAAAAAAAAAAAAAAAAAAAAgAAAAAAAABQAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMGVkdHMAAAAoZWxzdAAAAAAA" +
    "AAACAAAAKP////8AAQAAAAAAKAAAAAAAAQAAAAABnm1kaWEAAAAgbWRoZAAAAAAAAAAAAAAAAAAAA+gAAAAoAAAA" +
    "AAAAACBoZGxyAAAAAAAAAABtZXRhAAAAAAAAAAAAAAAAAAABVm1pbmYAAAAgZ21oZAAAABhnbWluAAAAAAAAAAAA" +
    "AAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAxhbGlzAAAAAQAAAQpzdGJsAAAApnN0c2QAAAAAAAAA" +
    "AQAAAJZtZWJ4AAAAAAAAAAEAAACGa2V5cwAAAEgAAAABAAAAMGtleWRtZHRhY29tLmFwcGxlLnF1aWNrdGltZS5z" +
    "dGlsbC1pbWFnZS10aW1lAAAAEGR0eXAAAAAAAAAAQQAAADYAAAACAAAAHmtleWRtZHRhb3JkaW5hcnk6c3ludGhl" +
    "dGljAAAAEGR0eXAAAAAAAAAAQQAAABhzdHRzAAAAAAAAAAEAAAABAAAAKAAAABxzdHNjAAAAAAAAAAEAAAABAAAA" +
    "AQAAAAEAAAAUc3RzegAAAAAAAAAJAAAAAQAAABRzdGNvAAAAAAAAAAEAAAAxAAAAqm1ldGEAAAAgaGRscgAAAAAA" +
    "AAAAbWR0YQAAAAAAAAAAAAAAAAAAAD5rZXlzAAAAAAAAAAEAAAAubWR0YWNvbS5hcHBsZS5xdWlja3RpbWUuY29u" +
    "dGVudC5pZGVudGlmaWVyAAAARGlsc3QAAAA8AAAAAQAAADRkYXRhAAAAAQAAAAAwMDExMjIzMy00NDU1LTY2Nzct" +
    "ODg5OS1hYWJiY2NkZGVlZmY="
