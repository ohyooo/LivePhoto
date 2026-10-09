package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class WindowsJpegEncoderTest {
    @Test fun explicitQualityChangesQuantizationAndDefaultBytesRemainUnchanged() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) for (x in 0 until 64) image.setRGB(x, y, (x * 4 shl 16) or (y * 4 shl 8) or ((x + y) * 2))
        val original = ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "jpeg", it)) }.toByteArray()
        assertContentEquals(original, WindowsJpegEncoder.encode(image, null, 100_000) {})
        val qualities = listOf(0u, 35u, 75u, 100u)
        val tables = qualities.map { quality ->
            val encoded = WindowsJpegEncoder.encode(image, quality, 100_000) {}
            val decoded = ImageIO.read(encoded.inputStream())
            assertEquals(64, decoded.width); assertEquals(64, decoded.height)
            quantization(encoded).also { assertEquals(128, it.size) }
        }
        assertTrue(tables.first().all { it == 255 })
        assertTrue(tables.last().all { it == 1 })
        tables.zipWithNext().forEach { (low, high) -> assertTrue(low.sum() > high.sum()) }
        assertContentEquals(original, WindowsJpegEncoder.encode(image, 75u, 100_000) {})
    }
    @Test fun outputBudgetAndCancellationDoNotReturnAnImage() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val limited = assertIs<CoreResult.Failure>(attemptNow { WindowsJpegEncoder.encode(image, 100u, 1) {} })
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), limited.error.code)
        assertEquals(Stage.EncodeImage, limited.error.stage)
        var checks = 0
        val cancelled = assertIs<CoreResult.Failure>(attemptNow {
            WindowsJpegEncoder.encode(image, 100u, 100_000) { if (++checks >= 2) fail("CANCELLED", "Cancelled", Stage.EncodeImage) }
        })
        assertEquals(IssueCode("CANCELLED"), cancelled.error.code)
        assertFailsWith<IllegalArgumentException> { WindowsJpegEncoder.encode(image, 101u, 100_000) {} }
    }

    /** Independent bounded JPEG marker walk; does not reuse the encoder's parameter API. */
    internal fun quantization(bytes: ByteArray): List<Int> {
        val values = mutableListOf<Int>()
        fun unsigned(at: Int) = bytes[at].toInt() and 255
        assertEquals(0xff, unsigned(0)); assertEquals(0xd8, unsigned(1))
        var at = 2
        while (at < bytes.size) {
            assertEquals(0xff, unsigned(at++))
            val marker = unsigned(at++)
            if (marker == 0xda) break
            val length = (unsigned(at) shl 8) or unsigned(at + 1)
            assertTrue(length >= 2 && at + length <= bytes.size)
            if (marker == 0xdb) {
                var table = at + 2
                while (table < at + length) {
                    assertEquals(0, unsigned(table++) ushr 4)
                    assertTrue(table + 64 <= at + length)
                    repeat(64) { values += unsigned(table++) }
                }
            }
            at += length
        }
        return values
    }
}
