package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.BmffVideoProbe
import livephoto.core.memory.MemoryBinarySource
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

/** Independent structure/timeline proof, not OS decode or device evidence. */
class WindowsPortableFixtureTest {
    @Test fun checkedInEncodedFixturesHaveProvenStructureAndFreshHashManifest(): Unit = runImmediate {
        val directory = Path.of("build", "portable-windows-fixtures")
        Files.createDirectories(directory)
        val manifest = StringBuilder("scope=nas-encoded-synthetic-avc-not-device-proof\nrunId=${UUID.randomUUID()}\n")
        for ((name, hash) in WindowsEncodedFixtures.hashes) {
            val bytes = WindowsEncodedFixtures.bytes(name)
            val source = MemoryBinarySource(Bytes(bytes), SourceId("encoded-$name"))
            val structure = BmffVideoProbe(BinaryReader(source, Context(Limits(128_000_000uL, 128_000_000uL))))
                .probe(ByteRange(0uL, bytes.size.toULong())).orThrow()
            val track = structure.tracks.single { it.handler == "vide" }
            assertEquals(VideoCodec.Avc, track.codec); assertEquals(64u, track.width); assertEquals(64u, track.height)
            assertEquals(if (name in setOf("b0", "b2")) 4 else 8, track.samples.size)
            assertEquals(if (name == "audio") 2 else 1, structure.tracks.size)
            if (name == "audio") assertEquals(AudioCodec.Aac, structure.tracks.single { it.handler == "soun" }.audioCodec)
            val pts = MessageDigest.getInstance("SHA-256")
            track.samples.sortedBy { it.presentationTime }.forEach {
                val product = Math.multiplyExact(it.presentationTime, 10_000_000L)
                assertEquals(0L, product % track.timescale.toLong())
                pts.update(ByteBuffer.allocate(8).putLong(product / track.timescale.toLong()).array())
            }
            val digest = pts.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            Files.write(directory.resolve("$name.mp4"), bytes)
            manifest.append("$name.mp4=$hash\n$name.frames=${track.samples.size}\n$name.ptsSha256=$digest\n")
        }
        Files.writeString(directory.resolve("manifest.txt"), manifest.toString())
    }
}
