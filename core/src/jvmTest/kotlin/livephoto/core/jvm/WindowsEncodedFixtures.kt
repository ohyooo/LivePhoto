package livephoto.core.jvm

import java.util.Base64
import java.security.MessageDigest
import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.BmffReader
import livephoto.core.memory.MemoryBinarySource

/** NAS FFmpeg-generated 64x64 synthetic media, not vendor/device originals.
 * Checked-in encoded bytes allow actual OS decoding without an optional fixture encoder on CI. */
internal object WindowsEncodedFixtures {
    val hashes = mapOf(
        "trim-high" to "92b30a35966d77d584f773299ad14fbddc92312eeb06cb5b9605b676ff5b7f1c",
        "b0" to "cf4959d6e5e6a9a67ce19fddc2ce2f806136b8b193a0131e6060e0d05c811165",
        "b2" to "694034d7f2d596db0d78357a09cab978f3b378d987acbbf030f191fb2a62df04",
        "vfr" to "5794679eb6e8a821f69e91ca1f783dac0a6c025bce7df6494754fc6fd5ffa72c",
        "audio" to "534546446072e5fbbbd48dc83d8ccd1497e5a465ed76b795552c823cf74a76d5",
        "remux-main" to "be48fea2d56bb357d2f7370f3efc9462171d4d47d61451d35b3a8c9d78cc56e6",
        "remux-high" to "8329be87e2c14118940765fcc24790b780e578d66379ce8f6d913a9c27b9c782",
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

    /** Explicitly synthetic QuickTime brand framing over checked-in real AVC samples.
     * Not a general remuxer, camera MOV, or independent MOV encoder proof. */
    suspend fun movBytes(name: String): ByteArray {
        val bytes = bytes(name)
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        val source = MemoryBinarySource(Bytes(bytes), SourceId("fixture-mov-brand-$name"))
        try {
            val ftyp = BmffReader(BinaryReader(source, context)).readBoxes(ByteRange(0uL, bytes.size.toULong())).orThrow().single { it.type == "ftyp" }
            check(ftyp.payload.length >= 8uL && (ftyp.payload.length - 8uL) % 4uL == 0uL)
            "qt  ".encodeToByteArray().copyInto(bytes, ftyp.payload.offset.toInt())
            var offset = ftyp.payload.offset + 8uL
            while (offset < ftyp.payload.endExclusive) {
                "qt  ".encodeToByteArray().copyInto(bytes, offset.toInt()); offset += 4uL
            }
            return bytes
        } finally { source.close() }
    }
}
