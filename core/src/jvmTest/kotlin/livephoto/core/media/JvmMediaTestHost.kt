package livephoto.core.media

import livephoto.core.*
import livephoto.core.ffmpeg.*
import livephoto.core.jvm.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

internal actual fun runMediaTest(block: suspend (MediaTestHost) -> Unit): Unit {
    val discovery = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
    if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(discovery.ffmpegPath, "Required FFmpeg missing")
    assumeTrue("No existing FFmpeg; real media tests were not run", discovery.ffmpegPath != null)
    val root = Files.createTempDirectory("livephoto-common-media-")
    val owned = linkedSetOf<Path>()
    val originals = linkedMapOf<String, Digest>()
    val host = object : MediaTestHost {
        override val executable = assertNotNull(discovery.ffmpegPath).toString()
        override val backend = assertNotNull(discovery.backend)
        override fun path(name: String): String {
            require(name.isNotBlank() && '/' !in name && '\\' !in name && name !in setOf(".", ".."))
            return root.resolve(name).also { owned.add(it) }.toString()
        }
        override fun reference(name: String): String {
            require(name in setOf("video.jpg", "video.mp4"))
            return generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .map { it.resolve("reference/$name") }.first(Files::isRegularFile).toString()
        }
        override fun read(path: String): ByteArray = Files.readAllBytes(Path.of(path))
        override suspend fun source(path: String): BinarySource {
            originals.getOrPut(path) { mediaFileDigest(read(path)) }
            return FileBinarySource(Path.of(path))
        }
        override suspend fun execute(arguments: List<String>, timeoutMillis: Long): FfmpegProcessResult =
            JvmFfmpegRuntime.execute(arguments, timeoutMillis, mediaTestContext)
    }
    try { runImmediate { block(host) } }
    finally {
        try {
            originals.forEach { (path, digest) ->
                assertEquals(digest, mediaFileDigest(Files.readAllBytes(Path.of(path))), "Original input changed")
            }
        } finally {
            owned.forEach { Files.deleteIfExists(it) }
            Files.delete(root) // Unexpected children must fail cleanup, never be recursively erased.
        }
    }
}
