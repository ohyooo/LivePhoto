package livephoto.core.jvm

import java.awt.image.BufferedImage
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** Only packed, even-sized eight-bit NV12 with explicit BT.709 limited-range left chroma. */
internal object WindowsFramePixels {
    // ITU-R BT.709-6 table 3 (matrix/range/OETF); IEC 61966-2-1 sRGB transfer.
    // https://www.itu.int/rec/R-REC-BT.709/en
    // https://www.w3.org/Graphics/Color/srgb
    fun srgb(encoded709: Double): Int {
        val e = encoded709.coerceIn(0.0, 1.0)
        val linear = if (e < 0.081) e / 4.5 else ((e + 0.099) / 1.099).pow(1.0 / 0.45)
        val value = if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1.0 / 2.4) - 0.055
        return (value * 255).roundToInt().coerceIn(0, 255)
    }
    fun image(bytes: ByteArray, width: Int, height: Int, checkpoint: () -> Unit = {}): BufferedImage {
        require(width in 48..1024 && height in 48..1024 && width % 2 == 0 && height % 2 == 0)
        require(bytes.size == width * height * 3 / 2)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val plane = width * height
        // Left chroma is x=2*i, y=2*j+0.5. Bilinear reconstruction with replicated edges.
        // https://learn.microsoft.com/en-us/windows/win32/api/mfobjects/ne-mfobjects-mfvideochromasubsampling
        fun chroma(x: Int, y: Int, channel: Int): Double {
            val cx = x / 2.0; val cy = (y - 0.5) / 2.0
            val x0 = floor(cx).toInt(); val y0 = floor(cy).toInt()
            val fx = cx - x0; val fy = cy - y0
            fun at(i: Int, j: Int) = (bytes[plane + j.coerceIn(0, height / 2 - 1) * width +
                i.coerceIn(0, width / 2 - 1) * 2 + channel].toInt() and 255).toDouble()
            return ((1 - fy) * ((1 - fx) * at(x0, y0) + fx * at(x0 + 1, y0)) +
                fy * ((1 - fx) * at(x0, y0 + 1) + fx * at(x0 + 1, y0 + 1)) - 128) / 224
        }
        for (y in 0 until height) {
            checkpoint()
            for (x in 0 until width) {
                val luma = ((bytes[y * width + x].toInt() and 255) - 16) / 219.0
                val cb = chroma(x, y, 0); val cr = chroma(x, y, 1)
                val r = luma + 1.5748 * cr; val b = luma + 1.8556 * cb
                val g = (luma - 0.2126 * r - 0.0722 * b) / 0.7152
                image.setRGB(x, y, (srgb(r) shl 16) or (srgb(g) shl 8) or srgb(b))
            }
        }
        return image
    }
}
