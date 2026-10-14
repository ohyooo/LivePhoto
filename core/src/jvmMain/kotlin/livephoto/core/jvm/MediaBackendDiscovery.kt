package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.implementation.FallbackMediaBackend
import livephoto.core.ffmpeg.FfmpegMediaBackend
import java.nio.file.Files
import java.nio.file.Path

public data class BackendDiscovery(
    public val backend: MediaBackend?,
    public val ffmpegPath: Path?,
    public val issues: List<Issue>,
)

/** No downloads, shell aliases, working-directory lookup, or implicit installation. */
public object JvmMediaBackends {
    /**
     * Desktop defaults to an explicit executable, then absolute PATH entries, then disabled.
     * Retained system adapters are not discovered or initialized automatically.
     * Explicit dependency injection remains available for isolated adapter tests.
     * Unsupported operations may fall through; decode/IO/policy errors never trigger a retry.
     */
    public fun discover(ffmpegPath: Path? = null, systemBackends: List<MediaBackend> = emptyList()): BackendDiscovery =
        discover(ffmpegPath, System.getenv("PATH") ?: "", System.getProperty("os.name").startsWith("Windows"), systemBackends, ::verifyFfmpeg)

    internal fun verifyFfmpeg(path: Path, run: (List<String>, Long) -> ProcessResult = { arguments, timeout -> ExternalProcess.run(arguments, timeout) }): Boolean {
        // Cold-start DLL loading / antivirus on Windows can exceed three seconds. This is a
        // bounded read-only version check, not an operation retry or a media-decode timeout.
        val result = run(listOf(path.toString(), "-version"), 15_000L)
        return result.code == 0 && !result.outputLimited && !result.ioFailed && !result.timedOut && !result.cancelled &&
            result.output.lineSequence().firstOrNull()?.startsWith("ffmpeg version ") == true
    }

    internal fun discover(explicit: Path?, searchPath: String, windows: Boolean, systems: List<MediaBackend>,
        verify: (Path) -> Boolean): BackendDiscovery {
        val issues = mutableListOf<Issue>()
        val candidates = buildList {
            if (explicit != null) add(explicit.toAbsolutePath().normalize())
            for (entry in searchPath.split(if (windows) ';' else ':')) {
                if (entry.isBlank()) continue
                val directory = try { Path.of(entry.removeSurrounding("\"")) } catch (_: IllegalArgumentException) { continue }
                if (directory.isAbsolute) add(directory.resolve(if (windows) "ffmpeg.exe" else "ffmpeg"))
            }
        }.distinct()
        var selected: Path? = null
        for (candidate in candidates) {
            val valid = try {
                candidate.toRealPath().takeIf { Files.isRegularFile(it) && Files.isExecutable(it) && verify(it) }
            } catch (_: Exception) { null }
            if (valid != null) { selected = valid; break }
            if (explicit != null && candidate == explicit.toAbsolutePath().normalize()) issues += Issue(
                IssueCode("FFMPEG_EXPLICIT_PATH_UNAVAILABLE"), Severity.Warning, Layer.Compatibility)
        }
        val backends = listOfNotNull(selected?.let { FfmpegMediaBackend(it.toString(), JvmFfmpegRuntime) }) + systems
        if (backends.isEmpty()) issues += Issue(IssueCode("MEDIA_BACKEND_UNAVAILABLE"), Severity.Warning, Layer.Compatibility)
        return BackendDiscovery(backends.takeIf { it.isNotEmpty() }?.let(::FallbackMediaBackend), selected, issues.toList())
    }
}
