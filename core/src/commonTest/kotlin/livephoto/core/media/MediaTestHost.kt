package livephoto.core.media

import livephoto.core.*
import livephoto.core.ffmpeg.FfmpegProcessResult

internal val mediaTestContext = Context(Limits(128_000_000uL, 128_000_000uL))

/** Test-only host adapter. Native uses the same runtime and file snapshots as the product. */
internal interface MediaTestHost {
    val executable: String
    val backend: MediaBackend
    fun path(name: String): String
    fun reference(name: String): String
    fun read(path: String): ByteArray
    suspend fun source(path: String): BinarySource
    suspend fun execute(arguments: List<String>, timeoutMillis: Long = 60_000): FfmpegProcessResult
}
internal expect fun runMediaTest(block: suspend (MediaTestHost) -> Unit)

internal fun frameFixtureArguments(executable: String, path: String, colors: Boolean = true): List<String> =
    listOf(executable, "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
        "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "8", "-vf", "setpts='if(lt(N,4),N,4+(N-4)*2)/(25*TB)'" +
            (if (colors) ",setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709" else ""), "-fps_mode", "vfr",
        "-c:v", "libx264", "-preset", "medium", "-bf", "2", "-g", "8", "-pix_fmt", "yuv420p") +
        (if (colors) listOf("-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709") else emptyList()) +
        listOf("-map_metadata", "-1", "-map_chapters", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", path)
