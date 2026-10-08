package livephoto.core.jvm

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.heif.HeifFixtures
import livephoto.core.huawei.HuaweiFixtures
import livephoto.core.memory.*
import kotlin.test.*

/** Independent synthetic framing, exported for no-backend packaged CLI checks, not decoding or devices. */
class HuaweiHeicPortableFixtureTest {
    @Test fun exportOnlyAfterReadAndExactExtractionProof(): Unit = runImmediate {
        val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
        val f = HuaweiFixtures.photo(jpeg = HeifFixtures.plain(multiple = true), video = GoogleFixtures.video().bytes)
        val input = SourceSet.Single(MemoryBinarySource(Bytes(f.bytes), SourceId("portable-huawei")))
        val core = DefaultLivePhotoCore()
        val inspection = core.inspect(ReadRequest(input, context)).orThrow()
        assertEquals(Disposition.Candidate, inspection.detection.disposition)
        val output = MemoryOutputTransaction(context, "portable-huawei-proof")
        core.extract(ExtractRequest(input, emptyList(), inspection.snapshot, output = output, context = context)).orThrow()
        assertEquals(Bytes(f.video), output.committedAssets().values.single())
        val directory = Path.of("build", "reports", "huawei-heic-fixtures")
        Files.createDirectories(directory)
        val manifest = StringBuilder("scope=synthetic-protocol-framing-not-decoder-or-device-proof\nrunId=${UUID.randomUUID()}\n")
        for ((name, bytes) in linkedMapOf("carrier.heic" to f.bytes, "movie.mp4" to f.video)) {
            Files.write(directory.resolve(name), bytes)
            manifest.append("$name=${Sha256().also { it.update(Bytes(bytes)) }.finish().value}\n")
        }
        Files.writeString(directory.resolve("manifest.txt"), manifest.toString())
    }
}
