package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/** Platform encoding adapter; null quality preserves the existing ImageIO default path. */
internal object WindowsJpegEncoder {
    fun encode(image: BufferedImage, quality: UInt?, outputLimit: Int, checkpoint: () -> Unit): ByteArray {
        require(quality == null || quality <= 100u)
        require(outputLimit > 0)
        checkpoint()
        val encoded = object : ByteArrayOutputStream() {
            override fun write(b: Int) {
                checkpoint()
                if (count >= outputLimit) fail("RESOURCE_LIMIT_EXCEEDED", "JPEG exceeds output budget", Stage.EncodeImage)
                super.write(b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                checkpoint()
                if (len > outputLimit - count) fail("RESOURCE_LIMIT_EXCEEDED", "JPEG exceeds output budget", Stage.EncodeImage)
                super.write(b, off, len)
            }
        }
        MemoryCacheImageOutputStream(encoded).use { stream ->
            if (quality == null) {
                if (!ImageIO.write(image, "jpeg", stream)) fail("ENCODE_FAILED", "Existing JDK JPEG encoder unavailable", Stage.EncodeImage)
            } else {
                val writers = ImageIO.getImageWritersByFormatName("jpeg")
                if (!writers.hasNext()) fail("ENCODE_FAILED", "Existing JDK JPEG encoder unavailable", Stage.EncodeImage)
                val writer = writers.next()
                try {
                    val parameters = JPEGImageWriteParam(Locale.ROOT)
                    parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    parameters.compressionQuality = quality.toFloat() / 100f
                    writer.output = stream
                    writer.write(null, IIOImage(image, null, null), parameters)
                } finally { writer.dispose() }
            }
            stream.flush()
        }
        checkpoint()
        return encoded.toByteArray()
    }
}
