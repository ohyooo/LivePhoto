package livephoto.core.media

import livephoto.adapters.files.*
import livephoto.core.*
import livephoto.core.ffmpeg.*
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.use
import kotlin.random.Random
import kotlin.test.*

internal suspend fun runFileMediaTest(supplied: String, block: suspend (MediaTestHost) -> Unit) {
    val fs = localFileSystem
    val parent = fs.canonicalize(FileSystem.SYSTEM_TEMPORARY_DIRECTORY)
    val root = parent / "livephoto-common-media-${Random.nextLong().toULong().toString(16)}"
    fs.createDirectory(root, mustCreate = true)
    val owned = linkedSetOf<okio.Path>()
    val originals = linkedMapOf<String, Digest>()
    val session = FileInputSession((root / "snapshots").toString(), mediaTestContext)
    fun <T> success(value: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(value, value.toString()).value
    try {
        val runtime = NativeFfmpeg({ root.toString() }, { session.spoolBytes })
        val chosenExecutable = fs.canonicalize(supplied.toPath()).toString()
        val discovery = runtime.discover(chosenExecutable, "")
        val host = object : MediaTestHost {
            override val executable = chosenExecutable
            override val backend = assertNotNull(discovery.backend, "Required real Native FFmpeg backend missing")
            override fun path(name: String): String {
                require(name.isNotBlank() && '/' !in name && '\\' !in name && name !in setOf(".", ".."))
                return (root / name).also { owned.add(it) }.toString()
            }
            override fun reference(name: String): String {
                require(name in setOf("video.jpg", "video.mp4"))
                return listOf("reference/$name", "../reference/$name").map { it.toPath() }
                    .first { fs.exists(it) }.let { fs.canonicalize(it).toString() }
            }
            override fun read(path: String): ByteArray = fs.read(path.toPath()) { readByteArray() }
            override fun write(path: String, bytes: ByteArray) {
                val target = path.toPath()
                require(target in owned && path !in originals)
                fs.openReadWrite(target, mustCreate = true).use { handle ->
                    handle.write(0L, bytes, 0, bytes.size)
                    handle.flush()
                }
            }
            override suspend fun source(path: String): BinarySource {
                originals.getOrPut(path) { mediaFileDigest(read(path)) }
                return success(session.capture(path))
            }
            override suspend fun execute(arguments: List<String>, timeoutMillis: Long): FfmpegProcessResult =
                runtime.execute(arguments, timeoutMillis, mediaTestContext)
        }
        block(host)
    } finally {
        try {
            originals.forEach { (path, digest) ->
                assertEquals(digest, mediaFileDigest(fs.read(path.toPath()) { readByteArray() }), "Original input changed")
            }
        }
        finally {
            try { success(session.close()) }
            finally {
                try { owned.forEach { fs.delete(it) } }
                finally { fs.delete(root, mustExist = true) }
            }
        }
    }
}
