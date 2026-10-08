package livephoto.core.jvm

import java.util.Base64
import java.security.MessageDigest

/** NAS FFmpeg-generated 64x64 synthetic media, not vendor/device originals.
 * Checked-in encoded bytes allow actual OS decoding without an optional fixture encoder on CI. */
internal object WindowsEncodedFixtures {
    val hashes = mapOf(
        "b0" to "cf4959d6e5e6a9a67ce19fddc2ce2f806136b8b193a0131e6060e0d05c811165",
        "b2" to "694034d7f2d596db0d78357a09cab978f3b378d987acbbf030f191fb2a62df04",
        "vfr" to "5794679eb6e8a821f69e91ca1f783dac0a6c025bce7df6494754fc6fd5ffa72c",
        "audio" to "534546446072e5fbbbd48dc83d8ccd1497e5a465ed76b795552c823cf74a76d5",
    )
    fun bytes(name: String): ByteArray {
        val expected = hashes.getValue(name)
        val encoded = WindowsEncodedFixtures::class.java.getResourceAsStream("/windows-media/$name.mp4.base64")
            ?.use { it.readNBytes(40_000).toString(Charsets.US_ASCII).trim() } ?: error("Missing encoded OS fixture")
        val bytes = Base64.getDecoder().decode(encoded)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(hash == expected) { "Encoded OS fixture digest differs" }
        return bytes
    }
}
