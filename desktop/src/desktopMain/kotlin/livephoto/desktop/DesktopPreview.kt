package livephoto.desktop

import livephoto.core.Bytes
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

/** Display-only derivative. Official cover output still comes unchanged from Core/FFmpeg. */
internal fun desktopPreview(bytes: Bytes, edge: Int): Bytes {
    require(edge in 1..960)
    Image.makeFromEncoded(bytes.toByteArray()).use { image ->
        require(image.width > 0 && image.height > 0 && image.width.toLong() * image.height <= 64_000_000) {
            "预览图片尺寸超过限制"
        }
        val scale = minOf(1.0, edge.toDouble() / maxOf(image.width, image.height))
        val width = (image.width * scale).roundToInt().coerceAtLeast(1)
        val height = (image.height * scale).roundToInt().coerceAtLeast(1)
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
            surface.makeImageSnapshot().use { resized ->
                checkNotNull(resized.encodeToData(EncodedImageFormat.JPEG, 80)).use { data ->
                    return Bytes(data.bytes)
                }
            }
        }
    }
}
