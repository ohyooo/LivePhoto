@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.core.media

import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import livephoto.adapters.files.*
import livephoto.core.*
import livephoto.core.ffmpeg.*
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.*

internal actual fun runMediaTest(block: suspend (MediaTestHost) -> Unit): Unit = runBlocking<Unit> {
    val fs = localFileSystem
    val parent = fs.canonicalize(FileSystem.SYSTEM_TEMPORARY_DIRECTORY)
    val root = parent / "livephoto-common-media-${Random.nextLong().toULong().toString(16)}"
    fs.createDirectory(root, mustCreate = true)
    val owned = linkedSetOf<okio.Path>()
    val session = FileInputSession((root / "snapshots").toString(), mediaTestContext)
    fun <T> success(value: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(value, value.toString()).value
    try {
        val runtime = NativeFfmpeg({ root.toString() }, { session.spoolBytes })
        val supplied = assertNotNull(platform.posix.getenv("LIVEPHOTO_FFMPEG")?.toKString(), "Native CI must supply FFmpeg")
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
            override suspend fun source(path: String): BinarySource = success(session.capture(path))
            override suspend fun execute(arguments: List<String>, timeoutMillis: Long): FfmpegProcessResult =
                runtime.execute(arguments, timeoutMillis, mediaTestContext)
        }
        block(host)
    } finally {
        try { success(session.close()) }
        finally {
            try { owned.forEach { fs.delete(it) } }
            finally { fs.delete(root, mustExist = true) }
        }
    }
}
